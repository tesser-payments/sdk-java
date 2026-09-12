# Tesser Java SDK examples

End-to-end reference programs that double as manual verification harnesses
against Tesser staging. If you can run an example and it succeeds, the SDK is
wired correctly on your machine.

| Example | Exercises | Status |
|---|---|---|
| [`create-wallet`](./create-wallet) | `LocalSigner.signCreateWallet` end-to-end against `POST /v1/accounts/wallets`. | Ported from the Kotlin SDK's equivalent. Builds and fails with the expected missing-env-var message; not exercised against the live API from this repo. |
| [`sign-rebalance-step-webhooks`](./sign-rebalance-step-webhooks) | The full rebalance signing flow driven by Tesser webhooks: create a rebalance, receive the `step.signature_requested` and terminal step events, sign with `LocalSigner.signStep`, submit to `POST /v1/treasury/rebalances/{transferId}/steps/{stepId}/sign`. | Compile-verified only. Webhook delivery from Tesser staging is also currently unreliable, and webhook signature verification is intentionally skipped pending a documented algorithm. |
| [`sign-rebalance-step-polling`](./sign-rebalance-step-polling) | Same rebalance signing flow as the webhooks variant but polls `GET /v1/treasury/rebalances/{id}` instead of waiting for webhook events. No tunnel, no webhook subscription. | Compile-verified only. Recommended over the webhook variant while webhook delivery is unreliable. |

Each example has its own README (or, for `create-wallet`, the walkthrough
below) covering env setup, expected output, and troubleshooting.

---

## `create-wallet`

Verifies `LocalSigner.signCreateWallet` against Tesser staging by performing the
full happy path:

1. OAuth `client_credentials` token exchange against the configured auth URL.
2. Build a `CreateWalletParams` and call `LocalSigner.signCreateWallet(...)` to
   produce the signed payload locally.
3. POST the signed payload to `${API_BASE_URL}/v1/accounts/wallets`.
4. Print the API response, or fail loudly if any step errors.

### Prerequisites

| | |
|---|---|
| **Java 17** | `java -version` should print 17 (or newer). On macOS: `brew install openjdk@17`. |
| **Gradle wrapper** | Already in this repo (`./gradlew`). No separate Gradle install required. |
| **Tesser staging credentials** | OAuth client ID + secret, obtained from the Tesser dashboard under Settings, then API Credentials. You also need the OAuth token URL (`AUTH_TOKEN_URL`); this is hosted on a separate auth subdomain from the API base. Ask Tesser support if you don't have it. |
| **Signing key** | Public key, private key, and enclave ID. Surfaced in the Tesser dashboard under Settings, then Signing Keys. |

### Step-by-step

**1. Verify Java 17 is on PATH.**

```bash
java -version
```

You should see `openjdk version "17.x.x"`. If you see `Unable to locate a Java
Runtime` (macOS's stub message) or a different version:

```bash
# macOS / Homebrew. Install once, then add to PATH:
brew install openjdk@17
export PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH"

# Make it permanent for future shells:
echo 'export PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH"' >> ~/.zshrc
```

Other tools that work: `sdkman install java 17.0.13-tem`, `asdf install java
openjdk-17`, `mise use java@17`.

**2. Copy the env template and fill in real values.**

```bash
cd /path/to/sdk-java
cp examples/create-wallet/.env.example examples/create-wallet/.env.local
$EDITOR examples/create-wallet/.env.local
```

`.env.local` is gitignored, so your secrets stay on your machine. Each variable
in the template has a comment explaining where to get the value and what format
it needs.

**3. Source the env file into your shell.**

```bash
set -a && source examples/create-wallet/.env.local && set +a
```

`set -a` exports all subsequent variable assignments automatically, so child
processes (Gradle, the JVM, the example) inherit them.

If you'd rather not source manually, alternatives that do the same thing:
- **direnv:** `direnv allow` in the example directory, with `.env.local` linked
  from `.envrc`.
- **dotenv-cli:** `dotenv -e examples/create-wallet/.env.local ./gradlew :examples:create-wallet:run`.
- **IntelliJ run config:** paste the contents of `.env.local` into the run
  configuration's "Environment variables" field.

The SDK itself does **not** bundle a `.env` loader. `Main.java` uses plain
`System.getenv()` and fails fast on missing variables.

**4. Run the example.**

```bash
./gradlew :examples:create-wallet:run --no-daemon
```

### Expected output

```text
Fetching access token from https://dev-awqy75wdabpsnsvu.us.auth0.com/oauth/token (audience=https://sandbox.tesserx.co) ...
Signing CreateWallet activity for type=STABLECOIN_ETHEREUM name=... ...
Submitting to https://sandbox.tesserx.co/v1/accounts/wallets ...
Wallet created. Response: {"wallet_id":"wal_...","address":"0x...", ...}
```

The exact response shape depends on the Tesser API version. The key signal is
that `Wallet created.` printed and the response contains a `wallet_id`.

### Troubleshooting

| Symptom | Diagnosis | Fix |
|---|---|---|
| `Unable to locate a Java Runtime` / `Please visit http://www.java.com` | Java 17 not on PATH (macOS stub `/usr/bin/java` showing) | `brew install openjdk@17 && export PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH"`. Add the export to `~/.zshrc` to persist. |
| `Missing required environment variable: ...` | Step 3 didn't run, or `.env.local` is missing the listed variable | From the repo root (same working directory as Step 3), re-run `set -a && source examples/create-wallet/.env.local && set +a`; check the variable is uncommented. |
| `OAuth token exchange failed: 401` | Bad `API_CLIENT_ID` or `API_CLIENT_SECRET` | Re-copy from the Tesser dashboard, Settings, then API Credentials. |
| `OAuth token exchange failed: 404 ... "/oauth/token"` | `AUTH_TOKEN_URL` is set to the API base URL. Tesser hosts OAuth on a separate auth host. | Set `AUTH_TOKEN_URL` to your environment's auth endpoint (for example, `https://dev-awqy75wdabpsnsvu.us.auth0.com/oauth/token`); confirm the exact URL with Tesser support. |
| `OAuth token exchange failed: 403 ... "No audience parameter was provided"` | You've explicitly overridden `API_AUDIENCE` to an empty string. The example defaults audience to `API_BASE_URL`. | Unset `API_AUDIENCE` (or set it to `$API_BASE_URL`) and re-source `.env.local`. |
| `OAuth response did not contain access_token` | Wrong `AUTH_TOKEN_URL` host or trailing slash | Verify the URL ends in `/oauth/token` exactly; check no trailing whitespace in `.env.local`. |
| `POST .../v1/accounts/wallets failed: 401` | Token authenticated but wrong audience or scope | Coordinate with Tesser support; the token may need a specific scope. |
| `POST .../v1/accounts/wallets failed: 400 ... bad signature` | Stamp wire-format mismatch (rare) | File an issue with `signed.metadata().body()` attached so the activity payload can be inspected. |
| `POST .../v1/accounts/wallets failed: 502 ... wallet label must be unique` | A wallet with that name already exists in your Tesser account | The example appends a millisecond timestamp to the wallet name, so this normally cannot recur. If it does, change the name in `Main.java` or delete the existing wallet from the dashboard. |
| `TesserError.SigningError: Signing failed: Hex string contains non-hex characters` | `SIGNING_PRIVATE_KEY` has whitespace or a `0x` prefix | Clean to bare 64 hex characters (no `0x`, no spaces). |
| `TesserError.SigningError: Signing failed: Hex string must have even length` | Key is the wrong length | Re-export. The key needs to be exactly 64 hex characters (32 bytes). |
| `... CURVE_ED25519 ...` rejected for Solana or Stellar | Account spec for Solana/Stellar isn't yet verified against the live API | File the exact API error so the spec can be adjusted. |
| `Could not resolve org.bouncycastle:...` | Network blocked Maven Central | Check your VPN/proxy. Maven Central must be reachable for the build. |
| Tests pass but example errors with `NoClassDefFoundError` | Stale build cache | `./gradlew clean :examples:create-wallet:run` |

### Cleanup

```bash
rm examples/create-wallet/.env.local   # if you don't need the secrets locally anymore
```

Wallets you create stay in your Tesser staging account. Delete them via the
dashboard if needed.
