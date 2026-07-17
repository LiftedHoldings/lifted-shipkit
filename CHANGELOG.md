# Changelog

All notable changes to Lifted ShipKit are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **Managed-deployment hooks** — two optional, env-driven features for operators
  hosting ShipKit instances from a control plane; both OFF by default:
  - **Remote markup config**: `POST /api/config/markup` now also accepts a
    `{markup_pct, fixed_fee, card_fee_pct?}` percent-and-dollars body (bounds:
    0–100% / $0–$1000; `card_fee_pct` validated + echoed, not persisted) and,
    when `SHIPKIT_MANAGED_CONFIG_TOKEN` is set, an
    `Authorization: Bearer <token>` alternative to the `sk_` key — constant-time
    compare, scoped to that single endpoint, disabled (fail closed) when the
    variable is unset.
  - **Usage-event webhook** (`events/UsageEvents.kt`): when
    `SHIPKIT_EVENTS_WEBHOOK_URL` is set, every successful label purchase — the
    3-D Secure session path and the saved-card path — POSTs a `label.purchased`
    v1 envelope (money in integer cents; tenant identified by the caller's
    non-secret key prefix; no card data) with an optional
    `Authorization: Bearer $SHIPKIT_EVENTS_WEBHOOK_TOKEN`. Fire-and-forget on a
    background daemon thread with one retry; can never block or fail a
    purchase. Documented in `.env.example` and the README managed section.

- **Framework examples** in `src/main/resources/public/examples/`: Next.js
  (App Router, via `next/script`), Svelte, React + TypeScript (`ShipWidget.tsx`),
  a managed-CDN page (`cdn.html`), and a server-side Node/Express example
  (`server-node.js`) that buys labels with a secret key.
- **TypeScript type definitions** (`examples/shipkit.d.ts`) — typed
  `ShipKit.init(...)`, the `endpoint`+`apiKey` **xor** `managedKey` config union,
  and the `Rate` / `PurchaseResult` / `QuoteResult` payloads.
- **`docs/comparison.md`** — ShipKit vs raw EasyPost, Shippo/ShipEngine, and
  rolling your own; surfaced as a "How ShipKit compares" section in the README.
- **`docs/good-first-issues.md`** — a seeded set of scoped starter tasks, linked
  from the README and CONTRIBUTING.
- **Zero-credential "Try it now" path** at the top of `docs/quickstart.md` and a
  no-account callout in the README, pointing at the self-contained `demo/index.html`.

### Changed

- **`docs/api.md`** now documents the account-gated saved-card / frictionless
  routes (`POST /api/payment/save-card`, `POST /api/payment/charge-saved-card`),
  the tier gate (`GET /api/config/tier`), and the SMS admin-session handshake
  (`/api/verification/start` + `/check`, `X-Session-ID`), with matching rows in
  the endpoint-summary table. Fixed the `docs/authentication.md` "admin flow"
  link to resolve to that new section.
- **README** gains a table of contents, a live GitHub Actions CI badge and a
  Codecov badge (replacing the static build/coverage shields), a clarifier
  distinguishing the `/shippingtool` demo from the `/shipkit` signup host, and
  expanded framework-example pointers.
- **`docs/integration.md`** framework snippets expanded with Next.js, Svelte,
  TypeScript, and a server-side Node example, plus a copy-paste table linking
  every example file.

## [1.0.0] - 2026-01-01

Initial public release of ShipKit — the open shipping toolkit, secured by
Lifted Payments 3-D Secure.

### Added

- **Multi-carrier shipping** via EasyPost: address verification, live rate and
  SmartRates comparison, label purchase, batch, scan forms, customs, tracking,
  webhooks, and EndShipper support.
- **Card payments secured by Lifted Payments 3-D Secure** through the
  `LiftedPaymentsClient` abstraction — hosted-form card entry with a 3-D Secure
  authentication step and issuer liability shift on authenticated transactions.
- **API-key authentication.** Every `/api/*` route (except `GET /api/health` and
  the HMAC-verified `POST /api/webhook/easypost`) requires a `ShipKit-Api-Key`
  header (`sk_live_…`/`sk_test_…`); missing/invalid/revoked → `401 { "success": false, "error": … }`.
  Keys are crypto-secure, shown once, stored only as a SHA-256 hash, verified in
  constant time, and never logged (`security/KeyGenerator.kt`,
  `security/ApiKeyStore.kt` with in-memory + PostgreSQL `shipkit_api_keys`
  backends).
- **Key minting & management.** `shipkitKeygen` Gradle task
  (`./gradlew shipkitKeygen -Plabel=… [-Ptest]`) / `com.lifted.shipkit.KeygenKt`
  CLI, plus admin-gated `POST/GET/DELETE /api/keys` (mint / list / revoke).
- **OpenAPI 3.1 spec** at `openapi.yaml`, served at `GET /openapi.yaml`, with a
  self-contained (no external CDN) API reference at `GET /docs`.
- **Postman collection** (`shipkit.postman_collection.json`, Collection v2.1) covering
  every endpoint, with `ShipKit-Api-Key` auth wired at the collection level, example
  bodies, `{{baseUrl}}` / `{{apiKey}}` variables, and Shipping / Payments / Keys / Admin
  folders. Import instructions in the README.
- **Drop-in JS widget** (`shipkit.js`) — dependency-free UMD global
  (`window.ShipKit`), loaded with a `<script>` tag. Address entry and verify,
  live rate compare, buy-label, 3-D Secure card step, and label/QR result.
  Themeable via CSS custom properties. Callbacks: `onQuote`, `onPurchase`,
  `onError`. The `apiKey` (self-host) / `managedKey` (managed) option — and the
  `data-api-key` / `data-managed-key` script-tag attributes — are sent as the
  `ShipKit-Api-Key` header.
- **Three adoption tiers** (see `docs/tiers.md`): **(1) Self-host** — DIY, free
  MIT; bring your own EasyPost and your own 3-D Secure merchant account. **(2)
  Lifted 3-D Secure merchant account** — your checkout on our 3DS account, host
  it yourself or let us host it (both free); priced at 3.75% + $0.15/transaction
  + $25/month for the merchant account only, with the processing fee optionally
  surchargeable to the buyer. **(3) Fully managed** — one JS snippet on our rails
  (our 3DS + our EasyPost), free hosting, funded by a configurable markup on the
  shipping rate. Plus a custom integration / software-development option
  (support@liftedholdings.com).
- **Env-driven configuration** — every credential loads from the environment
  with safe, non-secret defaults or fail-fast. Documented in `.env.example`.
- **Pluggable label store** — in-memory by default, optional PostgreSQL
  backend (HikariCP pool, `sslmode=require`, `ON CONFLICT` upserts).
- **Optional Twilio SMS module** — off by default, no secrets required to run.
- **Documentation set:** tiers & pricing, quickstart, integration guide, managed
  tier, authentication, backend architecture, API reference, and a 3-D Secure
  explainer (including the underlying Maverick `/payment/sale` gateway contract
  and both hosted-fields and hosted-form card-capture models).
- **Self-contained demo** (`demo/index.html`) showcasing the widget and the
  payment security model.
- Docker and Docker Compose deployment, both env-driven and secret-free.
- Continuous integration: `./gradlew build` (build + tests) on every push and
  pull request.

[Unreleased]: https://github.com/LiftedHoldings/lifted-shipkit/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/LiftedHoldings/lifted-shipkit/releases/tag/v1.0.0
