package xyz.tesser.sdk.java.error;

/**
 * One entry from Tesser's documented {@code errors[]} response envelope.
 *
 * <p>The wire format is snake_case; this type is camelCase. See
 * https://docs.tesser.xyz/overviews/errors.
 *
 * @param errorCode machine-readable code, e.g. {@code AUTH_INVALID}
 * @param errorMessage developer-facing message
 * @param uiMessage deprecated, see {@link #uiMessage()}; may be null
 */
public record TesserErrorDetail(
        String errorCode, String errorMessage, @Deprecated String uiMessage) {

    /** Convenience for the common case with no UI message. */
    public TesserErrorDetail(String errorCode, String errorMessage) {
        this(errorCode, errorMessage, null);
    }

    /**
     * Returns the {@code ui_message} from the error response, or null when it was not sent.
     *
     * @return the {@code ui_message}, or null
     * @deprecated {@code ui_message} is meant for Tesser's dashboard. The Tesser API will stop
     *     returning it to API-key requests on a date to be announced, and this will then return
     *     null. Use {@link #errorMessage()} instead, and {@link #errorCode()} for logic.
     */
    @Deprecated
    public String uiMessage() {
        return uiMessage;
    }
}
