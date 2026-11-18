package com.geminitts.book

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Ogg {
    private val table = IntArray(256).also { t ->
        for (i in 0 until 256) {
            var r = i shl 24
            repeat(8) { r = if (r < 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
            t[i] = r
        }
    }

    private fun crc(b: ByteArray): Int {
        var c = 0
        for (x in b) c = (c shl 8) xor table[((c ushr 24) xor (x.toInt() and 0xFF)) and 0xFF]
        return c
    }

    fun isOpus(f: File): Boolean = f.inputStream().use { s ->
        val h = ByteArray(36)
        val n = s.read(h)
        n >= 36 && String(h, 0, 4, Charsets.ISO_8859_1) == "OggS" &&
            String(h, 28, 8, Charsets.ISO_8859_1) == "OpusHead"
    }

    /** Exact length of ONE Opus part in microseconds (last page position minus pre-skip); 0 if unreadable. */
    fun durationUs(f: File): Long {
        if (!isOpus(f)) return 0L
        return try {
            val b = f.readBytes()
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            var pos = 0
            var pre = -1
            var last = -1L
            while (pos + 27 <= b.size && b[pos] == 0x4F.toByte() && b[pos + 1] == 0x67.toByte() &&
                b[pos + 2] == 0x67.toByte() && b[pos + 3] == 0x53.toByte()
            ) {
                val ns = b[pos + 26].toInt() and 0xFF
                var dl = 0
                for (s in 0 until ns) dl += b[pos + 27 + s].toInt() and 0xFF
                val len = 27 + ns + dl
                if (pos + len > b.size) break
                if (pre < 0) pre = bb.getShort(pos + 27 + ns + 10).toInt() and 0xFFFF
                val g = bb.getLong(pos + 6)
                if (g != -1L) last = g
                pos += len
            }
            if (pre < 0 || last < 0) 0L else ((last - pre) * 1_000_000L / 48000L).coerceAtLeast(0L)
        } catch (e: Exception) {
            0L
        }
    }

    /** Merge Ogg/Opus files into ONE clean stream (single header, continuous pages, fixed CRC). */
    fun merge(parts: List<File>, out: File): LongArray {
        if (!parts.all { isOpus(it) }) {
            out.outputStream().buffered().use { os -> parts.forEach { os.write(it.readBytes()) } }
            return LongArray(0)
        }
        val ends = LongArray(parts.size)
        var pre0 = 0L
        var seq = 0
        var serial = 0
        var last = 0L
        out.outputStream().buffered().use { os ->
            parts.forEachIndexed { idx, f ->
                val b = f.readBytes()
                val bb0 = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
                var pos = 0
                var n = 0
                var offset = 0L
                while (pos + 27 <= b.size && b[pos] == 0x4F.toByte() && b[pos + 1] == 0x67.toByte() &&
                    b[pos + 2] == 0x67.toByte() && b[pos + 3] == 0x53.toByte()
                ) {
                    val ns = b[pos + 26].toInt() and 0xFF
                    var dl = 0
                    for (s in 0 until ns) dl += b[pos + 27 + s].toInt() and 0xFF
                    val len = 27 + ns + dl
                    if (pos + len > b.size) break
                    if (n == 0) {
                        val pre = bb0.getShort(pos + 27 + ns + 10).toInt() and 0xFFFF
                        if (idx == 0) { serial = bb0.getInt(pos + 14); pre0 = pre.toLong() } else offset = last - pre
                    }
                    val next = pos + len
                    val isEnd = next + 4 > b.size || b[next] != 0x4F.toByte()
                    if (idx > 0 && n < 2) { // drop OpusHead + OpusTags of later files
                        pos = next
                        n++
                        continue
                    }
                    val page = b.copyOfRange(pos, next)
                    val bb = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN)
                    var flags = page[5].toInt() and 0xFF
                    if (idx > 0) flags = flags and 0x02.inv()
                    flags = if (isEnd && idx == parts.lastIndex) flags or 0x04 else flags and 0x04.inv()
                    page[5] = flags.toByte()
                    val g = bb.getLong(6)
                    if (g != -1L) {
                        val ng = g + offset
                        bb.putLong(6, ng)
                        if (ng > last) last = ng
                    }
                    bb.putInt(14, serial)
                    bb.putInt(18, seq++)
                    bb.putInt(22, 0)
                    bb.putInt(22, crc(page))
                    os.write(page)
                    pos = next
                    n++
                }
                ends[idx] = last
            }
        }
        // end of each part on the merged timeline, in microseconds (48 kHz granules minus the pre-skip)
        return LongArray(parts.size) { ((ends[it] - pre0) * 1_000_000L / 48000L).coerceAtLeast(0L) }
    }
}
