package com.geminitts.book

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Minimal Matroska (.mkv) writer: the H.264 slides from the hardware encoder plus the audio packets
 * copied as they are (Ogg Opus, AAC or MP3), with no decoding and no re-encoding. MX Player plays it.
 */
internal object Mkv {
    private class CuePt(val ms: Long, val rel: Long, val key: Boolean)

    // ---------- EBML helpers ----------
    private fun idBytes(id: Long): ByteArray {
        val n = if (id > 0xFFFFFFL) 4 else if (id > 0xFFFFL) 3 else if (id > 0xFFL) 2 else 1
        return ByteArray(n) { i -> ((id shr (8 * (n - 1 - i))) and 0xFF).toByte() }
    }

    private fun sizeBytes(n: Long): ByteArray {
        var len = 1
        while (len < 8 && n >= (1L shl (7 * len)) - 1L) len++
        val b = ByteArray(len)
        var v = n
        for (i in len - 1 downTo 0) {
            b[i] = (v and 0xFF).toByte()
            v = v shr 8
        }
        b[0] = (b[0].toInt() or (0x80 shr (len - 1))).toByte()
        return b
    }

    private fun fixed8(n: Long): ByteArray {
        val b = ByteArray(8)
        b[0] = 1
        for (i in 1..7) b[i] = ((n shr (8 * (7 - i))) and 0xFF).toByte()
        return b
    }

    private fun be8(n: Long): ByteArray = ByteArray(8) { i -> ((n shr (8 * (7 - i))) and 0xFF).toByte() }

    private fun uintBytes(v: Long): ByteArray {
        var n = 1
        while (n < 8 && (v ushr (8 * n)) != 0L) n++
        return ByteArray(n) { i -> ((v shr (8 * (n - 1 - i))) and 0xFF).toByte() }
    }

    private fun el(id: Long, payload: ByteArray): ByteArray = idBytes(id) + sizeBytes(payload.size.toLong()) + payload
    private fun uint(id: Long, v: Long): ByteArray = el(id, uintBytes(v))
    private fun str(id: Long, s: String): ByteArray = el(id, s.toByteArray(Charsets.US_ASCII))
    private fun dbl(id: Long, d: Double): ByteArray = el(id, be8(java.lang.Double.doubleToLongBits(d)))

    // ---------- H.264 helpers ----------
    /** [start, end) of every NAL unit in an Annex-B buffer (start codes removed). */
    private fun nalRanges(a: ByteArray, size: Int): List<IntArray> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 2 < size) {
            if (a[i].toInt() == 0 && a[i + 1].toInt() == 0 && a[i + 2].toInt() == 1) {
                starts.add(i)
                i += 3
            } else {
                i++
            }
        }
        val res = ArrayList<IntArray>()
        for (k in starts.indices) {
            val b = starts[k] + 3
            var e = if (k + 1 < starts.size) starts[k + 1] else size
            while (e > b && a[e - 1].toInt() == 0) e--
            if (e > b) res.add(intArrayOf(b, e))
        }
        return res
    }

    private fun bufBytes(f: MediaFormat, key: String): ByteArray? {
        val b = f.getByteBuffer(key) ?: return null
        val d = b.duplicate()
        d.rewind()
        val arr = ByteArray(d.remaining())
        d.get(arr)
        return arr
    }

    private fun avcC(fmt: MediaFormat, firstKey: ByteArray?): ByteArray {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        fun scan(a: ByteArray?) {
            if (a == null) return
            for (r in nalRanges(a, a.size)) {
                val t = a[r[0]].toInt() and 0x1F
                if (t == 7 && sps == null) sps = a.copyOfRange(r[0], r[1])
                if (t == 8 && pps == null) pps = a.copyOfRange(r[0], r[1])
            }
        }
        scan(bufBytes(fmt, "csd-0"))
        scan(bufBytes(fmt, "csd-1"))
        if (sps == null || pps == null) scan(firstKey)
        val s = sps ?: throw IOException("the video encoder gave no H.264 SPS")
        val p = pps ?: throw IOException("the video encoder gave no H.264 PPS")
        val bo = ByteArrayOutputStream()
        bo.write(1)
        bo.write(s[1].toInt())
        bo.write(s[2].toInt())
        bo.write(s[3].toInt())
        bo.write(0xFF)
        bo.write(0xE1)
        bo.write(s.size shr 8)
        bo.write(s.size and 0xFF)
        bo.write(s, 0, s.size)
        bo.write(1)
        bo.write(p.size shr 8)
        bo.write(p.size and 0xFF)
        bo.write(p, 0, p.size)
        return bo.toByteArray()
    }

    // ---------- audio helpers ----------
    private fun opusHead(f: MediaFormat): ByteArray? {
        val h = bufBytes(f, "csd-0") ?: return null
        if (h.size < 19) return null
        return if (String(h, 0, 8, Charsets.ISO_8859_1) == "OpusHead") h else null
    }

    /** True if this file's audio can be copied into the MKV untouched. */
    fun supports(path: String): Boolean {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(path)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (!m.startsWith("audio/")) continue
                if (m == "audio/opus") return opusHead(f) != null
                if (m == "audio/mp4a-latm") return bufBytes(f, "csd-0") != null
                return m == "audio/mpeg"
            }
            return false
        } catch (e: Exception) {
            return false
        } finally {
            ex.release()
        }
    }

    private fun audioEntry(f: MediaFormat): ByteArray {
        val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
        val rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).toLong()
        val head = uint(0xD7L, 2) + uint(0x73C5L, 2) + uint(0x83L, 2) + uint(0x9CL, 0)
        return when (mime) {
            "audio/opus" -> {
                val oh = opusHead(f) ?: throw IOException("bad OpusHead")
                val pre = (oh[10].toInt() and 0xFF) or ((oh[11].toInt() and 0xFF) shl 8)
                val delayNs = pre.toLong() * 1_000_000_000L / 48000L
                el(0xAEL, head + str(0x86L, "A_OPUS") + el(0x63A2L, oh) + uint(0x56AAL, delayNs) + uint(0x56BBL, 80_000_000L) +
                    el(0xE1L, dbl(0xB5L, 48000.0) + uint(0x9FL, ch)))
            }
            "audio/mp4a-latm" -> {
                val c0 = bufBytes(f, "csd-0") ?: throw IOException("AAC without config")
                el(0xAEL, head + str(0x86L, "A_AAC") + el(0x63A2L, c0) +
                    el(0xE1L, dbl(0xB5L, rate.toDouble()) + uint(0x9FL, ch)))
            }
            else -> el(0xAEL, head + str(0x86L, "A_MPEG/L3") + el(0xE1L, dbl(0xB5L, rate.toDouble()) + uint(0x9FL, ch)))
        }
    }

    private fun seekHead(info: Long, tracks: Long, cues: Long): ByteArray {
        fun entry(id: Long, p: Long): ByteArray = el(0x4DBBL, el(0x53ABL, idBytes(id)) + el(0x53ACL, be8(p)))
        return el(0x114D9B74L, entry(0x1549A966L, info) + entry(0x1654AE6BL, tracks) + entry(0x1C53BB6BL, cues))
    }

    // ---------- the writer ----------
    fun write(
        out: File, vfile: File, enc: Encoded, audioPath: String, w: Int, h: Int, durationUs: Long,
        check: () -> Unit, progress: (Double) -> Unit
    ) {
        var pos = 0L
        var segSizePos = 0L
        var segStart = 0L
        var shPos = 0L
        var infoRel = 0L
        var tracksRel = 0L
        var cuesRel = 0L
        val cues = ArrayList<CuePt>()

        val ex = MediaExtractor()
        try {
            ex.setDataSource(audioPath)
            var ai = -1
            for (i in 0 until ex.trackCount) {
                if ((ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                    ai = i
                    break
                }
            }
            if (ai < 0) throw IOException("no audio track")
            val afmt = ex.getTrackFormat(ai)
            ex.selectTrack(ai)

            RandomAccessFile(vfile, "r").use { vr ->
                val vbuf = ByteArray(maxOf(enc.maxSize, 16))
                var firstKey: ByteArray? = null
                for (s in enc.samples) {
                    if (s.key) {
                        vr.seek(s.off)
                        vr.readFully(vbuf, 0, s.size)
                        firstKey = vbuf.copyOf(s.size)
                        break
                    }
                }

                BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { o ->
                    fun put(b: ByteArray) {
                        o.write(b)
                        pos += b.size.toLong()
                    }

                    // EBML header + Segment
                    put(el(0x1A45DFA3L, uint(0x4286L, 1) + uint(0x42F7L, 1) + uint(0x42F2L, 4) + uint(0x42F3L, 8) +
                        str(0x4282L, "matroska") + uint(0x4287L, 4) + uint(0x4285L, 2)))
                    put(idBytes(0x18538067L))
                    segSizePos = pos
                    put(fixed8(0))
                    segStart = pos

                    shPos = pos
                    put(seekHead(0, 0, 0))

                    infoRel = pos - segStart
                    put(el(0x1549A966L, uint(0x2AD7B1L, 1_000_000L) + dbl(0x4489L, durationUs / 1000.0) +
                        str(0x4D80L, "GeminiTTS") + str(0x5741L, "GeminiTTS")))

                    tracksRel = pos - segStart
                    val video = el(0xAEL, uint(0xD7L, 1) + uint(0x73C5L, 1) + uint(0x83L, 1) + uint(0x9CL, 0) +
                        str(0x86L, "V_MPEG4/ISO/AVC") + el(0x63A2L, avcC(enc.format, firstKey)) +
                        el(0xE0L, uint(0xB0L, w.toLong()) + uint(0xBAL, h.toLong())))
                    put(el(0x1654AE6BL, video + audioEntry(afmt)))

                    // clusters: a new one at every video key frame (so seeking always lands on a key frame)
                    val cl = ByteArrayOutputStream()
                    var clTc = -1L
                    var clKey = false

                    fun flushCluster() {
                        if (clTc < 0L) return
                        val body = uint(0xE7L, clTc) + cl.toByteArray()
                        cues.add(CuePt(clTc, pos - segStart, clKey))
                        put(idBytes(0x1F43B675L) + sizeBytes(body.size.toLong()) + body)
                        cl.reset()
                        clTc = -1L
                        clKey = false
                    }

                    fun startCluster(tMs: Long, key: Boolean) {
                        flushCluster()
                        clTc = tMs
                        clKey = key
                    }

                    fun block(track: Int, tMs: Long, key: Boolean, d: ByteArray, len: Int) {
                        var rel = tMs - clTc
                        if (rel < -32768L) rel = -32768L
                        if (rel > 32767L) rel = 32767L
                        cl.write(idBytes(0xA3L))
                        cl.write(sizeBytes((4 + len).toLong()))
                        cl.write(0x80 or track)
                        cl.write(((rel shr 8) and 0xFF).toInt())
                        cl.write((rel and 0xFF).toInt())
                        cl.write(if (key) 0x80 else 0)
                        cl.write(d, 0, len)
                    }

                    val nb = ByteArrayOutputStream()
                    val abuf = java.nio.ByteBuffer.allocate(1 shl 18)
                    val nV = enc.samples.size
                    var vi = 0
                    var aTime = ex.sampleTime
                    var cnt = 0
                    while (vi < nV || aTime >= 0L) {
                        check()
                        val vT = if (vi < nV) enc.samples[vi].pts else Long.MAX_VALUE
                        val useV = vi < nV && (aTime < 0L || vT <= aTime)
                        var tMs: Long
                        if (useV) {
                            val s = enc.samples[vi]
                            vi++
                            vr.seek(s.off)
                            vr.readFully(vbuf, 0, s.size)
                            nb.reset()
                            for (r in nalRanges(vbuf, s.size)) {
                                val t = vbuf[r[0]].toInt() and 0x1F
                                if (t == 7 || t == 8 || t == 9) continue
                                val len = r[1] - r[0]
                                nb.write(len ushr 24)
                                nb.write(len ushr 16)
                                nb.write(len ushr 8)
                                nb.write(len)
                                nb.write(vbuf, r[0], len)
                            }
                            val data = nb.toByteArray()
                            tMs = (s.pts + 500L) / 1000L
                            if (data.isEmpty()) continue
                            if (clTc < 0L || s.key || tMs - clTc > 30_000L) startCluster(tMs, s.key)
                            block(1, tMs, s.key, data, data.size)
                        } else {
                            val n = ex.readSampleData(abuf, 0)
                            if (n < 0) {
                                aTime = -1L
                                continue
                            }
                            tMs = (aTime + 500L) / 1000L
                            if (clTc < 0L || tMs - clTc > 30_000L) startCluster(tMs, false)
                            block(2, tMs, true, abuf.array(), n)
                            ex.advance()
                            aTime = ex.sampleTime
                        }
                        cnt++
                        if ((cnt and 255) == 0) progress(minOf(1.0, tMs * 1000.0 / maxOf(1L, durationUs)))
                    }
                    flushCluster()

                    // cues
                    cuesRel = pos - segStart
                    val cb = ByteArrayOutputStream()
                    for (c in cues) {
                        var tp = ByteArray(0)
                        if (c.key) tp += el(0xB7L, uint(0xF7L, 1) + uint(0xF1L, c.rel))
                        tp += el(0xB7L, uint(0xF7L, 2) + uint(0xF1L, c.rel))
                        val cp = el(0xBBL, uint(0xB3L, c.ms) + tp)
                        cb.write(cp, 0, cp.size)
                    }
                    put(el(0x1C53BB6BL, cb.toByteArray()))
                    o.flush()
                }
            }
        } finally {
            try { ex.release() } catch (e: Exception) { /* ignore */ }
        }

        // patch the Segment size and the SeekHead now that every position is known
        RandomAccessFile(out, "rw").use { f ->
            f.seek(segSizePos)
            f.write(fixed8(pos - segStart))
            f.seek(shPos)
            f.write(seekHead(infoRel, tracksRel, cuesRel))
        }
        progress(1.0)
    }
}
