package com.geminitts.book

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper

class Options(
    val words: Int,
    val lang: String,
    val voice: Int,
    val slot4: Any,
    val workers: Int,
    val direct: Boolean,
    val video: Boolean = false,
    val maxWords: Int = 150,
    val hd: Boolean = false,
    val footer: Boolean = true
)

class Job(val uri: Uri, val name: String, val opt: Options, val fresh: Boolean)

object Bus {
    private val main = Handler(Looper.getMainLooper())
    private val lines = ArrayDeque<String>()

    @Volatile var running = false
    @Volatile var done = 0
    @Volatile var total = 0
    @Volatile var status = "Idle"
    @Volatile var output: Uri? = null
    @Volatile var videoOutput: Uri? = null
    @Volatile var phase = 0 // 0 = audio batches, 1 = building the video
    @Volatile var job: Job? = null
    @Volatile var listener: (() -> Unit)? = null

    fun log(s: String) {
        synchronized(lines) {
            lines.addLast(s)
            while (lines.size > 200) lines.removeFirst()
        }
        ping()
    }

    fun clearLog() {
        synchronized(lines) { lines.clear() }
    }

    fun tail(n: Int): String = synchronized(lines) { lines.toList().takeLast(n).joinToString("\n") }

    fun ping() {
        main.post { listener?.invoke() }
    }
}

object Store {
    fun sp(c: Context) = c.getSharedPreferences("gtts", Context.MODE_PRIVATE)
}
