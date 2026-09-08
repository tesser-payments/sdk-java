# Contributing to the Tesser Java SDK

Public-facing user docs are in [README.md](./README.md). This file is for SDK
maintainers and contributors: building, testing, and releasing.

---

## Local development setup

**Java 17.** Verify with `java -version`. On macOS:

```bash
brew install openjdk@17
echo 'export PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc
```

Other tools that work: `sdkman install java 17.0.13-tem`, `asdf install java openjdk-17`, `mise use java@17`.

**Gradle wrapper.** No separate install needed; `./gradlew` is committed and
pins Gradle 9.5.0 via `gradle/wrapper/gradle-wrapper.properties`. The wrapper
was copied verbatim from `sdk-kotlin` so both repos build on the identical
Gradle version.

**Verify your setup:**

```bash
git clone https://github.com/tesser-payments/sdk-java.git
cd sdk-java
./gradlew :sdk:test
```

You should see `BUILD SUCCESSFUL` with all tests passing.

---

## Project layout

| Module | Purpose |
|---|---|
| `:sdk` | The published library (`xyz.tesser:sdk-java`). All production code lives here. |
| `:fixtures` | Kotlin, test-support only, **never published**. Generates the golden parity fixtures by running the real Kotlin SDK. See [Regenerating parity fixtures](#regenerating-parity-fixtures). |
| `:examples:create-wallet` | End-to-end harness exercising `signCreateWallet` against Tesser staging. |
| `:examples:sign-rebalance-step-webhooks` | Rebalance step signing driven by Tesser webhooks. Compile-verified only. |
| `:examples:sign-rebalance-step-polling` | The same flow, polling instead of webhooks. Compile-verified only. |

---

## Building

```sh
./gradlew :sdk:build spotlessCheck apiCheck
```

Use the `:sdk`-scoped form. A bare `./gradlew build` also builds `:fixtures`,
which resolves `xyz.tesser:sdk:0.0.4` from your local Maven repository — so on a
fresh clone it fails with an unresolved-dependency error that has nothing to do
with your change. Publish the Kotlin SDK locally first if you genuinely want a
full root build.

Run `clean` as its **own** invocation rather than prefixing it:

```sh
./gradlew clean
./gradlew :sdk:build spotlessCheck apiCheck
```

This build has `org.gradle.parallel=true`, and `./gradlew clean build` lets
`clean` delete build directories while other tasks are still reading them. It
usually works and intermittently fails with `Issue processing file: ...` from
Spotless, which is a race, not a formatting problem.

## Build, test, lint commands

```bash
./gradlew :sdk:build spotlessCheck apiCheck   # what CI runs
./gradlew :sdk:test                           # tests only
./gradlew spotlessCheck                       # lint (:sdk and all three examples)
./gradlew spotlessApply                       # auto-fix formatting
./gradlew :sdk:apiCheck                       # public-API lockfile check (sdk/api/sdk-java.api)
./gradlew :sdk:apiDump                        # regenerate the lockfile after intentional API changes
./gradlew :sdk:publishToMavenLocal            # dry-run a publish into ~/.m2/repository/xyz/tesser/sdk-java/
```

CI additionally compiles the three example modules. `:fixtures` is deliberately
excluded from CI — it needs the Kotlin SDK in `mavenLocal`, which CI has no
reason to build, and its output is committed.

### Conventions

- **Single shared JSON writer.** Every serialization site uses
  `xyz.tesser.sdk.java.internal.util.Json`. Never construct an ad-hoc
  `ObjectMapper`: the SDK's output must stay byte-identical to
  kotlinx.serialization's, and `Json` carries the `CharacterEscapes` that makes
  that true. Its `ObjectMapper` is private precisely so it cannot be
  reconfigured out from under the parity tests.
- **Wire-format constants are copied verbatim** from the Kotlin SDK —
  `ACTIVITY_TYPE_*`, `CURVE_*`, `TRANSACTION_TYPE_*`, derivation paths, wallet
  wire values. Never "improve" one; parity is the point.
- **No emojis in committed files.** Plain ASCII in source. Section signs (`§`)
  and arrow glyphs are avoided in source files for terminal portability.
- **`internal` packages.** Java has no `internal` keyword, so
  `xyz.tesser.sdk.java.internal.*` is technically public. It is documented as
  internal and excluded from the binary-compatibility lockfile; treat it as
  private and change it freely.

---

## Regenerating parity fixtures

`GoldenBodyParityTest` and `JsonEscapingParityTest` compare against golden output
generated from the Kotlin SDK. Regenerate after any change to the Kotlin SDK's
payload shape:

```sh
cd ~/code/sdk-kotlin && ./gradlew publishToMavenLocal
cd ~/code/sdk-java   && ./gradlew :fixtures:run
```

Commit the regenerated files under `sdk/src/test/resources/fixtures/`. CI does
not run `:fixtures` — it has no reason to build the Kotlin SDK.

The fixtures are pinned to the Kotlin SDK at `df0986e` (tag `v0.0.4`). If
`~/code/sdk-kotlin` HEAD moves off that tag, the generator silently starts
emitting golden output from the newer working tree, and the parity tests then
validate against a moving target. Confirm the pin before regenerating:

```sh
cd ~/code/sdk-kotlin && git rev-parse --short HEAD   # expect df0986e
```

Either check out `v0.0.4` for fixture generation, or deliberately re-pin and
regenerate every fixture in one commit. Do not mix.

---

## Public-API binary compatibility

`sdk/api/sdk-java.api` is a lockfile capturing every public and protected type
and member the SDK exposes, including generic signatures, sealed `permits`
clauses, and record accessors.

sdk-kotlin uses the Kotlin
[`binary-compatibility-validator`](https://github.com/Kotlin/binary-compatibility-validator)
for this. That plugin only registers its tasks for Kotlin compilations —
applied to a `java-library` project it contributes nothing at all — so the
lockfile here is produced by a small `apiDump`/`apiCheck` pair defined in
`sdk/build.gradle.kts`. The model is the same; only the implementation differs.

**CI runs `apiCheck` on every PR.** A diff against the committed lockfile fails
the build, which forces a deliberate decision when the public API changes.

### When you change the public API

1. **Regenerate the lockfile:**
   ```bash
   ./gradlew :sdk:apiDump
   ```
2. **Inspect the diff** in `sdk/api/sdk-java.api`. Confirm the change is intentional.
3. **Add a CHANGELOG entry** under `Unreleased`.
4. **Decide the SemVer impact:**
   - New public symbol added: minor bump.
   - Existing symbol changed or removed: major bump.
   - No public-API change (internal refactor): patch bump.

---

## CHANGELOG

Format: [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/).

Add entries under `## Unreleased` as work lands. Use the standard subsection
headings: `### Added`, `### Changed`, `### Fixed`, `### Removed`. Don't open new
release blocks until cutting a release; the release runbook does that.

---

## Releasing

Releases are manual. There is no push-triggered publish — merging to `main`
never releases anything.

1. Bump `version=` in `gradle.properties`.
2. In `CHANGELOG.md`, rename `Unreleased` to the new version and add the date.
3. Update the install snippets in `README.md` to the new version. Both the
   Gradle and Maven snippets carry the literal version string.
4. Run `./gradlew :sdk:apiDump` and commit `sdk/api/sdk-java.api` if it changed.
5. Merge all of it to `main` through the normal PR flow. Nothing publishes yet.
6. **Actions → Release → Run workflow**, on `main`, leaving `dry_run` checked.
   Confirm it goes green and that every artifact in the listing has a matching
   `.asc` signature.
7. Run it again on `main` with `dry_run` **unchecked** and `confirm_version` set
   to the version from step 1. This publishes to the Central Portal, pushes the
   `v<version>` tag, and creates the GitHub release.

`publishAndReleaseToMavenCentral` both uploads and releases the deployment —
there is no separate "close/release" click in the Portal UI. Artifacts usually
appear on Maven Central within about 30 minutes.

**A published version is permanent.** Maven Central does not allow deleting or
overwriting one. If you ship a mistake, the only remedy is to publish a new
version. That is why the workflow asks you to uncheck a box *and* type the
version.

### Why this differs from sdk-kotlin

The Kotlin SDK releases on push to `main`: its gate publishes whenever the
version in `gradle.properties` has no matching tag, and `workflow_dispatch` is
an always-dry-run. This repo inverts that — there is no `push` trigger at all,
and dispatch is the release path. Both repos still share the whole publishing
*mechanism* (the vanniktech configuration, the gate, GPG handling, artifact
layout); only the trigger differs.

That buys three things:

- **Releases cannot happen as a side effect of merging.** Under the push model,
  merging this very workflow would have published `0.0.1`, because no `v0.0.1`
  tag exists.
- **Publishing to Maven Central is irreversible**, so the trigger for it should
  be an explicit act rather than a consequence of an ordinary merge.
- **The dry run becomes genuinely useful.** Under the push model a dry run could
  only ever test the dry-run path. Here the same job runs both, so a green dry
  run exercises the exact job that will publish.

Three safeguards replace the protection the push model got from its tag gate:
`dry_run` defaults to true, so the default action of clicking "Run workflow"
cannot publish; a real release must run from `main`, since dispatch can target
any branch; and it must echo back the version in `gradle.properties`, which
catches releasing a stale checkout.

### One-time setup (before the first real release)

The release workflow is wired but inert until these are in place.

**1. Sonatype Central Portal account.** The `xyz.tesser` namespace is already
verified for the Kotlin SDK, and namespaces are per-group, so `xyz.tesser:sdk-java`
is covered by it. No new namespace work is needed.

**2. GPG key for Maven Central signing.**

```bash
gpg --full-generate-key                                    # RSA 4096; passphrase recommended
gpg --keyserver keys.openpgp.org --send-keys <key-id>      # publish public key
gpg --keyserver keyserver.ubuntu.com --send-keys <key-id>  # secondary keyserver
gpg --export-secret-keys --armor <key-id> | base64         # export for CI
```

Maven Central refuses artifacts signed with a key whose public half is not
discoverable on a public keyserver.

**3. GitHub repo secrets.** Settings → Secrets and variables → Actions:

| Secret | What it is | Where to get it |
|---|---|---|
| `SONATYPE_CENTRAL_USERNAME` | Central Portal **user token** username | <https://central.sonatype.com> → Sign in → Account → *Generate User Token*. This is the Portal token, **not** the website password and not legacy OSSRH credentials. |
| `SONATYPE_CENTRAL_PASSWORD` | Password paired to that token | Generated together with the username; copy both at once — the portal shows the password only once. |
| `GPG_PRIVATE_KEY_B64` | Base64 of the ASCII-armored secret signing key | `gpg --export-secret-keys --armor <key-id> \| base64`. On Linux use `base64 -w0` (single line); macOS `base64` is single-line already. Base64 exists so the multi-line PEM survives GitHub's secret pipeline without CRLF/whitespace mangling that breaks Bouncy Castle's PGP parser. |
| `GPG_PASSPHRASE` | Passphrase of that GPG key | Whatever the key was created with. |

If sdk-java uses the same Sonatype account and GPG key as sdk-kotlin, use the
same four values — but recover them from wherever sdk-kotlin's were sourced
(password manager / keychain), since GitHub does not let you read a secret back
after saving. Better: configure them once as **organization** secrets on
`tesser-payments` scoped to both repos. One rotation point, no per-repo drift;
the workflow needs no change, because `secrets.X` resolves org secrets
transparently.

Nothing else needs configuring. `GH_TOKEN` uses the built-in `github.token`, and
the workflow declares `permissions: contents: write` so it can push the tag and
create the release. The `ORG_GRADLE_PROJECT_*` variables are constructed inside
the workflow from the secrets — you never add those to GitHub yourself.

### Verifying with a dry run

The workflow must be on `main` before the "Run workflow" button appears —
`workflow_dispatch` is only offered for workflows present on the default branch.
Merging is safe: with no `push` trigger, merging the file does nothing.

Once merged and the secrets exist, run it with `dry_run` checked. Expected:

- `gate` reads the version and **skips** the validation step (dry runs are exempt).
- `publish` runs the full verify build, then `:sdk:publishToMavenLocal`, then
  lists artifacts under `~/.m2/repository/xyz/tesser/sdk-java` — jar, sources
  jar, javadoc jar, POM, module file, and a `.asc` signature for each.
- **No tag, no GitHub release, nothing reaches the Central Portal.**

If every artifact has a matching `.asc`, signing works end to end and the real
path differs only in the upload destination.

Failure modes worth recognising:

| Symptom | Cause | Fix |
|---|---|---|
| `Could not read PGP secret key` | The base64 was mangled | Re-export single-line and update the secret. |
| `GPG_PRIVATE_KEY_B64 secret is empty or unset` | Secrets not configured, or the run targeted a fork | Add the secrets to the repo (or the org). |
| `confirm_version (...) does not match gradle.properties` | Stale checkout, or a typo | Confirm what `main` actually has in `gradle.properties`. |
| `Tag vX.Y.Z already exists` | That version is already released | Bump `version=` in `gradle.properties` first. |

### If a release fails midway

| Symptom | Recovery |
|---|---|
| The verify build fails (test / spotless / apiCheck) | Nothing was published. Fix on `main` and re-run the workflow. |
| Publish succeeded but tagging failed | The artifact is on Maven Central but the tag was not created. Create it manually (`git tag vX.Y.Z && git push origin vX.Y.Z`), then bump to the next version for further work. Do not re-run the publish: the version cannot be overwritten. |
| Workflow seems hung | Check the Actions log. The Sonatype Central Portal API is occasionally slow during high traffic; uploads can stall but typically recover within roughly 10 minutes. |
| The Central Portal shows a staged-but-not-released bundle | Check the bundle status in the Portal UI; a stuck staging bundle can usually be dropped there. |
