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
