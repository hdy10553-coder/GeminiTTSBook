package com.geminitts.book

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

/**
 * Where each batch (chunk) sits inside the FINAL merged audiobook.
 * Result: ends[i] = end of batch i in microseconds; batch i starts where batch i-1 ended
 * (batch 0 starts at 0). Built from the real duration of every batch's own audio.
 */
object Timeline {
    fun mediaDurationUs(f: File): Long {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(f.absolutePath)
            var dur = 0L
            for (i in 0 until ex.trackCount) {
                val fm = ex.getTrackFormat(i)
                if ((fm.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/") && fm.containsKey(MediaFormat.KEY_DURATION)) {
                    dur = fm.getLong(MediaFormat.KEY_DURATION)
                    break
                }
            }
            dur
        } catch (e: Exception) {
            0L
        } finally {
            ex.release()
        }
    }

    /** Part-by-part: measure each batch's own audio and add the lengths up. Null if any part can't be measured. */
    private fun sumParts(parts: List<File>): LongArray? {
        val out = LongArray(parts.size)
        var acc = 0L
        for (i in parts.indices) {
            var x = Ogg.durationUs(parts[i])
            if (x <= 0L) x = mediaDurationUs(parts[i])
            if (x <= 0L) return null
            acc += x
            out[i] = acc
        }
        return out
    }

    /** Last resort: share the real total by text length (an estimate only). */
    private fun estimate(chunks: List<String>, total: Long): LongArray {
        val totalChars = maxOf(1L, chunks.sumOf { it.length }.toLong())
        val out = LongArray(chunks.size)
        var acc = 0L
        for (i in chunks.indices) {
            acc += chunks[i].length.toLong()
            out[i] = total * acc / totalChars
        }
        return out
    }

    fun build(chunks: List<String>, parts: List<File>, merged: LongArray, mergedFile: File): LongArray {
        val n = chunks.size
        if (n == 0) return LongArray(0)
        var how = ""
        var found: LongArray? = null

        // 1) exact: positions measured while merging the Opus parts
        if (merged.size == n && merged[n - 1] > 0L) {
            found = merged
            how = "each batch's measured audio length"
        }
        // 2) otherwise measure every part on its own and add them up
        if (found == null) {
            found = sumParts(parts)
            if (found != null) how = "each part's own duration"
        }
        val total = mediaDurationUs(mergedFile)
        // 3) last resort
        if (found == null) {
            if (total <= 0L) return LongArray(0)
            found = estimate(chunks, total)
            how = "text length (estimate only)"
        }

        val base: LongArray = found
        val last = base[n - 1]
        // safety net: if the sum disagrees with the real merged file, stretch the windows to fit it
        val ends: LongArray
        if (total > 0L && last > 0L && Math.abs(total - last) > maxOf(1_000_000L, last / 100)) {
            Bus.log("timeline sum ${Slides.fmt(last)} differs from merged audio ${Slides.fmt(total)}: rescaling")
            val sc = total.toDouble() / last
            ends = LongArray(n) { (base[it] * sc).toLong() }
        } else {
            ends = base
        }
        Bus.log("sync windows from $how, total ${Slides.fmt(ends[n - 1])}")
        return ends
    }
}
