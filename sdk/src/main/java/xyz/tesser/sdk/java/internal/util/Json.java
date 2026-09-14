package xyz.tesser.sdk.java.internal.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.io.CharacterEscapes;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import xyz.tesser.sdk.java.error.TesserError;

/**
 * The single JSON writer for the SDK.
 *
 * <p>Only writing is needed: the SDK never deserializes anything. Output must be byte-identical to
 * kotlinx.serialization's, because the signature is base64 of this exact text; see
 * JsonEscapingParityTest.
 *
 * <p>ObjectNode preserves insertion order (it is LinkedHashMap-backed), and Jackson writes compact
 * by default, which together match {@code buildJsonObject { ... }.toString()}.
 */
public final class Json {

    /**
     * Private, not public.
     *
     * <p>An exposed {@code ObjectMapper} is mutable: any consumer calling {@code
     * MAPPER.configure(...)} would silently change escaping for every signature produced in the
     * process: the exact failure this SDK's test suite exists to prevent, and one no test can
     * catch, because the mutation happens in consumer code. Keeping it private makes that
     * unreachable rather than merely discouraged.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static {
        MAPPER.getFactory().setCharacterEscapes(new LowercaseUnicodeEscapes());
    }

    private Json() {}

    /**
     * Makes Jackson's {@code \\uXXXX} escapes use lowercase hex digits, as kotlinx.serialization
     * does.
     *
     * <p>This is not cosmetic. Jackson writes U+001F as {@code \\u001F} and kotlinx writes {@code
     * \\u001f}; the bytes differ, so a wallet name containing any control character above U+0009
     * would be signed over different text by the two SDKs and produce a different signature.
     * JsonEscapingParityTest caught exactly this, which is why it is the first gate in the build.
     *
     * <p>Only the C0 range needs handling: the five characters with short escapes ({@code \\b \\t
     * \\n \\f \\r}) already agree, everything from U+0020 up is written raw by both writers
     * (including DEL, U+2028 and U+2029), and {@code "} and {@code \\} agree. U+0000-U+0009 have no
     * letter digits, so they are unaffected either way; they are included rather than special-cased
     * because "escape the whole range uniformly" is easier to verify than a hand-pruned list.
     */
    private static final class LowercaseUnicodeEscapes extends CharacterEscapes {

        private static final long serialVersionUID = 1L;

        /** Indexed by codepoint; null for characters this class does not touch. */
        private static final SerializableString[] ESCAPES = new SerializedString[0x20];

        private static final int[] ASCII_ESCAPE_CODES;

        static {
            int[] codes = CharacterEscapes.standardAsciiEscapesForJSON();
            for (int c = 0x00; c < 0x20; c++) {
                if (c == '\b' || c == '\t' || c == '\n' || c == '\f' || c == '\r') {
                    continue; // short escapes; Jackson and kotlinx already agree
                }
                codes[c] = CharacterEscapes.ESCAPE_CUSTOM;
                ESCAPES[c] = new SerializedString(String.format("\\u%04x", c));
            }
            ASCII_ESCAPE_CODES = codes;
        }

        @Override
        public int[] getEscapeCodesForAscii() {
            // Cloned because the array is handed to Jackson's generator, and a
            // shared mutable int[] is the same footgun as a shared ObjectMapper.
            return ASCII_ESCAPE_CODES.clone();
        }

        @Override
        public SerializableString getEscapeSequence(int ch) {
            return (ch >= 0 && ch < ESCAPES.length) ? ESCAPES[ch] : null;
        }
    }

    /** A new, empty object node. */
    public static ObjectNode newObject() {
        return MAPPER.createObjectNode();
    }

    /** Serializes {@code node} to compact JSON. */
    public static String write(ObjectNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            // Unreachable for ObjectNode trees built from strings, but the SDK
            // must never leak a raw Jackson type through its own error surface.
            throw new TesserError.SigningError("Failed to serialize activity JSON", e);
        }
    }

    /**
     * Parses {@code json}. Used only by tests and fixtures; the SDK itself never deserializes
     * anything.
     */
    public static JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Not valid JSON", e);
        }
    }
}
