package com.geminitts.book

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONTokener
import java.io.File

class MainActivity : Activity() {
    private lateinit var tvFile: TextView
    private lateinit var etWords: EditText
    private lateinit var etWorkers: EditText
    private lateinit var swDirect: Switch
    private lateinit var swFresh: Switch
    private lateinit var btnStart: Button
    private lateinit var bar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var outBox: LinearLayout
    private lateinit var vidBox: LinearLayout
    private lateinit var swVideo: Switch
    private lateinit var etMax: EditText
    private lateinit var rbSd: RadioButton
    private lateinit var rbHd: RadioButton
    private lateinit var swFooter: Switch
    private lateinit var tvFont: TextView

    private var fileUri: Uri? = null
    private var fileName = ""

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun head(t: String) = TextView(this).apply {
        text = t
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(18), 0, dp(4))
    }

    private fun hint(t: String) = TextView(this).apply {
        text = t
        textSize = 12f
        alpha = 0.65f
        setPadding(0, dp(8), 0, 0)
    }

    private fun btn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun edit(value: String, number: Boolean) = EditText(this).apply {
        setText(value)
        setSingleLine()
        if (number) inputType = InputType.TYPE_CLASS_NUMBER
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sp = Store.sp(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        setContentView(ScrollView(this).apply { addView(col) })

        col.addView(hint("No sign-in needed: Gemini cookies are fetched automatically, and refreshed by themselves if they run out mid-conversion."))

        col.addView(head("1 · Book"))
        tvFile = TextView(this).apply { text = "No file chosen (TXT, EPUB, HTML…)" }
        col.addView(tvFile)
        col.addView(btn("Choose file") {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 1
            )
        })

        col.addView(head("2 · Options"))
        col.addView(hint("Words per chunk (-w)"))
        etWords = edit(sp.getString("words", "500")!!, true); col.addView(etWords)
        col.addView(hint("Concurrent workers (-j)"))
        etWorkers = edit(sp.getString("workers", "10")!!, true); col.addView(etWorkers)
        swDirect = Switch(this).apply {
            text = "Direct mode (skip echo step, half the requests)"
            isChecked = sp.getBoolean("direct", true)
            setPadding(0, dp(10), 0, dp(4))
        }
        col.addView(swDirect)
        swFresh = Switch(this).apply {
            text = "Start fresh (ignore earlier unfinished parts)"
            isChecked = false
            setPadding(0, dp(4), 0, dp(4))
        }
        col.addView(swFresh)

        col.addView(head("3 · Video (optional)"))
        swVideo = Switch(this).apply {
            text = "Also make a video, slides synced to the audio"
            isChecked = sp.getBoolean("v_on", false)
            setPadding(0, dp(4), 0, dp(4))
        }
        col.addView(swVideo)
        col.addView(hint("Built in the same run, right after the batches are merged: each batch's real audio length decides when its slides appear. Black screen, white text, vertical; saved next to the audio (MKV, or MP4 if the audio needs converting)."))
        col.addView(hint("Max words per screen (fewer = bigger text)"))
        etMax = edit(sp.getString("v_max", "150")!!, true); col.addView(etMax)
        col.addView(hint("Resolution"))
        rbSd = RadioButton(this).apply { text = "720 × 1600 (smaller, faster)"; id = View.generateViewId() }
        rbHd = RadioButton(this).apply { text = "1080 × 2400"; id = View.generateViewId() }
        val rg = RadioGroup(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(rbSd)
            addView(rbHd)
        }
        col.addView(rg)
        if (sp.getBoolean("v_hd", false)) rbHd.isChecked = true else rbSd.isChecked = true
        swFooter = Switch(this).apply {
            text = "Show time and progress bar"
            isChecked = sp.getBoolean("v_footer", true)
            setPadding(0, dp(10), 0, dp(4))
        }
        col.addView(swFooter)
        col.addView(hint("Font"))
        tvFont = TextView(this)
        col.addView(tvFont)
        col.addView(row(
            btn("Import font (.ttf/.otf)") {
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 3
                )
            },
            btn("Default font") {
                File(filesDir, "video_font.ttf").delete()
                refreshFont()
            }
        ))
        refreshFont()

        btnStart = btn("Start conversion") { startConversion() }
        col.addView(btnStart)

        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        col.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        tvStatus = TextView(this)
        col.addView(tvStatus)

        outBox = row(
            btn("Play audio") { openAudio() },
            btn("Share audio") { shareAudio() }
        )
        col.addView(outBox)
        vidBox = row(
            btn("Play video") { openVideo() },
            btn("Share video") { shareVideo() }
        )
        col.addView(vidBox)

        tvLog = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(0, dp(10), 0, 0)
        }
        col.addView(tvLog)

        col.addView(btn("Delete saved partial work") {
            File(filesDir, "jobs").deleteRecursively()
            toast("Partial work deleted")
        })
    }

    override fun onResume() {
        super.onResume()
        Bus.listener = { refresh() }
        refresh()
    }

    override fun onPause() {
        Bus.listener = null
        super.onPause()
    }

    private fun refreshFont() {
        val has = File(filesDir, "video_font.ttf").exists()
        tvFont.text = if (has) "Custom: " + Store.sp(this).getString("v_fontname", "font")
        else "Default (serif)"
    }

    private fun importFont(u: Uri) {
        try {
            val tmp = File(filesDir, "video_font.tmp")
            val input = contentResolver.openInputStream(u) ?: throw java.io.IOException("cannot open")
            input.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            Typeface.createFromFile(tmp) // throws if it is not a usable font
            val dst = File(filesDir, "video_font.ttf")
            dst.delete()
            if (!tmp.renameTo(dst)) throw java.io.IOException("cannot save font")
            Store.sp(this).edit().putString("v_fontname", Reader.displayName(contentResolver, u)).apply()
            refreshFont()
        } catch (e: Exception) {
            File(filesDir, "video_font.tmp").delete()
            toast("Not a usable font file")
        }
    }

    private fun refresh() {
        if (Bus.phase == 1) {
            bar.max = 1000
            bar.progress = (VBus.overall() * 1000).toInt()
            tvStatus.text = "${(VBus.overall() * 100).toInt()}%  ·  ${Bus.status}\n${VBus.detail()}"
        } else {
            bar.max = maxOf(Bus.total, 1)
            bar.progress = Bus.done
            tvStatus.text = if (Bus.total > 0) "${Bus.status}  (${Bus.done}/${Bus.total})" else Bus.status
        }
        tvLog.text = Bus.tail(60)
        btnStart.text = if (Bus.running) "Stop" else "Start conversion"
        outBox.visibility = if (Bus.output != null) View.VISIBLE else View.GONE
        vidBox.visibility = if (Bus.videoOutput != null) View.VISIBLE else View.GONE
    }

    @Deprecated("simple framework result API")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        when (req) {
            1 -> {
                val u = data?.data
                if (res == RESULT_OK && u != null) {
                    fileUri = u
                    fileName = Reader.displayName(contentResolver, u)
                    tvFile.text = fileName
                }
            }
            3 -> {
                val u = data?.data
                if (res == RESULT_OK && u != null) importFont(u)
            }
        }
    }

    private fun startConversion() {
        if (Bus.running) {
            Engine.stop()
            toast("Stopping… finished parts are kept")
            return
        }
        val uri = fileUri ?: return toast("Choose a file first")
        val words = etWords.text.toString().toIntOrNull()?.takeIf { it >= 1 } ?: return toast("Words per chunk must be 1 or more")
        val workers = etWorkers.text.toString().toIntOrNull()?.takeIf { it >= 1 } ?: return toast("Workers must be 1 or more")
        // fixed values (the old Language / Voice / Slot 4 options were removed from the UI)
        val lang = "en-US"
        val voice = 2
        val slot4: Any = org.json.JSONObject.NULL

        val maxw = etMax.text.toString().toIntOrNull()?.takeIf { it >= 10 }
        if (swVideo.isChecked && maxw == null) return toast("Max words per screen must be 10 or more")

        Store.sp(this).edit()
            .putString("words", words.toString())
            .putString("workers", workers.toString()).putBoolean("direct", swDirect.isChecked)
            .putBoolean("v_on", swVideo.isChecked)
            .putString("v_max", (maxw ?: 150).toString())
            .putBoolean("v_hd", rbHd.isChecked)
            .putBoolean("v_footer", swFooter.isChecked)
            .apply()

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)

        Bus.job = Job(
            uri, fileName,
            Options(words, lang, voice, slot4, workers, swDirect.isChecked, swVideo.isChecked, maxw ?: 150, rbHd.isChecked, swFooter.isChecked),
            swFresh.isChecked
        )
        Bus.output = null
        Bus.videoOutput = null
        Bus.status = "Starting…"
        startForegroundService(Intent(this, ConvertService::class.java))
        refresh()
    }

    private fun openAudio() {
        val u = Bus.output ?: return
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(u, "audio/ogg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(Intent.createChooser(i, "Play")) } catch (e: Exception) { toast("No audio player found") }
    }

    private fun openVideo() {
        val u = Bus.videoOutput ?: return
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(u, contentResolver.getType(u) ?: "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(Intent.createChooser(i, "Play")) } catch (e: Exception) { toast("No video player found") }
    }

    private fun shareVideo() {
        val u = Bus.videoOutput ?: return
        val i = Intent(Intent.ACTION_SEND).setType(contentResolver.getType(u) ?: "video/*").putExtra(Intent.EXTRA_STREAM, u)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, "Share"))
    }

    private fun shareAudio() {
        val u = Bus.output ?: return
        val i = Intent(Intent.ACTION_SEND).setType("audio/ogg").putExtra(Intent.EXTRA_STREAM, u)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, "Share"))
    }
}
