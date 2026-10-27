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

class OpsxPlayer {

    private var videoDecoder: MediaCodec? = null
    private var audioDecoder: MediaCodec? = null
    private var audioTrack: AudioTrack? = null

    @Volatile
    private var isPlaying = false

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
            playStream(inputStream, surface, isLive = true, onStatus = onStatus)
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
        playStream(fis, surface, isLive = false, onStatus = onStatus)
    }

    private fun initAudioTrack(sampleRate: Int, channelCount: Int) {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (ignored: Exception) {}

        val channelConfig = if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setBufferSizeInBytes(Math.max(minBuf * 2, 32768))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack?.play()
    }

    suspend fun playStream(
        inputStream: InputStream,
        surface: Surface,
        isLive: Boolean,
        onStatus: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        isPlaying = true
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
            onStatus("تهيئة فك العتاد MediaCodec: مسار $trackId، دقة ${width}x${height}، مِرماز $codecName + صوت ستيريو AAC")

            // 1. تهيئة مفكك الفيديو
            val vFormat = MediaFormat.createVideoFormat(mimeType, width, height)
            videoDecoder = MediaCodec.createDecoderByType(mimeType)
            videoDecoder?.configure(vFormat, surface, null, 0)
            videoDecoder?.start()

            // 2. تهيئة مفكك الصوت AAC مع دعم ADTS التلقائي
            try {
                val aFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 2)
                aFormat.setInteger(MediaFormat.KEY_IS_ADTS, 1)
                audioDecoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                audioDecoder?.configure(aFormat, null, null, 0)
                audioDecoder?.start()
                initAudioTrack(48000, 2)
            } catch (e: Exception) {
                onStatus("تنبيه الصوت: ${e.message}")
            }

            val vBufferInfo = MediaCodec.BufferInfo()
            val aBufferInfo = MediaCodec.BufferInfo()
            val chunkHeader = ByteArray(28)
            val crcBuf = ByteArray(4)
            var frameCount = 0L

            var playbackStartRealtimeNs = -1L
            var playbackStartPtsMs = -1L

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

                if (cTrackId.toInt() == 2 || chunkType.toInt() == 2) {
                    // معالجة وإخراج الصوت (Audio Track)
                    audioDecoder?.let { aDecoder ->
                        val inIndex = aDecoder.dequeueInputBuffer(4000)
                        if (inIndex >= 0) {
                            val inBuf = aDecoder.getInputBuffer(inIndex)
                            inBuf?.clear()
                            inBuf?.put(payload)
                            aDecoder.queueInputBuffer(inIndex, 0, payload.size, pts * 1000, 0)
                        }

                        var outIndex = aDecoder.dequeueOutputBuffer(aBufferInfo, 0)
                        while (outIndex >= 0 || outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                val newFmt = aDecoder.outputFormat
                                val sRate = newFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                val chCount = newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                initAudioTrack(sRate, chCount)
                            } else if (outIndex >= 0) {
                                val outBuf = aDecoder.getOutputBuffer(outIndex)
                                if (outBuf != null && aBufferInfo.size > 0) {
                                    val pcmData = ByteArray(aBufferInfo.size)
                                    outBuf.position(aBufferInfo.offset)
                                    outBuf.get(pcmData, 0, aBufferInfo.size)
                                    audioTrack?.write(pcmData, 0, pcmData.size)
                                }
                                aDecoder.releaseOutputBuffer(outIndex, false)
                            }
                            outIndex = aDecoder.dequeueOutputBuffer(aBufferInfo, 0)
                        }
                    }
                } else {
                    // معالجة وإخراج الفيديو (Video Track)
                    videoDecoder?.let { vDecoder ->
                        val inIndex = vDecoder.dequeueInputBuffer(8000)
                        if (inIndex >= 0) {
                            val inBuffer = vDecoder.getInputBuffer(inIndex)
                            inBuffer?.clear()
                            inBuffer?.put(payload)
                            vDecoder.queueInputBuffer(inIndex, 0, payload.size, pts * 1000, 0)
                        }

                        val outIndex = vDecoder.dequeueOutputBuffer(vBufferInfo, 8000)
                        if (outIndex >= 0) {
                            // ضبط دقيق لسرعة البث الطبيعية 1.0x بدون بطء ولا تسريع
                            if (playbackStartRealtimeNs == -1L) {
                                playbackStartRealtimeNs = System.nanoTime()
                                playbackStartPtsMs = pts
                            }

                            val elapsedRealtimeMs = (System.nanoTime() - playbackStartRealtimeNs) / 1_000_000L
                            val targetStreamMs = pts - playbackStartPtsMs
                            val diffMs = targetStreamMs - elapsedRealtimeMs

                            if (diffMs in 2..35) {
                                try { Thread.sleep(diffMs) } catch (ignored: Exception) {}
                            }

                            vDecoder.releaseOutputBuffer(outIndex, true)
                            frameCount++
                            if (frameCount % 90L == 0L) {
                                onStatus("عرض مباشر متزامن: تم بث $frameCount إطار عتادياً بالصوت والصورة")
                            }
                        }
                    }
                }
            }
            onStatus("تم إنهاء جلسة التشغيل.")
        } catch (e: Exception) {
            if (isPlaying) onStatus("تنبيه أثناء التشغيل: ${e.message}")
        } finally {
            stop()
            try { inputStream.close() } catch (ignored: Exception) {}
        }
    }

    fun stop() {
        isPlaying = false
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
