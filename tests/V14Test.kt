import com.pragon.mobile.*

var failed = 0; var passed = 0
fun eq(said: String, endMark: Boolean, want: String) {
    val got = Typer.punctuate(said, endMark)
    if (got != want) { println("FAIL: '$said' (end=$endMark) -> '$got' (wanted '$want')"); failed++ } else passed++
}

fun main() {
    eq("hello how are you", true, "hello, how are you?")
    eq("what time is it", true, "what time is it?")
    eq("can you send me the file", true, "can you send me the file?")
    eq("i think i'll go home but i am tired", true, "I think I'll go home, but I am tired.")
    eq("do not forget the milk", true, "do not forget the milk.")
    eq("wow that is amazing", true, "wow that is amazing!")
    eq("meet me at five", true, "meet me at five.")
    eq("when you arrive call me", true, "when you arrive call me.")
    eq("how many people are coming", true, "how many people are coming?")
    eq("hello", true, "hello")                                   // one word: left alone
    eq("john at gmail dot com", true, "john at gmail dot com.")  // spoken words, no symbols: treated as a sentence
    eq("visit www.site.com now", true, "visit www.site.com now") // link: untouched
    eq("hello, how are you?", true, "hello, how are you?")       // already punctuated by voice
    eq("best pizza near me", false, "best pizza near me")        // one-line box: no full stop
    eq("i am fine", false, "I am fine")                          // capital I still fixed
    eq("hello how are you", false, "hello, how are you")
    println("passed=$passed failed=$failed")
    if (failed > 0) System.exit(1)
}
