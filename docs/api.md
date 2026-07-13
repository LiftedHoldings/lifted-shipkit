# Shipping REST API reference (EasyPost + 3-D Secure)

The ShipKit backend exposes one JSON REST API. The widget uses it, and you can call it
directly from any language. All request and response bodies are `application/json` unless
noted. Base path is the host you deploy to (for example `http://localhost:8080`); the
widget calls it under a configurable `endpoint` (default `/api`).

- **Auth:** every `/api/*` route — except `GET /api/health` and the HMAC-verified
  `POST /api/webhook/easypost` — requires a ShipKit API key in the `ShipKit-Api-Key`
  header (`sk_live_…`/`sk_test_…`). Missing, invalid, or revoked → `401 { "success": false, "error": … }`.
  Mint keys with `./gradlew shipkitKeygen -Plabel=…` or the admin-gated `/api/keys`
  endpoints. Full details in [Authentication](authentication.md). **ShipKit Managed** uses
  the same header — see [Managed tier](managed.md).
- **Errors:** failures return a non-2xx status with `{ "success": false, "error": "<message>" }`.
  EasyPost validation errors surface as `400`; missing sessions as `404`; a feature whose
  credentials are not configured returns `503`; unexpected faults as `500`.
- **Interactive reference:** a running server serves this API interactively at
  **`GET /docs`** and the raw **OpenAPI 3.1 spec at `GET /openapi.yaml`**.
- **Money:** all amounts — rates and charges — are **strings** (e.g. `"8.42"`). Parse them
  as decimals; never use a float or a string comparison. Rates come straight from the
  carriers via EasyPost.

## Endpoint summary

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/address/verify` | Verify and normalize a delivery address. |
| `POST` | `/api/shipment/create` | Create a shipment and return carrier rates. |
| `POST` | `/api/shipment/smartrates` | Get delivery-date-accurate SmartRates for a shipment. |
| `POST` | `/api/shipment/buy` | Buy a label directly for a chosen rate (no card step). |
| `POST` | `/api/payment/session` | Open a Lifted Payments 3-D Secure payment session. |
| `GET` | `/api/payment/status/{sessionId}` | Poll the server-verified payment / 3-D Secure status. |
| `POST` | `/api/payment/purchase-label/{sessionId}` | Buy the label for an approved session (idempotent). |
| `POST` | `/api/batch/create` | Create and buy a batch of shipments. |
| `POST` | `/api/scanform/create` | Generate a SCAN form (manifest) for a batch. |
| `POST` | `/api/customs/create` | Create customs info + items for international shipments. |
| `POST` | `/api/webhook/easypost` | Receive EasyPost tracking / event webhooks (HMAC-verified). |
| `GET` | `/api/endshipper/get` | Read the configured EndShipper identity. |
| `POST` | `/api/endshipper/create` | Register / update the EndShipper identity. |
| `GET` | `/api/config/markup` | Read the markup applied on top of carrier rates. |
| `POST` | `/api/config/markup` | Update the markup (percentage + fixed fee). |
| `GET` | `/api/label/{labelId}` | Fetch a stored label by id. |
| `GET` | `/api/label/session/{sessionId}` | Fetch a stored label by payment session id. |
| `DELETE` | `/api/label/session/{sessionId}/shred` | Remove a stored label from storage (does not void it with the carrier). |
| `POST` | `/api/keys` | Mint an API key (admin-gated; key shown once). |
| `GET` | `/api/keys` | List API keys (metadata only). Admin-gated. |
| `DELETE` | `/api/keys/{id}` | Revoke an API key. Admin-gated. |
| `GET` | `/api/health` | Liveness/readiness probe (no key required). |

---

## Addresses

### `POST /api/address/verify`

Runs USPS delivery verification and returns a normalized address plus any correction
messages.

**Request**

```json
{
  "name": "Ada Lovelace",
  "company": "Analytical Engines",
  "street1": "1600 Pennsylvania Ave NW",
  "street2": "",
  "city": "Washington",
  "state": "DC",
  "zip": "20500",
  "country": "US"
}
```

`street1`, `city`, `state`, `zip` are required. `country` defaults to `US`. `name`,
`company`, `street2` are optional.

**Response `200`**

```json
{
  "verified": true,
  "address": {
    "name": "Ada Lovelace",
    "company": "Analytical Engines",
    "street1": "1600 PENNSYLVANIA AVE NW",
    "street2": "",
    "city": "WASHINGTON",
    "state": "DC",
    "zip": "20500-0003",
    "country": "US",
    "phone": ""
  },
  "residential": false,
  "errors": []
}
```

`residential` is set by EasyPost delivery verification and drives residential
surcharges — read it back before comparing rates. When verification fails,
`verified` is `false` and `errors` carries `{ "code", "message" }` entries
(surface the message to the user).

---

## Shipments and rates

### `POST /api/shipment/create`

Creates an EasyPost shipment from a from-address, to-address, and parcel, and returns the
full list of carrier rates.

**Request**

```json
{
  "from": {
    "name": "Analytical Engines",
    "street1": "1 Foundry St",
    "city": "Atlanta",
    "state": "GA",
    "zip": "30301",
    "country": "US",
    "phone": "4045551234"
  },
  "to": {
    "name": "Ada Lovelace",
    "street1": "1600 Pennsylvania Ave NW",
    "city": "Washington",
    "state": "DC",
    "zip": "20500",
    "country": "US"
  },
  "parcel": {
    "weight_oz": 32,
    "length_in": 10,
    "width_in": 8,
    "height_in": 4
  }
}
```

Parcel dimensions are inches; `weight_oz` is **ounces** (required, must be > 0 — the #1
silent rating bug is passing grams or pounds). The from-address must carry a `name` or
`company` — USPS requires it at label purchase.

**Response `200`**

```json
{
  "id": "shp_...",
  "rates": [
    {
      "id": "rate_...",
      "carrier": "USPS",
      "service": "Priority",
      "rate": "8.42",
      "currency": "USD",
      "delivery_days": 2
    },
    {
      "id": "rate_...",
      "carrier": "UPS",
      "service": "Ground",
      "rate": "11.07",
      "currency": "USD",
      "delivery_days": 3
    }
  ],
  "messages": []
}
```

Each `rate` is a **string** — parse it as a decimal and sort by value, never string-sort.
`delivery_days` is nullable. **An empty `rates` array with a populated `messages` array is a
carrier error** (bad account, over weight limit, lane not served); surface
`messages[].message` instead of showing "no options" blankly. Select a rate by its explicit
`id`, never by array index.

### `POST /api/shipment/smartrates`

Returns SmartRates — rates enriched with EasyPost's delivery-date accuracy percentiles for
an already-created shipment.

**Request**

```json
{ "shipment_id": "shp_..." }
```

**Response `200`**

```json
{
  "message": "SmartRates retrieved",
  "shipment_id": "shp_...",
  "rates": [ { "carrier": "USPS", "service": "Priority", "rate": "8.42", "delivery_date": "2026-07-15", "delivery_date_guaranteed": false } ]
}
```

### `POST /api/shipment/buy`

Buys a label directly for a chosen rate. Use this when payment is handled out of band (no
interactive 3-D Secure card step). For card-secured purchases, use the payment flow below.

**Request**

```json
{
  "shipment_id": "shp_...",
  "rate_id": "rate_...",
  "end_shipper_id": "es_..."
}
```

`end_shipper_id` is optional; if omitted, the configured EndShipper is used.

**Response `200`**

```json
{
  "tracking_code": "9400100000000000000000",
  "label_url": "https://easypost-files.s3.../label.png",
  "carrier": "USPS",
  "service": "Priority",
  "rate": "8.42"
}
```

---

## Payment — Lifted Payments 3-D Secure

Card entry happens off ShipKit entirely — either on **tokenized hosted fields** (the primary
model: the card is tokenized in the browser and only the token reaches the server) or on a
**Maverick-hosted payment form** — and 3-D Secure runs on Lifted Payments. The raw card
number never touches ShipKit or your server. **3-D Secure is always enforced; there is no
code path that authorizes a charge without a completed authentication result.**

You call the three `/api/payment/*` routes below; ShipKit translates them into the underlying
gateway contract (`POST /payment/sale` with the card token nested under `card` and a boolean
`3ds`, plus `capture` / `refund` / transaction lookup). That contract, both browser capture
models, and the sandbox-vs-live hosts are documented in
[3-D Secure → How ShipKit talks to the gateway](3d-secure.md#how-shipkit-talks-to-the-gateway).
Read [3-D Secure explained](3d-secure.md) for why any of this matters.

### `POST /api/payment/session`

Opens a Lifted Payments 3-D Secure payment session for a chosen rate and returns the
hosted-fields form URL. **The amount is computed on the server** from the rate plus your
configured markup — any client-sent amount is ignored, so a caller cannot underpay for a
label.

**Request**

```json
{
  "shipment_id": "shp_...",
  "rate_id": "rate_...",
  "end_shipper_id": "es_..."
}
```

`end_shipper_id` is optional; if omitted, the configured EndShipper is used.

**Response `200`**

```json
{
  "session_id": "sess_...",
  "form_url": "https://dashboard.maverickpayments.com/hosted-fields/sess_...",
  "amount": "8.42",
  "currency": "USD",
  "expires_at": "2026-07-12T18:05:00Z"
}
```

The canonical payment URL key is **`form_url`** — the browser mounts the tokenized hosted
fields and 3-D Secure step there. `amount` is a **string**, server-computed and
authoritative.

### `GET /api/payment/status/{sessionId}`

Polls the session's server-verified payment and 3-D Secure state. The status is confirmed
against Lifted Payments — it is **never** derived from redirect / return-URL parameters.

**Response `200`**

```json
{
  "status": "approved",
  "three_ds": {
    "eci": "05",
    "cavv": "AAABBABZUAAAAAAAxxxxxxxx=",
    "liability_shift": true
  }
}
```

`status` is one of `pending`, `authenticated`, `approved`, `declined`, `failed`. A label may
be purchased **only** when `status == "approved"` **and** `three_ds.liability_shift == true`.
The `eci` value is scheme-specific (Visa `05` / Mastercard `02` = fully authenticated).

**Response `404`** when the session is unknown or expired:

```json
{ "success": false, "error": "Payment session not found" }
```

### `POST /api/payment/purchase-label/{sessionId}`

Buys the label for an approved session. **Idempotent** — a second call returns the same
label rather than buying again.

**Response `200`**

```json
{
  "label_url": "https://easypost-files.s3.../label.png",
  "qr_code_url": "https://easypost-files.s3.../qr.png",
  "tracking_code": "9400100000000000000000",
  "carrier": "USPS",
  "service": "Priority"
}
```

Take live payments through your own 3-D Secure merchant account —
[apply at liftedholdings.com/payments](https://liftedholdings.com/payments).

---

## Bulk and international

### `POST /api/batch/create`

Creates and buys a batch of shipments in one call.

**Request**

```json
{ "shipment_ids": ["shp_a", "shp_b", "shp_c"] }
```

**Response `200`**

```json
{ "id": "batch_...", "state": "created", "num_shipments": 3 }
```

### `POST /api/scanform/create`

Generates a single SCAN form (manifest) for a batch so the carrier scans one barcode
instead of every label.

**Request**

```json
{ "batch_id": "batch_..." }
```

**Response `200`**

```json
{ "id": "sf_...", "form_url": "https://easypost-files.s3.../scanform.pdf", "status": "created" }
```

### `POST /api/customs/create`

Builds customs info and items for an international shipment.

**Request**

```json
{
  "customs_signer": "Ada Lovelace",
  "contents_type": "merchandise",
  "eel_pfc": "NOEEI 30.37(a)",
  "items": [
    {
      "description": "Cotton t-shirt",
      "quantity": 2,
      "value": 20.0,
      "weight": 8.0,
      "hs_tariff_number": "610910",
      "origin_country": "US"
    }
  ]
}
```

**Response `200`**

```json
{ "id": "cstinfo_...", "customs_certify": true, "contents_type": "merchandise" }
```

---

## EndShipper

A registered commercial shipper identity reused across purchases. Configure it once.

### `GET /api/endshipper/get`

```json
{ "success": true, "end_shipper_id": "es_...", "configured": true }
```

### `POST /api/endshipper/create`

**Request**

```json
{
  "name": "Analytical Engines",
  "company": "Analytical Engines LLC",
  "phone": "4045551234",
  "street1": "1 Foundry St",
  "city": "Atlanta",
  "state": "GA",
  "zip": "30301",
  "country": "US"
}
```

**Response `200`**

```json
{ "success": true, "end_shipper_id": "es_..." }
```

---

## Markup configuration

The amount a customer is charged in the payment flow is the carrier rate **plus your
configured markup**. The markup is a percentage of the rate plus an optional flat fee, and
the charge amount is always computed server-side (see [`POST /api/payment/session`](#post-apipaymentsession)).
Both routes require the `ShipKit-Api-Key` header like the rest of `/api/*`.

### `GET /api/config/markup`

Reads the current markup.

**Response `200`**

```json
{ "percentage_markup": 12.0, "fixed_fee_cents": 50, "fixed_fee_dollars": 0.5 }
```

`percentage_markup` is a percent (e.g. `12.0` = +12%). `fixed_fee_cents` is a flat add-on in
cents; `fixed_fee_dollars` is the same value in dollars for convenience.

### `POST /api/config/markup`

Updates the markup. Both fields are required.

**Request**

```json
{ "percentage_markup": 12.0, "fixed_fee_cents": 50 }
```

**Response `200`**

```json
{ "success": true, "percentage_markup": 12.0, "fixed_fee_cents": 50 }
```

An invalid body (negative values, non-numeric) returns `400`.

---

## Stored labels

Purchased labels are persisted in the [`LabelStore`](architecture.md#storage-backends).
These routes read and remove them; all require the `ShipKit-Api-Key` header.

### `GET /api/label/{labelId}`

Fetches a stored label by its id.

**Response `200`** — the stored label record (tracking code, label URL, QR, carrier, service).
**`404`** when no label with that id exists.

### `GET /api/label/session/{sessionId}`

Fetches the stored label for a payment session id — handy right after a purchase when you
hold the `session_id` but not the label id.

**Response `200`** — the stored label record. **`404`** when the session has no label.

### `DELETE /api/label/session/{sessionId}/shred`

Removes a label from ShipKit's storage. **This only deletes the local record — it does not
void or refund the label with the carrier.**

**Response `200`** on removal; **`404`** when there is nothing stored for that session.

---

## Tracking webhook

### `POST /api/webhook/easypost`

Receives EasyPost event webhooks (tracker updates, batch completion, etc.). Register this
URL in your EasyPost dashboard with a webhook secret.

ShipKit verifies every delivery before processing it:

1. Read the **`X-Hmac-Signature`** header.
2. Normalize the shared secret with **NFKD** compatibility decomposition (UTF-8 bytes).
3. Compute `HMAC-SHA256(key = NFKD(secret), message = the RAW request body bytes)` — never
   re-serialized JSON.
4. Compare, in **constant time**, against the literal `hmac-sha256-hex=` + the hex digest.
   A mismatch is rejected.

Read the changed object from `event.result` and treat the `evt_` id as an idempotency key
(EasyPost redelivers).

**Request** — an EasyPost event envelope:

```json
{
  "id": "evt_...",
  "description": "tracker.updated",
  "result": {
    "tracking_code": "9400100000000000000000",
    "status": "in_transit",
    "carrier": "USPS"
  }
}
```

**Response `200`** on a verified event; **`401`** on a missing or invalid signature.

---

## Status codes

| Code | Meaning |
|---|---|
| `200` | Success. |
| `400` | Invalid request or carrier/validation error (`{ "success": false, "error": "..." }`). |
| `401` | Missing/invalid/revoked API key, or an invalid webhook signature. |
| `403` | Authenticated but not authorized (admin-only action). |
| `404` | Unknown or expired payment session / label. |
| `503` | A feature's credentials are not configured (e.g. EasyPost or Lifted Payments keys unset). |
| `500` | Unexpected server fault. |

## See also

- [Authentication](authentication.md) — API keys, the `ShipKit-Api-Key` header, minting and revoking.
- [Architecture](architecture.md) — how requests flow through the services.
- [3-D Secure explained](3d-secure.md) — the payment layer, liability shift, and approval
  rates.
- [Widget integration](integration.md) — the drop-in JS that calls this API for you.

---

Maintained by Daniel Wilson Kemp · Lifted Holdings. Take live payments through a 3-D Secure merchant account — [apply at liftedholdings.com/payments](https://liftedholdings.com/payments).
