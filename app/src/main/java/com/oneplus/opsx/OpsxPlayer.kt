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
    private var hasAudioPlayedFirstPcm = false

    private data class StreamPacket(
        val trackId: Int,
        val isKey: Boolean,
        val pts: Long,
        val payload: ByteArray
    )

    data class AacFrameInfo(
        val sampleRate: Int,
        val channelCount: Int,
        val csd0: ByteArray,
        val rawData: ByteArray
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
    private fun initAudioTrack(sampleRate: Int, channelCount: Int, onStatus: (String) -> Unit) {
        try {
            if (audioTrack != null) {
                try { audioTrack?.stop() } catch (ignored: Exception) {}
                try { audioTrack?.release() } catch (ignored: Exception) {}
                audioTrack = null
            }

            val channelConfig = if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
            val bufSize = if (minBuf > 0) Math.max(minBuf * 4, 32768) else 65536

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
                try { at.setVolume(1.0f) } catch (ignored: Exception) {}
                at.play()
                audioTrack = at
                onStatus("مسار الصوت جاهز للتشغيل: تردد $sampleRate Hz ستيريو عتادياً")
            } else {
                onStatus("خطأ في تهيئة AudioTrack: الحالة غير جاهزة")
            }
        } catch (e: Exception) {
            onStatus("تنبيه في AudioTrack: ${e.message}")
        }
    }

    private fun extractRawAacFrames(payload: ByteArray): List<AacFrameInfo> {
        val list = ArrayList<AacFrameInfo>(8)
        var offset = 0
        while (offset + 7 <= payload.size) {
            val b0 = payload[offset].toInt() and 0xFF
            val b1 = payload[offset + 1].toInt() and 0xFF
            if (b0 == 0xFF && (b1 and 0xF0) == 0xF0) {
                val protection = b1 and 0x01
                val headerLen = if (protection == 1) 7 else 9
                val b2 = payload[offset + 2].toInt() and 0xFF
                val b3 = payload[offset + 3].toInt() and 0xFF
                val b4 = payload[offset + 4].toInt() and 0xFF
                val b5 = payload[offset + 5].toInt() and 0xFF
                val flen = ((b3 and 3) shl 11) or (b4 shl 3) or ((b5 ushr 5) and 7)

                if (flen in headerLen..8192 && offset + flen <= payload.size) {
                    val profile = (b2 ushr 6) and 3
                    val aot = profile + 1
                    val sfi = (b2 ushr 2) and 0x0F
                    val channels = ((b2 and 1) shl 2) or ((b3 ushr 6) and 3)

                    val sampleRates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
                    val sRate = if (sfi in sampleRates.indices) sampleRates[sfi] else 44100
                    val chCount = if (channels in 1..8) channels else 2

                    val csd0_0 = (((aot and 0x1F) shl 3) or ((sfi ushr 1) and 7)).toByte()
                    val csd0_1 = (((sfi and 1) shl 7) or ((channels and 0x0F) shl 3)).toByte()
                    val csd0 = byteArrayOf(csd0_0, csd0_1)

                    val rawData = payload.copyOfRange(offset + headerLen, offset + flen)
                    list.add(AacFrameInfo(sRate, chCount, csd0, rawData))
                    offset += flen
                    continue
                }
            }
            offset++
        }
        return list
    }

    private suspend fun playStream(
        inputStream: InputStream,
        surface: Surface,
        onStatus: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        isPlaying = true
        hasAudioPlayedFirstPcm = false
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
            onStatus("تهيئة فك العتاد MediaCodec: دقة ${width}x${height}، مِرماز $codecName")

            // 1. تهيئة مفكك الفيديو
            val vFormat = MediaFormat.createVideoFormat(mimeType, width, height)
            videoDecoder = MediaCodec.createDecoderByType(mimeType).apply {
                configure(vFormat, surface, null, 0)
                start()
            }

            // 2. خيط معالجة وتغذية الصوت (Audio Thread مع دعم فك CSD-0 المباشر)
            val audioThread = Thread({
                val aBufInfo = MediaCodec.BufferInfo()
                var currentDecoder: MediaCodec? = null

                while (isPlaying) {
                    try {
                        val packet = audioQueue.poll(20, TimeUnit.MILLISECONDS)
                        if (packet != null) {
                            val frames = extractRawAacFrames(packet.payload)
                            if (frames.isNotEmpty()) {
                                // تهيئة أو تحديث مفكك الصوت بناءً على مواصفات الإطار الأولى
                                if (currentDecoder == null) {
                                    val first = frames.first()
                                    try {
                                        val aFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, first.sampleRate, first.channelCount)
                                        aFormat.setByteBuffer("csd-0", ByteBuffer.wrap(first.csd0))
                                        val dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                                            configure(aFormat, null, null, 0)
                                            start()
                                        }
                                        currentDecoder = dec
                                        audioDecoder = dec
                                        initAudioTrack(first.sampleRate, first.channelCount, onStatus)
                                        onStatus("تم تشغيل مفكك صوت AAC-LC ستيريو (${first.sampleRate} Hz) بنجاح")
                                    } catch (e: Exception) {
                                        onStatus("تنبيه مفكك الصوت: ${e.message}")
                                    }
                                }

                                currentDecoder?.let { dec ->
                                    var framePtsUs = packet.pts * 1000L
                                    for (frame in frames) {
                                        var inIndex = dec.dequeueInputBuffer(4000)
                                        var retries = 0
                                        while (inIndex < 0 && isPlaying && retries < 3) {
                                            drainAudio(dec, aBufInfo, onStatus)
                                            inIndex = dec.dequeueInputBuffer(4000)
                                            retries++
                                        }
                                        if (inIndex >= 0) {
                                            val inBuf = dec.getInputBuffer(inIndex)
                                            inBuf?.clear()
                                            inBuf?.put(frame.rawData)
                                            dec.queueInputBuffer(inIndex, 0, frame.rawData.size, framePtsUs, 0)
                                            val frameDurUs = (1024L * 1000_000L) / frame.sampleRate
                                            framePtsUs += frameDurUs
                                        }
                                    }
                                }
                            }
                        }

                        currentDecoder?.let { dec ->
                            drainAudio(dec, aBufInfo, onStatus)
                        }
                    } catch (e: Exception) {
                        // ignore error during audio playback
                    }
                }
            }, "OPSX-AudioWorker")
            audioThread.start()

            // 3. خيط معالجة وعرض الفيديو المتناسق 1.0x (Video Thread)
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

            // 4. حلقة القراءة والتفكيك الرئيسية (Reader Loop)
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

    private fun drainAudio(aDecoder: MediaCodec, aBufInfo: MediaCodec.BufferInfo, onStatus: (String) -> Unit) {
        while (isPlaying) {
            val outIndex = aDecoder.dequeueOutputBuffer(aBufInfo, 2000)
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFmt = aDecoder.outputFormat
                val sRate = if (newFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) newFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
                val chCount = if (newFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
                initAudioTrack(sRate, chCount, onStatus)
            } else if (outIndex >= 0) {
                val outBuf = aDecoder.getOutputBuffer(outIndex)
                if (outBuf != null && aBufInfo.size > 0) {
                    val pcmData = ByteArray(aBufInfo.size)
                    outBuf.position(aBufInfo.offset)
                    outBuf.get(pcmData, 0, aBufInfo.size)
                    val written = audioTrack?.write(pcmData, 0, pcmData.size) ?: -1
                    if (!hasAudioPlayedFirstPcm && written > 0) {
                        hasAudioPlayedFirstPcm = true
                        onStatus("نجاح إخراج الصوت: بدأ ضخ دفق PCM إلى مكبر الصوت مباشرة")
                    }
                }
                aDecoder.releaseOutputBuffer(outIndex, false)
            } else {
                break
            }
        }
    }

    fun stop() {
        isPlaying = false
        hasAudioPlayedFirstPcm = false
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
