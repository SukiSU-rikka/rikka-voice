package com.rikka.voice.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * 模型文件都放在 Android/data/com.rikka.voice/files/models/ 下，
 * 电脑上下载好的文件也可以直接用文件管理器拷进去（或在 App 里“导入”）。
 */
class ModelStore(private val ctx: Context) {
    val root: File = (ctx.getExternalFilesDir("models") ?: File(ctx.filesDir, "models")).apply { mkdirs() }

    fun dir(name: String) = File(root, name).apply { mkdirs() }

    /** VOICEVOX 与 piper-plus 共用的 OpenJTalk 词典目录。 */
    val openJtalkDict: File get() = File(root, "open_jtalk_dic_utf_8-1.11")
    fun hasOpenJtalkDict(): Boolean = File(openJtalkDict, "sys.dic").isFile

    fun sizeOf(f: File): Long = if (f.isDirectory) f.listFiles()?.sumOf { sizeOf(it) } ?: 0 else f.length()

    // ── 下载 ──
    fun download(url: String, dest: File, progress: (Long, Long) -> Unit, cancelled: () -> Boolean = { false }) {
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        var current = url
        var conn: HttpURLConnection
        var hops = 0
        while (true) {
            conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20000; readTimeout = 60000
                setRequestProperty("User-Agent", "RikkaVoice/1.0 (Android)")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: error("下载重定向缺少地址")
                current = URL(URL(current), loc).toString(); conn.disconnect()
                if (++hops > 8) error("下载重定向次数过多"); continue
            }
            if (code !in 200..299) { conn.disconnect(); error("下载失败：HTTP $code（$current）") }
            break
        }
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            part.outputStream().use { out ->
                val buf = ByteArray(256 * 1024); var done = 0L; var last = 0L
                while (true) {
                    if (cancelled()) { part.delete(); error("已取消") }
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); done += n
                    if (done - last > 512 * 1024) { progress(done, total); last = done }
                }
                progress(done, total)
            }
        }
        conn.disconnect()
        if (dest.exists()) dest.deleteRecursively()
        check(part.renameTo(dest)) { "无法保存下载的文件" }
    }

    // ── 解压 ──
    fun isArchive(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".zip") || n.endsWith(".tar.gz") || n.endsWith(".tgz") ||
            n.endsWith(".tar.bz2") || n.endsWith(".tbz2") || n.endsWith(".tar")
    }

    /** 解压到 destDir；如果压缩包里只有一个顶层文件夹，会把里面的内容提上来。 */
    fun extract(archive: File, destDir: File, displayName: String = archive.name) {
        val tmp = File(destDir.parentFile, destDir.name + ".unpack").apply { deleteRecursively(); mkdirs() }
        val n = displayName.lowercase()
        BufferedInputStream(FileInputStream(archive), 1 shl 16).use { raw ->
            when {
                n.endsWith(".zip") -> unzip(raw, tmp)
                n.endsWith(".tar.gz") || n.endsWith(".tgz") -> untar(GzipCompressorInputStream(raw), tmp)
                n.endsWith(".tar.bz2") || n.endsWith(".tbz2") -> untar(BZip2CompressorInputStream(raw), tmp)
                n.endsWith(".tar") -> untar(raw, tmp)
                else -> error("不认识的压缩格式：$displayName")
            }
        }
        val kids = tmp.listFiles()?.filter { !it.name.startsWith(".") } ?: emptyList()
        val src = if (kids.size == 1 && kids[0].isDirectory) kids[0] else tmp
        destDir.deleteRecursively()
        if (!src.renameTo(destDir)) { src.copyRecursively(destDir, overwrite = true) }
        tmp.deleteRecursively()
    }

    private fun safeChild(dir: File, name: String): File {
        val f = File(dir, name)
        require(f.canonicalPath.startsWith(dir.canonicalPath + File.separator)) { "压缩包路径不安全：$name" }
        return f
    }

    private fun unzip(input: InputStream, dir: File) {
        ZipInputStream(input).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val f = safeChild(dir, e.name)
                if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) } }
            }
        }
    }

    private fun untar(input: InputStream, dir: File) {
        TarArchiveInputStream(input).use { t ->
            while (true) {
                val e = t.nextEntry ?: break
                if (!t.canReadEntryData(e)) continue
                val f = safeChild(dir, e.name)
                if (e.isDirectory) f.mkdirs() else if (e.isFile) { f.parentFile?.mkdirs(); f.outputStream().use { t.copyTo(it) } }
            }
        }
    }

    // ── 从手机文件导入 ──
    fun displayName(uri: Uri): String {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: "import.bin"
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "import.bin"
    }

    fun copyIn(uri: Uri, dest: File, progress: (Long) -> Unit = {}) {
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        ctx.contentResolver.openInputStream(uri)!!.use { input ->
            part.outputStream().use { out ->
                val buf = ByteArray(256 * 1024); var done = 0L
                while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); done += n; progress(done) }
            }
        }
        if (dest.exists()) dest.deleteRecursively()
        check(part.renameTo(dest)) { "无法保存导入的文件" }
    }

    fun cacheFile(name: String) = File(ctx.cacheDir, "dl").apply { mkdirs() }.let { File(it, name) }
}
