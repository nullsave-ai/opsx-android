package com.oneplus.opsx

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class OpsxPlayer {

    private var videoDecoder: MediaCodec? = null
    private var audioDecoder: MediaCodec? = null
    private var audioTrack: AudioTrack? = null

    @Volatile
    private var isPlaying = false

    private data class StreamPacket(
        val trackId: Int,
        val isKey: Boolean,
        val pts: Long,
        val payload: ByteArray
    )

    private val videoQueue = LinkedBlockingQueue<StreamPacket>(80)
    private val audioQueue = LinkedBlockingQueue<StreamPacket>(120)

    private fun readFully(inputStream: InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val r = inputStream.read(buffer, offset, buffer.size - offset)
            if (r == -1) return false
            offset += r
        }
        return true
    }

    suspend fun playUrl(streamUrl: String, surface: Surface, onStatus: (String) -> Unit) = withContext(Dispatchers.IO) {
        try {
            onStatus("الاتصال بدفق OPSX المباشر: $streamUrl")
            val conn = URL(streamUrl).openConnection()
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            val inputStream = conn.getInputStream()
            playStream(inputStream, surface, onStatus = onStatus)
        } catch (e: Exception) {
            onStatus("تنبيه في تشغيل الدفق المباشر: ${e.message}")
        }
    }

    suspend fun playFile(opsxFile: File, surface: Surface, onStatus: (String) -> Unit) = withContext(Dispatchers.IO) {
        if (!opsxFile.exists()) {
            onStatus("خطأ: ملف OPSX غير موجود.")
            return@withContext
        }
        val fis = FileInputStream(opsxFile)
        playStream(fis, surface, onStatus = onStatus)
    }

    @Synchronized
    private fun initAudioTrack(sampleRate: Int, channelCount: Int) {
        try {
            if (audioTrack != null) {
                try { audioTrack?.stop() } catch (ignored: Exception) {}
                try { audioTrack?.release() } catch (ignored: Exception) {}
                audioTrack = null
            }

            val channelConfig = if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
            val bufSize = Math.max(minBuf * 4, 65536)

            val at = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (at.state == AudioTrack.STATE_INITIALIZED) {
                at.play()
                audioTrack = at
            }
        } catch (ignored: Exception) {}
    }

    private fun extractAdtsFrames(payload: ByteArray): List<ByteArray> {
        val frames = ArrayList<ByteArray>(8)
        var offset = 0
        while (offset + 7 <= payload.size) {
            val b0 = payload[offset].toInt() and 0xFF
            val b1 = payload[offset + 1].toInt() and 0xFF
            if (b0 == 0xFF && (b1 and 0xF0) == 0xF0) {
                val b3 = payload[offset + 3].toInt() and 0xFF
                val b4 = payload[offset + 4].toInt() and 0xFF
                val b5 = payload[offset + 5].toInt() and 0xFF
                val flen = ((b3 and 3) shl 11) or (b4 shl 3) or ((b5 ushr 5) and 7)
                if (flen in 7..8192 && offset + flen <= payload.size) {
                    frames.add(payload.copyOfRange(offset, offset + flen))
                    offset += flen
                    continue
                }
            }
            offset++
        }
        if (frames.isEmpty()) {
            frames.add(payload)
        }
        return frames
    }

    private suspend fun playStream(
        inputStream: InputStream,
        surface: Surface,
        onStatus: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        isPlaying = true
        videoQueue.clear()
        audioQueue.clear()

        try {
            val headerBytes = ByteArray(40)
            if (!readFully(inputStream, headerBytes)) {
                onStatus("خطأ: تعذر قراءة رأس OPSX.")
                return@withContext
            }

            val magic = String(headerBytes.copyOfRange(0, 4), Charsets.US_ASCII)
            if (magic != "OPSX") {
                onStatus("خطأ: تنسيق الملف غير صالح (Magic=$magic)")
                return@withContext
            }

            // قراءة واصف المسار الأول
            val trackHeader = ByteArray(28)
            if (!readFully(inputStream, trackHeader)) {
                onStatus("خطأ: تعذر قراءة واصف المسار.")
                return@withContext
            }
            val trackBuf = ByteBuffer.wrap(trackHeader).order(ByteOrder.BIG_ENDIAN)
            val trackId = trackBuf.get()
            val trackType = trackBuf.get()
            val codecId = trackBuf.short
            trackBuf.int // timescale
            var width = trackBuf.short.toInt()
            var height = trackBuf.short.toInt()

            if (width <= 0) width = 1920
            if (height <= 0) height = 1080

            val mimeType = if (codecId.toInt() == 0x0002) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            val codecName = if (codecId.toInt() == 0x0002) "HEVC (H.265)" else "AVC (H.264)"
            onStatus("تهيئة فك العتاد MediaCodec: دقة ${width}x${height}، مِرماز $codecName + صوت ستيريو AAC")

            // 1. تهيئة مفكك الفيديو
            val vFormat = MediaFormat.createVideoFormat(mimeType, width, height)
            videoDecoder = MediaCodec.createDecoderByType(mimeType).apply {
                configure(vFormat, surface, null, 0)
                start()
            }

            // 2. تهيئة مفكك الصوت ومسار الصوت
            try {
                val aFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2)
                aFormat.setInteger(MediaFormat.KEY_IS_ADTS, 1)
                audioDecoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                    configure(aFormat, null, null, 0)
                    start()
                }
                initAudioTrack(44100, 2)
            } catch (e: Exception) {
                onStatus("تنبيه مفكك الصوت: ${e.message}")
            }

            // 3. خيط معالجة وتغذية الصوت (Audio Thread)
            val audioThread = Thread({
                val aBufInfo = MediaCodec.BufferInfo()
                val aDecoder = audioDecoder ?: return@Thread

                while (isPlaying) {
                    try {
                        val packet = audioQueue.poll(20, TimeUnit.MILLISECONDS)
                        if (packet != null) {
                            val frames = extractAdtsFrames(packet.payload)
                            var framePtsUs = packet.pts * 1000L
                            for (frame in frames) {
                                var inIndex = aDecoder.dequeueInputBuffer(4000)
                                var retries = 0
                                while (inIndex < 0 && isPlaying && retries < 3) {
                                    drainAudio(aDecoder, aBufInfo)
                                    inIndex = aDecoder.dequeueInputBuffer(4000)
                                    retries++
                                }
                                if (inIndex >= 0) {
                                    val inBuf = aDecoder.getInputBuffer(inIndex)
                                    inBuf?.clear()
                                    inBuf?.put(frame)
                                    aDecoder.queueInputBuffer(inIndex, 0, frame.size, framePtsUs, 0)
                                    framePtsUs += 23220L // ~23.2ms per frame @ 44.1kHz
                                }
                            }
                        }
                        drainAudio(aDecoder, aBufInfo)
                    } catch (e: Exception) {
                        // ignore error during audio playback
                    }
                }
            }, "OPSX-AudioWorker")
            audioThread.start()

            // 4. خيط معالجة وعرض الفيديو المتناسق 1.0x (Video Thread)
            val videoThread = Thread({
                val vBufInfo = MediaCodec.BufferInfo()
                val vDecoder = videoDecoder ?: return@Thread
                var frameCount = 0L

                var playbackStartRealtimeMs = -1L
                var playbackStartPtsMs = -1L

                while (isPlaying) {
                    try {
                        val packet = videoQueue.poll(20, TimeUnit.MILLISECONDS)
                        if (packet != null) {
                            val inIndex = vDecoder.dequeueInputBuffer(8000)
                            if (inIndex >= 0) {
                                val inBuf = vDecoder.getInputBuffer(inIndex)
                                inBuf?.clear()
                                inBuf?.put(packet.payload)
                                vDecoder.queueInputBuffer(inIndex, 0, packet.payload.size, packet.pts * 1000L, 0)
                            }
                        }

                        // سحب الإطارات المعالجة وتوقيت العرض الطبيعي 1.0x
                        var outIndex = vDecoder.dequeueOutputBuffer(vBufInfo, 8000)
                        while (outIndex >= 0 && isPlaying) {
                            val framePtsMs = vBufInfo.presentationTimeUs / 1000L

                            if (playbackStartRealtimeMs == -1L) {
                                playbackStartRealtimeMs = System.currentTimeMillis()
                                playbackStartPtsMs = framePtsMs
                            }

                            val targetElapsedMs = framePtsMs - playbackStartPtsMs
                            val actualElapsedMs = System.currentTimeMillis() - playbackStartRealtimeMs
                            val diffMs = targetElapsedMs - actualElapsedMs

                            if (diffMs > 500L || diffMs < -500L) {
                                // إعادة ضبط تزامن التوقيت في حال قفزات الدفق المباشر
                                playbackStartRealtimeMs = System.currentTimeMillis()
                                playbackStartPtsMs = framePtsMs
                            } else if (diffMs > 1L) {
                                val sleepMs = Math.min(diffMs, 45L)
                                try { Thread.sleep(sleepMs) } catch (ignored: Exception) {}
                            }

                            vDecoder.releaseOutputBuffer(outIndex, true)
                            frameCount++
                            if (frameCount % 100L == 0L) {
                                onStatus("بث مباشر متناسق 1.0x: تم عرض $frameCount إطار عتادياً بالصوت والصورة")
                            }

                            outIndex = vDecoder.dequeueOutputBuffer(vBufInfo, 1000)
                        }
                    } catch (e: Exception) {
                        // ignore error during video playback
                    }
                }
            }, "OPSX-VideoWorker")
            videoThread.start()

            // 5. حلقة القراءة والتفكيك الرئيسية (Reader Loop)
            val chunkHeader = ByteArray(28)
            val crcBuf = ByteArray(4)

            while (isPlaying) {
                if (!readFully(inputStream, chunkHeader)) break

                val cBuf = ByteBuffer.wrap(chunkHeader).order(ByteOrder.BIG_ENDIAN)
                val chunkType = cBuf.get()
                val cTrackId = cBuf.get()
                val flags = cBuf.get()
                cBuf.get()
                val pts = cBuf.long
                val dts = cBuf.long
                val payloadSize = cBuf.int
                val storedHeaderCrc = cBuf.int

                if (payloadSize <= 0 || payloadSize > 15_000_000) continue

                val payload = ByteArray(payloadSize)
                if (!readFully(inputStream, payload)) break

                if (!readFully(inputStream, crcBuf)) break
                val storedPayloadCrc = ByteBuffer.wrap(crcBuf).order(ByteOrder.BIG_ENDIAN).int

                val isPayloadValid = OpsxNativeBridge.verify(payload, storedPayloadCrc)
                if (!isPayloadValid) {
                    continue
                }

                val isKey = (flags.toInt() and 0x01) != 0
                val packet = StreamPacket(
                    trackId = cTrackId.toInt(),
                    isKey = isKey,
                    pts = pts,
                    payload = payload
                )

                if (cTrackId.toInt() == 2 || chunkType.toInt() == 2) {
                    audioQueue.offer(packet, 500, TimeUnit.MILLISECONDS)
                } else {
                    videoQueue.offer(packet, 500, TimeUnit.MILLISECONDS)
                }
            }

            onStatus("تم إنهاء دفق البث.")
        } catch (e: Exception) {
            if (isPlaying) onStatus("تنبيه أثناء التشغيل: ${e.message}")
        } finally {
            stop()
            try { inputStream.close() } catch (ignored: Exception) {}
        }
    }

    private fun drainAudio(aDecoder: MediaCodec, aBufInfo: MediaCodec.BufferInfo) {
        while (isPlaying) {
            val outIndex = aDecoder.dequeueOutputBuffer(aBufInfo, 2000)
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFmt = aDecoder.outputFormat
                val sRate = if (newFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) newFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
                val chCount = if (newFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
                initAudioTrack(sRate, chCount)
            } else if (outIndex >= 0) {
                val outBuf = aDecoder.getOutputBuffer(outIndex)
                if (outBuf != null && aBufInfo.size > 0) {
                    val pcmData = ByteArray(aBufInfo.size)
                    outBuf.position(aBufInfo.offset)
                    outBuf.get(pcmData, 0, aBufInfo.size)
                    audioTrack?.write(pcmData, 0, pcmData.size)
                }
                aDecoder.releaseOutputBuffer(outIndex, false)
            } else {
                break
            }
        }
    }

    fun stop() {
        isPlaying = false
        videoQueue.clear()
        audioQueue.clear()

        try {
            videoDecoder?.stop()
            videoDecoder?.release()
        } catch (ignored: Exception) {}
        videoDecoder = null

        try {
            audioDecoder?.stop()
            audioDecoder?.release()
        } catch (ignored: Exception) {}
        audioDecoder = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (ignored: Exception) {}
        audioTrack = null
    }
}
