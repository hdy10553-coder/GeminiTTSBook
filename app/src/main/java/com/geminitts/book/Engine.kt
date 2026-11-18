package com.geminitts.book

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

private class State(@Volatile var client: Session) {
    val lock = ReentrantLock()
    @Volatile var gen = 0
    @Volatile var fails = 0
    val stop = AtomicBoolean(false)
    @Volatile var echoOff = false
    val done = AtomicInteger(0)
}

private class Env(
    val ctx: Context,
    val chunks: List<String>,
    val dir: File,
    val opt: Options,
    val base: Int
)

object Engine {
    @Volatile private var cur: State? = null
    private const val ECHO_SUFFIX = "\n\nSend this text back, say nothing else."
    private const val EXHAUSTED_MSG = "Gemini says this session is exhausted / over its limit"

    fun stop() {
        cur?.stop?.set(true)
        VideoMaker.stop()
    }

    private fun partOk(f: File) = f.exists() && f.length() > 1000

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)

    private fun cleanEcho(reply: String): String {
        var r = reply.trim()
        if (r.length >= 2 && r.first() == r.last() && r.first() in "\"'\u201c\u201d") {
            r = r.substring(1, r.length - 1).trim()
        } else if (r.startsWith("\u201c") && r.endsWith("\u201d")) {
            r = r.substring(1, r.length - 1).trim()
        }
        return r
    }

    // ---------- main entry ----------
    fun run(ctx: Context, job: Job) {
        val opt = job.opt
        Bus.done = 0
        Bus.total = 0
        Bus.output = null
        Bus.videoOutput = null
        Bus.phase = 0
        Bus.clearLog()
        Bus.status = "Reading ${job.name}…"
        Bus.ping()

        val text = Reader.readText(ctx.contentResolver, job.uri, job.name)
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) {
            Bus.status = "File is empty"
            return
        }
        val chunks = words.chunked(opt.words).map { it.joinToString(" ") }

        val key = sha1("${opt.words}|${opt.lang}|${opt.voice}|${opt.slot4}|$text")
        val dir = File(ctx.filesDir, "jobs/$key")
        if (job.fresh) dir.deleteRecursively()
        dir.mkdirs()
        dir.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() }

        val parts = chunks.indices.map { File(dir, "part_%04d.ogg".format(it + 1)) }
        val pending = parts.indices.filter { !partOk(parts[it]) }.map { it + 1 }
        Bus.total = chunks.size
        Bus.done = chunks.size - pending.size
        Bus.log("${chunks.size} chunks of ~${opt.words} words")
        Bus.log("${Bus.done} already done, ${pending.size} to do, ${opt.workers} worker(s)")
        Bus.status = "Working"
        Notifier.update(ctx, Bus.done, Bus.total)

        if (pending.isNotEmpty()) {
            var first: Session? = null
            var err: String? = null
            try {
                first = Session().also { it.init() }
            } catch (e: Exception) {
                err = "${e.javaClass.simpleName}: ${e.message}"
            }
            val st = State(first ?: Session())
            cur = st
            if (first == null) {
                if (!switchCookie(st, "Could not start a Gemini guest session ($err).", ctx)) {
                    cur = null
                    Bus.status = "Stopped (could not reach Gemini)"
                    return
                }
            }

            val queue = ConcurrentLinkedQueue<Int>(pending)
            val env = Env(ctx, chunks, dir, opt, chunks.size - pending.size)
            val n = maxOf(1, minOf(opt.workers, pending.size))
            val threads = (0 until n).map { w ->
                Thread {
                    try {
                        worker(w, st, queue, env)
                    } catch (e: Throwable) {
                        Bus.log("worker crashed: ${e.javaClass.simpleName} ${e.message}")
                    }
                }.also { it.start() }
            }
            threads.forEach { it.join() }
            cur = null
        }

        val missing = parts.count { !partOk(it) }
        if (missing > 0) {
            Bus.status = "Not finished: $missing part(s) missing"
            Bus.log("To continue later: pick the same file, same settings, 'Start fresh' OFF, press Start.")
            return
        }

        Bus.status = "Merging…"
        Bus.log("merging…")
        val stem = job.name.substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").ifBlank { "book" }
        val tmp = File(ctx.cacheDir, "$stem.ogg")
        val merged = Ogg.merge(parts, tmp)
        // Every batch's real audio length -> its time window inside the FINAL merged audiobook.
        val ends = Timeline.build(chunks, parts, merged, tmp)
        val uri = exportToDownloads(ctx, tmp, "$stem.ogg")
        Bus.output = uri
        Bus.log("audio saved: Download/GeminiTTS/$stem.ogg")

        if (!opt.video) {
            tmp.delete()
            dir.deleteRecursively()
            Bus.status = "Done: Download/GeminiTTS/$stem.ogg"
            return
        }

        // ---- video, same run: slides are placed by the batch windows measured above ----
        Bus.phase = 1
        Bus.status = "Building video…"
        Bus.ping()
        var ok = false
        try {
            ok = VideoMaker.run(ctx, VideoRequest(chunks, ends, tmp, stem, opt.maxWords, opt.hd, opt.footer))
        } catch (e: Throwable) {
            Bus.log("video error: ${e.javaClass.simpleName} ${e.message}")
            Bus.status = "Audio done, video failed: ${e.message}"
        }
        Bus.phase = 0
        tmp.delete()
        if (ok) {
            dir.deleteRecursively()
        } else {
            Bus.log("The audio is saved. To retry only the video: same file, same settings, 'Start fresh' OFF, press Start (no audio is redone).")
        }
    }

    private fun exportToDownloads(ctx: Context, f: File, name: String): Uri {
        val cr = ctx.contentResolver
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "audio/ogg")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/GeminiTTS")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
            ?: throw java.io.IOException("cannot create file in Downloads")
        cr.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
        v.clear()
        v.put(MediaStore.Downloads.IS_PENDING, 0)
        cr.update(uri, v, null, null)
        return uri
    }

    // ---------- retry / echo ----------
    private fun <T> withRetry(st: State, tries: Int = 4, fn: () -> T): T {
        for (i in 1..tries) {
            try {
                return fn()
            } catch (e: Exception) {
                Bus.log("   attempt $i/$tries failed: ${e.javaClass.simpleName} ${e.message}")
                if (i == tries || isQuota(e) || st.stop.get()) throw e
                Thread.sleep(5000L * i)
            }
        }
        throw IllegalStateException("unreachable")
    }

    private fun echoStep(st: State, client: Session, chunk: String): String {
        val reply = try {
            withRetry(st) { cleanEcho(client.generate("\"$chunk\"$ECHO_SUFFIX")) }
        } catch (e: Exception) {
            if (isQuota(e)) throw NeedCookie("$EXHAUSTED_MSG: ${e.javaClass.simpleName} ${e.message}")
            if (isTimeout(e)) throw NeedCookie("Gemini keeps timing out (no answer within 120s, 4 tries in a row).")
            st.echoOff = true
            Bus.log("   echo step failed - switching to direct mode for the rest of this run")
            return chunk
        }
        val nIn = chunk.split(" ").size
        val nOut = reply.split(Regex("\\s+")).filter { it.isNotEmpty() }.size
        if (reply.isNotEmpty() && nOut >= 0.7 * nIn && nOut <= 1.3 * nIn) return reply
        if (reply.isNotEmpty() && looksExhausted(reply) && nOut < 0.7 * nIn) {
            throw NeedCookie("Gemini answered with a limit message instead of the text: ${reply.take(200)}")
        }
        if (reply.isNotEmpty()) Bus.log("   echo looked wrong ($nOut vs $nIn words), using original chunk")
        return chunk
    }

    // ---------- automatic cookie refresh (no sign-in, no prompts) ----------
    private fun sleepOrStop(st: State, secs: Long): Boolean {
        var left = secs
        while (left > 0) {
            if (st.stop.get()) return false
            Thread.sleep(1000)
            left--
        }
        return !st.stop.get()
    }

    private fun switchCookie(st: State, reason: String, ctx: Context): Boolean {
        Bus.log("!! $reason")
        Bus.log("!! Getting fresh Gemini cookies automatically (finished parts are saved).")
        Bus.status = "Refreshing cookies…"
        Bus.ping()
        val pre = if (st.fails > 1) minOf(st.fails * 10L, 60L) else 0L
        val waits = longArrayOf(pre, 5, 15, 30, 60, 120)
        for (w in waits) {
            if (w > 0) {
                Bus.log("   trying again in ${w}s")
                if (!sleepOrStop(st, w)) return false
            }
            if (st.stop.get()) return false
            try {
                val s = Session()
                s.init()
                st.client = s
                Bus.log("fresh cookies acquired, continuing...")
                Bus.status = "Working"
                Bus.ping()
                return true
            } catch (e: Exception) {
                Bus.log("   cookie refresh failed: ${e.javaClass.simpleName} ${e.message}")
            }
        }
        Notifier.alert(ctx, "Could not get fresh Gemini cookies. Finished parts are saved.")
        return false
    }

    private fun handleFailure(st: State, gen: Int, reason: String, ctx: Context): Boolean {
        st.lock.lock()
        try {
            if (st.stop.get()) return false
            if (st.gen != gen) return true // someone else already switched while we waited
            st.fails++
            if (st.fails > 8) {
                Bus.log("!! Fresh cookies did not help after 8 tries in a row. Stopping; finished parts are saved.")
                Notifier.alert(ctx, "Gemini keeps refusing, even with fresh cookies. Finished parts are saved.")
                st.stop.set(true)
                return false
            }
            val ok = switchCookie(st, reason, ctx)
            if (ok) st.gen++ else st.stop.set(true)
            return ok
        } finally {
            st.lock.unlock()
        }
    }

    // ---------- worker ----------
    private fun worker(wid: Int, st: State, queue: ConcurrentLinkedQueue<Int>, e: Env) {
        Thread.sleep(wid * 1500L) // stagger first requests
        val total = e.chunks.size
        while (!st.stop.get()) {
            val i = queue.poll() ?: return
            val chunk = e.chunks[i - 1]
            val part = File(e.dir, "part_%04d.ogg".format(i))
            Bus.log("[$i/$total] working... (worker ${wid + 1})")
            var audio: ByteArray? = null
            while (!st.stop.get()) {
                val client = st.client
                val gen = st.gen
                try {
                    val text = if (e.opt.direct || st.echoOff) chunk else echoStep(st, client, chunk)
                    audio = try {
                        withRetry(st) { client.tts(text, e.opt.lang, e.opt.voice, e.opt.slot4) }
                    } catch (ex: Exception) {
                        val why = when {
                            isQuota(ex) -> EXHAUSTED_MSG
                            isTimeout(ex) -> "Gemini keeps timing out (no answer within 120s, 4 tries in a row)"
                            else -> "Audio request kept failing"
                        }
                        throw NeedCookie(
                            "$why (${ex.javaClass.simpleName}: ${ex.message}). " +
                                "Quota may be used up, the cookie expired, or the network dropped."
                        )
                    }
                    break
                } catch (nc: NeedCookie) {
                    if (!handleFailure(st, gen, nc.message ?: "", e.ctx)) return
                }
            }
            val data = audio ?: return // stop was set
            val tmp = File(e.dir, "part_%04d.%d.tmp".format(i, wid))
            tmp.writeBytes(data)
            tmp.renameTo(part) // a part only appears once fully written
            val d = e.base + st.done.incrementAndGet()
            Bus.done = d
            st.fails = 0
            Bus.log("[$i/$total] saved")
            Notifier.update(e.ctx, d, total)
            Thread.sleep(1000)
        }
    }
}
