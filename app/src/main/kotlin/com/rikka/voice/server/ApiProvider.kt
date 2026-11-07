package com.rikka.voice.server

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import com.rikka.voice.core.Engines
import com.rikka.voice.core.Wav
import org.json.JSONObject
import java.io.FileNotFoundException

/**
 * 直连通道：六花日语通过系统的 ContentProvider 机制直接调用，不开任何网络端口，
 * 所以不受系统端口限制、防火墙模块、后台清理影响——六花一调用，系统就会把六花声库拉起来。
 *
 *   contentResolver.call(Uri.parse("content://com.rikka.voice.api"), "voices", null, null)  → Bundle["json"]
 *   contentResolver.call(..., "info", null, null)                                            → Bundle["json"]
 *   contentResolver.openInputStream(Uri.parse("content://com.rikka.voice.api/tts?q=" + Uri.encode(json)))  → WAV
 *   （json 同 HTTP 接口 /rikka/tts：{"text","speaker","speed","pitch","intonation","volume"}）
 */
class ApiProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        Engines.init(context!!)
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        Engines.awaitFirstLoad()
        val json = when (method) {
            "voices" -> Api.voicesJson().toString()
            "info" -> Api.info().toString()
            "refresh" -> { Engines.refresh(); "{}" }
            else -> JSONObject().put("error", "unknown method $method").toString()
        }
        return Bundle().apply { putString("json", json) }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (uri.path?.trimEnd('/') != "/tts") throw FileNotFoundException("没有这个接口：${uri.path}")
        val q = uri.getQueryParameter("q") ?: throw FileNotFoundException("缺少参数 q")
        Engines.awaitFirstLoad()
        // 在调用线程里同步合成：出错时直接抛给六花，错误信息能原样显示
        val wav = try {
            val (speaker, text, params) = Api.parseTts(JSONObject(q))
            require(text.isNotBlank()) { "没有要朗读的文字" }
            Wav.encode(Engines.synthesize(speaker?.takeIf { it >= 0 }, text, params))
        } catch (t: Throwable) {
            Log.w("RikkaProvider", "tts failed", t)
            throw FileNotFoundException(Engines.explain(t))
        }
        val pipe = ParcelFileDescriptor.createPipe()
        Thread {
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(wav) }
        }.start()
        return pipe[0]
    }

    override fun getType(uri: Uri): String = "audio/wav"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0
}
