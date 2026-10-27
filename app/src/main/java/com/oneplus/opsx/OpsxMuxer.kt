package com.oneplus.opsx

import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * مغلف صيغة OPSX v1.0
 * يكتب الرؤوس الثنائية (40 بايت)، واصفات المسارات، والمقاطع (28 بايت) بترتيب Big-Endian
 */
class OpsxMuxer(private val outputStream: FileOutputStream) {

    private val indexEntries = mutableListOf<IndexEntry>()
    private var bytesWritten = 0L

    data class IndexEntry(val pts: Long, val offset: Long, val trackId: Int)

    fun writeHeader(timescale: Int = 90000, trackCount: Int = 1) {
        val buf = ByteBuffer.allocate(40).order(ByteOrder.BIG_ENDIAN)
        buf.put("OPSX".toByteArray(Charsets.US_ASCII)) // 4
        buf.put(1.toByte())                            // Version: 1
        buf.put(1.toByte())                            // Flags: Live
        buf.putShort(40.toShort())                     // Header Size: 40
        buf.putInt(timescale)                          // Timescale: 90000
        buf.putLong(0L)                                // Duration: 0
        buf.put(trackCount.toByte())                   // Track Count
        buf.put(ByteArray(3))                          // Reserved1

        // حساب CRC32 للرأس الأولي مع صفر
        buf.putInt(0)
        buf.put(ByteArray(12))                         // Reserved2

        val rawHeader = buf.array()
        val crc = OpsxNativeBridge.computeCrc(rawHeader)

        // إعادة كتابة الـ CRC الحقيقي عند الإزاحة 24
        val finalBuf = ByteBuffer.wrap(rawHeader).order(ByteOrder.BIG_ENDIAN)
        finalBuf.position(24)
        finalBuf.putInt(crc)

        outputStream.write(rawHeader)
        bytesWritten += 40
    }

    fun writeTrackDescriptor(trackId: Int, trackType: Int, codecId: Int, width: Int, height: Int, codecConfig: ByteArray = ByteArray(0)) {
        val totalSize = 24 + codecConfig.size
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)

        buf.put(trackId.toByte())
        buf.put(trackType.toByte())
        buf.putShort(codecId.toShort())
        buf.putInt(90000)
        buf.putShort(width.toShort())
        buf.putShort(height.toShort())
        buf.putInt(0)                                  // Sample rate
        buf.put(0.toByte())                            // Channels
        buf.put(ByteArray(5))                          // Reserved
        buf.putShort(codecConfig.size.toShort())       // Config Size
        if (codecConfig.isNotEmpty()) {
            buf.put(codecConfig)
        }

        val trackData = buf.array()
        val crc = OpsxNativeBridge.computeCrc(trackData)

        outputStream.write(trackData)
        val crcBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(crc).array()
        outputStream.write(crcBuf)
        bytesWritten += (totalSize + 4)
    }

    fun writeChunk(trackId: Int, isKeyframe: Boolean, pts: Long, payload: ByteArray) {
        val chunkOffset = bytesWritten
        if (isKeyframe) {
            indexEntries.add(IndexEntry(pts, chunkOffset, trackId))
        }

        val headerBuf = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
        headerBuf.put(1.toByte())                      // Chunk Type: Video
        headerBuf.put(trackId.toByte())
        headerBuf.put(if (isKeyframe) 1.toByte() else 0.toByte())
        headerBuf.put(0.toByte())
        headerBuf.putLong(pts)
        headerBuf.putLong(pts)
        headerBuf.putInt(payload.size)

        val first24 = headerBuf.array()
        val headerCrc = OpsxNativeBridge.computeCrc(first24)
        val payloadCrc = OpsxNativeBridge.computeCrc(payload)

        // كتابة الرأس 28 بايت
        outputStream.write(first24)
        outputStream.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(headerCrc).array())
        // كتابة الحمولة
        outputStream.write(payload)
        // كتابة data_crc32
        outputStream.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(payloadCrc).array())

        bytesWritten += (28 + payload.size + 4)
    }

    fun close(finalPts: Long) {
        // كتابة كتلة الفهرس IDX
        val indexOffset = bytesWritten
        val idxBody = ByteBuffer.allocate(8 + indexEntries.size * 20).order(ByteOrder.BIG_ENDIAN)
        idxBody.put("IDX\u0000".toByteArray(Charsets.US_ASCII))
        idxBody.putInt(indexEntries.size)

        for (e in indexEntries) {
            idxBody.putLong(e.pts)
            idxBody.putLong(e.offset)
            idxBody.put(e.trackId.toByte())
            idxBody.put(ByteArray(3))
        }

        val idxRaw = idxBody.array()
        val idxCrc = OpsxNativeBridge.computeCrc(idxRaw)
        outputStream.write(idxRaw)
        outputStream.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(idxCrc).array())

        // كتابة نهاية البث END (24 بايت)
        val eosBuf = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
        eosBuf.put("END\u0000".toByteArray(Charsets.US_ASCII))
        eosBuf.putLong(finalPts)
        eosBuf.putLong(indexOffset)
        val eosRaw = eosBuf.array()
        val eosCrc = OpsxNativeBridge.computeCrc(eosRaw)

        outputStream.write(eosRaw)
        outputStream.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(eosCrc).array())

        outputStream.flush()
        outputStream.close()
    }
}
