# Security Policy

We take the security of ShipKit and the developers who deploy it seriously.
ShipKit moves shipping labels and card payments secured by Lifted Payments
3-D Secure, so responsible disclosure matters.

## Supported versions

ShipKit follows semantic versioning. Security fixes land on the latest `1.x`
release line.

| Version | Supported          |
| ------- | ------------------ |
| 1.x     | :white_check_mark: |
| < 1.0   | :x:                |

## Reporting a vulnerability

**Please report vulnerabilities privately. Do not open a public issue, pull
request, or Discussion for a security report.**

Email **support@liftedholdings.com** with the subject line `ShipKit Security`.
Where possible, include:

- A description of the vulnerability and its impact.
- Steps to reproduce, or a minimal proof of concept.
- Affected version, commit, or deployment mode (self-host or managed).
- Any suggested remediation.

If you prefer encrypted communication, mention it in your first email and we
will arrange a secure channel.

### What to expect

- **Acknowledgement** within 3 business days.
- An initial assessment and severity triage within 7 business days.
- Regular updates as we work toward a fix.
- Credit in the release notes once the issue is resolved, if you would like it.

We ask that you give us a reasonable window to remediate before any public
disclosure. We will coordinate a disclosure timeline with you.

## Never post secrets

When filing any issue, PR, log, or report — security or otherwise — **never
include real secrets**: API keys, bearer tokens, merchant IDs, database
credentials, private IP addresses, or `.env` contents. ShipKit loads every
credential from the environment (see [`.env.example`](.env.example)); no secret
belongs in the repository, an issue, or a screenshot.

If a secret is exposed accidentally:

1. **Rotate it immediately** at the provider (EasyPost, Lifted Payments, etc.).
2. Email **support@liftedholdings.com** so we can help assess exposure.
3. Do not rely on rewriting Git history alone — treat any committed secret as
   compromised.

## Scope

In scope: the ShipKit backend, the drop-in JS widget, the demo, and the
documented deployment paths in this repository.

Out of scope: third-party services (EasyPost, carrier APIs, the Lifted Payments
processor) — report those to their respective vendors — and denial-of-service
testing against any hosted or managed endpoint.

Thank you for helping keep ShipKit and its users safe.
