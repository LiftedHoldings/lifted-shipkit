# Architecture

ShipKit is a small, focused backend that fronts two external systems — **EasyPost**
(multi-carrier rating and label purchase) and **Lifted Payments 3-D Secure** (card
payment via a hosted, authenticated form) — behind one clean REST API. A dependency-free
JavaScript widget consumes that API in the browser.

This document describes the components, how a label is bought end to end, and where
each concern lives in the code.

## Design goals

- **One API, two front doors.** The same REST surface serves the self-hosted widget and
  the ShipKit Managed edge. Nothing carrier- or payment-specific leaks into the client.
- **No secrets in code.** Every credential is read from the environment through a single
  config object. See [`.env.example`](../.env.example).
- **Swappable storage.** Label and session records go through an abstract `LabelStore`.
  The default is in-memory; PostgreSQL is opt-in via one env var (`SHIPKIT_STORE=postgres`).
- **Payment is always authenticated.** Cards are entered in tokenized hosted fields and
  every charge requires a completed 3-D Secure result — there is no code path that
  authorizes a card without it, and no flag to disable it. The card never touches ShipKit
  or the merchant's server — no PCI scope for card data on your infrastructure.

## Components

| Component | Package / path | Responsibility |
|---|---|---|
| **Bootstrap + routes** | `App.kt` | Builds the Javalin server, wires dependencies, registers every route. No business logic. |
| **Configuration** | `config/ShipKitConfig.kt` | Reads all settings from environment variables. Fails fast when a required key is missing. Zero hardcoded credentials. |
| **Shipping service** | `shipping/EasyPostService.kt` | All EasyPost calls: address verification, shipment creation, rates, SmartRates, label buy, batch, scan form, customs, EndShipper, tracking. |
| **Payments client** | `payments/LiftedPaymentsClient.kt` | Generates the Lifted Payments 3-D Secure hosted-form session, builds return URLs, and reads authentication/settlement status. |
| **Label store** | `store/LabelStore.kt` | Abstract persistence for label records and payment sessions. `InMemoryLabelStore` (default) and `PostgresLabelStore` (optional, HikariCP-pooled) implement it. |
| **HTTP handlers** | `http/Handlers.kt` | Thin translation layer: parse request → call a service → shape the JSON response. Uniform error mapping. |
| **Models** | `model/*.kt` | Request/response data classes: `Address`, `Parcel`, `Shipment`, `Rate`, `PaymentSession`, `LabelRecord`, and friends. |
| **Widget** | `src/main/resources/public/js/shipkit.js` | Framework-free UMD widget (`window.ShipKit`), loaded with a `<script>` tag. Address entry, live rate compare, 3-D Secure card step, label/QR result. |

## System context

```
                +-------------------+          +---------------------------+
   Browser      |                   |  REST    |         ShipKit           |
 +----------+   |   shipkit.js      | -------> |   Javalin backend         |
 | merchant | = |   (widget)        |          |                           |
 |   page   |   |                   | <------- |  http/Handlers.kt         |
 +----------+   +-------------------+   JSON   |         |                 |
                                               |   +-----+------+          |
                                               |   |            |          |
                                               |   v            v          |
                                               | EasyPost   LiftedPayments |
                                               | Service     Client        |
                                               |   |            |          |
                                               |   v            v          |
                                               | LabelStore  (sessions)    |
                                               +---|------------|----------+
                                                   |            |
                                                   v            v
                                           +-------------+  +---------------------+
                                           |  EasyPost   |  | Lifted Payments 3DS |
                                           |  carriers   |  | hosted form + issuer|
                                           +-------------+  +---------------------+
```

## Data flow: buying one label

The widget drives a linear flow. Each step is one REST call.

1. **Verify address** — `POST /api/address/verify`. `EasyPostService` runs USPS delivery
   verification and returns a normalized address plus any correction messages.
2. **Create shipment + rate** — `POST /api/shipment/create`. From/to addresses and a
   parcel go to EasyPost; the response carries a `shipment_id` and the full list of
   carrier `rates`. Optionally enrich with `POST /api/shipment/smartrates` for
   delivery-date-accurate rates.
3. **Start payment** — `POST /api/payment/session`. `LiftedPaymentsClient` opens a
   3-D Secure session, **computing the amount server-side** from the chosen rate plus
   markup (client-sent amounts are ignored), mints a `session_id`, records it in
   `LabelStore`, and returns the hosted-fields `form_url`. The browser mounts the tokenized
   card fields there.
4. **Authenticate + pay** — the cardholder completes 3-D Secure in the hosted fields
   (biometric / OTP / risk-based). Liability for authenticated fraud shifts to the issuer.
5. **Status (server-verified)** — the widget polls `GET /api/payment/status/{session_id}`.
   ShipKit confirms the outcome directly against Lifted Payments — **never from redirect or
   return-URL parameters** — and only reports `approved` when the charge is approved and
   3-D Secure produced a liability shift.
6. **Buy label** — once `status == "approved"` and `three_ds.liability_shift == true`, the
   server buys the label via `EasyPostService`, matching the rate the customer paid for.
   The widget can trigger this explicitly with
   `POST /api/payment/purchase-label/{session_id}`, which is **idempotent** (a second call
   returns the same label). The `LabelRecord` — tracking code, label URL, QR — is persisted
   in `LabelStore` and returned to the widget.

Direct, non-interactive callers (no card step) can skip payment and buy immediately with
`POST /api/shipment/buy` when they already hold funds/authorization out of band.

## Payment session lifecycle

A `PaymentSession` is the join between an EasyPost shipment/rate and a Lifted Payments
3-D Secure transaction. The status the API reports mirrors these states:

```
pending ──▶ authenticated ──▶ approved ──▶ (label purchased)
   │                              │
   │                              └──▶ declined
   └──▶ failed (auth abandoned / expired)
```

A label is purchased only from an `approved` session that carries a 3-D Secure liability
shift. Sessions are keyed by an opaque `session_id` and stored through `LabelStore`, so the
same lifecycle works in-memory for development and in PostgreSQL for production.

## Bulk operations

For high-volume merchants the same services expose batch primitives:

- `POST /api/batch/create` — create and buy a batch of shipments in one call.
- `POST /api/scanform/create` — generate a single SCAN form (manifest) for a batch so the
  carrier scans one barcode instead of every label.
- `POST /api/customs/create` — build customs info + items for international shipments.
- `GET`/`POST /api/endshipper/*` — manage the registered commercial EndShipper identity
  reused across purchases.

## Storage backends

| Backend | When to use | Enable |
|---|---|---|
| **In-memory** (default) | Local dev, demos, stateless deployments where labels are consumed immediately. | Nothing — this is the default. |
| **PostgreSQL** (optional) | Production, durable label history, multi-instance deployments. | Set `SHIPKIT_STORE=postgres` and `SHIPKIT_DATABASE_URL` (+ `SHIPKIT_DB_USER` / `SHIPKIT_DB_PASSWORD`) in [`.env.example`](../.env.example). |

`PostgresLabelStore` uses the PostgreSQL JDBC driver with a HikariCP pool, TLS required
(`sslmode=require`), parameterized statements only, and `ON CONFLICT` upserts —
matching Lifted's managed Postgres. Both backends implement the same `LabelStore`
interface, so switching is a config change, not a code change.

## Optional modules

- **SMS notifications** — an off-by-default module can text a tracking link to the
  recipient. Disabled unless `SHIPKIT_SMS_ENABLED=true` and the `TWILIO_*` credentials are
  supplied. No secrets ship in the repo.

## Deployment

ShipKit is a single self-contained JVM service (Kotlin 2.0.21, JVM 17, Javalin 5). Build a
runnable jar with `./gradlew build`, or use the provided `Dockerfile` /
`docker-compose.yml`. It serves both the REST API and the static widget assets from
`src/main/resources/public`.

If you would rather not run any of this, **ShipKit Managed** hosts the entire backend for
you — you embed one script tag. See [Managed tier](managed.md).

## Where to go next

- [REST API reference](api.md) — every endpoint, request, and response.
- [3-D Secure explained](3d-secure.md) — why the payment layer is built this way.
- [Widget integration guide](integration.md) — embed ShipKit in minutes.

---

Maintained by Daniel Wilson Kemp · Lifted Holdings. The payment layer is secured by [Lifted Payments 3-D Secure](https://liftedholdings.com/payments).
