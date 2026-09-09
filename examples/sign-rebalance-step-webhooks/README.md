# `sign-rebalance-step-webhooks` example

End-to-end harness that exercises the full Tesser rebalance flow:

1. Authenticate via OAuth `client_credentials`.
2. Listen on a local webhook endpoint for `step.signature_requested` and the
   terminal `status=completed` step event.
3. Create a rebalance via `POST /v1/treasury/rebalances`.
4. Receive the signing event for *this run's* rebalance, re-read the step over an
   authenticated `GET /v1/treasury/rebalances/{id}`, sign that with
   `LocalSigner.signStep`, and POST the signature to
   `/v1/treasury/rebalances/{id}/steps/{stepId}/sign`.
5. Wait for a step event with `data.object.status == "completed"`, then confirm it
   with a GET and report `completed_at`.

> **Status:** this example is **compile-verified only**. It builds and fails
> with the expected message when environment variables are absent, but the full
> round trip has not been exercised — that needs Tesser staging credentials and
> a publicly reachable callback URL.
>
> Separately, webhook delivery from Tesser staging is currently **unreliable**.
> If you don't see events arrive within the configured timeout, switch to the
> polling variant in [`../sign-rebalance-step-polling`](../sign-rebalance-step-polling).
> Webhook signature verification is also still intentionally skipped pending
> the staging probe that captures the verification algorithm; do not run this
> against production until that lands.

## What the example does and does not trust

The listener is reachable by anyone who finds the tunnel URL, and signature
verification is not implemented, so **every incoming POST is treated as
untrusted**. The example uses webhook events only as a *trigger*:

- The `step.signature_requested` event must carry the `rebalance_id` returned by
  this run's `createRebalance`, so a forged or left-over event is skipped.
- The bytes that get signed — `unsigned_transaction` — always come back from an
  authenticated `GET`, never from the POST body. Signing the payload directly
  would let an unauthenticated caller choose the transaction this program signs
  with the enclave key and submits to the real API.
- The final "complete" line is likewise re-read from the API rather than printed
  off the event, and the read is retried until the API itself reports
  `status=completed` — an unchecked single read would happily print a stale
  status with `completed_at=null` and exit successfully.

Request bodies are capped at 64 KiB (`413` beyond that) and the event queue holds
256 events (`503` beyond that), so a POST flood against the tunnel cannot grow the
heap without limit.

---

## Prerequisites

Same baseline as [`examples/create-wallet`](../README.md): Java 17, the Gradle
wrapper (`./gradlew`), and Tesser staging credentials. Also:

- A tunnel tool to expose your local webhook listener to the public internet so Tesser can POST to it. Any of the following works:
  - **cloudflared** (recommended; no signup): `brew install cloudflared`. Run `cloudflared tunnel --url http://localhost:8787`. The command prints a public `https://....trycloudflare.com` URL.
  - **ngrok**: `brew install --cask ngrok`, then `ngrok http 8787`. Free tier rotates the URL on every run.
  - **localhost.run**: `ssh -R 80:localhost:8787 localhost.run`. No install required.
- A webhook subscription registered in the Tesser dashboard (Settings, then Webhooks) pointing at the tunnel URL, subscribed to the `step.*` events. The listener accepts POSTs on **any** path, so it does not matter whether your tunnel appends one.

## Setup

```bash
cp examples/sign-rebalance-step-webhooks/.env.example examples/sign-rebalance-step-webhooks/.env.local
$EDITOR examples/sign-rebalance-step-webhooks/.env.local
```

Fill in:

- API and signing-key vars (same shape as `examples/create-wallet/.env.example`).
- `WEBHOOK_PORT` if you want something other than `8787`.
- `FROM_ACCOUNT_ID` / `FROM_AMOUNT` / `FROM_CURRENCY` / `FROM_NETWORK` (the last three optional) for the source.
- `TO_ACCOUNT_ID` / `TO_CURRENCY` / `TO_NETWORK` (the last two optional) for the destination.

`WEBHOOK_PUBLIC_URL` and `WEBHOOK_SECRET` are in the template for reference but
are not read by the example: the listener accepts any path, and signature
verification is not implemented yet.

## Run

```bash
# In one terminal, run the tunnel:
cloudflared tunnel --url http://localhost:8787

# In another terminal:
set -a && source examples/sign-rebalance-step-webhooks/.env.local && set +a
./gradlew :examples:sign-rebalance-step-webhooks:run
```

## Expected output

```text
Signer ready for enclave=org_...
Webhook listener started on http://0.0.0.0:8787/ (any path)
Fetching access token from https://auth.tesser.xyz/oauth/token (audience=https://staging.tesser.xyz) ...
Creating rebalance: POST https://staging.tesser.xyz/v1/treasury/rebalances
Request payload: {"desired":{"from":{...},"to":{...}}}
Rebalance created: id=reb_...
Waiting for `step.signature_requested` event for rebalance reb_... ...
  webhook received: path=/ type=step.signature_requested id=evt_...
Signing step step_... for transfer reb_... (signWith=0x..., network=BASE_SEPOLIA) ...
Local signature produced (... chars)
Submitting signature: POST https://staging.tesser.xyz/v1/treasury/rebalances/reb_.../steps/step_.../sign
Step submitted. API response: {...}
Waiting for step step_... to reach `status=completed` ...
  webhook received: path=/ type=step.submitted id=evt_...
  (skipping webhook event — step step_... status=completed not satisfied; type=step.submitted id=evt_...)
  webhook received: path=/ type=step.completed id=evt_...
Rebalance complete. step.id=step_... status=completed completed_at=...
```

If you see `Step submitted.` and a 2xx response body, the round-trip succeeded
end-to-end.

## Notes on the port

The Kotlin original drives this flow with coroutines. The Java version differs
in the following places:

| Kotlin | Java |
|---|---|
| `Channel<JsonObject>(capacity = Channel.UNLIMITED)` | `LinkedBlockingQueue<JsonNode>` bounded to 256 — the Kotlin channel is fed by a private listener, this one by the public internet |
| `withTimeout(t) { events.receive() }` | `queue.poll(remaining, NANOSECONDS)` against a deadline computed once, with an explicit null-on-timeout branch — `poll` reports a timeout by returning null rather than throwing |
| `kotlinx.serialization` `JsonObject` | Jackson `JsonNode` |
| `runBlocking { signer.signStep(step) }` | `signer.signStep(step).join()` |

`com.sun.net.httpserver.HttpServer` is **not** a substitution — the Kotlin
example already uses that exact JDK class, so the server code ports verbatim.

Note that `join()` wraps failures in `CompletionException`; unwrap with
`getCause()` to reach the underlying `TesserError`.

## Troubleshooting

| Symptom | Diagnosis | Fix |
|---|---|---|
| `Missing required environment variable: ...` | `set -a && source ...` did not run, or the variable is uncommented | Re-source `.env.local`. Confirm the variable has a value. |
| `OAuth token exchange failed: 401` | Bad `API_CLIENT_ID` / `API_CLIENT_SECRET` | Re-copy from the Tesser dashboard. |
| `POST .../v1/treasury/rebalances failed: 422` | Bad `desired` block (missing fields, currency mismatch, etc.) | Inspect the API error message. Confirm `from` and `to` fields match Tesser's account/currency expectations. |
| `Timed out after PT1M waiting for webhook event: type=step.signature_requested` | Webhook is not registered with Tesser, or the tunnel URL changed | Re-check the dashboard subscription URL. Re-run the tunnel and update the registration. Or switch to the polling example. |
| Webhook arrives but is skipped | The event doesn't satisfy the predicate being awaited | The skip line prints the event `type` and `id`. The harness only acts on a `step.signature_requested` whose `rebalance_id` matches this run, then on a step event with `status=completed`. A `signature_requested` left over from an earlier run is skipped by design. |
| `Missing required field \`unsigned_transaction\` in: ...` | The step DTO field names changed on the server side | File an issue with the captured `GET /v1/treasury/rebalances/{id}` response. The expected step fields are `id`, `transfer_id`, `unsigned_transaction`. |
| `Timed out after PT30S waiting for step ... to carry an \`unsigned_transaction\`` | The webhook beat the read-your-writes window by more than 30s, or the step never got a transaction | Re-run. If it repeats, use the polling example and capture the rebalance GET responses. |
| `Timed out after PT30S waiting for step ... to report \`status=completed\`` | The `completed` event arrived but the authenticated GET never agreed within 30s | The step is probably fine — the example refuses to print success it cannot confirm. Check the rebalance in the dashboard, or re-read it with `GET /v1/treasury/rebalances/{id}`. |
| `POST .../sign failed: 422 ... bad signature` | Stamp wire format does not match what the server expects | Capture the unsigned transaction bytes and the produced signature; file an issue with both. The current implementation stamps the unsigned transaction hex string verbatim using the X-Stamp envelope. |
| `POST .../sign failed: 409 ... step already signed / expired` | Either a previous run already submitted, or the rebalance timed out | Create a fresh rebalance and try again. |
| `Address already in use` on startup | Something else holds `WEBHOOK_PORT` | Change `WEBHOOK_PORT`, or stop the other process. |
| `Could not resolve org.bouncycastle:...` | Network blocked Maven Central | Check VPN/proxy. Maven Central must be reachable for the SDK dependency. |
