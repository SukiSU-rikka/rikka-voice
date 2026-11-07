package com.rikka.voice.engines.remote

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal object Http {
    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun join(base: String, path: String): String = base.trimEnd('/') + path

    fun get(url: String, timeoutMs: Int = 120_000): ByteArray = call("GET", url, null, null, timeoutMs)

    fun post(url: String, body: ByteArray?, type: String? = "application/json", timeoutMs: Int = 180_000): ByteArray =
        call("POST", url, body, type, timeoutMs)

    private fun call(method: String, url: String, body: ByteArray?, type: String?, timeoutMs: Int): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 6000
            c.readTimeout = timeoutMs
            c.setRequestProperty("Accept", "*/*")
            if (body != null) {
                c.doOutput = true
                if (type != null) c.setRequestProperty("Content-Type", "$type; charset=utf-8")
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            } else if (method == "POST") {
                c.doOutput = true; c.setFixedLengthStreamingMode(0); c.outputStream.close()
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            if (code !in 200..299) {
                val msg = String(bytes, Charsets.UTF_8).take(300)
                throw IllegalStateException("电脑引擎返回 HTTP $code：$msg")
            }
            return bytes
        } catch (e: java.net.ConnectException) {
            throw IllegalStateException("连不上 $url：电脑端是否已启动、手机和电脑是否在同一 Wi-Fi、防火墙是否放行端口？")
        } catch (e: java.net.SocketTimeoutException) {
            throw IllegalStateException("电脑引擎响应超时（$url）")
        } finally {
            c.disconnect()
        }
    }
}
