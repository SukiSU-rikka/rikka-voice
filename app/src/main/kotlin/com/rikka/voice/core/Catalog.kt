package com.rikka.voice.core

import android.content.Context
import org.json.JSONObject

/** assets/catalog.json：可下载的模型清单。地址写在 JSON 里，失效时只改这个文件。 */
class Catalog(ctx: Context) {
    val json: JSONObject = ctx.assets.open("catalog.json").bufferedReader().use { JSONObject(it.readText()) }

    data class Item(val id: String, val title: String, val note: String, val url: String, val size: String, val target: String)

    fun item(section: String): Item {
        val o = json.getJSONObject(section)
        return Item(section, o.getString("title"), o.optString("note"), o.getString("url"), o.optString("size"), o.optString("target"))
    }

    fun vvmItems(): List<Item> {
        val o = json.getJSONObject("voicevox_vvm")
        val pattern = o.getString("urlPattern")
        val a = o.getJSONArray("items")
        return (0 until a.length()).map {
            val x = a.getJSONObject(it)
            val n = x.getString("file")
            Item("vvm:$n", "声音包 $n", x.getString("chars"), pattern.replace("{file}", n), x.optString("size"), n)
        }
    }

    fun piperFiles(): Pair<String, String> {
        val o = json.getJSONObject("piper_plus")
        return o.getString("model") to o.getString("config")
    }
}
