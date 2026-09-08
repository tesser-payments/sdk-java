package xyz.tesser.sdk.java.internal.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RedactTest {

    @Test
    void redactsSecretBearingKeysCaseInsensitively() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("Authorization", "Bearer abc");
        payload.put("X-Stamp", "stampvalue");
        payload.put("api_key", "k");
        payload.put("API-KEY", "k");
        payload.put("apikey", "k");
        payload.put("secret", "s");
        payload.put("token", "t");
        payload.put("enclaveId", "org_1");

        Map<String, Object> redacted = Redact.redact(payload);

        assertThat(redacted)
                .containsEntry("Authorization", "***")
                .containsEntry("X-Stamp", "***")
                .containsEntry("api_key", "***")
                .containsEntry("API-KEY", "***")
                .containsEntry("apikey", "***")
                .containsEntry("secret", "***")
                .containsEntry("token", "***")
                .containsEntry("enclaveId", "org_1");
    }

    @Test
    void preservesKeyOrderAndNonSecretValues() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("a", 1);
        payload.put("b", null);
        assertThat(Redact.redact(payload)).containsExactly(entry("a", 1), entry("b", null));
    }
}
