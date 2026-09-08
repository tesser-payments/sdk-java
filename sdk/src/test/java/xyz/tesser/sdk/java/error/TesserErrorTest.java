package xyz.tesser.sdk.java.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TesserErrorTest {

    @Test
    void configErrorCarriesMessageAndCause() {
        IllegalArgumentException cause = new IllegalArgumentException("root");
        TesserError.ConfigError err = new TesserError.ConfigError("bad input", cause);
        assertThat(err).hasMessage("bad input").hasCause(cause).isInstanceOf(TesserError.class);
    }

    @Test
    void apiErrorExposesStatusHeadersRequestIdAndErrors() {
        TesserError.APIError err =
                new TesserError.APIError(
                        "401 Unauthorized",
                        401,
                        Map.of("request-id", List.of("req_abc")),
                        "req_abc",
                        List.of(new TesserErrorDetail("AUTH_INVALID", "bad token", null)),
                        null);
        assertThat(err.status()).isEqualTo(401);
        assertThat(err.requestId()).isEqualTo("req_abc");
        assertThat(err.headers().get("request-id")).first().isEqualTo("req_abc");
        assertThat(err.errors())
                .first()
                .extracting(TesserErrorDetail::errorCode)
                .isEqualTo("AUTH_INVALID");
    }

    @Test
    void apiErrorHeadersDefaultToEmpty() {
        TesserError.APIError err = new TesserError.APIError("no headers", 500);
        assertThat(err.headers()).isEmpty();
        assertThat(err.errors()).isEmpty();
        assertThat(err.errorCode()).isNull();
    }

    @Test
    void errorCodeReturnsTheFirstCode() {
        TesserError.APIError err =
                new TesserError.APIError(
                        "fail",
                        400,
                        Map.of(),
                        null,
                        List.of(
                                new TesserErrorDetail("FIRST", "x", null),
                                new TesserErrorDetail("SECOND", "y", null)),
                        null);
        assertThat(err.errorCode()).isEqualTo("FIRST");
    }

    @Test
    void hasCodeMatchesAnyEntry() {
        TesserError.APIError err =
                new TesserError.APIError(
                        "fail",
                        400,
                        Map.of(),
                        null,
                        List.of(
                                new TesserErrorDetail("A", "", null),
                                new TesserErrorDetail("B", "", null)),
                        null);
        assertThat(err.hasCode("B")).isTrue();
        assertThat(err.hasCode("X", "B")).isTrue();
        assertThat(err.hasCode("Y")).isFalse();
    }

    @Test
    void everyErrorIsUncheckedSoCallersAreNotForcedIntoThrowsClauses() {
        // Kotlin has no checked exceptions, so `TesserError : Exception` there is
        // effectively unchecked. Extending Exception in Java would change that.
        assertThat(RuntimeException.class).isAssignableFrom(TesserError.class);
    }

    @Test
    void hierarchyIsSealedToExactlyTheFiveKnownVariants() {
        // Java 17's pattern-matching switch is preview-only, so the Kotlin
        // "exhaustive when with no else" test cannot be transliterated. This is
        // stronger: it also fails if someone *adds* a variant.
        Set<String> permitted =
                java.util.Arrays.stream(TesserError.class.getPermittedSubclasses())
                        .map(Class::getSimpleName)
                        .collect(java.util.stream.Collectors.toSet());
        assertThat(permitted)
                .containsExactlyInAnyOrder(
                        "ConfigError",
                        "APIError",
                        "ConnectionError",
                        "TimeoutError",
                        "SigningError");
    }

    @Test
    void signingErrorIsATesserError() {
        assertThatThrownBy(
                        () -> {
                            throw new TesserError.SigningError("stamp blew up", null);
                        })
                .isInstanceOf(TesserError.SigningError.class)
                .isInstanceOf(TesserError.class);
    }

    @Test
    void detailUiMessageIsOptional() {
        assertThat(new TesserErrorDetail("C", "m", null).uiMessage()).isNull();
        assertThat(new TesserErrorDetail("C", "m", "friendly").uiMessage()).isEqualTo("friendly");
    }
}
