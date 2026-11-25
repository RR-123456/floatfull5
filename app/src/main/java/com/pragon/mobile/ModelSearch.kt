package com.pragon.mobile

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * v7.7: search Hugging Face for another on-device model (Settings > Offline > Find another model).
 *
 * Only LiteRT-LM files (.litertlm) can run in this app's engine, so the results are narrowed to those. Files built for one phone chip
 * (names with sm8750, mt6993 and the like) are hidden because they will not load on other phones. A chosen file becomes the CHAT model.
 * The search and the file list are plain public Hugging Face API calls started by you; nothing runs in the background.
 * Your Hugging Face token is only ever sent to huggingface.co / hf.co, never to another host.
 */
object ModelSearch {

    class Repo(val id: String, val downloads: Long, val likes: Long)
    class Item(val name: String, val mb: Long)

    @Volatile var busy = false
    @Volatile var msg = ""
    @Volatile var repos: List<Repo> = emptyList()
    @Volatile var repo = ""
    @Volatile var files: List<Item> = emptyList()

    private val REPO_OK = Regex("^[A-Za-z0-9_.-]{1,96}/[A-Za-z0-9_.-]{1,96}$")
    private val CHIP_ONLY = Regex("(?i)(sm|mt)\\d{4}")

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).followRedirects(true).build()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(c: Context, url: String): String {
        val rb = Request.Builder().url(url).header("Accept", "application/json")
        val t = Prefs.hfToken(c)
        if (t.isNotBlank()) rb.header("Authorization", "Bearer $t")      // url is always huggingface.co here
        http.newCall(rb.build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("Hugging Face answered HTTP ${r.code}")
            return r.body?.string() ?: throw java.io.IOException("empty answer")
        }
    }

    private fun parse(js: String): List<Repo> {
        val a = JSONArray(js)
        val out = ArrayList<Repo>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val id = o.optString("id", o.optString("modelId", ""))
            if (REPO_OK.matches(id)) out.add(Repo(id, o.optLong("downloads", 0), o.optLong("likes", 0)))
        }
        return out
    }

    fun search(c: Context, query: String) {
        val q = query.trim()
        if (q.isEmpty() || busy) return
        if (!Net.online(c)) { msg = "Connect to the internet to search."; return }
        busy = true; msg = "Searching..."; repo = ""; files = emptyList()
        val app = c.applicationContext
        Thread {
            try {
                val base = "https://huggingface.co/api/models?limit=20&sort=downloads&direction=-1&search=${enc(q)}"
                var found = parse(get(app, "$base&filter=litert-lm"))
                if (found.isEmpty()) found = parse(get(app, "$base&author=litert-community"))
                repos = found
                msg = if (found.isEmpty()) "Nothing found for \"$q\". Try a shorter word such as gemma, qwen or phi."
                else "Tap a model to see its .litertlm files."
            } catch (e: Exception) {
                repos = emptyList(); msg = "Search failed: ${e.message}"
            } finally { busy = false }
        }.start()
    }

    fun listFiles(c: Context, id: String) {
        if (busy) return
        if (!REPO_OK.matches(id)) { msg = "That isn't a valid model name."; return }
        busy = true; msg = "Reading the file list..."; repo = id; files = emptyList()
        val app = c.applicationContext
        Thread {
            try {
                val o = JSONObject(get(app, "https://huggingface.co/api/models/$id?blobs=true"))
                val sib = o.optJSONArray("siblings") ?: JSONArray()
                val all = ArrayList<Item>()
                var hidden = 0
                for (i in 0 until sib.length()) {
                    val s = sib.optJSONObject(i) ?: continue
                    val n = s.optString("rfilename", "")
                    if (!n.lowercase().endsWith(".litertlm")) continue
                    if (CHIP_ONLY.containsMatchIn(n)) { hidden++; continue }
                    all.add(Item(n, s.optLong("size", 0) / 1_000_000))
                }
                files = all.sortedBy { it.mb }
                msg = when {
                    all.isNotEmpty() -> "Pick a file. Smaller files run faster and need less memory."
                    hidden > 0 -> "This model only has files for one phone chip, so none will run here."
                    else -> "No .litertlm files in this model. Only LiteRT-LM files work here (.task and .gguf files do not)."
                }
            } catch (e: Exception) {
                files = emptyList(); msg = "Couldn't read that model: ${e.message}"
            } finally { busy = false }
        }.start()
    }

    /** The https link of one file, or null when the repo or file name looks wrong. */
    fun fileUrl(repoId: String, file: String): String? {
        if (!REPO_OK.matches(repoId)) return null
        if (!file.lowercase().endsWith(".litertlm") || file.contains("..") || file.startsWith("/") || file.contains("\\")) return null
        val path = file.split('/').joinToString("/") { enc(it).replace("+", "%20") }
        return "https://huggingface.co/$repoId/resolve/main/$path"
    }

    fun json(): String {
        val r = JSONArray()
        repos.forEach { r.put(JSONObject().put("id", it.id).put("dl", it.downloads).put("likes", it.likes)) }
        val f = JSONArray()
        files.forEach { f.put(JSONObject().put("name", it.name).put("mb", it.mb)) }
        return JSONObject().put("busy", busy).put("msg", msg).put("repos", r).put("repo", repo).put("files", f).toString()
    }
}
