package com.oneplus.opsx

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OpsxPlayer {

    private var decoder: MediaCodec? = null
    private var isPlaying = false

    suspend fun play(opsxFile: File, surface: Surface, onStatus: (String) -> Unit) = withContext(Dispatchers.IO) {
        if (!opsxFile.exists()) {
            onStatus("خطأ: ملف OPSX غير موجود.")
            return@withContext
        }

        val fis = FileInputStream(opsxFile)
        val headerBytes = ByteArray(40)
        fis.read(headerBytes)

        val headerBuf = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(4)
        headerBuf.get(magic)
        if (String(magic) != "OPSX") {
            onStatus("خطأ: ملف غير صالح (Magic غير مطابق).")
            fis.close()
            return@withContext
        }

        // قراءة واصف المسار الأول
        val trackHeader = ByteArray(28)
        fis.read(trackHeader)
        val trackBuf = ByteBuffer.wrap(trackHeader).order(ByteOrder.BIG_ENDIAN)
        val trackId = trackBuf.get()
        val trackType = trackBuf.get()
        val codecId = trackBuf.short
        trackBuf.int // timescale
        val width = trackBuf.short.toInt()
        val height = trackBuf.short.toInt()

        onStatus("تم التحقق من دفق OPSX: المسار $trackId، الدقة ${width}x${height}، مِرماز: H.264")

        // تهيئة MediaCodec لفك التشفير وعرضه على السطح مباشرة
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        decoder?.configure(format, surface, null, 0)
        decoder?.start()

        isPlaying = true
        val bufferInfo = MediaCodec.BufferInfo()

        try {
            while (isPlaying && fis.available() > 0) {
                val chunkHeader = ByteArray(28)
                val readH = fis.read(chunkHeader)
                if (readH < 28) break

                val cBuf = ByteBuffer.wrap(chunkHeader).order(ByteOrder.BIG_ENDIAN)
                val chunkType = cBuf.get()
                val cTrackId = cBuf.get()
                val flags = cBuf.get()
                cBuf.get()
                val pts = cBuf.long
                val dts = cBuf.long
                val payloadSize = cBuf.int
                val storedHeaderCrc = cBuf.int

                val payload = ByteArray(payloadSize)
                fis.read(payload)

                val storedPayloadCrcBuf = ByteArray(4)
                fis.read(storedPayloadCrcBuf)
                val storedPayloadCrc = ByteBuffer.wrap(storedPayloadCrcBuf).order(ByteOrder.BIG_ENDIAN).int

                // فحص CRC32 عبر محرك Rust السريع
                val isPayloadValid = OpsxNativeBridge.verify(payload, storedPayloadCrc)
                if (!isPayloadValid) {
                    onStatus("تحذير: تلف في فحص CRC للمقطع عند PTS=$pts")
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
                    // render = true لعرض الإطار فوراً على الشاشة
                    decoder?.releaseOutputBuffer(outIndex, true)
                }

                Thread.sleep(30) // محاكاة معدل الإطارات 30fps
            }
            onStatus("اكتمل تشغيل مقطع OPSX بنجاح.")
        } catch (e: Exception) {
            onStatus("استثناء أثناء التشغيل: ${e.message}")
        } finally {
            fis.close()
            decoder?.stop()
            decoder?.release()
        }
    }

    fun stop() {
        isPlaying = false
    }
}
