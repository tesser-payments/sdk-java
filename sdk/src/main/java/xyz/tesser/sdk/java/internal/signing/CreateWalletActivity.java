package xyz.tesser.sdk.java.internal.signing;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import xyz.tesser.sdk.java.CreateWalletParams;
import xyz.tesser.sdk.java.SignedResult;
import xyz.tesser.sdk.java.SignedResultMetadata;
import xyz.tesser.sdk.java.SigningConfig;
import xyz.tesser.sdk.java.error.TesserError;
import xyz.tesser.sdk.java.internal.util.Json;

/**
 * Builds an {@code ACTIVITY_TYPE_CREATE_WALLET} payload, stamps it, and returns
 * the composite {@code base64({body, stamp})} signature Tesser's API consumes.
 *
 * <p>Internal. Public callers go through {@link xyz.tesser.sdk.java.LocalSigner}.
 */
public final class CreateWalletActivity {

    private CreateWalletActivity() {}

    /**
     * @param stamp injected for testability; production passes {@link Stamp#create()}
     * @param clock injected for deterministic bodies in tests; production passes
     *     {@code System::currentTimeMillis}
     */
    public static CompletableFuture<SignedResult> sign(
            SigningConfig signing, CreateWalletParams params, Stamp stamp, LongSupplier clock) {
        try {
            List<AccountSpec> accounts = WalletTypeAccounts.forType(params.type());
            if (accounts == null) {
                throw new TesserError.ConfigError(
                        "No account spec registered for " + params.type());
            }

            ObjectNode body = Json.newObject();
            body.put("type", "ACTIVITY_TYPE_CREATE_WALLET");
            body.put("timestampMs", Long.toString(clock.getAsLong()));
            body.put("organizationId", signing.enclaveId());

            ObjectNode parameters = body.putObject("parameters");
            parameters.put("walletName", params.name());
            ArrayNode accountsNode = parameters.putArray("accounts");
            for (AccountSpec spec : accounts) {
                ObjectNode a = accountsNode.addObject();
                a.put("curve", spec.curve());
                a.put("pathFormat", spec.pathFormat());
                a.put("path", spec.path());
                a.put("addressFormat", spec.addressFormat());
            }

            String bodyJson = Json.write(body);

            return stamp.stamp(signing, bodyJson)
                    .thenApply(
                            stamped -> {
                                ObjectNode composite = Json.newObject();
                                composite.put("body", bodyJson);
                                composite.put("stamp", stamped.stampHeaderValue());

                                String signature =
                                        Base64.getEncoder()
                                                .encodeToString(
                                                        Json.write(composite)
                                                                .getBytes(StandardCharsets.UTF_8));

                                return new SignedResult(
                                        signature,
                                        new SignedResultMetadata(
                                                stamped.stampHeaderName(),
                                                stamped.stampHeaderValue(),
                                                bodyJson));
                            });
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
