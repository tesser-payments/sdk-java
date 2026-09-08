package xyz.tesser.sdk.java.error;

import java.util.List;
import java.util.Map;

/**
 * Sealed root of every SDK error.
 *
 * <p>Extends {@link RuntimeException}, not {@link Exception}, deliberately. The
 * Kotlin source declares {@code TesserError : Exception}, but Kotlin has no
 * checked exceptions, so that is effectively unchecked. Transliterating the
 * literal supertype into Java would force {@code throws} clauses through every
 * caller and a try/catch inside every lambda handed to a CompletableFuture.
 *
 * <p>The signer throws {@link ConfigError} for bad caller input and
 * {@link SigningError} for cryptographic failures. {@link APIError},
 * {@link ConnectionError} and {@link TimeoutError} are part of the public
 * surface so future HTTP-issuing operations can throw them without a breaking
 * change.
 */
public abstract sealed class TesserError extends RuntimeException
        permits TesserError.ConfigError,
                TesserError.APIError,
                TesserError.ConnectionError,
                TesserError.TimeoutError,
                TesserError.SigningError {

    protected TesserError(String message, Throwable cause) {
        super(message, cause);
    }

    /** Bad input at construction or call site. SDK-side validation, not Tesser API. */
    public static final class ConfigError extends TesserError {
        public ConfigError(String message) {
            this(message, null);
        }

        public ConfigError(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Network or transport failure (DNS, connection reset, IOException). */
    public static final class ConnectionError extends TesserError {
        public ConnectionError(String message) {
            this(message, null);
        }

        public ConnectionError(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Request exceeded the configured timeout. */
    public static final class TimeoutError extends TesserError {
        public TimeoutError(String message) {
            this(message, null);
        }

        public TimeoutError(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Local signing failure. Wraps cryptographic stamper errors. */
    public static final class SigningError extends TesserError {
        public SigningError(String message) {
            this(message, null);
        }

        public SigningError(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Non-2xx HTTP from Tesser, carrying the parsed {@code errors[]} envelope.
     * See https://docs.tesser.xyz/overviews/errors.
     */
    public static final class APIError extends TesserError {
        private final int status;
        private final Map<String, List<String>> headers;
        private final String requestId;
        private final List<TesserErrorDetail> errors;

        public APIError(String message, int status) {
            this(message, status, Map.of(), null, List.of(), null);
        }

        public APIError(
                String message,
                int status,
                Map<String, List<String>> headers,
                String requestId,
                List<TesserErrorDetail> errors,
                Throwable cause) {
            super(message, cause);
            this.status = status;
            this.headers = headers == null ? Map.of() : Map.copyOf(headers);
            this.requestId = requestId;
            this.errors = errors == null ? List.of() : List.copyOf(errors);
        }

        public int status() {
            return status;
        }

        /** Unmodifiable. */
        public Map<String, List<String>> headers() {
            return headers;
        }

        /** May be null. */
        public String requestId() {
            return requestId;
        }

        /** Unmodifiable; never null. */
        public List<TesserErrorDetail> errors() {
            return errors;
        }

        /** The first {@code errorCode}, or null when {@link #errors()} is empty. */
        public String errorCode() {
            return errors.isEmpty() ? null : errors.get(0).errorCode();
        }

        /** True if any entry's {@code errorCode} matches one of {@code codes}. */
        public boolean hasCode(String... codes) {
            List<String> wanted = List.of(codes);
            return errors.stream().anyMatch(d -> wanted.contains(d.errorCode()));
        }
    }
}
