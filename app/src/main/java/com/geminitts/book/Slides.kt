package com.geminitts.book

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint

class Slide(val text: String, val startUs: Long, val endUs: Long)

class Look(val w: Int, val h: Int, val face: Typeface, val footer: Boolean, val totalUs: Long)

object Slides {
    fun fmt(us: Long): String {
        val s = us / 1_000_000L
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    private fun wc(s: String): Int = s.split(' ').count { it.isNotEmpty() }

    private fun sentences(t: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < t.length) {
            val c = t[i]
            sb.append(c)
            if (c == '.' || c == '!' || c == '?' || c == '\u2026' || c == '\u3002' || c == '\u061F' || c == '\u06D4') {
                var j = i + 1
                while (j < t.length && "\"'\u201d\u2019)]\u00bb".indexOf(t[j]) >= 0) {
                    sb.append(t[j])
                    j++
                }
                if (j >= t.length || t[j].isWhitespace()) {
                    val s = sb.toString().trim()
                    if (s.isNotEmpty()) out.add(s)
                    sb.setLength(0)
                    while (j < t.length && t[j].isWhitespace()) j++
                }
                i = j
            } else {
                i++
            }
        }
        val rest = sb.toString().trim()
        if (rest.isNotEmpty()) out.add(rest)
        return out
    }

    /** Sentences, with any monster sentence cut into <= maxWords pieces. */
    private fun pieces(t: String, maxWords: Int): List<String> {
        val out = ArrayList<String>()
        for (s in sentences(t)) {
            val ws = s.split(' ').filter { it.isNotEmpty() }
            if (ws.size <= maxWords) out.add(s) else ws.chunked(maxWords).forEach { out.add(it.joinToString(" ")) }
        }
        return out
    }

    /** Speaking-time weight of a text: its characters plus a pause for every sentence end and comma. */
    private fun weight(s: String): Int {
        var w = s.length
        for (c in s) {
            when (c) {
                '.', '!', '?', '\u2026', '\u3002', '\u061F', '\u06D4' -> w += 10
                ',', ';', ':', '\u060C', '\u061B' -> w += 4
                else -> {}
            }
        }
        return w
    }

    /** Each chunk's time window is split into balanced slides at sentence boundaries. */
    fun plan(chunks: List<String>, startUs: LongArray, endUs: LongArray, maxWords: Int): List<Slide> {
        val out = ArrayList<Slide>()
        for (i in chunks.indices) {
            val ps = pieces(chunks[i], maxWords)
            if (ps.isEmpty()) continue
            val total = maxOf(1, ps.sumOf { wc(it) })
            val n = (total + maxWords - 1) / maxWords
            val target = total.toDouble() / n
            val groups = ArrayList<String>()
            val cur = StringBuilder()
            var cum = 0
            var k = 0
            for (s in ps) {
                val w = wc(s)
                if (cur.isNotEmpty() && k < n - 1 && cum + w / 2.0 > target * (k + 1)) {
                    groups.add(cur.toString())
                    cur.setLength(0)
                    k++
                }
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(s)
                cum += w
            }
            if (cur.isNotEmpty()) groups.add(cur.toString())
            val chars = maxOf(1, groups.sumOf { weight(it) })
            val span = endUs[i] - startUs[i]
            var acc = 0
            for (g in groups) {
                val a = startUs[i] + span * acc / chars
                acc += weight(g)
                val b = startUs[i] + span * acc / chars
                out.add(Slide(g, a, b))
            }
        }
        return out
    }

    /** One font size for the whole video: the largest at which every slide fits. */
    fun fitSize(look: Look, slides: List<Slide>): Float {
        val r = Renderer(look)
        try {
            val lo0 = (look.w * 0.022f).toInt()
            val hi0 = (look.w * 0.075f).toInt()
            val probe = slides.sortedByDescending { it.text.length }.take(6)
            var lo = lo0
            var hi = hi0
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (probe.all { r.fits(it.text, mid.toFloat()) }) lo = mid else hi = mid - 1
            }
            var size = lo
            while (size > lo0 && !slides.all { r.fits(it.text, size.toFloat()) }) size -= maxOf(1, size / 60)
            return size.toFloat()
        } finally {
            r.recycle()
        }
    }
}

/** Draws a slide (white on black) and converts it straight to an 8-bit luma plane. Not thread-safe: one per thread. */
class Renderer(private val look: Look) {
    private val w = look.w
    private val h = look.h
    private val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bmp)
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = look.face
    }
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(140, 140, 140)
        typeface = Typeface.SANS_SERIF
        textSize = w * 0.032f
    }
    private val fillPaint = Paint()
    private val marginX = w * 0.085f
    private val top = h * 0.07f
    private val textW = (w - 2 * marginX).toInt()
    private val maxH = h - top - (if (look.footer) h * 0.085f else h * 0.04f)
    private val px = IntArray(w * 64)

    // Arabic/Persian-dominant slides are laid out right-to-left (right-aligned); everything else left-to-right.
    // Latin words inside an RTL slide still run left-to-right by the Unicode bidi rules.
    private fun isRtl(text: String): Boolean {
        var rtl = 0
        var ltr = 0
        for (ch in text) {
            val d = Character.getDirectionality(ch)
            if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) rtl++
            else if (d == Character.DIRECTIONALITY_LEFT_TO_RIGHT) ltr++
        }
        return rtl > ltr
    }

    private fun layout(text: String, size: Float): StaticLayout {
        paint.textSize = size
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, textW)
            .setTextDirection(if (isRtl(text)) TextDirectionHeuristics.RTL else TextDirectionHeuristics.LTR)
            .setLineSpacing(0f, 1.3f)
            .setIncludePad(false)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .build()
    }

    fun fits(text: String, size: Float): Boolean = layout(text, size).height <= maxH

    fun render(s: Slide, size: Float, y: ByteArray) {
        canvas.drawColor(Color.BLACK)
        val lay = layout(s.text, size)
        canvas.save()
        canvas.translate(marginX, top)
        lay.draw(canvas)
        canvas.restore()
        if (look.footer) {
            val barY = h - h * 0.04f
            val bh = maxOf(2f, h * 0.003f)
            fillPaint.color = Color.rgb(45, 45, 45)
            canvas.drawRect(marginX, barY, w - marginX, barY + bh, fillPaint)
            val frac = if (look.totalUs > 0) (s.startUs.toDouble() / look.totalUs).coerceIn(0.0, 1.0).toFloat() else 0f
            fillPaint.color = Color.rgb(170, 170, 170)
            canvas.drawRect(marginX, barY, marginX + (w - 2 * marginX) * frac, barY + bh, fillPaint)
            val ty = barY - h * 0.012f
            small.textAlign = Paint.Align.LEFT
            canvas.drawText(Slides.fmt(s.startUs), marginX, ty, small)
            small.textAlign = Paint.Align.RIGHT
            canvas.drawText(Slides.fmt(look.totalUs), w - marginX, ty, small)
        }
        var row = 0
        var o = 0
        while (row < h) {
            val n = minOf(64, h - row)
            bmp.getPixels(px, 0, w, 0, row, w, n)
            for (i in 0 until w * n) {
                y[o] = LUT[px[i] and 0xFF]
                o++
            }
            row += n
        }
    }

    fun recycle() {
        bmp.recycle()
    }

    companion object {
        // gray 0..255 -> video-range luma 16..235
        val LUT = ByteArray(256) { (16 + it * 219 / 255).toByte() }
    }
}
