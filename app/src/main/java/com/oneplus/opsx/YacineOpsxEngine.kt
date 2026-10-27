package com.oneplus.opsx

import android.util.Base64
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.LinkedHashSet
import java.util.concurrent.CopyOnWriteArrayList

/**
 * محرك البث والفك المباشر لمنظومة OPSF / OPSX
 * يدعم:
 * 1. فك روابط قنوات ياسين مباشرة (فك base64 والاتصال المباشر بمخدمات البث دون الحاجة لوسيط).
 * 2. استقبال أي رابط بث مخصص (M3U8 / TS / Cloudflare / Direct Origin).
 * 3. تحويل الدفق فورياً عبر نواة Rust الأصلية (libopsx_core.so) إلى مقاطع OPSX وبثها محلياً.
 */
class YacineOpsxEngine {

    @Volatile
    private var isRunning = false
    private var serverSocket: ServerSocket? = null
    private val clientSockets = CopyOnWriteArrayList<Socket>()
    private val seenChunkIds = Collections.synchronizedSet(object : LinkedHashSet<String>() {
        override fun add(element: String): Boolean {
            if (size > 300) {
                val it = iterator()
                if (it.hasNext()) {
                    it.next()
                    it.remove()
                }
            }
            return super.add(element)
        }
    })
    private var basePtsCounter = 0L
    private var activePort = 8989

    fun startStream(
        targetUrl: String,
        onUrlReady: (String) -> Unit,
        onStatus: (String) -> Unit
    ) {
        stop()
        isRunning = true

        // 1. تشغيل خادم بث OPSX المحلي مع دعم reuseAddress والمنافذ البديلة
        Thread {
            try {
                var sSocket: ServerSocket? = null
                for (p in listOf(8989, 8990, 8991, 8992, 8993)) {
                    try {
                        sSocket = ServerSocket().apply {
                            reuseAddress = true
                            bind(InetSocketAddress(p))
                        }
                        activePort = p
                        break
                    } catch (e: Exception) {
                        // منفذ مشغول، تجربة المنفذ التالي
                    }
                }

                if (sSocket == null) {
                    onStatus("خطأ: تعذر حجز منفذ محلي لخادم OPSX")
                    return@Thread
                }

                serverSocket = sSocket
                val localOpsxUrl = "http://127.0.0.1:$activePort/live.opsx"
                onUrlReady(localOpsxUrl)
                onStatus("خادم OPSX المحلي نشط على المنفذ $activePort (نواة Rust الأصلية متصلة)")

                while (isRunning) {
                    val client = serverSocket?.accept() ?: break
                    clientSockets.add(client)

                    val httpResponse = (
                        "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/octet-stream\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(Charsets.UTF_8)
                    client.getOutputStream().write(httpResponse)

                    // إرسال رأس ملف OPSX العام (40 بايت)
                    val header = ByteBuffer.allocate(40).order(ByteOrder.BIG_ENDIAN)
                    header.put("OPSX".toByteArray(Charsets.US_ASCII))
                    header.put(1.toByte())
                    header.put(1.toByte())
                    header.putShort(40.toShort())
                    header.putInt(90000)
                    header.putLong(0L)
                    header.put(1.toByte())
                    header.put(ByteArray(3))
                    header.putInt(0)
                    header.put(ByteArray(12))

                    val rawHeader = header.array()
                    val crc = OpsxNativeBridge.computeCrc(rawHeader)
                    ByteBuffer.wrap(rawHeader).order(ByteOrder.BIG_ENDIAN).putInt(24, crc)
                    client.getOutputStream().write(rawHeader)

                    // إرسال واصف المسار الأولي (28 بايت)
                    val trackDesc = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
                    trackDesc.put(1.toByte())       // Track ID
                    trackDesc.put(1.toByte())       // Video
                    trackDesc.putShort(1.toShort()) // H.264
                    trackDesc.putInt(90000)
                    trackDesc.putShort(1920.toShort())
                    trackDesc.putShort(1080.toShort())
                    trackDesc.putInt(0)
                    trackDesc.put(0.toByte())
                    trackDesc.put(ByteArray(5))
                    trackDesc.putShort(0.toShort())

                    val rawTrack = trackDesc.array()
                    val trackCrc = OpsxNativeBridge.computeCrc(rawTrack)
                    client.getOutputStream().write(rawTrack)
                    client.getOutputStream().write(
                        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(trackCrc).array()
                    )
                }
            } catch (e: Exception) {
                if (isRunning) onStatus("تنبيه الخادم المحلي: ${e.message}")
            }
        }.start()

        // 2. سحب وتحويل الدفق المباشر
        Thread {
            onStatus("بدء الاتصال بمصدر البث: $targetUrl")
            while (isRunning) {
                try {
                    processStreamInput(targetUrl, onStatus)
                } catch (e: Exception) {
                    if (isRunning) {
                        onStatus("محاولة إعادة الاتصال بمصدر البث: ${e.message}")
                        try { Thread.sleep(2000) } catch (ignored: Exception) {}
                    }
                }
            }
        }.start()
    }

    private fun processStreamInput(inputUrl: String, onStatus: (String) -> Unit) {
        val resolvedUrl = resolveTargetUrl(inputUrl)

        val conn = URL(resolvedUrl).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        conn.setRequestProperty("Referer", "https://yacineapp.tv/")
        conn.connectTimeout = 8000
        conn.readTimeout = 8000

        val contentType = conn.contentType ?: ""
        if (contentType.contains("mpegurl", ignoreCase = true) || resolvedUrl.contains(".m3u8", ignoreCase = true) || inputUrl.contains("/live?ch=") || inputUrl.contains("|")) {
            // معالجة قائمة HLS ومنع تكرار أي مقطع باستهداف المعرف الفريد للقطعة
            val lines = conn.inputStream.bufferedReader().readLines()
            val rawLines = lines.filter { it.isNotBlank() && !it.startsWith("#") }

            for (rawLine in rawLines) {
                if (!isRunning) break
                val actualChunkUrl = resolveChunkDirectUrl(resolvedUrl, rawLine)
                val chunkKey = extractChunkIdentifier(actualChunkUrl)

                if (!seenChunkIds.contains(chunkKey)) {
                    seenChunkIds.add(chunkKey)
                    downloadAndDemuxChunk(actualChunkUrl, onStatus)
                }
            }
            try { Thread.sleep(1500) } catch (ignored: Exception) {}
        } else {
            // دفق TS مباشر متصل
            val inputStream = conn.inputStream
            val buffer = ByteArray(188 * 100) // 18.8 KB حزم TS
            while (isRunning) {
                var bytesRead = 0
                while (bytesRead < buffer.size) {
                    val r = inputStream.read(buffer, bytesRead, buffer.size - bytesRead)
                    if (r == -1) break
                    bytesRead += r
                }
                if (bytesRead == 0) break

                val chunkData = if (bytesRead == buffer.size) buffer else buffer.copyOfRange(0, bytesRead)
                val t0 = System.nanoTime()
                val opsxData = OpsxNativeBridge.demuxTsToOpsx(chunkData, basePtsCounter)
                val durationMs = (System.nanoTime() - t0) / 1_000_000.0

                basePtsCounter += 2000L
                broadcastChunk(opsxData)
            }
        }
    }

    /**
     * فك الرابط والتعامل مع روابط ياسين المباشرة
     */
    private fun resolveTargetUrl(input: String): String {
        val trimmed = input.trim()
        return when {
            trimmed.matches(Regex("^[0-9]+\\|[0-9]+$")) -> {
                "https://null-stream.nullsave-ai.workers.dev/live?ch=$trimmed"
            }
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> {
                trimmed
            }
            else -> {
                "https://null-stream.nullsave-ai.workers.dev/live?ch=1080|1"
            }
        }
    }

    /**
     * فك تشفير وتوجيه قطعة TS:
     * إذا احتوت على ?b64= يتم فك Base64 والاتصال مباشرة بمخدم البث الأصلي بدون وسيط
     */
    private fun resolveChunkDirectUrl(baseUrl: String, rawLine: String): String {
        var finalUrl = rawLine
        if (!finalUrl.startsWith("http://") && !finalUrl.startsWith("https://")) {
            val basePrefix = baseUrl.substringBeforeLast('/')
            finalUrl = "$basePrefix/$finalUrl"
        }

        if (finalUrl.contains("b64=")) {
            try {
                val b64Part = finalUrl.substringAfter("b64=").substringBefore('&')
                val decodedBytes = Base64.decode(b64Part, Base64.DEFAULT)
                val decodedUrl = String(decodedBytes, Charsets.UTF_8).trim()
                if (decodedUrl.startsWith("http://") || decodedUrl.startsWith("https://")) {
                    return decodedUrl
                }
            } catch (e: Exception) {
                // المتابعة بالرابط كما هو في حال تعذر الفك
            }
        }
        return finalUrl
    }

    private fun extractChunkIdentifier(url: String): String {
        val clean = url.substringBefore('?')
        val filename = clean.substringAfterLast('/')
        return if (filename.isNotBlank() && filename.contains('.')) filename else url
    }

    private fun downloadAndDemuxChunk(chunkUrl: String, onStatus: (String) -> Unit) {
        try {
            val chunkConn = URL(chunkUrl).openConnection() as HttpURLConnection
            chunkConn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            chunkConn.setRequestProperty("Referer", "https://yacineapp.tv/")
            chunkConn.connectTimeout = 7000
            chunkConn.readTimeout = 7000

            val tsData = chunkConn.inputStream.readBytes()
            if (tsData.isEmpty()) return

            val tStart = System.nanoTime()
            val opsxPackets = OpsxNativeBridge.demuxTsToOpsx(tsData, basePtsCounter)
            val elapsedMs = (System.nanoTime() - tStart) / 1_000_000.0

            basePtsCounter += 2000L
            val kb = tsData.size / 1024
            onStatus("معالجة محرك Rust: استخراج $kb KB من البث -> تحويلها إلى OPSX في ${String.format("%.2f", elapsedMs)} ms")

            broadcastChunk(opsxPackets)
        } catch (e: Exception) {
            onStatus("تنبيه في سحب القطعة: ${e.message}")
        }
    }

    private fun broadcastChunk(data: ByteArray) {
        if (data.isEmpty()) return
        val dead = mutableListOf<Socket>()
        for (sock in clientSockets) {
            try {
                sock.getOutputStream().write(data)
            } catch (e: Exception) {
                dead.add(sock)
            }
        }
        clientSockets.removeAll(dead)
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (e: Exception) {}
        serverSocket = null
        for (s in clientSockets) {
            try { s.close() } catch (e: Exception) {}
        }
        clientSockets.clear()
    }
}
