package com.pragon.mobile

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * v19.4: image generation with the Pollinations unified API (https://gen.pollinations.ai/image/...).
 * The old image.pollinations.ai address stopped working; the new one wants an API key from enter.pollinations.ai
 * (paste it in Settings > SETUP > Image generation). The key is sent as "Authorization: Bearer".
 * v19: first version, kept below:
 * One GET returns the finished picture, which is saved to Pictures/Pragon so it shows up in the gallery and My Files.
 * Used by the Tools > Image generator screen and by the voice command "generate an image of ...".
 */
object ImageGen {

    val MODELS = listOf("flux", "turbo", "gptimage", "seedream", "nanobanana")
    val SIZES = mapOf("square" to (1024 to 1024), "portrait" to (768 to 1024), "wide" to (1024 to 768))

    class Result(val ok: Boolean, val msg: String, val bytes: ByteArray? = null, val uri: Uri? = null, val mime: String = "image/jpeg")

    /** Downloads one image. Blocking: call from a background thread. */
    fun generate(ctx: Context, prompt: String, width: Int = 1024, height: Int = 1024, model: String = "flux", seed: Long = -1, safe: Boolean = true): Result {
        val p = prompt.trim().take(900)
        if (p.isEmpty()) return Result(false, "Tell me what to draw first.")
        if (!Net.online(ctx)) return Result(false, "I need the internet to make an image.")
        val w = width.coerceIn(256, 2048)
        val h = height.coerceIn(256, 2048)
        // Ask for a slightly taller picture, then cut the extra strip off the bottom (where any service mark sits), so the final size is exactly w x h.
        val reqH = (h * (1 + TRIM)).toInt().coerceAtMost(2048)
        val m = if (model in MODELS) model else "flux"
        val sd = if (seed >= 0) seed else (Math.random() * 1_000_000).toLong()
        val key = Prefs.pollKey(ctx).trim()
        val reqH2 = if (key.isBlank()) reqH else h            // with a key there is no service mark, so no strip to cut off
        val url = "https://gen.pollinations.ai/image/" + URLEncoder.encode(p, "UTF-8").replace("+", "%20") +
            "?width=$w&height=$reqH2&seed=$sd&model=$m&nologo=true&safe=$safe"
        var lastErr = "The image service did not answer."
        // The free tier answers 429 when it is busy: one patient retry is worth it.
        for (attempt in 0..1) {
            var c: HttpURLConnection? = null
            try {
                c = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 120_000
                    setRequestProperty("User-Agent", "PragonMobile/7.0")
                    setRequestProperty("Accept", "image/*")
                    if (key.isNotBlank()) setRequestProperty("Authorization", "Bearer $key")
                }
                val code = c.responseCode
                if (code == 200) {
                    val type = (c.contentType ?: "").substringBefore(";").trim().lowercase()
                    val data = c.inputStream.use { it.readBytes() }
                    if (!type.startsWith("image/") || data.size < 1000) return Result(false, "The service sent something that is not an image.")
                    val cut = if (key.isBlank()) trimBottom(data, w, h) else null
                    return if (cut != null) Result(true, "Done.", cut, null, "image/jpeg") else Result(true, "Done.", data, null, type)
                }
                val detail = errorText(c)
                lastErr = when (code) {
                    401 -> "Pollinations needs an API key. Add yours in Settings > SETUP > Image generation (get one free at enter.pollinations.ai)." +
                        if (key.isNotBlank()) " The saved key was refused: check it is copied in full." else ""
                    402 -> "Your Pollinations balance (Pollen) is empty. Top up or earn Pollen at enter.pollinations.ai, or try the Flux model."
                    403 -> "Pollinations refused this: the key may not allow the \"$m\" model, or the prompt was blocked. Try Flux or different words." + detail
                    429 -> "Pollinations is busy or you hit a rate limit. Wait about 30 seconds and try again."
                    400 -> "Pollinations did not accept that request." + detail
                    404 -> "Model \"$m\" was not found. Pick another style engine."
                    else -> "The image service returned error $code." + detail
                }
                if (code != 429 && code < 500) return Result(false, lastErr)
            } catch (e: java.net.SocketTimeoutException) {
                lastErr = "The image took too long. Try again."
            } catch (e: Exception) {
                lastErr = "Could not reach the image service."
            } finally { try { c?.disconnect() } catch (_: Exception) { } }
            if (attempt == 0) try { Thread.sleep(4000) } catch (_: InterruptedException) { return Result(false, lastErr) }
        }
        return Result(false, lastErr)
    }

    private const val TRIM = 0.09

    /** Short reason from the error body (JSON {"error":{"message":..}} or plain text), or "". */
    private fun errorText(c: HttpURLConnection): String = try {
        val raw = (c.errorStream ?: return "").use { it.readBytes() }.toString(Charsets.UTF_8).take(600)
        val msg = try {
            val j = org.json.JSONObject(raw)
            val e = j.opt("error")
            (if (e is org.json.JSONObject) e.optString("message") else (e?.toString() ?: "")).ifBlank { j.optString("message") }
        } catch (_: Exception) { raw }
        if (msg.isBlank()) "" else " (" + msg.replace(Regex("\\s+"), " ").take(140) + ")"
    } catch (_: Exception) { "" }

    /** Checks the saved key against the account balance endpoint. Blocking: background thread only. Pair = (ok, message). */
    fun testKey(ctx: Context): Pair<Boolean, String> {
        val key = Prefs.pollKey(ctx).trim()
        if (key.isBlank()) return false to "No key saved yet."
        if (!Net.online(ctx)) return false to "I need the internet to check the key."
        var c: HttpURLConnection? = null
        return try {
            c = (URL("https://gen.pollinations.ai/account/balance").openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 20_000
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("User-Agent", "PragonMobile/7.0")
            }
            when (val code = c.responseCode) {
                200 -> {
                    val body = c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
                    val bal = try { org.json.JSONObject(body).opt("balance")?.toString() } catch (_: Exception) { null }
                    true to ("Key works." + if (bal != null) " Balance: $bal Pollen." else "")
                }
                401 -> false to "Pollinations rejected this key. Copy it again in full (it starts with sk_ or pk_)."
                403 -> true to "Key is valid, but it cannot read the balance (that is fine for image generation)."
                else -> false to "Could not verify the key (error $code)."
            }
        } catch (e: Exception) { false to "Could not reach Pollinations." } finally { try { c?.disconnect() } catch (_: Exception) { } }
    }

    /** Crops the picture to w x h from the top-left, removing the extra bottom strip. null = could not decode, keep the original. */
    private fun trimBottom(data: ByteArray, w: Int, h: Int): ByteArray? = try {
        val bmp = BitmapFactory.decodeByteArray(data, 0, data.size)
        if (bmp == null) null else {
            val cw = minOf(bmp.width, w)
            val ch = minOf(bmp.height, maxOf(1, (bmp.height * h / (h * (1 + TRIM))).toInt()))
            val out = Bitmap.createBitmap(bmp, 0, 0, cw, ch)
            val bo = java.io.ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, 95, bo)
            if (out !== bmp) out.recycle(); bmp.recycle()
            bo.toByteArray()
        }
    } catch (e: Throwable) { null }

    /** Saves bytes to Pictures/Pragon. Returns the gallery Uri, or null if the phone refused. */
    fun save(ctx: Context, bytes: ByteArray, mime: String): Uri? = try {
        val ext = if (mime.contains("png")) "png" else if (mime.contains("webp")) "webp" else "jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Pragon_AI_${System.currentTimeMillis()}.$ext")
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Pragon")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: run {
            try { ctx.contentResolver.delete(uri, null, null) } catch (_: Exception) { }
            return null
        }
        uri
    } catch (e: Exception) { null }
}
