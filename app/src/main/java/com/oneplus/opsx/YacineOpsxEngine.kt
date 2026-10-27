package com.oneplus.opsx

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * محرك فك تشفير وتحويل قنوات ياسين المباشرة محلياً
 * يعتمد على نواة Rust الأصلية (libopsx_core.so) لفك الحزم وتغليفها كـ OPSX في الذاكرة بدون GC Overhead
 */
class YacineOpsxEngine {

    private var isRunning = false
    private var serverSocket: ServerSocket? = null
    private val clientSockets = CopyOnWriteArrayList<Socket>()
    private val seenChunkUrls = mutableSetOf<String>()
    private var basePtsCounter = 0L

    fun startNativeGateway(
        channelQuery: String = "1080|1",
        onUrlReady: (String) -> Unit,
        onStatus: (String) -> Unit
    ) {
        isRunning = true

        // 1. تشغيل خادم بث OPSX المحلي
        Thread {
            try {
                serverSocket = ServerSocket(8989)
                val localUrl = "http://127.0.0.1:8989/live.opsx"
                onUrlReady(localUrl)
                onStatus("خادم OPSX المباشر متاح على: $localUrl (مدعوم بنواة Rust)")

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
                    header.put(2.toByte())
                    header.put(ByteArray(3))
                    header.putInt(0)
                    header.put(ByteArray(12))

                    val rawHeader = header.array()
                    val crc = OpsxNativeBridge.computeCrc(rawHeader)
                    ByteBuffer.wrap(rawHeader).order(ByteOrder.BIG_ENDIAN).putInt(24, crc)
                    client.getOutputStream().write(rawHeader)
                }
            } catch (e: Exception) {
                if (isRunning) onStatus("توقف خادم OPSX: ${e.message}")
            }
        }.start()

        // 2. سحب الدفق وتمريره إلى نواة Rust لتحويله إلى OPSX فوري
        Thread {
            onStatus("جاري فك وتغليف القناة عبر محرك Rust الأصلي...")
            while (isRunning) {
                try {
                    val streamPlaylistUrl = resolveChannelDirectStream(channelQuery)
                    if (streamPlaylistUrl != null) {
                        fetchAndProcessWithRust(streamPlaylistUrl, onStatus)
                    } else {
                        Thread.sleep(3000)
                    }
                } catch (e: Exception) {
                    onStatus("إعادة المحاولة: ${e.message}")
                    Thread.sleep(2000)
                }
            }
        }.start()
    }

    private fun resolveChannelDirectStream(channelQuery: String): String? {
        val upstreamUrl = "https://null-stream.nullsave-ai.workers.dev/live?ch=$channelQuery"
        val conn = URL(upstreamUrl).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.connectTimeout = 8000
        conn.readTimeout = 8000

        return if (conn.responseCode == 200) upstreamUrl else null
    }

    private fun fetchAndProcessWithRust(playlistUrl: String, onStatus: (String) -> Unit) {
        val conn = URL(playlistUrl).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        val lines = conn.inputStream.bufferedReader().readLines()

        val newChunks = lines.filter { it.isNotBlank() && !it.startsWith("#") && !seenChunkUrls.contains(it) }

        for (chunkUrl in newChunks) {
            seenChunkUrls.add(chunkUrl)
            if (seenChunkUrls.size > 50) seenChunkUrls.clear()

            // تنزيل كتلة الـ TS
            val chunkConn = URL(chunkUrl).openConnection() as HttpURLConnection
            chunkConn.setRequestProperty("User-Agent", "Mozilla/5.0")
            val tsData = chunkConn.inputStream.readBytes()

            // تمرير مصفوفة البايتات كاملة إلى محرك Rust لتفكيكها وتغليفها دفعة واحدة
            val tStart = System.nanoTime()
            val opsxPackets = OpsxNativeBridge.demuxTsToOpsx(tsData, basePtsCounter)
            val elapsedMs = (System.nanoTime() - tStart) / 1_000_000.0

            basePtsCounter += (tsData.size / 188) * 30L

            onStatus("محرك Rust: معالجة ${tsData.size / 1024} KB من البث -> تحويلها إلى OPSX في ${String.format("%.2f", elapsedMs)} ms")

            // إرسال حزم الـ OPSX الناتجة فوراً للمشتركين
            broadcastChunk(opsxPackets)
        }
    }

    private fun broadcastChunk(data: ByteArray) {
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
        serverSocket?.close()
        for (s in clientSockets) {
            try { s.close() } catch (e: Exception) {}
        }
        clientSockets.clear()
    }
}
