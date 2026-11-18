package com.geminitts.book

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Decode any audio and re-encode it to AAC in an .m4a. The audio is cut into equal parts that are
 * converted at the same time on several threads, then joined without a seam: every part starts with
 * about one second of warm-up audio whose encoded frames are thrown away.
 * Progress is published in VBus.audioFrac.
 */
object AacTranscoder {
    const val FRAME = 1024 // samples per AAC frame
    const val PRE = 48     // warm-up frames at the start of every part but the first (~1 s)
    const val POST = 4     // extra real frames fed after the end of a part

    fun run(src: String, out: File, stop: AtomicBoolean, totalUs: Long, workers: Int) {
        val pr = probe(src)
        val rate = pr[0]
        val ch = pr[1]
        val totalSamples = totalUs * rate / 1_000_000L
        val totalFrames = ((totalSamples + FRAME - 1) / FRAME).toInt()
        if (totalFrames <= 0) throw IOException("audio is empty")

        // parts of at least ~45 s each
        var n = minOf(workers.coerceIn(1, 8), maxOf(1, (totalUs / 45_000_000L).toInt()))
        val per = (totalFrames + n - 1) / n
        n = (totalFrames + per - 1) / per

        val parts = ArrayList<AacPart>()
        var planned = 0L
        for (k in 0 until n) {
            val f0 = k * per
            val f1 = minOf(totalFrames, f0 + per)
            val last = f1 >= totalFrames
            val pre = if (k == 0) 0 else PRE
            val feedStart = (f0 - pre).toLong() * FRAME
            val feedEnd = if (last) f1.toLong() * FRAME else (f1 + POST).toLong() * FRAME
            planned += feedEnd - feedStart
            parts.add(AacPart(k, f0, f1, last, pre, feedStart, feedEnd, File(out.parentFile, out.name + ".part$k")))
        }

        val prog = AacProg(planned)
        val abort = AtomicBoolean(false)
        val err = AtomicReference<Throwable?>(null)
        VBus.audioFrac = 0.0
        VBus.log("audio: ${parts.size} parts converted at the same time")

        val threads = ArrayList<Thread>()
        try {
            for (p in parts) {
                val t = Thread {
                    try {
                        AacSeg(src, rate, ch, p, stop, abort, prog).run()
                    } catch (e: Throwable) {
                        if (e !is Stopped) err.compareAndSet(null, e)
                        abort.set(true)
                    }
                }
                t.start()
                threads.add(t)
            }
            for (t in threads) t.join()
            if (stop.get()) throw Stopped()
            val e = err.get()
            if (e != null) throw e

            join(parts, out, rate)
            VBus.audioFrac = 1.0
            VBus.ping()
        } finally {
            abort.set(true)
            for (p in parts) {
                try { p.file.delete() } catch (e: Exception) { /* ignore */ }
            }
        }
    }

    private fun probe(src: String): IntArray {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(src)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                    return intArrayOf(f.getInteger(MediaFormat.KEY_SAMPLE_RATE), f.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                }
            }
            throw IOException("no audio track")
        } finally {
            ex.release()
        }
    }

    /** Write the frames of all parts, in order, into one .m4a. */
    private fun join(parts: List<AacPart>, out: File, rate: Int) {
        val fmt = parts[0].format ?: throw IOException("encoder produced no format")
        out.delete()
        val m = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            val track = m.addTrack(fmt)
            m.start()
            started = true
            val info = MediaCodec.BufferInfo()
            var arr = ByteArray(8192)
            var idx = 0L
            for (p in parts) {
                DataInputStream(BufferedInputStream(FileInputStream(p.file), 1 shl 16)).use { din ->
                    for (sz in p.sizes) {
                        if (sz > arr.size) arr = ByteArray(sz)
                        din.readFully(arr, 0, sz)
                        info.set(0, sz, idx * FRAME * 1_000_000L / rate, 0)
                        m.writeSampleData(track, ByteBuffer.wrap(arr, 0, sz), info)
                        idx++
                    }
                }
            }
            if (idx == 0L) throw IOException("no audio frames were produced")
        } finally {
            try { if (started) m.stop() } catch (e: Exception) { /* ignore */ }
            try { m.release() } catch (e: Exception) { /* ignore */ }
        }
    }
}

internal class AacPart(
    val idx: Int,
    val f0: Int,
    val f1: Int,
    val last: Boolean,
    val pre: Int,
    val feedStart: Long,
    val feedEnd: Long,
    val file: File
) {
    val sizes = ArrayList<Int>()
    @Volatile var format: MediaFormat? = null
}

internal class AacProg(private val total: Long) {
    private val done = AtomicLong(0)
    @Volatile private var lastAt = 0L

    fun add(samples: Long) {
        val d = done.addAndGet(samples)
        val now = SystemClock.elapsedRealtime()
        if (now - lastAt > 250) {
            lastAt = now
            VBus.audioFrac = minOf(0.999, d.toDouble() / maxOf(1L, total))
            VBus.ping()
        }
    }
}

internal class AacSeg(
    private val src: String,
    private val rate: Int,
    private val ch: Int,
    private val p: AacPart,
    private val stop: AtomicBoolean,
    private val abort: AtomicBoolean,
    private val prog: AacProg
) {
    private var enc: MediaCodec? = null
    private var chan: FileChannel? = null
    private var encDone = false
    private var samplesFed = 0L
    private var emitted = 0
    private var kept = 0
    private val keep = p.f1 - p.f0
    private val eInfo = MediaCodec.BufferInfo()

    private fun check() {
        if (stop.get() || abort.get()) throw Stopped()
    }

    private fun startEnc(f: MediaFormat) {
        val r = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val c = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        if (r != rate || c != ch) throw IOException("decoder output $r Hz/$c ch differs from the file header $rate Hz/$ch ch")
        val ef = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, ch)
        ef.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        ef.setInteger(MediaFormat.KEY_BIT_RATE, if (ch == 1) 64000 else 96000)
        ef.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
        val e = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        e.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        e.start()
        enc = e
    }

    /** Collects encoded frames; returns true if it handled anything. */
    private fun drain(timeoutUs: Long): Boolean {
        val e = enc ?: return false
        var any = false
        while (true) {
            val oi = e.dequeueOutputBuffer(eInfo, if (any) 0L else timeoutUs)
            if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) return any
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                p.format = e.outputFormat
                any = true
                continue
            }
            if (oi < 0) continue
            any = true
            val b = e.getOutputBuffer(oi)
            if (b != null && eInfo.size > 0 && (eInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                emitted++
                if (emitted > p.pre && (p.last || kept < keep)) {
                    b.position(eInfo.offset)
                    b.limit(eInfo.offset + eInfo.size)
                    val c = chan!!
                    while (b.hasRemaining()) c.write(b)
                    p.sizes.add(eInfo.size)
                    kept++
                }
            }
            e.releaseOutputBuffer(oi, false)
            if ((eInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                encDone = true
                return true
            }
        }
    }

    private fun feed(pcm: ByteBuffer, bytes: Int) {
        val e = enc!!
        val frame = 2 * ch
        var left = bytes
        while (left > 0) {
            check()
            val ii = e.dequeueInputBuffer(3_000)
            if (ii < 0) {
                drain(0)
                continue
            }
            val eb = e.getInputBuffer(ii)!!
            eb.clear()
            var n = minOf(eb.remaining(), left)
            n -= n % frame
            if (n <= 0) throw IOException("encoder input buffer too small")
            val lim = pcm.limit()
            pcm.limit(pcm.position() + n)
            eb.put(pcm)
            pcm.limit(lim)
            e.queueInputBuffer(ii, 0, n, samplesFed * 1_000_000L / rate, 0)
            samplesFed += (n / frame).toLong()
            left -= n
            prog.add((n / frame).toLong())
        }
    }

    private fun sendEos() {
        val e = enc ?: throw IOException("decoder produced no audio")
        while (true) {
            check()
            val ii = e.dequeueInputBuffer(10_000)
            if (ii >= 0) {
                e.queueInputBuffer(ii, 0, 0, samplesFed * 1_000_000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                return
            }
            drain(0)
        }
    }

    fun run() {
        val ex = MediaExtractor()
        var dec: MediaCodec? = null
        try {
            chan = FileOutputStream(p.file).channel
            ex.setDataSource(src)
            var ti = -1
            var inFmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                    ti = i
                    inFmt = f
                    break
                }
            }
            if (ti < 0 || inFmt == null) throw IOException("no audio track")
            ex.selectTrack(ti)
            // start decoding ~1.2 s before the part so the decoder is warm; the extra samples are skipped below
            val warmUs = maxOf(0L, p.feedStart * 1_000_000L / rate - 1_200_000L)
            if (warmUs > 0L) ex.seekTo(warmUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val d = MediaCodec.createDecoderByType(inFmt.getString(MediaFormat.KEY_MIME)!!)
            dec = d
            d.configure(inFmt, null, null, 0)
            d.start()

            val frameBytes = 2 * ch
            val dInfo = MediaCodec.BufferInfo()
            var inDone = false
            var decDone = false
            var pos = -1L // absolute index of the first sample of the next decoded buffer
            var lastMove = SystemClock.elapsedRealtime()

            while (!encDone) {
                check()
                var moved = false

                if (!inDone) {
                    val ii = d.dequeueInputBuffer(0)
                    if (ii >= 0) {
                        val b = d.getInputBuffer(ii)!!
                        val n = ex.readSampleData(b, 0)
                        if (n < 0) {
                            d.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            d.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                        moved = true
                    }
                }

                if (!decDone) {
                    while (true) {
                        val oi = d.dequeueOutputBuffer(dInfo, 0)
                        if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) break
                        if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            if (enc == null) startEnc(d.outputFormat)
                            moved = true
                            continue
                        }
                        if (oi < 0) continue
                        moved = true
                        if (enc == null) startEnc(d.outputFormat)
                        val pcm = d.getOutputBuffer(oi)
                        val samples = dInfo.size / frameBytes
                        if (pcm != null && samples > 0) {
                            if (pos < 0L) pos = Math.round(dInfo.presentationTimeUs * rate / 1_000_000.0)
                            var skip = 0
                            if (pos < p.feedStart) skip = minOf(samples.toLong(), p.feedStart - pos).toInt()
                            var take = samples - skip
                            val room = p.feedEnd - (pos + skip)
                            if (take.toLong() > room) take = maxOf(0L, room).toInt()
                            if (take > 0) {
                                pcm.position(dInfo.offset + skip * frameBytes)
                                pcm.limit(dInfo.offset + (skip + take) * frameBytes)
                                feed(pcm, take * frameBytes)
                            }
                            pos += samples
                        }
                        d.releaseOutputBuffer(oi, false)
                        if ((dInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 || (pos >= 0L && pos >= p.feedEnd)) {
                            decDone = true
                            sendEos()
                            break
                        }
                    }
                }

                if (drain(0)) moved = true

                val now = SystemClock.elapsedRealtime()
                if (moved) {
                    lastMove = now
                } else {
                    if (now - lastMove > 60_000L) throw IOException("audio conversion stalled for 60 s")
                    if (decDone) {
                        if (drain(10_000)) lastMove = now
                    } else {
                        try { Thread.sleep(2) } catch (e: InterruptedException) { throw Stopped() }
                    }
                }
            }
            if (p.format == null) throw IOException("encoder produced no output format")
        } finally {
            try { dec?.stop() } catch (e: Exception) { /* ignore */ }
            try { dec?.release() } catch (e: Exception) { /* ignore */ }
            try { enc?.stop() } catch (e: Exception) { /* ignore */ }
            try { enc?.release() } catch (e: Exception) { /* ignore */ }
            try { chan?.close() } catch (e: Exception) { /* ignore */ }
            try { ex.release() } catch (e: Exception) { /* ignore */ }
        }
    }
}
