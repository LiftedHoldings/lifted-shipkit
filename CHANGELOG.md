# Changelog

All notable changes to Lifted ShipKit are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed

- **Postgres purchase claim for not-yet-persisted sessions** — the saved-card
  flow claims its idempotency lock before any session row exists; the Postgres
  store's UPDATE-based claim matched zero rows and reported every saved-card
  purchase as "already in progress", permanently. The claim is now an atomic
  claim-or-create, and the session upsert carries **every** money field
  (amount, gateway transaction id, shipment/rate context) so a session saved
  over the claim placeholder can no longer lose its charge record.
- **Numeric gateway ids normalized** — the gateway returns numeric transaction,
  vault-customer, vault-card, and billing ids; Gson decodes JSON numbers to
  `Double`, and the previous `toString()` fallback rendered `223668` as
  `"223668.0"` — a string that 404s every later `/payment/{id}` refund,
  capture, or verification lookup, and poisons persisted `vaultId` references.
  Whole numbers now render as plain integers everywhere an id is read.
- **Status polls never downgrade a persisted `approved` charge** — a
  verification read that returns `pending` (as it always does for a saved-card
  session, whose reference is a gateway transaction id) no longer stamps
  `pending` over a stored `approved`, closing a double-charge window on the
  saved-card retry path when a poll landed between a successful charge and a
  failed label buy. Purchase decisions still run on the fresh verification,
  unconditionally.

- **`verifyPayment()` externalId lookup route** now follows the gateway
  contract: `GET {gateway}/payments?filter[externalId]=…` (URL-encoded, Bearer
  auth) instead of the non-existent `/api/transaction?externalId=…` path. The
  reply is the standard `{items, _meta{totalCount}}` list envelope and the item
  is trusted **only when `_meta.totalCount == 1`** — a missing/malformed filter
  silently returns the full unfiltered transaction list, so any other count maps
  to `pending` (not-found semantics), with the exact-match `externalId` guard
  kept as defense-in-depth.
- **`capture()` partial shape** — a partial capture now sends a **top-level**
  `amount` (the old `partial:{amount}` object is rejected by the gateway), and
  split-shipment captures additionally send `partial:{sequence,total}` via the
  new `capture(transactionId, amount, sequence, total)` signature (`sequence`
  validated in `1..total`; `sequence`/`total` must come together with an
  `amount`).
- **Gateway error messages surfaced** — a non-2xx money-call reply now parses
  the gateway's `{name,message,code,status}` error envelope and includes its
  `message` in the thrown `IOException` (e.g. a 422 decline or
  duplicate-suspected reason) instead of the bare status code.

### Changed

- **Hosted-form path verified** — `/api/gateway/hosted-form` is now the
  verified default creation path per the gateway contract; the `UNVERIFIED`
  markers were removed from the client, docs, and `.env.example`, and the path
  remains overridable via `LIFTED_PAYMENTS_HOSTED_FORM_PATH`.
- **`LiftedPaymentsClient` KDoc** documents two gateway-contract behaviors:
  declines surface as HTTP 422 with no transaction id (record your own attempt
  row keyed by `externalId` before charging), and repeat same-card/same-amount
  attempts may 422 as duplicate-suspected — resolve via the externalId lookup
  before re-charging.

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
