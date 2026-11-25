package com.pragon.mobile

/**
 * v7.5 phrases for the on-phone vision tools (Vision.kt). No Android classes here, so it can be tested on a plain JVM.
 *
 * Action / value ("camera" = take a photo first, "screen" = use a screenshot):
 *   ocr_read       "OCR", "scan the text", "read the text in this picture", "scan the text with the camera"      default screen
 *   vision_label   "label this picture", "label this with the camera"                                           default screen
 *   vision_cut     "cut out the subject", "remove the background", "make a sticker", "... with the camera"      default screen
 *   vision_detect  "what objects do you see", "detect objects", "find the objects on the screen"                 default camera
 *   vision_scene   "what do you see", "describe the scene", "describe what you see"                              default camera
 * Screen use needs "Let Pragon read my screen" (Settings > Security) and the accessibility service; camera use needs the camera permission.
 */
object VisionVoice {

    private val I = RegexOption.IGNORE_CASE
    private val LEAD = Regex("^(?:(?:hey|hi|hello|ok|okay|please|yo)\\s+)+", I)
    private val LEAD_P = Regex("^(?:pragon|paragon|pragan|pragun)\\b[,]?\\s*", I)
    private val TRAIL = Regex("(?:\\s+(?:please|now|for me))+$", I)
    /** "... with the camera" at the end, or "camera ..." at the start. */
    private val CAM_END = Regex("\\s+(?:with|using|from|through|by|via)\\s+(?:the\\s+|my\\s+|your\\s+)?(?:phone\\s+)?camera$", I)
    private val CAM_START = Regex("^(?:camera|photo)\\s+", I)
    private val SCREEN_WORD = Regex("\\b(?:screen|page|display|screenshot)\\b", I)

    private fun tidy(raw: String): String {
        var s = raw.trim().replace("’", "'").replace(Regex("\\s+"), " ")
        s = LEAD.replaceFirst(s, ""); s = LEAD_P.replaceFirst(s, ""); s = TRAIL.replace(s, "")
        return s.trim().trimEnd('.', '!', '?', ',').trim()
    }

    /** Optional "on the screen" / "in this picture" tail. */
    private const val WHERE = "(?:\\s+(?:on|in|from|of)\\s+(?:the\\s+|my\\s+|this\\s+|that\\s+)?(?:screen|page|display|picture|image|photo|pic|screenshot))?"
    private const val THING = "(?:the\\s+|this\\s+|that\\s+|my\\s+)?(?:image|picture|photo|pic|screen|screenshot)"

    private val OCR = listOf(
        Regex("^(?:(?:run|do|use)\\s+)?ocr$WHERE\\$", I),
        Regex("^(?:scan|extract|grab|copy|get)\\s+(?:all\\s+)?(?:the\\s+)?(?:text|words|writing)$WHERE\\$", I),
        Regex("^read\\s+(?:out\\s+)?(?:all\\s+)?(?:the\\s+)?(?:text|words|writing)\\s+(?:in|on|from|of)\\s+(?:the\\s+|my\\s+|this\\s+|that\\s+)?(?:picture|image|photo|pic|screenshot)(?:\\s+(?:out loud|aloud))?\\$", I),
        Regex("^read\\s+(?:the\\s+|my\\s+|this\\s+)?(?:screen|page|text)\\s+(?:with|using|by)\\s+ocr\\$", I),
        Regex("^read\\s+(?:this|that|the\\s+(?:text|writing|sign|label|page))\\$", I),   // only reached with "with the camera"
    )

    private val LABEL = listOf(
        Regex("^(?:label|tag)\\s+$THING\\$", I),
        Regex("^(?:label|tag)\\s+(?:this|that|it)\\$", I),
        Regex("^(?:detect|find)\\s+(?:the\\s+)?labels$WHERE\\$", I),
    )

    private val CUT = listOf(
        Regex("^(?:cut\\s*out|extract|isolate|lift)\\s+(?:the\\s+|this\\s+|that\\s+)?(?:main\\s+)?(?:subject|object|person|foreground|thing)$WHERE\\$", I),
        Regex("^(?:remove|delete|erase|cut)\\s+(?:the\\s+)?background$WHERE\\$", I),
        Regex("^make\\s+(?:a\\s+)?sticker(?:\\s+(?:from|of)\\s+(?:the\\s+|this\\s+|that\\s+)?(?:image|picture|photo|pic|screen|subject))?\\$", I),
    )

    private val DETECT = listOf(
        Regex("^(?:what|which)\\s+objects?\\s+(?:do\\s+you\\s+see|can\\s+you\\s+see|are\\s+(?:there\\s+)?(?:in\\s+front\\s+of\\s+(?:me|you)|around|visible))$WHERE\\$", I),
        Regex("^(?:what|which)\\s+objects?\\s+(?:are\\s+|is\\s+)?(?:there\\s+)?(?:visible\\s+)?(?:on|in)\\s+(?:the\\s+|my\\s+|this\\s+)?(?:screen|page|display|picture|image|photo|pic)\\$", I),
        Regex("^(?:list|name|detect|find|spot|show|identify)\\s+(?:me\\s+)?(?:all\\s+)?(?:the\\s+)?objects$WHERE\\$", I),
        Regex("^object\\s+detection\\$", I),
    )

    private val SCENE = listOf(
        Regex("^(?:what\\s+(?:do|can)\\s+you|tell\\s+me\\s+what\\s+you(?:\\s+can)?)\\s+see\\$", I),
        Regex("^describe\\s+what\\s+you\\s+see\\$", I),
        Regex("^describe\\s+(?:the\\s+|this\\s+|my\\s+)?(?:scene|view|surroundings|room|place)$WHERE\\$", I),
        Regex("^what(?:'s| is)\\s+in\\s+front\\s+of\\s+(?:me|you)\\$", I),
        Regex("^scene\\s+description\\$", I),
    )

    fun parse(raw: String): VoiceParser.Action? {
        var s = tidy(raw)
        if (s.isEmpty()) return null
        val cam = CAM_END.containsMatchIn(s) || CAM_START.containsMatchIn(s)
        s = CAM_START.replaceFirst(CAM_END.replace(s, ""), "").trim()
        val src = if (cam) "camera" else "screen"
        // "read this" and friends are only the camera when the person said so; otherwise the normal screen reader handles them
        if (OCR.take(4).any { it.matches(s) } || (cam && OCR[4].matches(s))) return VoiceParser.Action("ocr_read", src)
        if (LABEL.any { it.matches(s) }) return VoiceParser.Action("vision_label", src)
        if (CUT.any { it.matches(s) }) return VoiceParser.Action("vision_cut", src)
        if (DETECT.any { it.matches(s) }) return VoiceParser.Action("vision_detect", if (!cam && SCREEN_WORD.containsMatchIn(s)) "screen" else "camera")
        if (SCENE.any { it.matches(s) }) return VoiceParser.Action("vision_scene", if (!cam && SCREEN_WORD.containsMatchIn(s)) "screen" else "camera")
        return null
    }
}
