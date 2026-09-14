# Security Policy

## Reporting a vulnerability

**Do not open a public issue for a security report.**

Use GitHub's private vulnerability reporting on this repository:
**Security → Advisories → Report a vulnerability**
(<https://github.com/tesser-payments/sdk-java/security/advisories/new>).

That channel is private to you and the maintainers, and it gives us a place to
coordinate a fix and a CVE before anything is public.

Please include, as far as you can:

- the SDK version (`xyz.tesser:sdk-java:<version>`) and JDK version;
- what an attacker gains: signature forgery, key disclosure, wrong-transaction
  signing, denial of service;
- a reproduction, ideally a failing test against this repository.

We aim to acknowledge a report within 3 working days and to give an initial
assessment within 10. If you have not heard back within that window, please
re-ping on the same advisory thread rather than opening a public issue.

Please give us a reasonable window to ship a fix before disclosing publicly. We
will credit you in the advisory unless you ask us not to.

## Supported versions

The SDK is pre-1.0. Only the latest published version receives security fixes;
there are no maintenance branches for older releases. Upgrade to the newest
version before reporting.

| Version | Supported |
| --- | --- |
| latest release | yes |
| anything older | no, upgrade first |

## Scope

In scope, in roughly descending order of severity:

- anything that causes a signature to be produced over bytes the caller did not
  intend, or with a key the caller did not intend;
- private-key disclosure through the SDK's own surface: logging, `toString`,
  exception messages, serialization;
- a divergence from the Kotlin SDK's wire format that would make a payload
  signed by this SDK be interpreted differently by the server;
- dependency vulnerabilities that are actually reachable from SDK code paths.

Out of scope:

- the `examples/` directory. The examples are teaching material, run against
  staging, and say so; the webhooks example in particular starts an
  unauthenticated public listener on purpose and documents it. Report example
  problems as ordinary issues.
- vulnerabilities in the Tesser API itself rather than this client. Those go to
  Tesser support, not here.
- reports that consist only of a scanner's output with no reachable code path.

## What this SDK does and does not protect

The SDK signs payloads with a P-256 private key held in process memory. It does
not, and cannot:

- **erase key material.** `SigningConfig.privateKey` is a `String`: immutable,
  possibly interned, and present in heap dumps for as long as the config is
  reachable. `toString()` masks it, but any component holding a `SigningConfig`
  should be treated as holding key material.
- **authenticate anything.** It performs no HTTP and holds no API credentials;
  transport security and webhook verification are the caller's responsibility.
- **validate transaction semantics.** It signs the `unsignedTransaction` it is
  given. Deciding that those bytes are the transaction you meant to send is the
  caller's job; see the note in the webhooks example about never signing bytes
  that arrived over an unauthenticated channel.
