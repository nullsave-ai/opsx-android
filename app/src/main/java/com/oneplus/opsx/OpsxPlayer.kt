package com.oneplus.opsx

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

    private var decoder: MediaCodec? = null
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
            conn.readTimeout = 12000
            val inputStream = conn.getInputStream()
            playStream(inputStream, surface, isLive = true, onStatus = onStatus)
        } catch (e: Exception) {
            onStatus("خطأ في تشغيل الدفق المباشر: ${e.message}")
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

            if (width <= 0) width = 1280
            if (height <= 0) height = 720

            val mimeType = if (codecId.toInt() == 0x0002) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            val codecName = if (codecId.toInt() == 0x0002) "HEVC (H.265)" else "AVC (H.264)"
            onStatus("تهيئة فك العتاد MediaCodec: مسار $trackId، دقة ${width}x${height}، مِرماز $codecName")

            val format = MediaFormat.createVideoFormat(mimeType, width, height)
            decoder = MediaCodec.createDecoderByType(mimeType)
            decoder?.configure(format, surface, null, 0)
            decoder?.start()

            val bufferInfo = MediaCodec.BufferInfo()
            val chunkHeader = ByteArray(28)
            val crcBuf = ByteArray(4)
            var frameCount = 0L

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

                // فحص النزاهة السريع عبر Rust JNI
                val isPayloadValid = OpsxNativeBridge.verify(payload, storedPayloadCrc)
                if (!isPayloadValid) {
                    onStatus("تنبيه: تم رفض إطار تالف CRC عند PTS=$pts")
                    continue
                }

                // تمرير الإطار إلى مفكك العتاد MediaCodec
                val inIndex = decoder?.dequeueInputBuffer(10000) ?: -1
                if (inIndex >= 0) {
                    val inBuffer = decoder?.getInputBuffer(inIndex)
                    inBuffer?.clear()
                    inBuffer?.put(payload)
                    decoder?.queueInputBuffer(inIndex, 0, payload.size, pts * 1000, 0)
                }

                val outIndex = decoder?.dequeueOutputBuffer(bufferInfo, 10000) ?: -1
                if (outIndex >= 0) {
                    decoder?.releaseOutputBuffer(outIndex, true)
                    frameCount++
                    if (frameCount % 60L == 0L) {
                        onStatus("عرض مباشر: تم بث $frameCount إطار عتادياً بدون تقطيع")
                    }
                }

                if (!isLive) {
                    Thread.sleep(30)
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
            decoder?.stop()
            decoder?.release()
        } catch (ignored: Exception) {}
        decoder = null
    }
}
