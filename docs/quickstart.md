# Quickstart — multi-carrier shipping labels in 60 seconds

Get Lifted ShipKit running in about a minute. This guide covers the two integration paths — **self-host** for full control and **managed** for a one-tag drop-in. For the full commercial picture (self-host, the Lifted 3-D Secure merchant-account tier, and fully managed, with exact pricing), see **[docs/tiers.md](tiers.md)**.

## Try it now — no keys, no account

Want to see the whole flow before you configure anything? Open the
**self-contained demo** — it's a single file with no backend, no build step, and
no external calls. Clone (or download) the repo and open it in a browser:

```bash
git clone https://github.com/LiftedHoldings/lifted-shipkit.git
# then just open demo/index.html — double-click it, or:
#   macOS:   open   demo/index.html
#   Linux:   xdg-open demo/index.html
#   Windows: start  demo/index.html
```

You'll get the full drop-in widget — address entry, live rate compare, the
cyan **3-D Secure** challenge moment, and the label + QR result — running
entirely in the page. Nothing to sign up for. When you're ready to buy real
labels, pick a path below.

---

## One-command demo (Docker)

Already have Docker and just want the server up? Pull and run the published image — no clone, no JDK, no build:

```bash
docker run --rm -e SHIPKIT_PORT=8080 -p 8080:8080 ghcr.io/liftedholdings/lifted-shipkit
```

Then open **http://localhost:8080**. CI builds and pushes this image to the GitHub Container Registry (`ghcr.io/liftedholdings/lifted-shipkit`) on every release tag and every push to `main`, tagged `latest`, `sha-<commit>`, and the semver of tagged releases. It comes up with the widget and demo pages served immediately; shipping and payments respond `503` until you pass real credentials (`-e EASYPOST_API_KEY=…`, `-e LIFTED_PAYMENTS_BEARER=…`, etc. — the same variables as the [self-host env table](#1-clone-and-configure)).

> **Make the package public after the first CI publish.** GitHub Container Registry packages are **private by default**, so an anonymous `docker pull` (and the command above) will fail with `denied`/`unauthorized` until a maintainer flips the package to public. This is a **one-time** step, done once after the first successful `docker-publish` workflow run:
>
> 1. Open the repo on GitHub → **Packages** (right sidebar) → the **`shipkit`** container package.
> 2. **Package settings** → **Danger Zone** → **Change visibility** → **Public** → confirm.
>
> After that, `docker pull ghcr.io/liftedholdings/lifted-shipkit` works for anyone with no login. Until then, an authenticated pull still works: `echo $GITHUB_TOKEN | docker login ghcr.io -u <your-username> --password-stdin`.

---

- [Try it now — no keys](#try-it-now--no-keys-no-account)
- [One-command demo (Docker)](#one-command-demo-docker)
- [Self-host (free, MIT)](#self-host-free-mit)
- [Managed (plug-and-play)](#managed-plug-and-play)
- [Verify it works](#verify-it-works)
- [Next steps](#next-steps)

---

## Self-host (free, MIT)

Run the Kotlin backend on your own infrastructure. You bring an EasyPost key and a Lifted Payments 3-D Secure merchant account.

### Prerequisites

- JDK 17
- An [EasyPost](https://www.easypost.com/) API key
- A Lifted Payments 3-D Secure merchant account — [apply at liftedholdings.com/payments](https://liftedholdings.com/payments)

### 1. Clone and configure

```bash
git clone https://github.com/LiftedHoldings/lifted-shipkit.git
cd shipkit
cp .env.example .env
```

Open `.env` and fill in your keys. The essentials — these are the exact names ShipKit reads, so a filled-in copy of `.env.example` boots as-is:

| Variable | What it is |
|---|---|
| `SHIPKIT_PORT` | Port the server listens on (default `8080`) |
| `SHIPKIT_BASE_URL` | Public base URL of this deployment — used for 3-D Secure return URLs and absolute links (default `http://localhost:8080`) |
| `SHIPKIT_CORS_ORIGINS` | Comma-separated allowed origins for the widget (`*` in dev) |
| `EASYPOST_API_KEY` | Your EasyPost API key (shipping features return `503` until set) |
| `LIFTED_PAYMENTS_API_BASE` | Gateway host for payment processing (default `https://gateway.maverickpayments.com`) |
| `LIFTED_PAYMENTS_DASHBOARD_BASE` | Dashboard host for hosted-fields tokens / vault (default `https://dashboard.maverickpayments.com`) |
| `LIFTED_PAYMENTS_BEARER` | Bearer token issued with your merchant account (payments return `503` until set) |
| `LIFTED_PAYMENTS_TERMINAL_ID` | Terminal id — sent on every money call |
| `LIFTED_PAYMENTS_DBA_ID` | DBA id from your merchant account |
| `SHIPKIT_STORE` | `memory` (default) or `postgres` |
| `SHIPKIT_DATABASE_URL` | Postgres DSN when `SHIPKIT_STORE=postgres`, e.g. `jdbc:postgresql://host:5432/shipkit?sslmode=require` |
| `SHIPKIT_DB_USER` / `SHIPKIT_DB_PASSWORD` | Postgres credentials |

Every setting is read from the environment — no credentials live in the code. 3-D Secure is always enforced; there is no flag to disable it. A filled-in `.env.example` boots the server as-is, but the widget also needs a ShipKit **API key** (next step) — every `/api/*` call requires it. See [`.env.example`](../.env.example) for the complete, documented list, including the optional Postgres and Twilio SMS settings (`SHIPKIT_SMS_ENABLED`, `TWILIO_*`) and the admin allowlist (`SHIPKIT_ADMIN_PHONES`).

### 2. Build, mint a key, and run

```bash
./gradlew build                          # compiles + runs tests
./gradlew shipkitKeygen -Plabel=my-store # mint a ShipKit API key — printed once, copy it now
./gradlew run                            # starts the API + widget on http://localhost:8080
```

Every `/api/*` route requires a `ShipKit-Api-Key` header, so mint a key before embedding the
widget — see [Authentication](authentication.md) for details. Prefer containers:

```bash
docker compose up                                                          # in-memory store (default)
```

To run on the bundled PostgreSQL instead, layer the Postgres override — one command starts the database, waits for it to be healthy, and boots the app with `SHIPKIT_STORE=postgres`:

```bash
docker compose --profile postgres -f docker-compose.yml -f docker-compose.postgres.yml up --build
```

> A Compose *profile* alone can't do this: `docker compose --profile postgres up` starts Postgres but leaves the app on the in-memory store (a profile can't change another service's environment), so it silently ignores the database. The override flips the app's store env — that's why both the `--profile postgres` flag (starts the db) and the override file (points the app at it) are needed. For production, point `SHIPKIT_DATABASE_URL` at a managed Postgres with `sslmode=require` rather than the bundled container.

### 3. Embed the widget

Add a mount point and initialize the widget on any page where customers buy a label. The
widget attaches a global `ShipKit`, so load it with a plain `<script>` tag — not an ES
`import`:

```html
<div id="ship"></div>
<script src="/js/shipkit.js"></script>
<script>
  ShipKit.init({
    mount: '#ship',
    endpoint: '/api',              // talks to your backend
    apiKey: 'pk_live_your_publishable_key', // publishable widget key — sent as the ShipKit-Api-Key header
    onQuote:    rates  => console.log('rates', rates),
    onPurchase: label  => console.log('label', label),
    onError:    err    => console.error(err),
  });
</script>
```

That's it. The widget handles address entry and verification, live rate compare, the 3-D Secure card step, and the label result.

> **Which key?** The widget runs in the browser, so it takes a **publishable** `pk_…` key — safe to expose because the backend confines it to the customer flow. Mint one with `--publishable`. Your **secret** `sk_…` key (the default) is for server-side and admin calls only and must never appear in client code. See [Authentication](authentication.md).

---

## Managed (plug-and-play)

No backend, no API keys, no PCI scope. Lifted runs the infrastructure and payments; you add one tag.

### 1. Get a managed key

[Create your free account → get the JS at liftedholdings.com/shipkit/start](https://liftedholdings.com/shipkit/start). No merchant-account application — just your name, email, and company. You'll receive a publishable managed key that looks like `pk_live_...` — safe to embed in the browser.

### 2. Drop in the script

```html
<div id="ship"></div>
<script
  src="https://cdn.liftedholdings.com/shipkit.js"
  integrity="sha384-REPLACE_WITH_PUBLISHED_SRI_HASH"
  crossorigin="anonymous"
  data-managed-key="pk_live_your_publishable_key"></script>
```

The `integrity` and `crossorigin` attributes pin the script to a published, verified build. Copy the exact Subresource Integrity hash for your version from the [releases page](https://github.com/LiftedHoldings/lifted-shipkit/releases).

> **Placeholders:** the CDN host and `integrity` hash above are placeholders until the first published release. A browser will not run a script whose SRI hash doesn't match, so the tag stays inert until you drop in the real host and hash from your managed account. That is expected before launch — not an error on your end.

Prefer to configure in JavaScript instead of markup? The widget is a UMD global — load it with a `<script src>` tag, then call `ShipKit.init`:

```html
<div id="ship"></div>
<script src="https://cdn.liftedholdings.com/shipkit.js"
        integrity="sha384-REPLACE_WITH_PUBLISHED_SRI_HASH"
        crossorigin="anonymous"></script>
<script>
  ShipKit.init({
    mount: '#ship',
    managedKey: 'pk_live_your_publishable_key',   // routes to the managed Lifted endpoint
    onPurchase: label => console.log('label', label),
  });
</script>
```

Rates, labels, and 3-D Secure card payment all route through the managed Lifted endpoint. The free tooling stays free via a small markup on the shipping rate — no per-label fee, no monthly minimum. See [docs/managed.md](managed.md) for the details, or [docs/tiers.md](tiers.md) for all three tiers.

---

## Verify it works

1. Load the page with the widget mounted.
2. Enter a shipping address — the widget verifies and normalizes it.
3. Compare the live carrier rates that appear and pick one.
4. Complete the 3-D Secure card step. In self-host, use your processor's test card and sandbox key.
5. Confirm the label (PDF/PNG/ZPL) and QR code render in the result view.

Watch the `onQuote`, `onPurchase`, and `onError` callbacks in your console to trace the flow.

---

## Next steps

- **[Integration guide](integration.md)** — full config table, callback reference, and React/Vue snippets.
- **[Architecture](architecture.md)** — how the backend modules fit together.
- **[API reference](api.md)** — the HTTP endpoints under `/api`.
- **[3-D Secure](3d-secure.md)** — why authenticated payments shift liability to the issuer, and how to apply.

Stuck? See [SUPPORT.md](../SUPPORT.md) or email [support@liftedholdings.com](mailto:support@liftedholdings.com).

---

Maintained by Daniel Wilson Kemp · Lifted Holdings.
