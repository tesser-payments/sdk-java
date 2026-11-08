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
import xyz.tesser.sdk.java.WalletType;
import xyz.tesser.sdk.java.error.TesserError;
import xyz.tesser.sdk.java.internal.util.Json;

/**
 * Resolves the wallet-creation activity body, stamps it, and returns the composite {@code
 * base64({body, stamp})} signature Tesser's API consumes.
 *
 * <p>Internal. Public callers go through {@link xyz.tesser.sdk.java.LocalSigner}.
 */
public final class CreateWalletActivity {

    private CreateWalletActivity() {}

    /**
     * @param stamp injected for testability; production passes {@link Stamp#create()}
     * @param clock injected for deterministic bodies in tests; production passes {@code
     *     System::currentTimeMillis}
     */
    public static CompletableFuture<SignedResult> sign(
            SigningConfig signing,
            CreateWalletParams params,
            Stamp stamp,
            LongSupplier clock,
            TesserApiConnection api) {
        try {
            return resolveCreateWalletBody(signing, params, stamp, clock, api)
                    .thenCompose(body -> stampCreateWalletBody(signing, body, stamp));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static CompletableFuture<String> resolveCreateWalletBody(
            SigningConfig signing,
            CreateWalletParams params,
            Stamp stamp,
            LongSupplier clock,
            TesserApiConnection api) {
        if (params.type() == WalletType.STABLECOIN_ETHEREUM) {
            return UnsignedPayload.fetchCheckedCreateWalletAccountsBody(
                    api, signing, params, stamp, clock);
        }
        return CompletableFuture.completedFuture(buildCreateWalletBody(signing, params, clock));
    }

    private static String buildCreateWalletBody(
            SigningConfig signing, CreateWalletParams params, LongSupplier clock) {
        List<AccountSpec> accounts = WalletTypeAccounts.forType(params.type());
        if (accounts == null) {
            throw new TesserError.ConfigError("No account spec registered for " + params.type());
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

        return Json.write(body);
    }

    private static CompletableFuture<SignedResult> stampCreateWalletBody(
            SigningConfig signing, String bodyJson, Stamp stamp) {
        return stamp.stamp(signing, bodyJson)
                .thenApply(stamped -> buildSignedResult(bodyJson, stamped));
    }

    private static SignedResult buildSignedResult(String bodyJson, StampResult stamped) {
        ObjectNode composite = Json.newObject();
        composite.put("body", bodyJson);
        composite.put("stamp", stamped.stampHeaderValue());

        String signature =
                Base64.getEncoder()
                        .encodeToString(Json.write(composite).getBytes(StandardCharsets.UTF_8));

        return new SignedResult(
                signature,
                new SignedResultMetadata(
                        stamped.stampHeaderName(), stamped.stampHeaderValue(), bodyJson));
    }
}
