import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Emits golden JSON produced by kotlinx.serialization for a corpus of hostile
 * strings. The Java SDK asserts Jackson reproduces these byte for byte.
 *
 * Regenerate with: ./gradlew :fixtures:run
 */
fun main() {
    val corpus = listOf(
        "plain" to "simple wallet",
        "empty" to "",
        "double-quote" to """he said "hi"""",
        "backslash" to """C:\path\to\file""",
        "forward-slash" to "a/b/c",
        "newline" to "line1\nline2",
        "carriage-return" to "line1\rline2",
        "tab" to "col1\tcol2",
        "backspace" to "a\bb",
        "form-feed" to "a\u000Cb",
        "null-char" to "a\u0000b",
        "control-0x1f" to "a\u001Fb",
        "del-0x7f" to "a\u007Fb",
        "emoji" to "wallet 💸🚀",
        "cjk" to "钱包 ウォレット 지갑",
        "rtl-mark" to "wallet \u200F reversed",
        "line-separator" to "a\u2028b",
        "paragraph-separator" to "a\u2029b",
        "astral" to "𝔘𝔫𝔦𝔠𝔬𝔡𝔢",
        "combining" to "e\u0301galite\u0301",
        "quote-backslash-mix" to """\"escaped\" \\ done""",
    ) +
        // Every C0 control character, individually. The single hand-picked
        // 0x1F case above caught a real divergence -- kotlinx writes the \u
        // escape with lowercase hex digits, Jackson with uppercase -- which is
        // exactly the kind of thing that must not be sampled. Each control char
        // either gets a short escape or a \u00xx escape whose hex case can
        // differ; only enumerating all 32 proves which is which.
        (0x00..0x1F).map { c -> "c0-%02x".format(c) to "a${c.toChar()}b" }

    val out = buildJsonObject {
        corpus.forEach { (name, value) ->
            put(name, buildJsonObject { put("walletName", value) }.toString())
        }
    }.toString()

    val target = File("../sdk/src/test/resources/fixtures/escaping.json")
    target.parentFile.mkdirs()
    target.writeText(out)
    println("Wrote ${target.absolutePath}")
}
