package xyz.tesser.sdk.java.error;

/**
 * One entry from Tesser's documented {@code errors[]} response envelope.
 *
 * <p>The wire format is snake_case; this type is camelCase. See
 * https://docs.tesser.xyz/overviews/errors.
 *
 * @param errorCode machine-readable code, e.g. {@code AUTH_INVALID}
 * @param errorMessage developer-facing message
 * @param uiMessage optional end-user-facing message; may be null
 */
public record TesserErrorDetail(String errorCode, String errorMessage, String uiMessage) {

    /** Convenience for the common case with no UI message. */
    public TesserErrorDetail(String errorCode, String errorMessage) {
        this(errorCode, errorMessage, null);
    }
}
