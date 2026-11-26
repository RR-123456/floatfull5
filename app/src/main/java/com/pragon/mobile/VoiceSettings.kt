package com.pragon.mobile

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * v34: say it to switch it. Every on/off option in Pragon's own Settings, the orb and emblem sizes, and every Tools screen
 * can be controlled by voice. Phone switches (Wi-Fi, torch, ...) stay in QuickVoice; options that already had their own
 * phrases (privacy shield, sidebar on/off, Pragon contacts, always-offline lock, parallel models, firewall) keep them.
 *
 * Actions: psetting (value "<id>:on|off|toggle|status"), pnumber (value "orb:<n>", "emblem:<n>", "orb:+10", "emblem:-10"),
 * ptool (value = tool id).
 * Phrases: "turn on live voice", "disable macros", "emblem follows orb size on", "is typer punctuation on",
 * "set orb size to 150", "make the orb bigger", "open neural", "open the drafter", "turn on the AC at 24 cool turbo",
 * "speak faster", "reply in Hindi", "use the ollama engine". (Orb size is the only size set by voice.)
 */
object VoiceSettings {

    private class Sw(val id: String, val label: String, val words: List<String>, val get: (Context) -> Boolean, val set: (Context, Boolean) -> String?)

    private fun msg(on: Boolean, label: String) = "$label is ${if (on) "on" else "off"}."

    private val SWITCHES: List<Sw> = listOf(
        Sw("speaktyped", "Speak replies to typed messages", listOf("speak replies to typed messages", "speak typed replies", "speak replies to typing", "read replies aloud to typed messages"),
            { Prefs.speakTyped(it) }, { c, on -> Prefs.setSpeakTyped(c, on); Speaker.get(c).reload(); null }),
        Sw("floatwake", "Float mode wake word", listOf("float mode wake word", "wake word", "only answer when i say pragon", "float wake word"),
            { Prefs.floatNeedsWake(it) }, { c, on -> Prefs.setFloatNeedsWake(c, on); null }),
        Sw("piper", "The realistic voice", listOf("realistic voice", "piper voice", "piper", "neural voice"),
            { Prefs.piperOn(it) }, { c, on -> Prefs.setPiperOn(c, on); null }),
        Sw("live", "Pragon Live voice", listOf("pragon live voice", "live voice", "pragon live"),
            { Prefs.liveOn(it) }, { c, on -> Prefs.setLiveOn(c, on); null }),
        Sw("barge", "Interrupting Pragon while it talks", listOf("interrupt pragon while it talks", "barge in", "interruptions", "let me interrupt pragon", "interrupt mode"),
            { Prefs.liveBargeIn(it) }, { c, on -> Prefs.setLiveBargeIn(c, on); null }),
        Sw("mediafriendly", "Media-friendly listening", listOf("media friendly listening", "media friendly mode", "media friendly"),
            { Guard.mediaFriendly(it) }, { c, on -> Guard.setMediaFriendly(c, on); null }),
        Sw("typerpunct", "Typer punctuation", listOf("typer punctuation", "typer adds punctuation", "auto punctuation", "automatic punctuation"),
            { Prefs.typerPunct(it) }, { c, on -> Prefs.setTyperPunct(c, on); null }),
        Sw("typerauto", "Typer finds the text box by itself", listOf("typer auto find", "typer finds the text box", "typer auto", "auto find text box"),
            { Guard.typerAuto(it) }, { c, on -> Guard.setTyperAuto(c, on); null }),
        Sw("macros", "Macros", listOf("macros", "allow macros"),
            { Guard.macrosOn(it) }, { c, on -> Guard.setMacros(c, on); null }),
        Sw("screenread", "Letting Pragon read your screen", listOf("screen reading", "let pragon read my screen", "read my screen permission", "screen read permission"),
            { Guard.screenReadingOn(it) }, { c, on -> Guard.setScreenReading(c, on); null }),
        Sw("a11yhelpers", "Letting Pragon switch accessibility helpers", listOf("accessibility helpers", "let pragon switch accessibility helpers", "accessibility switching"),
            { Guard.a11yFeaturesOn(it) }, { c, on -> Guard.setA11yFeatures(c, on); null }),
        Sw("allowlist", "Only open apps I allow", listOf("only open apps i allow", "app allow list", "allowed apps only", "allowlist"),
            { Guard.allowlistOn(it) }, { c, on ->
                if (on && Guard.allowedApps(c).isEmpty()) "Tick at least one allowed app in Settings first." else { Guard.setAllowlist(c, on); null } }),
        Sw("autosend", "Press Send for me", listOf("press send for me", "auto send", "automatic send"),
            { Prefs.autoSend(it) }, { c, on -> Prefs.setAutoSend(c, on); null }),
        Sw("showall", "Show all features", listOf("show all features", "show every feature"),
            { Prefs.showAllFeatures(it) }, { c, on -> Prefs.setShowAllFeatures(c, on); null }),
        Sw("emblemfollow", "Emblem follows orb size", listOf("emblem follows orb size", "emblem follow orb size", "emblem size follows orb size", "emblem follows the orb", "emblem follow"),
            { Prefs.emblemFollow(it) }, { c, on -> Prefs.saveEmblemFollow(c, on); FloatService.applyEmblem(); null }),
        Sw("sidebarleft", "Sidebar on the left edge", listOf("sidebar on the left", "sidebar left side", "sidebar on the left edge", "sidebar left"),
            { SidebarService.leftSide(it) }, { c, on ->
                SidebarService.setLeft(c, on)
                if (SidebarService.running) { SidebarService.stop(c); Handler(Looper.getMainLooper()).postDelayed({ SidebarService.start(c) }, 400) }
                null }),
    )

    private class Tool(val id: String, val label: String, val words: List<String>, val intent: (Context) -> Intent)

    private fun panel(c: Context, p: String) = Intent(c, ToolsActivity::class.java).putExtra("panel", p)

    private val TOOLS: List<Tool> = listOf(
        Tool("neural", "Neural", listOf("neural", "neural chat"), { Intent(it, NeuralActivity::class.java) }),
        Tool("video", "Video editor", listOf("video editor", "the video editor"), { Intent(it, VideoEditorActivity::class.java) }),
        Tool("tools", "Tools", listOf("tools", "tools menu", "tools screen"), { Intent(it, ToolsActivity::class.java) }),
        Tool("drafter", "Drafter", listOf("drafter", "the drafter"), { panel(it, "draft") }),
        Tool("acremote", "AC remote", listOf("ac remote", "air conditioner remote", "the ac remote"), { panel(it, "ir") }),
        Tool("monitors", "Notification monitors", listOf("notification monitors", "notifications tool", "notification tool"), { panel(it, "mon") }),
        Tool("calctool", "Calculator", listOf("pragon calculator", "scientific calculator"), { panel(it, "calc") }),
        Tool("msgtool", "Messages tool", listOf("messages tool", "message tool", "text tool"), { panel(it, "msg") }),
        Tool("calendartool", "Calendar tool", listOf("calendar tool", "new event tool"), { panel(it, "cal") }),
        Tool("clocktool", "Clock tool", listOf("clock tool", "alarm tool", "timer tool"), { panel(it, "clock") }),
    )

    private val I = RegexOption.IGNORE_CASE
    private val LEAD = Regex("^(?:(?:hey|hi|hello|ok|okay|please|yo)\\s+)+", I)
    private val LEAD_P = Regex("^(?:pragon|paragon|pragan|pragun)\\b[,]?\\s*", I)
    private val TRAIL = Regex("(?:\\s+(?:please|now|for me))+$", I)

    private fun tidy(raw: String): String {
        var s = raw.trim().replace("\u2019", "'").replace(Regex("\\s+"), " ")
        s = LEAD.replaceFirst(s, ""); s = LEAD_P.replaceFirst(s, ""); s = TRAIL.replace(s, "")
        return s.trim().trimEnd('.', '!', '?', ',').trim().lowercase()
    }

    private fun alt(words: List<String>) = words.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
    private val THE = "(?:the |my |pragon )*"

    private val NUM_SET = Regex("^(?:set|change|make|put|adjust)\\s+$THE(orb)(?: size)?\\s+(?:to|at)?\\s*(\\d{2,3})(?:\\s*(?:percent|%))?$|^$THE(orb) size\\s+(?:to\\s+)?(\\d{2,3})(?:\\s*(?:percent|%))?$|^(orb) (\\d{2,3})(?:\\s*(?:percent|%))?$")
    private val NUM_STEP = Regex("^(?:make|set)\\s+$THE(orb)(?: size)?\\s+(bigger|larger|smaller|a bit bigger|a bit smaller|a little bigger|a little smaller)$|^(increase|decrease|reduce|raise|lower|grow|shrink)\\s+$THE(orb)(?: size)?$|^(orb) size (up|down)$")
    private val NUM_RESET = Regex("^(?:reset|default)\\s+$THE(orb)(?: size)?(?: to default)?$|^(orb) size (?:reset|default)$")

    /** Pure phrase matching. null = not one of mine. */
    fun parse(raw: String): VoiceParser.Action? {
        val s = tidy(raw)
        if (s.isEmpty()) return null

        NUM_RESET.matchEntire(s)?.let { m ->
            val w = m.groupValues[1].ifEmpty { m.groupValues[2] }
            return VoiceParser.Action("pnumber", "${kind(w)}:100")
        }
        NUM_SET.matchEntire(s)?.let { m ->
            val g = m.groupValues
            val w = listOf(g[1], g[3], g[5]).first { it.isNotEmpty() }
            val n = listOf(g[2], g[4], g[6]).first { it.isNotEmpty() }
            return VoiceParser.Action("pnumber", "${kind(w)}:$n")
        }
        NUM_STEP.matchEntire(s)?.let { m ->
            val g = m.groupValues
            val w = listOf(g[1], g[4], g[5]).first { it.isNotEmpty() }
            val dir = listOf(g[2], g[3], g[6]).first { it.isNotEmpty() }
            val up = dir.contains("bigger") || dir.contains("larger") || dir in listOf("increase", "raise", "grow", "up")
            return VoiceParser.Action("pnumber", "${kind(w)}:${if (up) "+10" else "-10"}")
        }

        for (t in TOOLS) {
            val a = alt(t.words)
            if (Regex("^(?:open|show|start|launch|go to|take me to)\\s+$THE(?:$a)(?: tool| screen| page)?$").matches(s)) return VoiceParser.Action("ptool", t.id)
        }

        acParse(s)?.let { return it }
        voiceParse(s)?.let { return it }

        for (sw in SWITCHES) {
            val a = alt(sw.words)
            val x = "$THE(?:$a)(?: option| setting| switch| mode)?"
            Regex("^(?:turn|switch|set)\\s+(on|off)\\s+$x$").matchEntire(s)?.let { return sa(sw, it.groupValues[1]) }
            Regex("^(enable|disable|activate|deactivate)\\s+$x$").matchEntire(s)?.let { return sa(sw, if (it.groupValues[1].startsWith("en") || it.groupValues[1] == "activate") "on" else "off") }
            Regex("^$x\\s+(on|off)$").matchEntire(s)?.let { return sa(sw, it.groupValues[1]) }
            Regex("^(?:turn|switch|set) $x (on|off)$").matchEntire(s)?.let { return sa(sw, it.groupValues[1]) }
            Regex("^toggle\\s+$x$").matchEntire(s)?.let { return sa(sw, "toggle") }
            Regex("^(?:is|check|what(?:'s| is)?)\\s+$x(?:\\s+(?:on|off|enabled|status))?$|^$x\\s+status$").matchEntire(s)?.let { return sa(sw, "status") }
            if (sw.id == "emblemfollow" && Regex("^make (?:the )?(?:emblem|logo) (?:follow|stay with) (?:the )?orb(?: size)?$").matches(s)) return sa(sw, "on")
            if (sw.id == "emblemfollow" && Regex("^(?:fix|lock|keep) (?:the )?(?:emblem|logo)(?: size)?(?: fixed)?$|^make (?:the )?(?:emblem|logo)(?: size)? fixed$").matches(s)) return sa(sw, "off")
        }
        return null
    }

    private fun kind(w: String) = if (w == "orb") "orb" else "emblem"
    private fun sa(sw: Sw, v: String) = VoiceParser.Action("psetting", "${sw.id}:$v")

    fun run(ctx: Context, action: String, value: String): CommandExecutor.Res {
        return try {
            when (action) {
                "psetting" -> setting(ctx, value)
                "pnumber" -> number(ctx, value)
                "ptool" -> tool(ctx, value)
                "pac" -> ac(ctx, value)
                "pvoice" -> voice(ctx, value)
                else -> CommandExecutor.Res(false, "I don't know that one.")
            }
        } catch (e: Exception) { CommandExecutor.Res(false, "That didn't work: ${e.message}") }
    }

    private fun setting(ctx: Context, v: String): CommandExecutor.Res {
        val id = v.substringBefore(':'); val how = v.substringAfter(':', "toggle")
        val sw = SWITCHES.firstOrNull { it.id == id } ?: return CommandExecutor.Res(false, "I don't know that option.")
        val now = sw.get(ctx)
        if (how == "status") return CommandExecutor.Res(true, msg(now, sw.label), label = sw.label)
        val want = when (how) { "on" -> true; "off" -> false; else -> !now }
        if (want == now) return CommandExecutor.Res(true, "${msg(now, sw.label)} It was already that way.", label = sw.label)
        val err = sw.set(ctx, want)
        return if (err != null) CommandExecutor.Res(false, err, label = sw.label) else CommandExecutor.Res(true, msg(want, sw.label), label = sw.label)
    }

    private fun number(ctx: Context, v: String): CommandExecutor.Res {
        val what = v.substringBefore(':'); val arg = v.substringAfter(':')
        val orb = what == "orb"
        val lo = if (orb) 50 else 30; val hi = if (orb) 200 else 100
        val cur = if (orb) Prefs.orbScale(ctx) else Prefs.emblemScale(ctx)
        val target = when {
            arg.startsWith("+") || arg.startsWith("-") -> cur + arg.toInt()
            else -> arg.toIntOrNull() ?: return CommandExecutor.Res(false, "Say a number, like ${if (orb) "orb size 150" else "emblem size 80"}.")
        }
        val n = target.coerceIn(lo, hi)
        if (orb) { Prefs.saveOrbScale(ctx, n); FloatService.applyOrbSize() } else { Prefs.saveEmblemScale(ctx, n); FloatService.applyEmblem() }
        val clipped = if (n != target) " That's the ${if (target < lo) "smallest" else "biggest"} it goes." else ""
        return CommandExecutor.Res(true, "${if (orb) "Orb" else "Emblem"} size is $n percent.$clipped", label = if (orb) "Orb size" else "Emblem size")
    }

    private fun tool(ctx: Context, id: String): CommandExecutor.Res {
        val t = TOOLS.firstOrNull { it.id == id } ?: return CommandExecutor.Res(false, "I don't know that tool.")
        val i = t.intent(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { ctx.startActivity(i); CommandExecutor.Res(true, "Opening ${t.label}.", label = t.label) }
        catch (e: Exception) { CommandExecutor.Res(false, "I couldn't open ${t.label}.") }
    }

    // ───────────── AC remote (IR blaster) ─────────────
    private val AC_WORD = Regex("\\b(?:ac|a c|a/c|air ?con(?:ditioner|ditioning)?)\\b")
    private val AC_TEMP = Regex("\\b(1[7-9]|2\\d|30)\\b(?:\\s*(?:degrees?|\u00b0|c\\b|celsius))?")

    /** "turn on the ac", "ac 24 degrees cool", "set ac to auto", "ac turbo", "make the ac warmer", "turn off the ac". */
    private fun acParse(s: String): VoiceParser.Action? {
        if (!AC_WORD.containsMatchIn(s)) return null
        val off = Regex("\\b(?:off|stop|shut)\\b").containsMatchIn(s)
        val on = Regex("\\b(?:on|start)\\b").containsMatchIn(s)
        if (off && !on) return VoiceParser.Action("pac", "off")
        val p = mutableListOf<String>()
        if (on) p += "on"
        AC_TEMP.find(s)?.let { p += "temp:${it.groupValues[1]}" }
        val fanWord = Regex("\\bfan\\b|\\bspeed\\b").containsMatchIn(s)
        when {
            Regex("\\bfan (?:mode|only)\\b").containsMatchIn(s) -> p += "mode:fan"
            Regex("\\bdry\\b").containsMatchIn(s) -> p += "mode:dry"
            Regex("\\bheat(?:ing)?\\b|\\bwarm mode\\b").containsMatchIn(s) -> p += "mode:heat"
            Regex("\\bcool(?:ing)?\\b").containsMatchIn(s) && !Regex("\\bcooler\\b").containsMatchIn(s) -> p += "mode:cool"
            Regex("\\bauto\\b").containsMatchIn(s) && !fanWord -> p += "mode:auto"
        }
        Regex("\\b(auto|low|medium|med|high)\\b(?: fan| speed)?").findAll(s).forEach { m ->
            val w = m.groupValues[1]
            if (w == "auto") { if (fanWord) p += "fan:auto" } else p += "fan:" + (if (w == "medium") "med" else w)
        }
        Regex("\\b(turbo|swing|clean|econo|eco|economy)\\b").find(s)?.let { p += "btn:" + (if (it.groupValues[1].startsWith("eco")) "econo" else it.groupValues[1]) }
        if (Regex("\\b(?:light|display)\\b").containsMatchIn(s) && !Regex("\\blighter\\b").containsMatchIn(s)) p += "btn:light"
        Regex("\\bsleep(?: mode)?(?: (on|off))?\\b").find(s)?.let { p += "sleep:" + (it.groupValues[1].ifEmpty { if (off) "off" else "on" }) }
        Regex("\\b(warmer|hotter|increase|raise|up)\\b").find(s)?.let { if (p.none { it.startsWith("temp:") }) p += "step:+" + (Regex("\\bby (\\d)\\b").find(s)?.groupValues?.get(1) ?: "1") }
        Regex("\\b(cooler|colder|decrease|lower|down|reduce)\\b").find(s)?.let { if (p.none { it.startsWith("temp:") }) p += "step:-" + (Regex("\\bby (\\d)\\b").find(s)?.groupValues?.get(1) ?: "1") }
        Regex("\\b(?:type|remote) (a|b|midea|coolix)\\b").find(s)?.let { p += "type:" + (if (it.groupValues[1] in listOf("a", "midea")) "midea" else "coolix") }
        if (p.isEmpty()) return null
        return VoiceParser.Action("pac", p.joinToString(";"))
    }

    private fun ac(ctx: Context, v: String): CommandExecutor.Res {
        if (!IrRemote.available(ctx)) return CommandExecutor.Res(false, "This phone has no infrared blaster, so I can't control an AC.", label = "AC")
        val sp = ctx.getSharedPreferences("pragon_ac", Context.MODE_PRIVATE)
        var on = sp.getBoolean("on", false); var mode = sp.getString("mode", "cool") ?: "cool"; var fan = sp.getString("fan", "auto") ?: "auto"
        var temp = sp.getInt("temp", 24); var sleep = sp.getBoolean("sleep", false); var prof = sp.getString("prof", "midea") ?: "midea"
        var powerGiven = false; var stateChange = false; val buttons = mutableListOf<String>(); val said = mutableListOf<String>()
        for (part in v.split(';').filter { it.isNotBlank() }) {
            val k = part.substringBefore(':'); val a = part.substringAfter(':', "")
            when (k) {
                "on" -> { on = true; powerGiven = true; stateChange = true }
                "off" -> { on = false; powerGiven = true; stateChange = true }
                "temp" -> { temp = (a.toIntOrNull() ?: temp).coerceIn(17, 30); stateChange = true; said += "$temp degrees" }
                "step" -> { temp = (temp + (a.toIntOrNull() ?: 1)).coerceIn(17, 30); stateChange = true; said += "$temp degrees" }
                "mode" -> { mode = a; stateChange = true; said += "$a mode" }
                "fan" -> { fan = a; stateChange = true; said += "fan $a" }
                "sleep" -> { sleep = a == "on"; stateChange = true; said += "sleep $a" }
                "type" -> { prof = a; stateChange = true }
                "btn" -> buttons += a
            }
        }
        if (stateChange && !powerGiven && !on) on = true
        sp.edit().putBoolean("on", on).putString("mode", mode).putString("fan", fan).putInt("temp", temp).putBoolean("sleep", sleep).putString("prof", prof).apply()
        if (stateChange) {
            val code = IrCodes.state(prof, on, mode, fan, temp, sleep) ?: return CommandExecutor.Res(false, "Unknown remote type.", label = "AC")
            IrRemote.transmit(ctx, IrCodes.pattern(prof, code))?.let { return CommandExecutor.Res(false, it, label = "AC") }
        }
        for (b in buttons) {
            val code = IrCodes.button(prof, b) ?: return CommandExecutor.Res(false, "This remote type has no $b button. Say AC remote type B to switch.", label = "AC")
            if (stateChange) try { Thread.sleep(400) } catch (_: Exception) { }
            IrRemote.transmit(ctx, IrCodes.pattern(prof, code))?.let { return CommandExecutor.Res(false, it, label = "AC") }
        }
        val head = if (!stateChange) "" else if (powerGiven && !on) "AC off." else if (powerGiven) "AC on" else "AC set"
        val tail = (said + buttons.map { "$it sent" }).joinToString(", ")
        val msg = when { head.isEmpty() -> "Sent: $tail."; tail.isEmpty() -> head.removeSuffix(".") + "."; head.endsWith(".") -> head; else -> "$head: $tail." }
        return CommandExecutor.Res(true, msg, label = "AC")
    }

    // ───────────── voice, language, AI engine ─────────────
    private class Lang(val names: List<String>, val tag: String, val reply: String)
    private val LANGS = listOf(
        Lang(listOf("english india", "indian english"), "en-IN", "English"), Lang(listOf("english us", "american english", "us english"), "en-US", "English"),
        Lang(listOf("english uk", "british english", "uk english"), "en-GB", "English"), Lang(listOf("english"), "en-IN", "English"),
        Lang(listOf("tamil"), "ta-IN", "Tamil"), Lang(listOf("telugu"), "te-IN", "Telugu"), Lang(listOf("hindi"), "hi-IN", "Hindi"),
        Lang(listOf("kannada"), "kn-IN", "Kannada"), Lang(listOf("malayalam"), "ml-IN", "Malayalam"), Lang(listOf("marathi"), "mr-IN", "Marathi"),
        Lang(listOf("bengali", "bangla"), "bn-IN", "Bengali"), Lang(listOf("gujarati"), "gu-IN", "Gujarati"), Lang(listOf("punjabi"), "pa-IN", "Punjabi"),
        Lang(listOf("urdu"), "ur-IN", "Urdu"), Lang(listOf("spanish"), "es-ES", "Spanish"), Lang(listOf("french"), "fr-FR", "French"),
        Lang(listOf("german"), "de-DE", "German"), Lang(listOf("italian"), "it-IT", "Italian"), Lang(listOf("portuguese"), "pt-BR", "Portuguese"),
        Lang(listOf("russian"), "ru-RU", "Russian"), Lang(listOf("arabic"), "ar", "Arabic"), Lang(listOf("turkish"), "tr-TR", "Turkish"),
        Lang(listOf("indonesian"), "id-ID", "Indonesian"), Lang(listOf("thai"), "th-TH", "Thai"), Lang(listOf("japanese"), "ja-JP", "Japanese"),
        Lang(listOf("korean"), "ko-KR", "Korean"), Lang(listOf("chinese", "mandarin"), "zh-CN", "Chinese"),
    )
    private val LANG_ALT = LANGS.flatMap { it.names }.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
    private fun lang(name: String) = LANGS.first { l -> l.names.any { it == name.trim() } }

    private fun voiceParse(s: String): VoiceParser.Action? {
        Regex("^(?:set|change|make|put|adjust)\\s+$THE(?:speaking |speech |voice |talking )?(speed|rate|pitch)\\s+(?:to|at)?\\s*(\\d(?:\\.\\d+)?|\\d{2,3}\\s*(?:percent|%))$").matchEntire(s)?.let { m ->
            val raw = m.groupValues[2]; val n = raw.replace(Regex("[^0-9.]"), "").toFloatOrNull() ?: return null
            val v = if (raw.contains("%") || raw.contains("percent") || n > 5f) n / 100f else n
            return VoiceParser.Action("pvoice", (if (m.groupValues[1] == "pitch") "pitch:" else "rate:") + v)
        }
        Regex("^(?:speak|talk) (faster|quicker|slower|more slowly)$|^(?:speed up|slow down) (?:your )?(?:voice|speech|talking)$").matchEntire(s)?.let { m ->
            val w = m.groupValues[1].ifEmpty { m.value }; return VoiceParser.Action("pvoice", "rate:" + if (w.contains("slow")) "-0.1" else "+0.1")
        }
        Regex("^(?:use |make |give me |switch to )?(?:a |your )?(deeper|lower|higher|squeakier) (?:voice|pitch)$").matchEntire(s)?.let { m ->
            return VoiceParser.Action("pvoice", "pitch:" + if (m.groupValues[1] in listOf("deeper", "lower")) "-0.05" else "+0.05")
        }
        Regex("^(?:set|change|switch)\\s+$THE(?:speech|listening|recognition|input) language\\s+(?:to\\s+)?($LANG_ALT)$|^(?:listen|understand me) in ($LANG_ALT)$|^i (?:speak|talk) ($LANG_ALT)$").matchEntire(s)?.let { m ->
            val n = m.groupValues.drop(1).first { it.isNotEmpty() }; return VoiceParser.Action("pvoice", "speechlang:${lang(n).tag}")
        }
        Regex("^(?:set|change|switch)\\s+$THE(?:reply|response|answer|speaking) language\\s+(?:to\\s+)?($LANG_ALT)$|^(?:reply|respond|answer) in ($LANG_ALT)$").matchEntire(s)?.let { m ->
            val n = m.groupValues.drop(1).first { it.isNotEmpty() }; return VoiceParser.Action("pvoice", "replylang:${lang(n).reply}")
        }
        Regex("^(?:talk|speak|chat) to me in ($LANG_ALT)$|^(?:speak|talk) in ($LANG_ALT)$").matchEntire(s)?.let { m ->
            val n = m.groupValues.drop(1).first { it.isNotEmpty() }; return VoiceParser.Action("pvoice", "bothlang:${lang(n).tag}|${lang(n).reply}")
        }
        if (Regex("^(?:reply|respond|answer) in (?:the )?language i speak$|^reply language (?:auto|automatic|follow me)$").matches(s)) return VoiceParser.Action("pvoice", "replylang:")
        if (Regex("^(?:use|set|reset) (?:the )?phone(?:'s)? language(?: for listening)?$|^speech language (?:auto|default)$").matches(s)) return VoiceParser.Action("pvoice", "speechlang:")
        Regex("^(?:use|switch to|change to|set)\\s+$THE(gemini|google|pragon online|online|ollama|ghost|openai|custom)(?: engine| ai| brain| server| model)?$|^(?:set|change|switch)\\s+$THE(?:ai )?engine\\s+(?:to\\s+)?(gemini|google|online|ollama|ghost|openai|custom)$").matchEntire(s)?.let { m ->
            val w = m.groupValues[1].ifEmpty { m.groupValues[2] }
            val e = when (w) { "ollama", "ghost" -> "ollama"; "openai", "custom" -> "openai"; else -> "gemini" }
            return VoiceParser.Action("pvoice", "engine:$e")
        }
        if (Regex("^(?:which|what) (?:ai )?engine(?: are you using| is (?:on|selected))?$|^engine status$|^(?:which|what) (?:ai )?(?:brain|engine) (?:are you|is)(?: using)?$").matches(s)) return VoiceParser.Action("pvoice", "engine:status")
        return null
    }

    private fun voice(ctx: Context, v: String): CommandExecutor.Res {
        val k = v.substringBefore(':'); val a = v.substringAfter(':', "")
        val r = fun(m: String) = CommandExecutor.Res(true, m, label = "Voice")
        return when (k) {
            "rate" -> {
                val n = (if (a.startsWith("+") || a.startsWith("-")) Prefs.ttsRate(ctx) + a.toFloat() else a.toFloat()).coerceIn(0.5f, 2.0f)
                Prefs.setTtsRate(ctx, n); Speaker.get(ctx).reload(); r("Speaking speed is ${"%.1f".format(n)}.")
            }
            "pitch" -> {
                val n = (if (a.startsWith("+") || a.startsWith("-")) Prefs.ttsPitch(ctx) + a.toFloat() else a.toFloat()).coerceIn(0.5f, 1.5f)
                Prefs.setTtsPitch(ctx, n); Speaker.get(ctx).reload(); r("Voice pitch is ${"%.2f".format(n)}.")
            }
            "speechlang" -> { Prefs.setSpeechLang(ctx, a); Speaker.get(ctx).reload(); r(if (a.isEmpty()) "I'll listen in your phone's language." else "I'll listen for $a. Restart the bubble if I still mishear you.") }
            "replylang" -> { Prefs.setReplyLang(ctx, a); r(if (a.isEmpty()) "I'll reply in the language you speak." else "I'll reply in $a.") }
            "bothlang" -> { Prefs.setSpeechLang(ctx, a.substringBefore('|')); Prefs.setReplyLang(ctx, a.substringAfter('|')); Speaker.get(ctx).reload(); r("Listening and replying in ${a.substringAfter('|')}.") }
            "engine" -> {
                if (a == "status") return r("The AI engine is ${Prefs.engineLabel(ctx).lowercase()}.")
                Prefs.setEngineOnly(ctx, a)
                val lock = if (Offline.locked(ctx)) " The always-offline lock is on, so the on-phone engine still answers until you turn it off." else ""
                r("AI engine set to ${when (a) { "ollama" -> "Ghost (Ollama)"; "openai" -> "Custom (OpenAI-compatible)"; else -> "Pragon online (Gemini)" }}.$lock")
            }
            else -> CommandExecutor.Res(false, "I don't know that voice option.")
        }
    }
}
