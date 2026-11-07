package com.rikka.voice.core

object TextSplit {
    private val enders = setOf('。', '！', '？', '!', '?', '…', '\n', '」', '』')

    /** 按句子切开；单句超过 max 时在读点处再切。 */
    fun sentences(text: String, max: Int = 120): List<String> {
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        fun flush() { val s = buf.toString().trim(); if (s.isNotEmpty()) out += s; buf.clear() }
        for (c in text) {
            buf.append(c)
            if (c in enders && buf.length >= 2) flush()
            else if (buf.length >= max) {
                val cut = buf.lastIndexOfAny(charArrayOf('、', '，', ',', ' ', '　'))
                if (cut > max / 3) {
                    val rest = buf.substring(cut + 1); buf.setLength(cut + 1); flush(); buf.append(rest)
                } else flush()
            }
        }
        flush()
        return out
    }

    fun clean(text: String): String = text
        .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), "")
        .replace(Regex("[ \\t]+"), " ")
        .trim()
}
