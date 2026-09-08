package xyz.tesser.sdk.java.internal.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Replaces secret-bearing values with {@code ***} in structured log payloads. Used at every SDK log
 * site that emits a map.
 */
public final class Redact {

    private static final Pattern SECRET_KEY_PATTERN =
            Pattern.compile(
                    "(authorization|x-stamp|api[_-]?key|secret|token)", Pattern.CASE_INSENSITIVE);

    private Redact() {}

    /** Returns a copy with secret-keyed values masked. Key order is preserved. */
    public static Map<String, Object> redact(Map<String, Object> payload) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : payload.entrySet()) {
            boolean secret = SECRET_KEY_PATTERN.matcher(e.getKey()).find();
            out.put(e.getKey(), secret ? "***" : e.getValue());
        }
        return out;
    }
}
