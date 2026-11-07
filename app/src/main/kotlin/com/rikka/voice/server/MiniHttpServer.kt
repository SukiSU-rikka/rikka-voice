package com.rikka.voice.server

import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    val bodyText: String get() = String(body, Charsets.UTF_8)
}

class HttpResponse(val status: Int, val type: String, val body: ByteArray, val extra: Map<String, String> = emptyMap()) {
    companion object {
        fun json(text: String, status: Int = 200) = HttpResponse(status, "application/json; charset=utf-8", text.toByteArray())
        fun text(text: String, status: Int = 200) = HttpResponse(status, "text/plain; charset=utf-8", text.toByteArray())
        fun wav(bytes: ByteArray, extra: Map<String, String> = emptyMap()) = HttpResponse(200, "audio/wav", bytes, extra)
        fun error(status: Int, msg: String) = json(org.json.JSONObject().put("detail", msg).toString(), status)
    }
}

/**
 * 极简 HTTP/1.1 服务器：只绑 127.0.0.1，不依赖第三方库。
 * 每个连接处理一个请求（Connection: close），足够本机 App 调用。
 */
class MiniHttpServer(private val port: Int, private val handler: (HttpRequest) -> HttpResponse) {
    private var socket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4)
    @Volatile var running = false; private set

    fun start() {
        val ss = ServerSocket()
        try {
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 32)
        } catch (e: Exception) {
            runCatching { ss.close() }
            throw e
        }
        socket = ss; running = true
        Thread({
            while (running) {
                val client = try { ss.accept() } catch (e: Exception) { break }
                pool.execute { serve(client) }
            }
        }, "rikka-http").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        pool.shutdownNow(); runCatching { pool.awaitTermination(1, TimeUnit.SECONDS) }
    }

    private fun serve(s: Socket) {
        s.use { sock ->
            sock.soTimeout = 30_000
            val input = BufferedInputStream(sock.getInputStream())
            val out = sock.getOutputStream()
            val resp = try {
                val req = parse(input) ?: return
                if (req.method == "OPTIONS") HttpResponse(204, "text/plain", ByteArray(0)) else handler(req)
            } catch (e: Throwable) {
                Log.w("RikkaHttp", "request failed", e)
                HttpResponse.error(500, e.message ?: e.toString())
            }
            write(out, resp)
        }
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > 16 * 1024) error("请求头过长")
        }
        return buf.toString("UTF-8")
    }

    private fun parse(input: InputStream): HttpRequest? {
        val line = readLine(input) ?: return null
        val parts = line.split(" ")
        if (parts.size < 2) return null
        val headers = mutableMapOf<String, String>()
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            val i = h.indexOf(':'); if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        require(len <= 2 * 1024 * 1024) { "请求体过大" }
        val body = ByteArray(len)
        var read = 0
        while (read < len) { val n = input.read(body, read, len - read); if (n < 0) break; read += n }
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = if ('?' in target) target.substringAfter('?').split('&').filter { it.isNotEmpty() }.associate {
            val k = it.substringBefore('='); val v = it.substringAfter('=', "")
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        } else emptyMap()
        return HttpRequest(parts[0].uppercase(), path, query, headers, body)
    }

    private fun write(out: OutputStream, r: HttpResponse) {
        val reason = when (r.status) { 200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 404 -> "Not Found"; 422 -> "Unprocessable Entity"; else -> "Error" }
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ${r.status} $reason\r\n")
        sb.append("Content-Type: ${r.type}\r\n")
        sb.append("Content-Length: ${r.body.size}\r\n")
        sb.append("Connection: close\r\n")
        // 六花日语是 WebView 页面：需要 CORS + Private Network Access 才能访问本机端口
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        sb.append("Access-Control-Allow-Headers: Content-Type, Authorization\r\n")
        sb.append("Access-Control-Allow-Private-Network: true\r\n")
        sb.append("Access-Control-Expose-Headers: X-Rikka-Credit, X-Rikka-Voice\r\n")
        sb.append("Access-Control-Max-Age: 600\r\n")
        for ((k, v) in r.extra) sb.append("$k: $v\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        out.write(r.body)
        out.flush()
    }
}
