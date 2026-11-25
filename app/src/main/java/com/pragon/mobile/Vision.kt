package com.pragon.mobile

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.provider.MediaStore
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

/**
 * v7.3: three on-phone vision tools, English / Latin script only. Everything runs on the phone:
 *   - Text reading (OCR): ML Kit Text Recognition (Latin), bundled model, no download.
 *   - Image labels: ML Kit Image Labeling (about 400 everyday labels), bundled model, no download.
 *   - Subject cut-out: ML Kit Subject Segmentation. Its model comes through Google Play services (downloaded once, at install
 *     time or on first use). The cut-out is saved as a transparent PNG in Pictures/Pragon.
 * v7.5: the same tools also work on a photo from the camera (VisionCaptureActivity takes it), plus ML Kit Object Detection (boxes, each
 * box re-labelled for a finer name) and a scene description written by the on-phone chat model from the labels, objects and text found.
 * Plus an OCR fallback for "click <word>": when the accessibility tree has no node with that text (text drawn inside an
 * image, games, some web views), the screen is read with OCR and the matching word is tapped.
 *
 * All of it uses a screenshot of the screen, so it needs "Let Pragon read my screen" (Settings > Security) and the
 * accessibility service, and it refuses while a password box is visible. All calls are blocking: use a background thread.
 */
object Vision {

    private const val LABEL = "Vision"
    private const val WAIT_S = 20L

    private fun no(msg: String) = CommandExecutor.Res(false, msg, label = LABEL)
    private fun ok(msg: String) = CommandExecutor.Res(true, msg, label = LABEL)

    /** Why the screen can't be used right now, or null if it can. */
    private fun gate(ctx: Context): CommandExecutor.Res? {
        if (!Guard.screenReadingOn(ctx))
            return no("I'm not allowed to read your screen. Turn on \"Let Pragon read my screen\" in Settings > Security first.")
        val svc = PragonAccessibilityService.instance
            ?: return no("This needs the Pragon accessibility service turned on.")
        if (svc.passwordVisible()) return no("A password box is on the screen, so I won't look at it. Move away from it and ask again.")
        return null
    }

    private fun shot(): Bitmap? = PragonAccessibilityService.instance?.screenshot()

    private val NO_SHOT = "Android didn't give me a screenshot. The screen may be protected (banking, video apps), or it needs Android 11 or newer."

    // ── where the picture comes from ────────────────────────────────────

    /** The picture to analyse: the camera (a photo the person takes) or a screenshot of the screen. Blocking. */
    private fun source(ctx: Context, camera: Boolean): Pair<Bitmap?, CommandExecutor.Res?> {
        if (camera) {
            val (b, err) = VisionCaptureActivity.capture(ctx)
            return if (b != null) b to null else null to no(err.ifBlank { "No photo was taken." })
        }
        gate(ctx)?.let { return null to it }
        val b = shot() ?: return null to no(NO_SHOT)
        return b to null
    }

    private fun where(camera: Boolean) = if (camera) "in the photo" else "on the screen"

    // ── text reading (OCR) ──────────────────────────────────────────────

    private fun ocr(bmp: Bitmap): Text? = try {
        val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try { Tasks.await(rec.process(InputImage.fromBitmap(bmp, 0)), WAIT_S, TimeUnit.SECONDS) } finally { rec.close() }
    } catch (e: Throwable) { null }

    private fun ocrLines(bmp: Bitmap): List<String> =
        ocr(bmp)?.textBlocks?.flatMap { it.lines }?.map { it.text.trim() }?.filter { it.isNotBlank() }.orEmpty()

    /** "OCR": reads the English text on the screen (screenshot) or in a photo from the camera. */
    fun readText(ctx: Context, camera: Boolean = false): CommandExecutor.Res {
        val (bmp, fail) = source(ctx, camera)
        if (bmp == null) return fail!!
        val t = ocr(bmp) ?: return no("The text reader didn't start. Try again in a moment.")
        val lines = t.textBlocks.flatMap { it.lines }.map { it.text.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return no("I couldn't find any English text ${where(camera)}.")
        // The reply is spoken (and shown in chat) by the normal answer path, so this does not speak by itself.
        return ok("The text says: " + lines.joinToString(". ").take(2500))
    }

    // ── image labels ────────────────────────────────────────────────────

    private fun labelsOf(bmp: Bitmap, threshold: Float = 0.6f): List<com.google.mlkit.vision.label.ImageLabel>? = try {
        val lab = ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(threshold).build())
        try { Tasks.await(lab.process(InputImage.fromBitmap(bmp, 0)), WAIT_S, TimeUnit.SECONDS) } finally { lab.close() }
    } catch (e: Throwable) { null }

    /** "label this picture": the most likely things in the picture (screen or camera), with a confidence each. */
    fun label(ctx: Context, camera: Boolean = false): CommandExecutor.Res {
        val (bmp, fail) = source(ctx, camera)
        if (bmp == null) return fail!!
        val labels = labelsOf(bmp) ?: return no("The image labeller didn't start. Try again in a moment.")
        if (labels.isEmpty()) return no("I can't tell what is ${where(camera)}. The labeller only knows everyday things like animals, food, plants, vehicles and places.")
        val top = labels.sortedByDescending { it.confidence }.take(6)
        return ok("I can see: " + top.joinToString(", ") { it.text.lowercase() + " (" + (it.confidence * 100).toInt() + "%)" } + ".")
    }

    // ── object detection (boxes + labels) ───────────────────────────────

    class Found(val name: String, val box: android.graphics.Rect, val pos: String)

    private fun position(box: android.graphics.Rect, w: Int): String {
        val c = box.exactCenterX() / w
        return if (c < 0.34f) "on the left" else if (c > 0.66f) "on the right" else "in the middle"
    }

    /**
     * ML Kit finds up to five objects with boxes but only names them coarsely (fashion, food, home good, place, plant), so each box is
     * cropped and run through the image labeller to get a finer name ("cup", "laptop").
     */
    private fun detectObjects(bmp: Bitmap): List<Found>? {
        val objs = try {
            val det = ObjectDetection.getClient(
                ObjectDetectorOptions.Builder().setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE).enableMultipleObjects().enableClassification().build())
            try { Tasks.await(det.process(InputImage.fromBitmap(bmp, 0)), WAIT_S, TimeUnit.SECONDS) } finally { det.close() }
        } catch (e: Throwable) { return null }
        val out = ArrayList<Found>()
        for (o in objs.sortedByDescending { it.boundingBox.width() * it.boundingBox.height() }.take(5)) {
            val r = android.graphics.Rect(o.boundingBox)
            r.intersect(0, 0, bmp.width, bmp.height)
            var name = o.labels.maxByOrNull { it.confidence }?.text?.lowercase().orEmpty()
            if (r.width() >= 32 && r.height() >= 32) {
                try {
                    val crop = Bitmap.createBitmap(bmp, r.left, r.top, r.width(), r.height())
                    labelsOf(crop, 0.5f)?.maxByOrNull { it.confidence }?.let { name = it.text.lowercase() }
                } catch (e: Throwable) { }
            }
            out.add(Found(name.ifEmpty { "object" }, r, position(r, bmp.width)))
        }
        return out
    }

    private fun article(n: String) = if (n.isNotEmpty() && n[0] in "aeiou") "an $n" else "a $n"

    /** "what objects do you see": finds objects with the camera or on the screen, says where each is, and saves a copy with the boxes drawn. */
    fun detect(ctx: Context, camera: Boolean = true): CommandExecutor.Res {
        val (bmp, fail) = source(ctx, camera)
        if (bmp == null) return fail!!
        val found = detectObjects(bmp) ?: return no("The object detector didn't start. Try again in a moment.")
        if (found.isEmpty()) return no("I couldn't pick out any separate objects ${where(camera)}. Move closer, or put the object on a plain surface.")
        val canvasBmp = bmp.copy(Bitmap.Config.ARGB_8888, true)
        val cv = android.graphics.Canvas(canvasBmp)
        val box = android.graphics.Paint().apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = bmp.width / 150f + 2f; color = 0xFF00D4FF.toInt() }
        val txt = android.graphics.Paint().apply { color = 0xFFFFFFFF.toInt(); textSize = bmp.width / 24f; isAntiAlias = true; setShadowLayer(4f, 0f, 0f, 0xFF000000.toInt()) }
        for (f in found) { cv.drawRect(f.box, box); cv.drawText(f.name, f.box.left + 6f, f.box.top + txt.textSize, txt) }
        val saved = saveBitmap(ctx, canvasBmp, "Pragon_objects", asPng = false)
        val list = found.joinToString(", ") { article(it.name) + " " + it.pos }
        return ok("I see ${found.size} object" + (if (found.size > 1) "s" else "") + ": $list." +
            (if (saved) " A copy with boxes is saved in Pictures Pragon." else ""))
    }

    // ── scene description ───────────────────────────────────────────────

    /**
     * "describe the scene" / "what do you see": there is no vision model, so the facts come from three small ones (labels, objects,
     * text) and the on-phone chat model (Gemma 3 1B) turns them into one or two sentences. Without the chat model the facts are read out.
     */
    fun describe(ctx: Context, camera: Boolean = true): CommandExecutor.Res {
        val (bmp, fail) = source(ctx, camera)
        if (bmp == null) return fail!!
        val labels = labelsOf(bmp, 0.55f)?.sortedByDescending { it.confidence }?.take(6)?.map { it.text.lowercase() }.orEmpty()
        val objects = detectObjects(bmp).orEmpty().map { article(it.name) + " " + it.pos }
        val text = ocrLines(bmp).joinToString(" ").take(200)
        if (labels.isEmpty() && objects.isEmpty() && text.isBlank()) return no("I can't make out anything useful ${where(camera)}. Try better light, or move closer.")
        val facts = buildString {
            if (labels.isNotEmpty()) append("Things in the picture: ${labels.joinToString(", ")}. ")
            if (objects.isNotEmpty()) append("Objects: ${objects.joinToString("; ")}. ")
            if (text.isNotBlank()) append("Writing seen: \"$text\". ")
        }.trim()
        if (Offline.chatFile(ctx) == null) return ok("The offline chat model isn't installed, so here are the facts. $facts")
        val prompt = "These facts were found in a picture by an image tool.\n$facts\n\n" +
            "Describe the scene in one or two short spoken sentences. Use only these facts and do not invent anything."
        OnDeviceLlm.resetChat()
        val ans = try { OnDeviceLlm.chat(ctx, prompt, 120) } finally { OnDeviceLlm.resetChat() }
        val clean = ans?.replace(Regex("[*_`#]+"), "")?.trim().orEmpty()
        return ok(clean.ifBlank { facts })
    }

    // ── BlindCam: one camera frame in, a spoken summary out (v7.6) ──────

    enum class CamMode(val label: String) { AUTO("Auto"), TEXT("Read text"), OBJECTS("Objects"), DESCRIBE("Describe") }

    /** What to say, and a [key] that only changes when what the camera sees really changes (so live mode doesn't repeat itself). */
    class FrameResult(val spoken: String, val key: String)

    /**
     * Analyses one camera frame for BlindCam. Saves nothing. [quick] = skip the chat model (live mode must keep up, and the 1B model takes
     * seconds); then the sentence is built straight from the objects / labels. AUTO = scene sentence plus any text found. Blocking.
     */
    fun analyzeFrame(ctx: Context, bmp: Bitmap, mode: CamMode, quick: Boolean): FrameResult {
        val lines = if (mode == CamMode.OBJECTS) emptyList() else ocrLines(bmp)
        val textKey = lines.joinToString(" ").lowercase().replace(Regex("[^a-z0-9 ]"), "").trim()
        if (mode == CamMode.TEXT) {
            return if (lines.isEmpty()) FrameResult("No text found.", "")
            else FrameResult("Text: " + lines.joinToString(". ").take(600), textKey)
        }
        val found = detectObjects(bmp).orEmpty()
        val objects = found.map { article(it.name) + " " + it.pos }
        val key = (found.map { it.name }.sorted().joinToString(",") + "|" + textKey)
        if (mode == CamMode.OBJECTS)
            return if (objects.isEmpty()) FrameResult("No objects found.", "") else FrameResult("I see " + objects.joinToString(", ") + ".", key)
        val labels = if (objects.isEmpty()) labelsOf(bmp, 0.55f)?.sortedByDescending { it.confidence }?.take(5)?.map { it.text.lowercase() }.orEmpty() else emptyList()
        var scene = when {
            objects.isNotEmpty() -> "I see " + objects.joinToString(", ") + "."
            labels.isNotEmpty() -> "It looks like " + labels.joinToString(", ") + "."
            else -> ""
        }
        if (!quick && Offline.chatFile(ctx) != null && scene.isNotEmpty()) {
            val facts = scene + (if (lines.isNotEmpty()) " Writing seen: \"" + lines.joinToString(" ").take(150) + "\"." else "")
            val prompt = "These facts were found in a camera picture by an image tool.\n$facts\n\n" +
                "Describe the scene for a blind person in one or two short spoken sentences. Use only these facts and do not invent anything."
            OnDeviceLlm.resetChat()
            val ans = try { OnDeviceLlm.chat(ctx, prompt, 100) } finally { OnDeviceLlm.resetChat() }
            val clean = ans?.replace(Regex("[*_`#]+"), "")?.trim().orEmpty()
            if (clean.isNotBlank()) scene = clean
        }
        val text = if (mode == CamMode.AUTO && lines.isNotEmpty()) " Text: " + lines.joinToString(". ").take(200) else ""
        val all = (scene + text).trim()
        return if (all.isEmpty()) FrameResult("I can't make out anything. Try more light, or move closer.", "") else FrameResult(all, key)
    }

    // ── subject cut-out ─────────────────────────────────────────────────

    private fun saveBitmap(ctx: Context, bmp: Bitmap, prefix: String, asPng: Boolean): Boolean {
        val mime = if (asPng) "image/png" else "image/jpeg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${prefix}_${System.currentTimeMillis()}." + (if (asPng) "png" else "jpg"))
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Pragon")
        }
        return try {
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(if (asPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, if (asPng) 100 else 90, it) } ?: return false
            true
        } catch (e: Exception) { false }
    }

    /** "cut out the subject": saves the main subject of the screen or of a camera photo as a transparent PNG in Pictures/Pragon. */
    fun cutOut(ctx: Context, camera: Boolean = false): CommandExecutor.Res {
        val (bmp, fail) = source(ctx, camera)
        if (bmp == null) return fail!!
        val fg: Bitmap? = try {
            val seg = SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundBitmap().build())
            try { Tasks.await(seg.process(InputImage.fromBitmap(bmp, 0)), WAIT_S, TimeUnit.SECONDS).foregroundBitmap }
            finally { seg.close() }
        } catch (e: Throwable) { return no("The cut-out tool isn't ready yet. Google Play services downloads its model the first time, so connect to the internet once and try again.") }
        if (fg == null) return no("I couldn't find a clear subject ${where(camera)}. " + (if (camera) "Fill the frame with one object and try again." else "Open the photo full screen and try again."))
        return if (saveBitmap(ctx, fg, "Pragon_cutout", asPng = true)) ok("Cut out the subject. It is saved in Pictures Pragon as a transparent PNG.")
        else no("The phone wouldn't let me save the picture.")
    }

    // ── offline screen reading: ask a question about the screen (v7.4) ───

    /**
     * "what's on my screen?" with no internet: the on-phone chat model answers from the screen's text. If there is little accessible
     * text, OCR reads the pixels; if there is no text at all, the image labeller names what is in the picture. The 1B chat model is
     * small: expect plain summaries and simple look-ups, not careful reasoning. It can't see pictures itself.
     */
    fun askLocal(ctx: Context, question: String): CommandExecutor.Res {
        gate(ctx)?.let { return it }
        val svc = PragonAccessibilityService.instance ?: return no("This needs the Pragon accessibility service turned on.")
        var txt = try { AccessFeatures.Screen.dump(svc, ctx.packageName, 2500) } catch (e: Exception) { null }.orEmpty()
        if (txt.length < 80) {
            val bmp = shot()
            val t = if (bmp != null) ocr(bmp) else null
            val o = t?.textBlocks?.flatMap { it.lines }?.joinToString("\n") { it.text }.orEmpty()
            if (o.isNotBlank()) txt = (txt + "\n" + o).trim().take(2500)
        }
        if (txt.isBlank()) return label(ctx)   // a picture with no text: name what is in it
        if (Offline.chatFile(ctx) == null)
            return ok("The offline chat model isn't installed, so I can only read it out. The screen says: " + txt.lines().filter { it.isNotBlank() }.joinToString(". ").take(500))
        val q = question.ifBlank { "Briefly, what is on this screen?" }
        val prompt = "This is the text currently on the phone screen:\n$txt\n\nQuestion: $q\nAnswer briefly, using only the screen text. If the answer is not there, say so."
        OnDeviceLlm.resetChat()
        val ans = try { OnDeviceLlm.chat(ctx, prompt, 160) } finally { OnDeviceLlm.resetChat() }
        val clean = ans?.replace(Regex("[*_`#]+"), "")?.trim().orEmpty()
        return if (clean.isBlank()) no("The offline chat model didn't answer. ${OnDeviceLlm.chatState}") else ok(clean)
    }

    // ── offline screen clicking by description (v7.4) ───────────────────

    /**
     * "click the search icon", "tap the green call button": when no element is called exactly that and OCR finds nothing, the
     * on-phone chat model is shown a numbered list of the tappable items on screen and picks the one that fits. Returns null if it
     * can't (no chat model, nothing tappable, the model says none fits), so the caller gives its normal "couldn't find" answer.
     */
    fun pickAndTap(ctx: Context, description: String): CommandExecutor.Res? {
        if (!Guard.screenReadingOn(ctx)) return null
        if (Offline.chatFile(ctx) == null) return null
        val svc = PragonAccessibilityService.instance ?: return null
        if (svc.passwordVisible()) return null
        val cands = svc.clickCandidates(40)
        if (cands.isEmpty()) return null
        val list = cands.mapIndexed { i, c -> "${i + 1}. ${c.label}" }.joinToString("\n")
        val prompt = "Tappable items on the phone screen:\n$list\n\nThe user wants to tap: \"$description\".\n" +
            "Reply with only the number of the best matching item, or 0 if none matches."
        OnDeviceLlm.resetChat()
        val ans = try { OnDeviceLlm.chat(ctx, prompt, 8) } finally { OnDeviceLlm.resetChat() }
        val n = Regex("\\d+").find(ans ?: return null)?.value?.toIntOrNull() ?: return null
        if (n < 1 || n > cands.size) return null
        val c = cands[n - 1]
        return if (svc.pressNode(c.node)) CommandExecutor.Res(true, "Tapped ${c.label}.", label = c.label)
        else CommandExecutor.Res(false, "I picked '${c.label}' but the phone wouldn't press it.")
    }

    // ── "click <word>" fallback ─────────────────────────────────────────

    /**
     * Finds [target] on the screen with OCR and taps it. Returns null when it isn't allowed or nothing matched, so the caller can
     * give its normal "couldn't find" answer. Matches a whole line or a single word, ignoring case; the shortest match wins.
     */
    fun tapByOcr(ctx: Context, target: String): CommandExecutor.Res? {
        if (!Guard.screenReadingOn(ctx)) return null
        val svc = PragonAccessibilityService.instance ?: return null
        if (svc.passwordVisible()) return null
        val want = target.trim().lowercase()
        if (want.isEmpty()) return null
        val bmp = shot() ?: return null
        val t = ocr(bmp) ?: return null
        var best: android.graphics.Rect? = null
        var bestLen = Int.MAX_VALUE
        for (line in t.textBlocks.flatMap { it.lines }) {
            val box = line.boundingBox ?: continue
            if (line.text.lowercase().contains(want) && line.text.length < bestLen) { best = box; bestLen = line.text.length }
            for (el in line.elements) {
                val eb = el.boundingBox ?: continue
                if (el.text.lowercase().trim('.', ',', ':', ';') == want && el.text.length <= bestLen) { best = eb; bestLen = el.text.length }
            }
        }
        val r = best ?: return null
        val dm = ctx.resources.displayMetrics
        val x = r.exactCenterX() * dm.widthPixels / bmp.width
        val y = r.exactCenterY() * dm.heightPixels / bmp.height
        val done = svc.gesture(Path().apply { moveTo(x, y) }, 60)
        return if (done) CommandExecutor.Res(true, "Clicked $target (found by reading the screen).", label = target)
        else CommandExecutor.Res(false, "I found '$target' on the screen but the phone wouldn't press it.")
    }
}
