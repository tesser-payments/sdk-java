import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import xyz.tesser.sdk.CreateWalletParams
import xyz.tesser.sdk.LocalSigner
import xyz.tesser.sdk.SigningConfig
import xyz.tesser.sdk.StepForSigning
import xyz.tesser.sdk.WalletType
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

    generateBodies()
}

/**
 * Emits the exact `metadata.body` the Kotlin SDK produces for a matrix of
 * inputs. timestampMs is normalised to a placeholder because the Kotlin SDK
 * inlines System.currentTimeMillis() and offers no seam to fix it.
 */
private fun generateBodies() = runBlocking {
    val cfg = SigningConfig(
        publicKey = "02".repeat(33),
        privateKey = "0000000000000000000000000000000000000000000000000000000000000001",
        enclaveId = "org_fixture",
    )
    val signer = LocalSigner(cfg)

    val walletCases = listOf(
        "eth-plain" to CreateWalletParams("wallet", WalletType.STABLECOIN_ETHEREUM),
        "sol-plain" to CreateWalletParams("wallet", WalletType.STABLECOIN_SOLANA),
        "xlm-plain" to CreateWalletParams("wallet", WalletType.STABLECOIN_STELLAR),
        "eth-quotes" to CreateWalletParams("""a "quoted" name""", WalletType.STABLECOIN_ETHEREUM),
        "eth-emoji" to CreateWalletParams("wallet 💸", WalletType.STABLECOIN_ETHEREUM),
        "eth-newline" to CreateWalletParams("line1\nline2", WalletType.STABLECOIN_ETHEREUM),
        "eth-empty" to CreateWalletParams("", WalletType.STABLECOIN_ETHEREUM),
    )

    val stepCases =
        listOf("BASE", "BASE_SEPOLIA", "ETHEREUM", "ETHEREUM_SEPOLIA", "POLYGON", "POLYGON_AMOY", "SOLANA")
            .map { net ->
                net to StepForSigning(
                    id = "step_abc",
                    transferId = "reb_123",
                    unsignedTransaction = "0x02ed81893a850165a0bc",
                    signWith = "0xb909cbe4a348754b17b474df9f12ab8842020165",
                    network = net,
                )
            }

    // Signed up front rather than inside the JSON builder: buildJsonObject's
    // builder lambda is not a coroutine body, so the suspend calls cannot run
    // there.
    val walletBodies =
        walletCases.map { (name, params) ->
            name to normalise(signer.signCreateWallet(params).metadata.body)
        }
    val stepBodies =
        stepCases.map { (name, step) ->
            name to normalise(signer.signStep(step).metadata.body)
        }

    val out = buildJsonObject {
        putJsonObject("wallets") {
            walletBodies.forEach { (name, body) -> put(name, body) }
        }
        putJsonObject("steps") {
            stepBodies.forEach { (name, body) -> put(name, body) }
        }
    }.toString()

    val target = File("../sdk/src/test/resources/fixtures/bodies.json")
    target.parentFile.mkdirs()
    target.writeText(out)
    println("Wrote ${target.absolutePath}")
}

/** Replaces the timestamp value so bodies are comparable across runs. */
private fun normalise(body: String): String =
    body.replace(Regex("\"timestampMs\":\"\\d+\""), "\"timestampMs\":\"<TS>\"")
