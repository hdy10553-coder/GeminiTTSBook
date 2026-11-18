package com.geminitts.book

import android.content.ContentValues
import android.content.Context
import android.graphics.Typeface
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.app.NotificationManager
import android.os.SystemClock
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class Stopped : RuntimeException("stopped")

class VideoRequest(
    val chunks: List<String>,
    val endsUs: LongArray,
    val audio: File,
    val stem: String,
    val maxWords: Int,
    val hd: Boolean,
    val footer: Boolean
)

/** Video progress, reported through the one shared Bus (the video is built inside the conversion run). */
object VBus {
    @Volatile var done = 0
    @Volatile var total = 0
    @Volatile var audioFrac = -1.0
    @Volatile var stage = 0
    @Volatile var startedAt = 0L
    @Volatile var muxFrac = 0.0

    var status: String
        get() = Bus.status
        set(v) { Bus.status = v }

    var output: Uri?
        get() = Bus.videoOutput
        set(v) { Bus.videoOutput = v }

    // ---- real progress: prepare 5 %, render + audio 85 %, final mux 10 % ----
    fun overall(): Double {
        val slides = if (total > 0) minOf(1.0, done.toDouble() / total) else 0.0
        return when (stage) {
            0 -> 0.02
            1 -> 0.05 + 0.85 * (if (audioFrac >= 0.0) (slides + audioFrac) / 2.0 else slides)
            2 -> 0.90 + 0.10 * muxFrac
            else -> 1.0
        }
    }

    private fun clock(s: Long): String =
        if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format("%d:%02d", s / 60, s % 60)

    fun detail(): String {
        val parts = ArrayList<String>()
        if (total > 0) parts.add("slides $done/$total")
        if (audioFrac >= 0.0) parts.add("audio ${(audioFrac * 100).toInt()}%")
        val el = maxOf(0L, (System.currentTimeMillis() - startedAt) / 1000)
        parts.add("elapsed " + clock(el))
        val f = overall()
        if (f > 0.08 && f < 1.0) parts.add("~" + clock((el * (1.0 - f) / f).toLong()) + " left")
        return parts.joinToString("  ·  ")
    }

    fun log(s: String) = Bus.log(s)

    fun ping() = Bus.ping()
}

object VNotif {
    @Volatile private var lastAt = 0L

    fun progress(c: Context) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAt < 800) return
        lastAt = now
        try {
            val f = VBus.overall()
            c.getSystemService(NotificationManager::class.java)
                .notify(1, Notifier.run(c, "Video ${(f * 100).toInt()}%  ${VBus.status}", (f * 1000).toInt(), 1000))
        } catch (e: Exception) { /* optional */ }
    }
}

private class VFrame(val slide: Int, val pts: Long, val fresh: Boolean)
internal class VSample(val off: Long, val size: Int, val pts: Long, val key: Boolean)
internal class Encoded(val format: MediaFormat, val samples: List<VSample>, val maxSize: Int)

object VideoMaker {
    private val stopFlag = AtomicBoolean(false)
    private const val KEEP = 15_000_000L // a tiny repeat frame every 15 s keeps all players happy

    fun stop() {
        stopFlag.set(true)
    }

    private fun check() {
        if (stopFlag.get()) throw Stopped()
    }

    /** Returns true when the video file was written. */
    fun run(ctx: Context, req: VideoRequest): Boolean {
        stopFlag.set(false)
        VBus.done = 0
        VBus.total = 0
        VBus.output = null
        VBus.audioFrac = -1.0
        VBus.stage = 0
        VBus.muxFrac = 0.0
        VBus.startedAt = System.currentTimeMillis()
        val tmp = ArrayList<File>()
        var ok = false
        try {
            doRun(ctx, req, tmp)
            ok = true
        } catch (e: Stopped) {
            VBus.status = "Stopped (audio is saved)"
            VBus.log("video stopped")
        } finally {
            for (f in tmp) {
                try { f.delete() } catch (e: Exception) { /* ignore */ }
            }
        }
        return ok
    }

    // ---------- main pipeline ----------
    private fun doRun(ctx: Context, req: VideoRequest, tmp: MutableList<File>) {
        val t0 = System.currentTimeMillis()
        val chunks = req.chunks
        if (chunks.isEmpty()) throw IOException("nothing to show")
        check()

        // the merged audiobook is already on disk (owned by the conversion): it is only read here
        val audioFile = req.audio
        val totalUs = audioDurationUs(audioFile.absolutePath)
        if (totalUs <= 0L) throw IOException("cannot read the audio duration")
        VBus.log("video: audio ${Slides.fmt(totalUs)}, ${chunks.size} batches")
        check()

        // ---- timeline: each batch owns the window it really occupies in the merged audio ----
        val n = chunks.size
        val starts = LongArray(n)
        val ends = LongArray(n)
        val exact = req.endsUs
        if (exact.size == n && exact[n - 1] > 0L) {
            VBus.log("slides follow each batch's real audio window: exact sync")
            for (i in 0 until n) {
                ends[i] = minOf(exact[i], totalUs)
                starts[i] = if (i == 0) 0L else ends[i - 1]
            }
        } else {
            VBus.log("no batch timings available: estimating from text length")
            val totalChars = maxOf(1L, chunks.sumOf { it.length }.toLong())
            var acc = 0L
            for (i in 0 until n) {
                starts[i] = totalUs * acc / totalChars
                acc += chunks[i].length.toLong()
                ends[i] = totalUs * acc / totalChars
            }
        }
        ends[n - 1] = totalUs
        ends[n - 1] = totalUs

        // ---- slides + one global font size ----
        val w = if (req.hd) 1080 else 720
        val h = if (req.hd) 2400 else 1600 // 9:20, same as the phone screen
        val look = Look(w, h, loadFace(ctx), req.footer, totalUs)
        val slides = Slides.plan(chunks, starts, ends, req.maxWords)
        if (slides.isEmpty()) throw IOException("no slides to draw")
        VBus.status = "Fitting text…"
        VBus.ping()
        val size = Slides.fitSize(look, slides)
        VBus.total = slides.size
        VBus.log("${slides.size} slides, ${size.toInt()} px text, ${w}x$h")
        check()

        // ---- audio: untouched if the MP4 muxer accepts it, else AAC in a parallel thread ----
        var audioPath = audioFile.absolutePath
        var transcoder: Thread? = null
        val tcError = AtomicReference<Throwable?>(null)
        val probe = File(ctx.cacheDir, "v_probe.mp4")
        tmp.add(probe)
        val useMkv = Mkv.supports(audioPath)
        if (useMkv) {
            VBus.log("audio is copied into an MKV exactly as it is: no decoding, no re-encoding")
        } else if (canPassthrough(audioPath, probe)) {
            VBus.log("audio goes into the MP4 untouched (no re-encoding)")
        } else {
            val workers = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
            VBus.log("MP4 can't hold this audio as-is: converting to AAC in parallel")
            VBus.audioFrac = 0.0
            val aac = File(ctx.cacheDir, "v_audio.m4a")
            tmp.add(aac)
            val src = audioPath
            audioPath = aac.absolutePath
            transcoder = Thread {
                try {
                    AacTranscoder.run(src, aac, stopFlag, totalUs, workers)
                } catch (e: Throwable) {
                    tcError.set(e)
                }
            }
            transcoder.start()
        }

        // ---- video: parallel rendering + hardware encoding ----
        VBus.status = "Rendering slides…"
        VBus.stage = 1
        VBus.ping()
        val vfile = File(ctx.cacheDir, "v_video.bin")
        tmp.add(vfile)
        val enc = try {
            encodeVideo(ctx, look, slides, size, totalUs, vfile, if (useMkv) 10_000_000L else KEEP, useMkv)
        } catch (e: Throwable) {
            stopFlag.set(true) // make the transcoder quit too
            throw e
        }
        VBus.log("video encoded: ${enc.samples.size} frames, ${vfile.length() / 1024} KB")

        if (transcoder != null) {
            VBus.status = "Finishing audio…"
            VBus.ping()
            while (transcoder.isAlive) {
                check()
                transcoder.join(300)
                VBus.ping()
                VNotif.progress(ctx)
            }
            val err = tcError.get()
            if (err != null) throw err
        }
        check()

        // ---- final mux ----
        VBus.status = "Muxing…"
        VBus.muxFrac = 0.0
        VBus.stage = 2
        VBus.ping()
        val ext = if (useMkv) "mkv" else "mp4"
        val outTmp = File(ctx.cacheDir, "v_out.$ext")
        tmp.add(outTmp)
        outTmp.delete()
        if (useMkv) {
            Mkv.write(outTmp, vfile, enc, audioPath, w, h, totalUs, { check() }, { f ->
                VBus.muxFrac = f
                VBus.ping()
                VNotif.progress(ctx)
            })
        } else {
            mux(outTmp, vfile, enc, audioPath)
        }
        val stem = req.stem
        val uri = exportToDownloads(ctx, outTmp, "$stem.$ext")
        VBus.stage = 3
        VBus.output = uri
        VBus.status = "Done: Download/GeminiTTS/$stem.$ext"
        VBus.log("done in ${(System.currentTimeMillis() - t0) / 1000} s")
    }

    private fun loadFace(ctx: Context): Typeface {
        val f = File(ctx.filesDir, "video_font.ttf")
        if (f.exists()) {
            try {
                return Typeface.createFromFile(f)
            } catch (e: Exception) {
                VBus.log("custom font failed to load, using the default")
            }
        }
        return Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    }

    // ---------- audio helpers ----------
    private fun findAudio(ex: MediaExtractor): Int {
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) return i
        }
        throw IOException("no audio track found")
    }

    private fun audioDurationUs(path: String): Long {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(path)
            val idx = findAudio(ex)
            val f = ex.getTrackFormat(idx)
            var dur = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L
            if (dur <= 0L) {
                ex.selectTrack(idx)
                var last = 0L
                while (true) {
                    val t = ex.sampleTime
                    if (t < 0) break
                    last = t
                    ex.advance()
                }
                dur = last
            }
            return dur
        } finally {
            ex.release()
        }
    }

    /** Quick test: can this device's MP4 muxer store the audio as-is? */
    private fun canPassthrough(path: String, probe: File): Boolean {
        val ex = MediaExtractor()
        var mm: MediaMuxer? = null
        return try {
            ex.setDataSource(path)
            val idx = findAudio(ex)
            ex.selectTrack(idx)
            probe.delete()
            val m = MediaMuxer(probe.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            mm = m
            val t = m.addTrack(ex.getTrackFormat(idx))
            m.start()
            val buf = ByteBuffer.allocate(1 shl 16)
            val info = MediaCodec.BufferInfo()
            var cnt = 0
            while (cnt < 30) {
                val sz = ex.readSampleData(buf, 0)
                if (sz < 0) break
                val fl = if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_SYNC_FRAME else 0
                info.set(0, sz, ex.sampleTime, fl)
                m.writeSampleData(t, buf, info)
                ex.advance()
                cnt++
            }
            m.stop()
            cnt > 0
        } catch (e: Exception) {
            false
        } finally {
            try { mm?.release() } catch (e: Exception) { /* ignore */ }
            ex.release()
            probe.delete()
        }
    }

    // ---------- video ----------
    private fun startEncoder(w: Int, h: Int): MediaCodec {
        val bitrate = (w.toLong() * h * 0.4).toInt() // ~370 kbps at 720p, ~830 kbps at 1080p
        val names = listOf<String?>(null, "c2.android.avc.encoder", "OMX.google.h264.encoder")
        var lastErr: Exception? = null
        for (name in names) {
            for (vbr in listOf(true, false)) {
                var c: MediaCodec? = null
                try {
                    val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
                    f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                    f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    f.setInteger(MediaFormat.KEY_FRAME_RATE, 1)
                    f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 60)
                    f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                    if (vbr) f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    val cc = if (name == null) MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC) else MediaCodec.createByCodecName(name)
                    c = cc
                    cc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    cc.start()
                    return cc
                } catch (e: Exception) {
                    lastErr = e
                    try { c?.release() } catch (x: Exception) { /* ignore */ }
                }
            }
        }
        throw IOException("no usable H.264 encoder: ${lastErr?.message}")
    }

    /** Copy the luma plane into the codec's input image; chroma is constant 128 (everything is grayscale). */
    private fun putImage(img: Image, y: ByteArray, w: Int, h: Int, fill: ByteArray) {
        val planes = img.planes
        val b0 = planes[0].buffer
        val rs = planes[0].rowStride
        if (rs == w) {
            b0.position(0)
            b0.put(y, 0, w * h)
        } else {
            for (r in 0 until h) {
                b0.position(r * rs)
                b0.put(y, r * w, w)
            }
        }
        for (k in 1..2) {
            val b = planes[k].buffer
            b.position(0)
            while (b.hasRemaining()) b.put(fill, 0, minOf(b.remaining(), fill.size))
        }
    }

    private fun encodeVideo(ctx: Context, look: Look, slides: List<Slide>, size: Float, totalUs: Long, out: File, keepUs: Long, keyRepeat: Boolean): Encoded {
        val w = look.w
        val h = look.h

        // frame schedule: one frame per slide change (sync frame) + tiny repeats every 15 s
        val frames = ArrayList<VFrame>()
        var last = -1L
        for (j in slides.indices) {
            val s = slides[j]
            val t = maxOf(s.startUs, last + 1000L)
            frames.add(VFrame(j, t, true))
            last = t
            var k = s.startUs + keepUs
            while (k < s.endUs - 500_000L) {
                if (k > last) {
                    frames.add(VFrame(j, k, keyRepeat))
                    last = k
                }
                k += keepUs
            }
        }
        val tail = totalUs - 100_000L
        if (tail > last + 100_000L) frames.add(VFrame(slides.size - 1, tail, false)) // so the last slide lasts to the end

        // parallel slide renderer (bounded look-ahead so memory stays small)
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val pool = Executors.newFixedThreadPool(threads)
        val renderers = Collections.synchronizedList(ArrayList<Renderer>())
        val tl = ThreadLocal<Renderer>()
        val futures = arrayOfNulls<Future<ByteArray>>(slides.size)
        var submitted = 0
        val window = threads * 2 + 2
        fun submit(upTo: Int) {
            while (submitted < minOf(upTo, slides.size)) {
                val j = submitted
                submitted++
                futures[j] = pool.submit(Callable<ByteArray> {
                    val r = tl.get() ?: Renderer(look).also { tl.set(it); renderers.add(it) }
                    val y = ByteArray(w * h)
                    r.render(slides[j], size, y)
                    y
                })
            }
        }
        VBus.log("rendering on $threads threads")
        submit(window)

        val codec = startEncoder(w, h)
        val chan = FileOutputStream(out).channel
        val samples = ArrayList<VSample>()
        var pos = 0L
        var maxSize = 0
        var fmtOut: MediaFormat? = null
        val info = MediaCodec.BufferInfo()
        val fill = ByteArray(65536) { 128.toByte() }
        try {
            var fi = 0
            var eos = false
            var done = false
            var curJ = -1
            var curY: ByteArray? = null
            while (!done) {
                check()
                if (!eos) {
                    val ii = codec.dequeueInputBuffer(5_000)
                    if (ii >= 0) {
                        if (fi < frames.size) {
                            val fr = frames[fi]
                            if (fr.slide != curJ) {
                                submit(fr.slide + window)
                                curY = futures[fr.slide]!!.get()
                                futures[fr.slide] = null
                                curJ = fr.slide
                                VBus.done = fr.slide + 1
                                VBus.ping()
                                VNotif.progress(ctx)
                            }
                            if (fr.fresh) {
                                val b = Bundle()
                                b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                                codec.setParameters(b)
                            }
                            val img = codec.getInputImage(ii) ?: throw IOException("encoder gave no input image")
                            putImage(img, curY!!, w, h, fill)
                            codec.queueInputBuffer(ii, 0, w * h * 3 / 2, fr.pts, 0)
                            fi++
                        } else {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eos = true
                        }
                    }
                }
                drain@ while (true) {
                    val oi = codec.dequeueOutputBuffer(info, if (eos) 10_000L else 0L)
                    if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) break@drain
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        fmtOut = codec.outputFormat
                        continue@drain
                    }
                    if (oi < 0) continue@drain
                    val buf = codec.getOutputBuffer(oi)
                    if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        while (buf.hasRemaining()) chan.write(buf)
                        samples.add(VSample(pos, info.size, info.presentationTimeUs, (info.flags and MediaCodec.BUFFER_FLAG_SYNC_FRAME) != 0))
                        pos += info.size.toLong()
                        if (info.size > maxSize) maxSize = info.size
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        done = true
                        break@drain
                    }
                }
            }
        } finally {
            try { codec.stop() } catch (e: Exception) { /* ignore */ }
            try { codec.release() } catch (e: Exception) { /* ignore */ }
            try { chan.close() } catch (e: Exception) { /* ignore */ }
            pool.shutdownNow()
            try { pool.awaitTermination(3, TimeUnit.SECONDS) } catch (e: Exception) { /* ignore */ }
            for (r in renderers.toList()) {
                try { r.recycle() } catch (e: Exception) { /* ignore */ }
            }
        }
        val f = fmtOut ?: throw IOException("encoder produced no output format")
        if (samples.isEmpty()) throw IOException("encoder produced no frames")
        return Encoded(f, samples, maxSize)
    }

    // ---------- final MP4: interleave audio and video by time ----------
    private fun mux(out: File, vfile: File, enc: Encoded, audioPath: String) {
        val ex = MediaExtractor()
        var mm: MediaMuxer? = null
        var started = false
        val raf = RandomAccessFile(vfile, "r")
        try {
            ex.setDataSource(audioPath)
            val ai = findAudio(ex)
            ex.selectTrack(ai)
            val m = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            mm = m
            val vTrack = m.addTrack(enc.format)
            val aTrack = m.addTrack(ex.getTrackFormat(ai))
            m.start()
            started = true

            val abuf = ByteBuffer.allocate(1 shl 18)
            val ainfo = MediaCodec.BufferInfo()
            var aDone = false
            fun pumpAudio(untilUs: Long) {
                while (!aDone) {
                    val t = ex.sampleTime
                    if (t < 0) {
                        aDone = true
                        break
                    }
                    if (t > untilUs) break
                    val sz = ex.readSampleData(abuf, 0)
                    if (sz < 0) {
                        aDone = true
                        break
                    }
                    val fl = if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_SYNC_FRAME else 0
                    ainfo.set(0, sz, t, fl)
                    m.writeSampleData(aTrack, abuf, ainfo)
                    ex.advance()
                }
            }

            val vbytes = ByteArray(maxOf(enc.maxSize, 16))
            val vinfo = MediaCodec.BufferInfo()
            val nS = enc.samples.size
            for ((si, s) in enc.samples.withIndex()) {
                check()
                if ((si and 31) == 0) {
                    VBus.muxFrac = si.toDouble() / maxOf(1, nS)
                    VBus.ping()
                }
                pumpAudio(s.pts)
                raf.seek(s.off)
                raf.readFully(vbytes, 0, s.size)
                vinfo.set(0, s.size, s.pts, if (s.key) MediaCodec.BUFFER_FLAG_SYNC_FRAME else 0)
                m.writeSampleData(vTrack, ByteBuffer.wrap(vbytes, 0, s.size), vinfo)
            }
            pumpAudio(Long.MAX_VALUE)
        } finally {
            try { if (started) mm?.stop() } catch (e: Exception) { /* ignore */ }
            try { mm?.release() } catch (e: Exception) { /* ignore */ }
            try { raf.close() } catch (e: Exception) { /* ignore */ }
            ex.release()
        }
    }

    private fun exportToDownloads(ctx: Context, f: File, name: String): Uri {
        val cr = ctx.contentResolver
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, if (name.endsWith(".mkv")) "video/x-matroska" else "video/mp4")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/GeminiTTS")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
            ?: throw IOException("cannot create file in Downloads")
        cr.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
        v.clear()
        v.put(MediaStore.Downloads.IS_PENDING, 0)
        cr.update(uri, v, null, null)
        return uri
    }
}
