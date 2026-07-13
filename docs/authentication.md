# Authentication

Every Lifted ShipKit API call is authenticated with a **ShipKit API key**. Keys are the single
front door to the `/api/*` surface — the widget sends one, and so must any direct caller.

## The `ShipKit-Api-Key` header

Send your key in the `ShipKit-Api-Key` request header on every `/api/*` request:

```
ShipKit-Api-Key: sk_live_your_secret_key_here
```

- Keys come in two **scopes** — **secret** (`sk_…`, server-only) and **publishable** (`pk_…`, browser widget) — and two **modes** — production (`…_live_…`) and non-production (`…_test_…`). See [Secret keys and publishable keys](#secret-keys-and-publishable-keys) below.
- A missing, malformed, unknown, or revoked key returns **`401 { "success": false, "error": … }`**.

Two routes are intentionally **exempt** — they authenticate by other means and no
API-key holder drives them:

| Route | Why it is exempt |
|---|---|
| `GET /api/health` | An unauthenticated liveness/readiness probe. |
| `POST /api/webhook/easypost` | Authenticated by EasyPost's HMAC-SHA256 signature (`X-Hmac-Signature`) — a stronger, origin-bound scheme. EasyPost cannot send a ShipKit key. |

## How keys are stored (and why a leak is survivable)

ShipKit **never stores the key itself.** At mint time it keeps only:

- a **SHA-256 hash** of the key (used for a constant-time lookup on each request),
- a short display **prefix** (e.g. `sk_live_Ab12Cd`) so you can tell keys apart,
- the **label**, **created-at**, **last-used-at**, and a **revoked** flag.

The full key is shown **exactly once**, at creation, and can never be retrieved again —
if you lose it, revoke it and mint a new one. Because only a hash is at rest, a leaked
database yields no usable keys, and the constant-time compare means an attacker cannot
time their way to a valid key. Keys are **never logged**; ShipKit identifies a key in logs
by its `id`/`prefix` only.

## Secret keys and publishable keys

Every key carries a **scope** that decides how much of the API it can reach. The scope is the key's prefix, so you can tell them apart at a glance.

| Scope | Prefix | Where it belongs | What it can do |
|---|---|---|---|
| **Secret** | `sk_live_…` / `sk_test_…` | Server-side and admin only — **never** in browser or client code | Full access to every `/api/*` route: rating, the 3-D Secure payment flow, **and** privileged actions (direct label buy, batch, customs/EndShipper, markup writes, key management). |
| **Publishable** | `pk_live_…` / `pk_test_…` | Safe to embed in a browser — this is the widget's key | The customer flow only: verify an address, rate a shipment, open a payment session, poll its status, and buy the label the customer just paid for. |

A publishable key is confined by the backend: presented on a secret-only endpoint (direct buy, batch, customs, EndShipper, markup writes, admin, key management) the server returns **`403`** and refuses the call. That is why a `pk_…` key is safe in page source — a leaked publishable key drains nothing beyond the customer checkout it already runs.

> **The secret `sk_…` key must never appear in browser or client code**, in a public repo, or in a URL. It is the full server credential — keep it only in server-side environment variables. If one leaks, revoke it and mint a new one.

Mint whichever you need with the keygen CLI. The default scope is a full secret `sk_…` key; the `--publishable` flag mints a browser-safe `pk_…` key:

```bash
# Secret server key (default scope) — for your backend
./gradlew shipkitKeygen -Plabel=prod-server                              # sk_live_…

# Publishable widget key (browser-safe) — pass --publishable to the entrypoint
java -cp build/libs/shipkit-1.0.0-all.jar com.lifted.shipkit.KeygenKt \
  --label=web-widget --publishable                                      # pk_live_…
```

Over HTTP, `POST /api/keys` takes a `"scope"` of `"secret"` (default) or `"publishable"`.

## Minting a key

### With the CLI (works before the server is up)

```bash
./gradlew shipkitKeygen -Plabel=prod-checkout          # a sk_live_ key
./gradlew shipkitKeygen -Plabel=local-dev -Ptest       # a sk_test_ key
```

The task reads the **same environment** the server does (`SHIPKIT_STORE`,
`SHIPKIT_DATABASE_URL`, …), so a key minted against `SHIPKIT_STORE=postgres` is
immediately visible to the running server. It prints the full key **once** — copy it then.

> With the default `SHIPKIT_STORE=memory`, a key lives only for the process that minted
> it, so it will not be visible to a separately running server. Use
> `SHIPKIT_STORE=postgres` for durable, shared keys.

Equivalently, run the entrypoint directly:

```bash
java -cp build/libs/shipkit-1.0.0-all.jar com.lifted.shipkit.KeygenKt --label=prod-checkout
```

### Over HTTP (admin-gated)

`POST /api/keys` mints a key at runtime. It requires **both** a valid API key (the `/api/*`
gate) **and** a verified admin session — an SMS-verified session whose phone is in
`SHIPKIT_ADMIN_PHONES` (see the [admin session flow](api.md#admin-session-sms-verification)).
Provide the session id in the `X-Session-ID` header — obtain it with the
`verification/start` → `verification/check` handshake documented there.

```bash
curl -X POST https://your-host/api/keys \
  -H "ShipKit-Api-Key: sk_live_existing_admin_key" \
  -H "X-Session-ID: <verified-admin-session>" \
  -H "Content-Type: application/json" \
  -d '{ "label": "prod-checkout", "mode": "live" }'
```

```json
{
  "id": "b2f1c0de-1111-2222-3333-444455556666",
  "label": "prod-checkout",
  "prefix": "sk_live_Ab12Cd",
  "mode": "live",
  "revoked": false,
  "api_key": "sk_live_your_secret_key_here",
  "message": "Store this key now — it is shown only once and cannot be retrieved again."
}
```

## Listing and revoking

Both are admin-gated (same `X-Session-ID` requirement). Listing never returns the key or
its hash — only metadata.

```bash
# List every key (newest first)
curl https://your-host/api/keys \
  -H "ShipKit-Api-Key: sk_live_admin_key" -H "X-Session-ID: <session>"

# Revoke a key by id (idempotent)
curl -X DELETE https://your-host/api/keys/b2f1c0de-1111-2222-3333-444455556666 \
  -H "ShipKit-Api-Key: sk_live_admin_key" -H "X-Session-ID: <session>"
```

A revoked key stops authenticating immediately.

## Using a key from the widget

The widget runs in the browser, so it takes a **publishable** `pk_…` key — never a secret
one. It sends the key for you; pass it at init:

```js
// Self-host (your backend) — use a publishable key minted with --publishable
ShipKit.init({ mount: '#ship', endpoint: '/api', apiKey: 'pk_live_your_publishable_key' });

// Managed (the managedKey is the publishable pk_ key Lifted issues; same header)
ShipKit.init({ mount: '#ship', managedKey: 'pk_live_your_publishable_key' });
```

or via the script tag: `data-api-key="pk_live_…"` (self-host) / `data-managed-key="pk_live_…"`
(managed). See the [integration guide](integration.md).

## Live vs. test keys

`…_live_` and `…_test_` mark the **mode** (production vs non-production) and are distinguished
only by that token; both authenticate the same routes for their scope. Mode is independent of
scope, so you can mint `sk_test_`, `pk_test_`, `sk_live_`, and `pk_live_` keys. Use `…_test_`
keys for non-production environments so they are easy to spot and revoke. Never put a **secret**
`sk_…` key of either mode into client-side code you do not control — the browser widget always
uses a **publishable** `pk_…` key, and on the managed tier Lifted issues and scopes the `pk_…`
key that ships in the browser.

## The managed alternative

Don't want to run a backend or manage keys at all? **ShipKit Managed** hands you a publishable
`pk_live_` key that routes to Lifted's edge — no infrastructure, no PCI scope. See the
[Managed tier](managed.md).

---

Maintained by Daniel Wilson Kemp · Lifted Holdings. Take live payments through a 3-D Secure
merchant account — [apply at liftedholdings.com/payments](https://liftedholdings.com/payments).
