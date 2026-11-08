package xyz.tesser.sdk.java.internal.signing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import xyz.tesser.sdk.java.error.TesserError;

class TesserApiConnectionTest {

    @ParameterizedTest
    @CsvSource({
        "https://api.tesser.xyz,             https://api.tesser.xyz",
        "https://api.tesser.xyz/,            https://api.tesser.xyz",
        "https://api-sandbox.tesser.xyz/v1,  https://api-sandbox.tesser.xyz",
        "https://api-sandbox.tesser.xyz/v1/, https://api-sandbox.tesser.xyz",
        "'  https://api.tesser.xyz/v1  ',    https://api.tesser.xyz"
    })
    void apiBaseUrlDropsSurroundingSpaceATrailingSlashAndAV1Segment(
            String apiBaseUrl, String expected) {
        assertThat(TesserApiConnection.normalizeApiBaseUrl(apiBaseUrl)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"api.tesser.xyz", "", "https://", "ftp://api.tesser.xyz"})
    void apiBaseUrlWithoutAnHttpsSchemeAndHostIsRejected(String apiBaseUrl) {
        assertThatThrownBy(() -> TesserApiConnection.normalizeApiBaseUrl(apiBaseUrl))
                .isInstanceOf(TesserError.ConfigError.class)
                .hasMessageContaining("apiBaseUrl");
    }

    @Test
    void apiBaseUrlOverPlainHttpIsRejectedUnlessTheHostIsLoopback() {
        assertThatThrownBy(() -> TesserApiConnection.normalizeApiBaseUrl("http://api.tesser.xyz"))
                .isInstanceOf(TesserError.ConfigError.class);
        assertThat(TesserApiConnection.normalizeApiBaseUrl("http://localhost:3001/v1"))
                .isEqualTo("http://localhost:3001");
        assertThat(TesserApiConnection.normalizeApiBaseUrl("http://127.0.0.1:3001"))
                .isEqualTo("http://127.0.0.1:3001");
        assertThat(TesserApiConnection.normalizeApiBaseUrl("http://[::1]:3001"))
                .isEqualTo("http://[::1]:3001");
    }
}
