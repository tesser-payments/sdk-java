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

It also records the JPMS module descriptor from
`sdk/src/main/java/module-info.java` as its first stanza. Dropping an `exports`
breaks every consumer of that package just as surely as deleting a class, so it
belongs in the same gate. `module-info.class` itself is skipped by the class walk
(it has `ACC_MODULE` set and is not loadable by `Class.forName`); the stanza is
rendered from `ModuleDescriptor.read` instead.

The stanza covers `requires` (with modifiers), `exports`, `opens`, and
`provides`. Qualified directives render their targets — `exports foo to bar`, not
just `exports foo` — because narrowing an unqualified export to a qualified one
breaks every consumer except the named module, and the two would otherwise be
indistinguishable. `uses` is deliberately excluded: it declares what this module
consumes, which is an implementation detail rather than part of the contract
offered to consumers.

Adding a class to `xyz.tesser.sdk.java.internal.*` produces no lockfile diff:
that package tree is excluded from the dump *and* unexported by the module
descriptor, which is what makes it genuinely internal rather than merely
undocumented.

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

**Before the first release**, work through
[One-time setup](#one-time-setup-before-the-first-real-release). The workflow is
committed but inert until the repository is configured, and one of those steps —
granting the workflow write access — fails *after* the artifact is already
permanently published if it is missed.

Every release after that:

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

The release workflow is committed but **inert** until all of the following are in
place. Work through them in order; each one is independently verifiable.

The dry run at the end is **partial coverage**, not a full rehearsal. It runs the
verify build, GPG signing, and publication to Maven Local. It does *not* run the
real-release validation step (branch, `confirm_version`, tag absence), the upload
to the Central Portal, the tag push, or the GitHub release — all four are gated on
`dry_run == false`. So a green dry run proves the build and signing work; it
cannot tell you whether steps 2 and 3 below are configured correctly.

Quick audit of where the repo stands:

```bash
gh api repos/tesser-payments/sdk-java/actions/permissions            # expect enabled: true
gh api repos/tesser-payments/sdk-java/actions/permissions/workflow   # expect "write"
gh api repos/tesser-payments/sdk-java/actions/secrets -q '.secrets[].name'
gh workflow list --repo tesser-payments/sdk-java                     # expect "Release" present
```

---

#### 1. The workflow must exist on `main`

`workflow_dispatch` is only offered for workflows present on the **default
branch**, so "Actions → Release → Run workflow" does not appear until
`.github/workflows/release.yml` is merged. Merging is safe: the workflow has no
`push` trigger, so landing the file publishes nothing.

If the button is missing after merging, confirm `main` really is the default
branch (`gh repo view --json defaultBranchRef`).

#### 2. Enable Actions and grant the workflow write access

**Settings → Actions → General.**

- **Actions permissions** — Actions must be enabled. On an org-owned repo this
  can also be restricted at the org level; if the Actions tab is missing
  entirely, that is where to look.
- **Workflow permissions** — set to **"Read and write permissions"**.

The second one is the step most likely to be missed, and it fails at the worst
possible moment. The release job pushes the `v<version>` tag and calls
`gh release create`; both need `contents: write`. `release.yml` declares
`permissions: contents: write` at workflow level, but that declaration interacts
with the repository default, and GitHub's own documentation is not explicit about
which wins: it says only that a restricted default "will apply to the relevant
repositories" and that org owners "can restrict write access for the
`GITHUB_TOKEN` at the repository level".

Rather than depend on resolving that, set the default to write. If the repository
default does cap the workflow declaration, a release with it left on read-only
uploads to Maven Central successfully and *then* `403`s on the tag push — after
the version is already permanent and cannot be re-published. Setting it to write
costs nothing and removes the question entirely.

Note that a dry run cannot reassure you here: it never reaches the tagging step.
If you want certainty before a real release, trigger a dry run and read the
`GITHUB_TOKEN Permissions` block that Actions prints in the job's "Set up job"
log — that shows the effective permissions the token was actually granted.

Check and fix from the CLI:

```bash
gh api repos/tesser-payments/sdk-java/actions/permissions/workflow
# {"default_workflow_permissions":"read", ...}   <- set this to write

gh api -X PUT repos/tesser-payments/sdk-java/actions/permissions/workflow \
  -f default_workflow_permissions=write \
  -F can_approve_pull_request_reviews=false
```

`can_approve_pull_request_reviews` stays `false`; nothing here needs it.

#### 3. Check for rules that block tags

If you later protect `main` or add rulesets, note that the release job pushes a
tag rather than a branch. Branch protection on `main` does not affect it, but a
**tag ruleset** matching `refs/tags/v*` will — a "restrict creations" rule blocks
the push unless `github-actions[bot]` is in the bypass list.

There are no rulesets on this repo today, so nothing to do unless you add some:

```bash
gh api repos/tesser-payments/sdk-java/rulesets -q '.[].name'
```

#### 4. Sonatype Central Portal account

The `xyz.tesser` namespace is already verified, and namespaces are per-group, so
`xyz.tesser:sdk-java` is covered by it. No new namespace work is needed.

#### 5. GPG key for Maven Central signing

```bash
gpg --full-generate-key                                    # RSA 4096; passphrase recommended
gpg --keyserver keys.openpgp.org --send-keys <key-id>      # publish public key
gpg --keyserver keyserver.ubuntu.com --send-keys <key-id>  # secondary keyserver
gpg --export-secret-keys --armor <key-id> | base64         # export for CI
```

Maven Central refuses artifacts signed with a key whose public half is not
discoverable on a public keyserver. Publishing to a keyserver can take a few
minutes to propagate, so do this before the first real release rather than
during it.

#### 6. Repository secrets

**Settings → Secrets and variables → Actions.** All four are required; the
workflow fails fast if `GPG_PRIVATE_KEY_B64` is missing, but the others surface
as an authentication error partway through the upload.

| Secret | What it is | Where to get it |
|---|---|---|
| `SONATYPE_CENTRAL_USERNAME` | Central Portal **user token** username | <https://central.sonatype.com> → Sign in → Account → *Generate User Token*. This is the Portal token, **not** the website password and not legacy OSSRH credentials. |
| `SONATYPE_CENTRAL_PASSWORD` | Password paired to that token | Generated together with the username; copy both at once — the portal shows the password only once. |
| `GPG_PRIVATE_KEY_B64` | Base64 of the ASCII-armored secret signing key | `gpg --export-secret-keys --armor <key-id> \| base64`. On Linux use `base64 -w0` (single line); macOS `base64` is single-line already. Base64 exists so the multi-line PEM survives GitHub's secret pipeline without CRLF/whitespace mangling that breaks Bouncy Castle's PGP parser. |
| `GPG_PASSPHRASE` | Passphrase of that GPG key | Whatever the key was created with. |

If another `xyz.tesser` artifact already publishes with the same Sonatype account
and GPG key, reuse those values — but recover them from wherever they were
originally stored (password manager / keychain), since GitHub does not let you
read a secret back after saving.

Better still, configure them once as **organization** secrets on
`tesser-payments`. One rotation point, no per-repo drift, and the workflow needs
no change because `secrets.X` resolves org secrets transparently. Two caveats:

- Org secrets have three visibility settings: **All repositories** (`all`),
  **Private repositories** (`private`), and **Selected repositories**
  (`selected`). This repo is private, so the first two both reach it; with
  `selected` you must add this repository to the list explicitly, or the
  workflow sees nothing.
- Secrets are never exposed to workflow runs triggered from a fork, so a release
  can only be cut from a branch in this repo.

Verify they landed (values are never readable, only names). Repository secrets
and organization secrets are separate endpoints, and the first does **not** show
the second — so if you took the org-secret route, the repo-scoped command
returning nothing is expected, not a problem:

```bash
gh api repos/tesser-payments/sdk-java/actions/secrets -q '.secrets[].name'
gh api repos/tesser-payments/sdk-java/actions/organization-secrets -q '.secrets[].name'
```

The second lists exactly the org secrets *this repository* can see, which is the
thing that actually matters — it already accounts for the visibility setting
above. Between them the four names must all appear.

Nothing else needs configuring. `GH_TOKEN` uses the built-in `github.token`, and
the `ORG_GRADLE_PROJECT_*` variables Gradle expects are constructed inside the
workflow from the secrets above — you never add those to GitHub yourself.

#### Setup checklist

| | Check | How to confirm |
|---|---|---|
| 1 | `release.yml` is on the default branch | "Release" appears under the Actions tab |
| 2 | Actions enabled, workflow permissions = **write** | `gh api repos/OWNER/REPO/actions/permissions/workflow` |
| 3 | No tag ruleset blocking `refs/tags/v*` | `gh api repos/OWNER/REPO/rulesets` |
| 4 | Sonatype user token generated | Portal → Account |
| 5 | GPG key published to a keyserver | `gpg --keyserver keys.openpgp.org --recv-keys <key-id>` from a clean machine |
| 6 | Four secrets present | `gh api repos/OWNER/REPO/actions/secrets` |
| 7 | Dry run green with a `.asc` per artifact (partial coverage — see above) | See below |

### Verifying with a dry run

Once steps 1–6 are done, run the workflow with `dry_run` checked. Expected:

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
| `403` on `git push origin vX.Y.Z` | Effective token permissions are read-only, **or** a tag ruleset restricting creations on `refs/tags/v*`, **or** an org/enterprise policy capping the token | Check all three: setup step 2 for the repo default, setup step 3 for the ruleset, then org settings. A ruleset rejects the push even when the token has `contents: write`. |
| `403` on `gh release create` | Effective token permissions are read-only | Setup step 2, then org/enterprise policy. Rulesets do not affect this call, so if the tag push succeeded and only this failed, it is the token. |
| The "Release" workflow is not listed under Actions | The file is not on the default branch, or Actions is disabled | Setup steps 1 and 2. |

### If a release fails midway

| Symptom | Recovery |
|---|---|
| The verify build fails (test / spotless / apiCheck) | Nothing was published. Fix on `main` and re-run the workflow. |
| Publish succeeded but tagging failed | See below — the artifact is permanent, so the tag must point at the commit that produced it. |
| Workflow seems hung | Check the Actions log. The Sonatype Central Portal API is occasionally slow during high traffic; uploads can stall but typically recover within roughly 10 minutes. |
| The Central Portal shows a staged-but-not-released bundle | Check the bundle status in the Portal UI; a stuck staging bundle can usually be dropped there. |

#### Recovering from a publish-succeeded-but-tagging-failed run

The artifact is on Maven Central and cannot be replaced, so the tag has to point
at the exact commit that produced it. **Do not** run a bare `git tag vX.Y.Z`:
that tags whatever `HEAD` happens to be, and if `main` has moved on since the
failed run, the tag will claim to be the released version while pointing at
source that was never published.

Take the SHA from the failed run:

```bash
gh run list --workflow Release --limit 5          # find the failed run
SHA=$(gh run view <run-id> --json headSha -q .headSha)

git fetch origin
git tag "vX.Y.Z" "$SHA"                           # tag the published commit, not HEAD
git push origin "vX.Y.Z"
gh release create "vX.Y.Z" --generate-notes       # the workflow's other missing step
```

Then fix the cause before the next release — usually workflow permissions
(setup step 2) or a tag ruleset (setup step 3). **Do not re-run the publish:** the
version cannot be overwritten, and the run would fail at the Portal anyway.
