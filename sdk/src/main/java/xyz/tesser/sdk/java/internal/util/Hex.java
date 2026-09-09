package xyz.tesser.sdk.java.internal.util;

/**
 * ASCII-only hex codec for key material and signature bytes.
 *
 * <p>Internal. Extracted so the stamper and the key-pair check parse hex the same way — a private
 * key that one accepts and the other rejects would be worse than either behaviour alone.
 */
public final class Hex {

    private static final char[] LOWER = "0123456789abcdef".toCharArray();

    private Hex() {}

    /**
     * Decodes {@code hex} to bytes.
     *
     * @throws IllegalArgumentException if the length is odd or any character is not {@code
     *     [0-9a-fA-F]}
     */
    public static byte[] decode(String hex) {
        if (hex.length() % 2 != 0) {
            throw new IllegalArgumentException("Hex string must have even length");
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) ((digit(hex.charAt(i * 2)) << 4) | digit(hex.charAt(i * 2 + 1)));
        }
        return out;
    }

    /** Encodes {@code bytes} as lowercase hex. */
    public static String encode(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xff;
            out[i * 2] = LOWER[v >>> 4];
            out[i * 2 + 1] = LOWER[v & 0x0f];
        }
        return new String(out);
    }

    /**
     * ASCII-only hex digit, deliberately not {@link Character#digit}.
     *
     * <p>{@code Character.digit} accepts Unicode digits and letters from other blocks: it returns 5
     * for U+0665 (Arabic-Indic five) and 10 for U+FF21 (fullwidth A). The Kotlin SDK validates with
     * {@code it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F'}, so it rejects those. Using
     * Character.digit here would make the Java SDK silently accept a key the Kotlin SDK refuses and
     * sign with a different scalar than the caller intended — precisely the wrong-signature failure
     * this SDK is built to avoid.
     */
    private static int digit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        throw new IllegalArgumentException("Hex string contains non-hex characters");
    }
}
