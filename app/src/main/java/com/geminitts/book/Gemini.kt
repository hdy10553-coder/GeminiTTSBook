package com.geminitts.book

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

const val TTS_RPC = "XqA3Ic"
const val CALL_TIMEOUT_MS = 120_000
const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

class NeedCookie(msg: String) : Exception(msg)
class AuthError(msg: String) : IOException(msg)

val EXHAUSTED_WORDS = listOf(
    "quota", "usage limit", "limit exceeded", "limit reached", "reached your limit",
    "reached the limit", "exhaust", "credits", "rate limit", "too many requests",
    "429", "try again tomorrow", "come back later"
)

fun isQuota(e: Throwable): Boolean {
    val msg = (e.message ?: "").lowercase()
    return e is AuthError || EXHAUSTED_WORDS.any { msg.contains(it) }
}

fun isTimeout(e: Throwable): Boolean = e is SocketTimeoutException

fun looksExhausted(text: String): Boolean {
    val t = text.lowercase()
    return EXHAUSTED_WORDS.any { it != "429" && t.contains(it) }
}

private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

class Session {
    @Volatile private var ready = false
    private val jar = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var token = ""
    private var build = ""
    private var sid = ""
    private val reqid = AtomicInteger((10000..99999).random())

    private fun find(html: String, key: String): String? =
        Regex("\"$key\":\"(.*?)\"").find(html)?.groupValues?.get(1)

    private fun cookieHeader(): String = jar.entries.joinToString("; ") { it.key + "=" + it.value }

    private fun absorb(c: HttpURLConnection) {
        c.headerFields?.forEach { (k, v) ->
            if (k != null && k.equals("Set-Cookie", ignoreCase = true)) {
                v.forEach { sc ->
                    val nv = sc.substringBefore(';')
                    val n = nv.substringBefore('=').trim()
                    val value = nv.substringAfter('=', "").trim()
                    if (n.isNotEmpty()) {
                        if (value.isEmpty()) jar.remove(n) else jar[n] = value
                    }
                }
            }
        }
    }

    /** Guest session: just opening the Gemini page (signed out) hands out the cookies. */
    @Synchronized
    fun init() {
        if (ready) return
        jar.clear()
        jar["SOCS"] = "CAESEwgDEgk0ODE3Nzk3MjQaAmVuIAEaBgiA_LyaBg" // skips the consent wall
        try { get("https://www.google.com/") } catch (e: Exception) { /* optional */ }
        val html = get("https://gemini.google.com/app")
        val t = find(html, "SNlM0e")
        build = find(html, "cfb2h") ?: ""
        sid = find(html, "FdrFJe") ?: ""
        if (t == null && build.isEmpty()) throw AuthError("no guest session found on the Gemini page")
        token = t ?: ""
        ready = true
    }

    private fun get(startUrl: String): String {
        var url = startUrl
        for (hop in 0 until 8) {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 30_000
                c.readTimeout = CALL_TIMEOUT_MS
                c.setRequestProperty("User-Agent", UA)
                c.setRequestProperty("Cookie", cookieHeader())
                c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                val code = c.responseCode
                absorb(c)
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: throw IOException("redirect without Location")
                    url = URL(URL(url), loc).toString()
                    continue
                }
                if (code == 401 || code == 403) throw AuthError("HTTP $code (auth)")
                if (code >= 400) throw IOException("HTTP $code")
                return c.inputStream.bufferedReader().use { it.readText() }
            } finally {
                c.disconnect()
            }
        }
        throw IOException("too many redirects")
    }

    private fun post(url: String, form: Map<String, String>): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 30_000
            c.readTimeout = CALL_TIMEOUT_MS
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=utf-8")
            c.setRequestProperty("Origin", "https://gemini.google.com")
            c.setRequestProperty("Referer", "https://gemini.google.com/")
            c.setRequestProperty("X-Same-Domain", "1")
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Cookie", cookieHeader())
            val body = form.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            absorb(c)
            if (code == 401 || code == 403) throw AuthError("HTTP $code (auth)")
            if (code >= 400) throw IOException("HTTP $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun batch(rpc: String, payload: String): String {
        init()
        val fReq = JSONArray().put(
            JSONArray().put(JSONArray().put(rpc).put(payload).put(JSONObject.NULL).put("generic"))
        ).toString()
        val q = "rpcids=$rpc&_reqid=${reqid.getAndAdd(100000)}&rt=c&source-path=%2Fapp" +
            "&bl=${enc(build)}&f.sid=${enc(sid)}&hl=en"
        return post(
            "https://gemini.google.com/_/BardChatUi/data/batchexecute?$q",
            mapOf("at" to token, "f.req" to fReq)
        )
    }

    /** The TTS RPC: payload [null, text, lang, slot4, voice]. Returns raw audio bytes. */
    fun tts(text: String, lang: String, voice: Int, slot4: Any): ByteArray {
        val payload = JSONArray().put(JSONObject.NULL).put(text).put(lang).put(slot4).put(voice).toString()
        return decodeTts(batch(TTS_RPC, payload))
    }

    /** Best-effort chat call used by the optional "echo" step. */
    fun generate(prompt: String): String {
        init()
        val first = JSONArray().put(prompt).put(0).put(JSONObject.NULL).put(JSONObject.NULL)
            .put(JSONObject.NULL).put(JSONObject.NULL).put(0)
        val inner = JSONArray()
            .put(first)
            .put(JSONArray().put("en"))
            .put(JSONArray().put("").put("").put(""))
            .put(JSONObject.NULL).put(JSONObject.NULL).put(JSONObject.NULL)
            .put(JSONArray().put(1)).put(0).put(JSONArray()).put(JSONArray()).put(1).put(0)
        val fReq = JSONArray().put(JSONObject.NULL).put(inner.toString()).toString()
        val q = "bl=${enc(build)}&f.sid=${enc(sid)}&hl=en&_reqid=${reqid.getAndAdd(100000)}&rt=c"
        val raw = post(
            "https://gemini.google.com/_/BardChatUi/data/assistant.lamda.BardFrontendService/StreamGenerate?$q",
            mapOf("at" to token, "f.req" to fReq)
        )
        var last = ""
        for (line in raw.split("\n")) {
            val l = line.trim()
            if (!l.startsWith("[[")) continue
            val frames = try { JSONArray(l) } catch (e: Exception) { continue }
            for (k in 0 until frames.length()) {
                val fr = frames.optJSONArray(k) ?: continue
                if (fr.optString(0) != "wrb.fr" || fr.isNull(2)) continue
                try {
                    val body = JSONArray(fr.getString(2))
                    val t = body.optJSONArray(4)?.optJSONArray(0)?.optJSONArray(1)?.optString(0)
                    if (!t.isNullOrBlank()) last = t
                } catch (e: Exception) { /* skip frame */ }
            }
        }
        if (last.isEmpty()) throw IOException("empty chat reply")
        return last
    }
}

fun decodeTts(resp: String): ByteArray {
    val text = resp.removePrefix(")]}'").trimStart()
    for (raw in text.split("\n")) {
        val line = raw.trim()
        if (!line.startsWith("[[")) continue
        val frames = try { JSONArray(line) } catch (e: Exception) { continue }
        for (k in 0 until frames.length()) {
            val fr = frames.optJSONArray(k) ?: continue
            if (fr.length() > 2 && fr.optString(0) == "wrb.fr" && fr.optString(1) == TTS_RPC && !fr.isNull(2)) {
                val inner = JSONArray(fr.getString(2))
                return Base64.decode(inner.getString(0), Base64.DEFAULT)
            }
        }
    }
    throw IOException("no audio in TTS response")
}
