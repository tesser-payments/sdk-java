# `sign-rebalance-step-polling` example

End-to-end harness that exercises the Tesser rebalance flow using **polling**
instead of webhooks:

1. Authenticate via OAuth `client_credentials`.
2. Create a rebalance via `POST /v1/treasury/rebalances`.
3. Poll `GET /v1/treasury/rebalances/{id}` until the first step is in
   `signature_requested` status with a populated `unsigned_transaction`.
4. Sign the step locally with `LocalSigner.signStep`.
5. POST the signature to `/v1/treasury/rebalances/{id}/steps/{stepId}/sign`.
6. Poll until the step's `status` is `completed`.

> **Status:** this example is **compile-verified only**. It builds and fails
> with the expected message when environment variables are absent, but the full
> round trip has not been exercised; that needs Tesser staging credentials.
>
> Use this variant while Tesser webhook delivery is unreliable; it does not
> require a tunnel, public URL, or webhook subscription. The webhook variant
> lives in [`../sign-rebalance-step-webhooks`](../sign-rebalance-step-webhooks)
> and remains the preferred long-term shape once webhooks are dependable.

---

## Prerequisites

Same baseline as [`examples/create-wallet`](../README.md): Java 17, the Gradle
wrapper (`./gradlew`), and Tesser staging credentials. **No tunnel or webhook
subscription required.**

## Setup

```bash
cp examples/sign-rebalance-step-polling/.env.example examples/sign-rebalance-step-polling/.env.local
$EDITOR examples/sign-rebalance-step-polling/.env.local
```

Fill in:

- API and signing-key vars (same shape as `examples/create-wallet/.env.example`).
- `FROM_ACCOUNT_ID` / `FROM_AMOUNT` / `FROM_CURRENCY` / `FROM_NETWORK` (the last three optional) for the source.
- `TO_ACCOUNT_ID` / `TO_CURRENCY` / `TO_NETWORK` (the last two optional) for the destination.

## Run

```bash
set -a && source examples/sign-rebalance-step-polling/.env.local && set +a
./gradlew :examples:sign-rebalance-step-polling:run
```

## Expected output

```text
Signer ready for enclave=org_...
Fetching access token from https://dev-awqy75wdabpsnsvu.us.auth0.com/oauth/token (audience=https://sandbox.tesserx.co) ...
Creating rebalance: POST https://sandbox.tesserx.co/v1/treasury/rebalances
Request payload: {"desired":{"from":{...},"to":{...}}}
Rebalance created: id=reb_...
Polling rebalance reb_... until step is `signature_requested` ...
  step status=created (unsigned_transaction=null)
  step status=signature_requested (unsigned_transaction=present)
Signing step step_... for transfer reb_... (signWith=0x..., network=BASE_SEPOLIA) ...
Local signature produced (... chars)
Submitting signature: POST https://sandbox.tesserx.co/v1/treasury/rebalances/reb_.../steps/step_.../sign
Step submitted. API response: {...}
Polling rebalance until the step's status is `completed` ...
  step status=submitted completed_at=null failed_at=null
  step status=confirmed completed_at=null failed_at=null
  step status=completed completed_at=2026-... failed_at=null
Rebalance complete. step.id=step_... status=completed completed_at=2026-...
```

If you see `Rebalance complete.` with `status=completed`, the round-trip
succeeded end-to-end.

## Notes on the port

This is the webhook example's flow with the listener replaced by a polling loop.
Relative to the Kotlin original:

| Kotlin | Java |
|---|---|
| `delay(2.seconds)` | `Thread.sleep(...)`; this is a plain blocking program with no coroutine scope |
| `withTimeout(2.minutes) { … }` | a deadline computed once with `System.nanoTime()`, checked before each sleep |
| `kotlinx.serialization` `JsonObject` | Jackson `JsonNode` |
| `runBlocking { signer.signStep(step) }` | `signer.signStep(step).join()` |
| `jsonPrimitive.contentOrNull` | `node.path(field).asText(null)`; returns null for both a missing field and an explicit JSON `null`, which is what the `failed_at` check depends on |

Note that `join()` wraps failures in `CompletionException`; unwrap with
`getCause()` to reach the underlying `TesserError`.

## Troubleshooting

| Symptom | Diagnosis | Fix |
|---|---|---|
| `WARNING: step ... is \`confirmed\` ... but did not reach \`completed\` within PT5M` | The signed transaction was broadcast and mined (see `transaction_hash`). Tesser marks a step `completed` only once its block is finalized on the chain, and on Base Sepolia finality trails the head by roughly 15-20 minutes -- longer than the default wait. Signing and submission worked. | Nothing to fix. Re-run with `COMPLETION_WAIT_MINUTES=30` to wait for finality, or `GET /v1/treasury/rebalances/{id}` later. If the step is still `confirmed` well after its block finalized, report the rebalance id to Tesser. |
| `Missing required environment variable: ...` | `set -a && source ...` did not run, or the variable is uncommented | Re-source `.env.local`. Confirm the variable has a value. |
| `OAuth token exchange failed: 401` | Bad `API_CLIENT_ID` / `API_CLIENT_SECRET` | Re-copy from the Tesser dashboard. |
| `POST .../v1/treasury/rebalances failed: 422` | Bad rebalance request (missing fields, currency mismatch, unsupported network, etc.) | Inspect the API error message. Confirm `from`/`to` fields match Tesser's account/currency expectations. |
| `Timed out after PT2M waiting for step to reach \`signature_requested\`` | Tesser hasn't transitioned the step yet (provider issue, balance issue, network outage) | Inspect the rebalance via the Tesser dashboard. Common causes: source account has no balance, network is paused, or step prep is stuck. |
| `Step ... failed_at=...` thrown during polling | Tesser marked the step failed before signing or before finalization | The error includes `status_reasons` from the API; surface that to Tesser support. |
| `Account ... has no crypto_wallet_address` | Source `FROM_ACCOUNT_ID` is not a wallet-backed account | Use a different account. The example only signs transactions for crypto wallets. |
| `POST .../sign failed: 422 ... bad signature` | Stamp wire format does not match what the server expects | Capture the unsigned transaction bytes and the produced signature; file an issue with both. |
| `POST .../sign failed: 409 ... step already signed / expired` | A previous run already submitted, or the rebalance timed out | Create a fresh rebalance and try again. |
| `Timed out after PT5M waiting for step ... to reach \`completed\`` | Step is stuck post-submission (chain congestion, RPC outage, indexer lag) | Check the Tesser dashboard for the step's actual status. Increase the timeout in `Main.java::pollUntilStepCompleted` if needed. |
| `Could not resolve org.bouncycastle:...` | Network blocked Maven Central | Check VPN/proxy. Maven Central must be reachable for the SDK dependency. |
