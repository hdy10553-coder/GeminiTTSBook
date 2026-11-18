package com.geminitts.book

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Html
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.ZipInputStream

object Reader {
    fun displayName(cr: ContentResolver, uri: Uri): String {
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return uri.lastPathSegment ?: "book"
    }

    fun readText(cr: ContentResolver, uri: Uri, name: String): String {
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException("cannot open file")
        val low = name.lowercase()
        val zip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        return when {
            low.endsWith(".epub") || zip -> epub(bytes)
            low.endsWith(".html") || low.endsWith(".htm") || low.endsWith(".xhtml") ->
                html(String(bytes, Charsets.UTF_8))
            else -> String(bytes, Charsets.UTF_8)
        }
    }

    fun html(s: String): String {
        var t = s.replace(Regex("(?is)<(script|style|head)\\b.*?</\\1>"), " ")
        t = t.replace(Regex("(?i)</(p|div|h[1-6]|li|tr|blockquote)>|<br\\s*/?>"), "\n")
        return Html.fromHtml(t, Html.FROM_HTML_MODE_LEGACY).toString().replace('\uFFFC', ' ')
    }

    private fun attr(tag: String, name: String): String? =
        Regex("\\b$name\\s*=\\s*[\"']([^\"']*)[\"']").find(tag)?.groupValues?.get(1)

    private fun resolve(dir: String, rel: String): String {
        val parts = ArrayList<String>()
        if (dir.isNotEmpty()) parts.addAll(dir.split('/'))
        for (seg in rel.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    private fun epub(bytes: ByteArray): String {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val n = e.name.lowercase()
                if (!e.isDirectory && (n.endsWith(".xhtml") || n.endsWith(".html") || n.endsWith(".htm") ||
                        n.endsWith(".opf") || n.endsWith(".xml"))
                ) files[e.name] = z.readBytes()
                e = z.nextEntry
            }
        }
        val container = files["META-INF/container.xml"]?.toString(Charsets.UTF_8)
            ?: throw IOException("not a valid EPUB (no container.xml)")
        val opfPath = Regex("full-path=\"([^\"]+)\"").find(container)?.groupValues?.get(1)
            ?: throw IOException("not a valid EPUB (no OPF)")
        val opf = String(files[opfPath] ?: throw IOException("OPF missing"), Charsets.UTF_8)
        val dir = opfPath.substringBeforeLast('/', "")

        val manifest = HashMap<String, String>()
        Regex("<item\\b[^>]*>").findAll(opf).forEach { m ->
            val id = attr(m.value, "id")
            val href = attr(m.value, "href")
            if (id != null && href != null) manifest[id] = href
        }
        var order = Regex("<itemref\\b[^>]*>").findAll(opf)
            .mapNotNull { attr(it.value, "idref") }
            .mapNotNull { manifest[it] }
            .map { resolve(dir, Uri.decode(it.substringBefore('#'))) }
            .toList()
        if (order.isEmpty()) {
            order = files.keys.filter {
                val l = it.lowercase()
                l.endsWith(".xhtml") || l.endsWith(".html") || l.endsWith(".htm")
            }.sorted()
        }
        val sb = StringBuilder()
        for (path in order) {
            val data = files[path] ?: continue
            sb.append(html(String(data, Charsets.UTF_8))).append("\n\n")
        }
        if (sb.isBlank()) throw IOException("EPUB had no readable text")
        return sb.toString()
    }
}
