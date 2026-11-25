package com.pragon.mobile

import android.content.Context
import java.io.File

/** v8.2: one readable report of why an offline voice or the offline listener does or does not work. Shown by Settings > Offline > Test offline voices. */
object VoiceDiag {

    fun trace(e: Throwable): String {
        val sb = StringBuilder()
        var t: Throwable? = e
        var n = 0
        while (t != null && n < 3) {
            sb.append(t.javaClass.simpleName).append(": ").append(t.message ?: "").append('\n')
            t.stackTrace.take(3).forEach { sb.append("    at ").append(it.toString()).append('\n') }
            t = t.cause
            n++
        }
        return sb.toString().trim()
    }

    /** Tries to load the sherpa-onnx native library the way the voices and the listener do. */
    fun nativeLibs(c: Context): String {
        val r = try { System.loadLibrary("sherpa-onnx-jni"); "libsherpa-onnx-jni loaded" } catch (e: Throwable) { "FAILED to load libsherpa-onnx-jni: ${e.javaClass.simpleName}: ${e.message}" }
        val files = try { File(c.applicationInfo.nativeLibraryDir).list()?.sorted()?.joinToString(", ") ?: "none" } catch (e: Throwable) { "?" }
        return "$r\nnative files: $files"
    }

    fun report(c: Context): String {
        val sb = StringBuilder()
        sb.append("PRAGON VOICE TEST (v8.2)\n")
        sb.append("phone: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}\n")
        sb.append("online: ${Net.online(c)} | offline lock: ${Offline.locked(c)}\n\n")
        sb.append("SPEECH LIBRARY\n").append(nativeLibs(c)).append("\n\n")
        sb.append(try { PiperTts.diagnose(c) } catch (e: Throwable) { "REALISTIC VOICE test crashed: " + trace(e) + "\n" }).append('\n')
        for (l in MmsTts.LANGS) {
            if (MmsTts.hasModel(c, l)) sb.append(try { MmsTts.diagnose(c, l) } catch (e: Throwable) { "${l.name} voice test crashed: " + trace(e) + "\n" }).append('\n')
            else sb.append("${l.name.uppercase()} VOICE\nnot downloaded (Settings > Offline > ${l.name} voice > Download)\n\n")
        }
        sb.append("OFFLINE LISTENING (speech pack)\n")
        sb.append("installed: ${SherpaStt.hasModel(c)} | state: ${SherpaStt.state} | download: ${SherpaStt.dlState}\n")
        sb.append("engine setting: ${Prefs.sttEngine(c)} | listening language: ${Prefs.speechLang(c).ifBlank { "phone's" }}\n")
        sb.append("Pragon's own listener is used right now: ${SherpaStt.shouldUse(c)}\n")
        val lang = Prefs.speechLang(c).ifBlank { java.util.Locale.getDefault().language }
        if (!lang.startsWith("en", true) && Prefs.sttModelUrl(c) == SherpaStt.DEFAULT_MODEL_URL)
            sb.append("NOTE: the built-in speech pack only understands English. With a non-English listening language, Android's own recogniser listens (it needs its offline language pack), unless you paste a speech model link for that language.\n")
        else if (Prefs.sttEngine(c) == "auto" && Net.online(c))
            sb.append("NOTE: engine is Auto and you are online, so Android's own recogniser listens. Choose 'Pragon' to always use the on-phone listener.\n")
        return sb.toString()
    }
}
