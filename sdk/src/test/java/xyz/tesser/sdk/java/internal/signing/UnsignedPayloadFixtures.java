package xyz.tesser.sdk.java.internal.signing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import xyz.tesser.sdk.java.internal.util.Json;

public final class UnsignedPayloadFixtures {

    private static final String ETHEREUM_RESPONSE =
            "/fixtures/unsigned-payload/create-wallet-accounts-ethereum.json";

    private UnsignedPayloadFixtures() {}

    public static String loadEthereumResponse() {
        try (InputStream stream =
                UnsignedPayloadFixtures.class.getResourceAsStream(ETHEREUM_RESPONSE)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String loadEthereumBody() {
        return Json.readTree(loadEthereumResponse()).path("data").path("body").asText();
    }

    public static String organizationId() {
        return Json.readTree(loadEthereumBody()).path("organizationId").asText();
    }

    public static String walletName() {
        JsonNode account = Json.readTree(loadEthereumBody()).path("parameters").path("accounts");
        return account.path(0).path("name").asText();
    }

    public static String responseWithBody(String body) {
        ObjectNode response = Json.newObject();
        ObjectNode data = response.putObject("data");
        data.put("organization_id", organizationId());
        data.put("body", body);
        return Json.write(response);
    }
}
