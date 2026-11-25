package com.pragon.mobile

/**
 * Typer mode: while it is on, everything you say is typed into whatever text box is focused on the phone.
 * ("typer mode" ... "hello how are you question mark" ... "stop typing")
 *
 * No Android classes here on purpose (testable on a plain JVM). The actual typing is done by
 * PragonAccessibilityService.editField(), which calls [join] to merge the new words into the field.
 */
object Typer {

    private const val IDLE_MS = 10 * 60 * 1000L
    @Volatile private var until = 0L

    /** On, and heard something within the last 10 minutes (so it can never stay on forever by accident). */
    val active: Boolean get() = System.currentTimeMillis() < until
    fun start() { until = System.currentTimeMillis() + IDLE_MS; lastAdded = "" }
    fun touch() { if (active) until = System.currentTimeMillis() + IDLE_MS }
    fun stop() { until = 0L; lastAdded = "" }

    /** What the last dictation added to the field (including its leading space), so "delete that" can remove exactly it. */
    @Volatile var lastAdded: String = ""

    // ── start / stop phrases (matched on the normalised, wake-word-free sentence) ──

    private const val START_VERB = "(?:(?:start|begin|enable|turn on|switch to|go to|open|activate|enter|use) )?"
    private val START = Regex(
        "^$START_VERB(?:the )?(?:typer|dictation|voice typing|voice typer|speech to text)(?: mode)?(?: on| please)?$|" +
            "^$START_VERB(?:the )?(?:typing|type) mode(?: on| please)?$|" +
            "^(?:start|begin) (?:typing|dictating)(?: for me)?$|^type for me$"
    )
    private val STOP = Regex(
        "^(?:stop|end|exit|quit|disable|finish|leave|close|turn off|cancel)(?: the)? " +
            "(?:typer|typing|type|dictation|dictating|voice typing|voice typer)(?: mode)?(?: now| please)?$|" +
            "^(?:typer|typing|type|dictation|voice typing)(?: mode)? off$|" +
            "^(?:done|finished|im done|i am done) (?:typing|dictating)$|^(?:typing|dictation) (?:done|finished|over)$"
    )

    /** "typer mode", "start typing" ... -> true. */
    fun isStart(c: String): Boolean = START.matches(c)
    /** "stop typing", "typer mode off", "done typing" -> true. */
    fun isStop(c: String): Boolean = STOP.matches(c)

    // ── what a heard sentence means while typer mode is on ───────────────

    sealed class Step {
        data class Text(val piece: String) : Step()
        object DeleteLast : Step()          // "delete that", "scratch that", "undo"
        object DeleteWord : Step()          // "delete last word"
        object Backspace : Step()           // "backspace"
        object ClearAll : Step()            // "clear all"
        object SelectAll : Step()           // "select all"
        object Enter : Step()               // "send", "enter", "search"
        object Stop : Step()
        object Nothing : Step()
    }

    fun interpret(raw: String): Step {
        val n = VoiceParser.norm(raw)
        if (n.isEmpty()) return Step.Nothing
        val c = VoiceParser.clean(n)
        if (isStop(c) || isStop(n)) return Step.Stop
        when (c) {
            "delete that", "delete this", "scratch that", "undo", "undo that", "remove that", "delete last sentence", "delete the last sentence" -> return Step.DeleteLast
            "delete last word", "delete the last word", "remove last word", "delete word", "back one word" -> return Step.DeleteWord
            "backspace", "delete character", "delete last character", "delete one character" -> return Step.Backspace
            "clear all", "clear everything", "delete all", "delete everything", "clear text", "clear the text", "clear field", "erase all", "erase everything" -> return Step.ClearAll
            "select all", "select everything" -> return Step.SelectAll
            "enter", "press enter", "send", "send it", "send message", "send this", "submit", "search", "press send", "press search", "go ahead and send" -> return Step.Enter
        }
        val piece = dictate(raw)
        return if (piece.isBlank()) Step.Nothing else Step.Text(piece)
    }

    // ── spoken punctuation ───────────────────────────────────────────────

    private const val CLOSERS = ".,?!:;)"
    private enum class K { CLOSER, OPENER, JOINER, NEWLINE, PLAIN }
    private class Sym(val text: String, val kind: K)

    private val SYMBOLS: Map<String, Sym> = mapOf(
        "full stop" to Sym(".", K.CLOSER), "period" to Sym(".", K.CLOSER),
        "comma" to Sym(",", K.CLOSER), "question mark" to Sym("?", K.CLOSER),
        "exclamation mark" to Sym("!", K.CLOSER), "exclamation point" to Sym("!", K.CLOSER),
        "colon" to Sym(":", K.CLOSER), "semicolon" to Sym(";", K.CLOSER), "semi colon" to Sym(";", K.CLOSER),
        "close bracket" to Sym(")", K.CLOSER), "close quote" to Sym("\"", K.CLOSER), "end quote" to Sym("\"", K.CLOSER),
        "open bracket" to Sym("(", K.OPENER), "open quote" to Sym("\"", K.OPENER), "hashtag" to Sym("#", K.OPENER),
        "at the rate" to Sym("@", K.JOINER), "at sign" to Sym("@", K.JOINER), "hyphen" to Sym("-", K.JOINER),
        "underscore" to Sym("_", K.JOINER), "slash" to Sym("/", K.JOINER),
        "new paragraph" to Sym("\n\n", K.NEWLINE), "new line" to Sym("\n", K.NEWLINE), "next line" to Sym("\n", K.NEWLINE),
        "dash" to Sym("-", K.PLAIN), "ampersand" to Sym("&", K.PLAIN), "plus sign" to Sym("+", K.PLAIN), "equals sign" to Sym("=", K.PLAIN),
        "percent sign" to Sym("%", K.CLOSER),
    )
    private val DOMAIN_END = setOf("com", "in", "org", "net", "co", "io", "edu", "gov", "app", "dev")

    /**
     * Turns what was heard into text to type: spoken punctuation becomes symbols ("hello comma how are you question mark"
     * -> "hello, how are you?"), "new line" becomes a line break. Keeps the recognizer's own capitals.
     * Spacing/capitalisation against the text already in the box is [join]'s job.
     */
    fun dictate(raw: String): String {
        val words = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        fun low(k: Int) = words.getOrNull(k)?.lowercase()?.trim(',', '.', '?', '!', ':', ';') ?: ""
        val sb = StringBuilder()
        var glue = true                    // true = no space before the next word (start of text, after "(" "@" or a line break)
        fun trimEnd() { while (sb.isNotEmpty() && sb.last() == ' ') sb.setLength(sb.length - 1) }
        var i = 0
        while (i < words.size) {
            val three = if (i + 2 < words.size) low(i) + " " + low(i + 1) + " " + low(i + 2) else ""
            val two = if (i + 1 < words.size) low(i) + " " + low(i + 1) else ""
            val one = low(i)
            var sym = SYMBOLS[three]
            var used = 3
            if (sym == null) { sym = SYMBOLS[two]; used = 2 }
            if (sym == null) { sym = SYMBOLS[one]; used = 1 }
            if (sym == null && one == "dot" && low(i + 1) in DOMAIN_END) { sym = Sym(".", K.JOINER); used = 1 }
            if (sym != null) {
                when (sym.kind) {
                    K.CLOSER -> { trimEnd(); sb.append(sym.text); glue = false }
                    K.OPENER -> { if (!glue && sb.isNotEmpty()) sb.append(' '); sb.append(sym.text); glue = true }
                    K.JOINER -> { trimEnd(); sb.append(sym.text); glue = true }
                    K.NEWLINE -> { trimEnd(); sb.append(sym.text); glue = true }
                    K.PLAIN -> { if (!glue && sb.isNotEmpty()) sb.append(' '); sb.append(sym.text); glue = false }
                }
                i += used
                continue
            }
            if (!glue && sb.isNotEmpty()) sb.append(' ')
            sb.append(words[i])
            glue = false
            i++
        }
        // capital letter after . ? ! inside the same sentence group: "hello. how are you" -> "hello. How are you"
        val out = Regex("([.!?][ \n]+)([a-z])").replace(sb.toString()) { it.groupValues[1] + it.groupValues[2].uppercase() }
        return out.trim(' ')
    }


    // ── automatic punctuation (rule-based, English, runs on the phone, no model file) ─────────

    private val AUX = setOf(
        "is", "are", "am", "was", "were", "do", "does", "did", "can", "could", "will", "would",
        "should", "shall", "have", "has", "had", "may", "might", "isnt", "arent", "dont", "doesnt",
        "didnt", "wont", "cant", "couldnt", "wouldnt", "shouldnt"
    )
    private val SUBJECTS = setOf(
        "i", "you", "we", "they", "he", "she", "it", "this", "that", "there", "the", "a", "an", "my", "your",
        "anyone", "anybody", "everyone", "someone"
    )
    private val WH_STRONG = setOf("what", "whats", "why", "how", "who", "whos", "whom")
    private val WH_WEAK = setOf("when", "where", "which", "whose")
    private val WH_EXTRA = setOf("time", "about", "else", "many", "much", "long", "far", "old", "often", "kind", "type")
    private val OPENERS = setOf(
        "hi", "hello", "hey", "okay", "ok", "yes", "however", "anyway", "firstly", "secondly", "finally",
        "sorry", "actually", "meanwhile", "therefore", "unfortunately", "honestly", "basically"
    )
    private val EXCLAIM = Regex("^(wow|congratulations|congrats|happy birthday|thank you so much|great job|well done|good luck|hurray|yay|awesome|amazing)\\b.*")
    private val NO_FIX = Regex("[@/\\\\#_=+<>{}]|\\.(com|in|org|net|io|co)\\b|https?:|www\\.|\\d{5,}")
    private val I_FORM = Regex("\\bi('(?:m|ll|ve|d))?(?![\\w'])")

    /** "i" -> "I", "i'm" -> "I'm", "i'll" -> "I'll"; leaves everything else alone. */
    fun fixI(text: String): String = I_FORM.replace(text) { "I" + it.groupValues[1] }

    private fun bare(w: String) = w.lowercase().trim(',', '.', '?', '!', ':', ';', '"', '(', ')').replace("'", "")
    private fun endsPunct(w: String) = w.isNotEmpty() && w.last() in ".,?!:;)\""

    private fun isQuestion(words: List<String>): Boolean {
        if (words.isEmpty()) return false
        val a = bare(words[0])
        val b = if (words.size > 1) bare(words[1]) else ""
        return when {
            a in WH_STRONG -> b in AUX || b in SUBJECTS || b in WH_EXTRA
            a in WH_WEAK -> b in AUX || b in WH_EXTRA
            a in AUX -> b in SUBJECTS
            else -> false
        }
    }

    /**
     * Adds the punctuation a person would not say out loud: a full stop, question mark or exclamation mark at the end,
     * a comma after "hello", "however", "okay" and similar openers, a comma before a mid-sentence "but" or "however",
     * and a capital "I". [endMark] false = leave the end alone (single-line boxes such as search bars, where "cats." is wrong).
     * Skips anything that looks like an email, link or code, one-word pieces, and text you already punctuated by voice.
     */
    fun punctuate(piece: String, endMark: Boolean = true): String {
        val t0 = piece.trim()
        if (t0.isEmpty() || t0.contains('\n') || NO_FIX.containsMatchIn(t0)) return piece
        val words = fixI(t0).split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        if (words.size < 2) return words.joinToString(" ")
        // comma after an opener: "hello how are you" -> "hello, how are you"; "thank you for ..." -> "thank you, ..." is left alone
        var skip = 0
        if (words.size >= 3 && bare(words[0]) in OPENERS) { skip = 1; if (!endsPunct(words[0])) words[0] = words[0] + "," }
        // comma before "but" / "however" when at least three words come before it
        for (k in 3 until words.size - 1) {
            val w = bare(words[k])
            if ((w == "but" || w == "however") && !endsPunct(words[k - 1])) words[k - 1] = words[k - 1] + ","
        }
        var out = words.joinToString(" ")
        if (endMark && words.size >= 3 && !endsPunct(words.last())) {
            out += when {
                EXCLAIM.matches(out.lowercase()) -> "!"
                isQuestion(words.drop(skip)) -> "?"
                else -> "."
            }
        }
        return out
    }

    // ── joining into the field ───────────────────────────────────────────

    /** The field's text after adding [piece] to [existing] (spaces and capital letters sorted out). */
    fun join(existing: String, piece: String): String {
        if (piece.isEmpty()) return existing
        var p = piece
        val e = existing
        val atStart = e.isBlank()
        val afterSentence = !atStart && e.trimEnd(' ').let { it.endsWith(".") || it.endsWith("!") || it.endsWith("?") || it.endsWith("\n") }
        if ((atStart || afterSentence) && p.isNotEmpty() && p[0].isLetter()) p = p[0].uppercaseChar() + p.substring(1)
        val needSpace = when {
            atStart -> false
            e.last() == ' ' || e.last() == '\n' || e.last() == '(' || e.last() == '@' || e.last() == '/' || e.last() == '_' || e.last() == '-' -> false
            p[0] == '\n' -> false
            p[0] in CLOSERS || p[0] == '@' || p[0] == '/' || p[0] == '_' || p[0] == '-' -> false
            else -> true
        }
        return e + (if (needSpace) " " else "") + p
    }
}
