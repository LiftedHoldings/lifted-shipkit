# Tiers & pricing

Lifted ShipKit ships in **three tiers**. They differ only in how much you run yourself and where
the money flows — the widget, the API, and the forced 3-D Secure card step are identical in
all three. Pick the one that fits, and switch later without changing your integration.

All three are powered by **[Lifted Payments 3-D Secure](3d-secure.md)** — no card charge ever
happens without an authenticated, liability-shifted result.

## At a glance

| | **1 · Self-host** · DIY, free | **2 · Lifted 3-D Secure merchant account** | **3 · Fully managed** · plug & play |
|---|---|---|---|
| What you run | Everything — your own rails | Your checkout on **our** 3DS merchant account | One JS snippet on **our** rails |
| Payments | Your own processor / merchant account | **Our** 3-D Secure merchant account | **Our** 3-D Secure account |
| EasyPost (carriers) | Your own EasyPost account | Your own EasyPost account | **Ours** — included |
| Hosting | You host the backend | **You host _or_ we host — both free** | We host — free |
| PCI scope for card data | Yours | Out of scope (hosted 3DS form) | Out of scope (hosted 3DS form) |
| Dev effort | Highest | Low — apply, plug in keys | Lowest — one tag |
| **Cost** | **Free** (MIT) | **3.75% + $0.15 / transaction + $25 / month** — the merchant account only¹ | **Free** — we earn on the shipping rate² |
| Best for | Full control | Our processing, your choice of host | "Just make it work" |
| Get started | [GitHub](https://github.com/LiftedHoldings/lifted-shipkit) | [Apply →](https://liftedholdings.com/payments) | [Create free account →](https://liftedholdings.com/shipkit/start) |

¹ Surchargeable — see [Tier 2](#tier-2--lifted-3-d-secure-merchant-account) below.
² Configurable shipping-rate markup — see [Tier 3](#tier-3--fully-managed-plug--play) below.

---

## Tier 1 · Self-host (DIY, free)

Clone the repository and run the Kotlin backend on your own infrastructure. You plug in
**your own payments** (your own 3-D Secure merchant account) and **your own EasyPost**
account. You get full control of every layer — and you do the most work.

- **Cost:** free. MIT licensed. You pay only your own carrier and processing costs.
- **The markup is yours.** You own the payments account, so you own the margin: set a
  **percentage markup and/or a fixed fee per label** (`percentage_markup` +
  `fixed_fee_cents`, applied server-side and shown at checkout) and buyers pay carrier
  rate + your markup straight into your account. Lifted takes nothing.
- **You bring:** an EasyPost API key and a 3-D Secure merchant account (don't have one?
  [apply at liftedholdings.com/payments](https://liftedholdings.com/payments)).
- **You run:** the backend, the database (optional), and the deployment.

```bash
git clone https://github.com/LiftedHoldings/lifted-shipkit.git
cd shipkit
cp .env.example .env      # add EASYPOST_API_KEY + your own Lifted Payments 3DS keys
./gradlew run
```

Full walkthrough: **[quickstart.md](quickstart.md)**.

> **Get started:** [GitHub](https://github.com/LiftedHoldings/lifted-shipkit) · need a hand?
> [support@liftedholdings.com](mailto:support@liftedholdings.com).

---

## Tier 2 · Lifted 3-D Secure merchant account

Keep the freedom to host ShipKit yourself, but run your card payments on **our** 3-D Secure
merchant account instead of sourcing your own. Apply at
[liftedholdings.com/payments](https://liftedholdings.com/payments) and we provision an
account with 3-D Secure enabled out of the box. You still bring your own EasyPost account, and
**you can host ShipKit yourself or let us host it — both are free.** The only cost is the
merchant account.

**Pricing — the merchant account only:**

| Component | Rate |
|---|---|
| Per transaction | **3.75% + $0.15** |
| Monthly | **$25 / month** |

- **Surchargeable.** The **3.75% + 15¢** processing cost can be passed to the buyer with the
  built-in **surcharge-framework toggle**, so the per-transaction fee lands on the cardholder
  instead of eating into your margin. Turn it off and absorb it yourself — your call.
- **Your choice of host, free either way.** Self-host the backend, or let Lifted host it —
  neither adds a hosting charge. The merchant-account pricing above is the whole cost.
- **Bring your own EasyPost — and keep the markup.** Carrier rates settle to your EasyPost
  account and **Lifted adds no markup in this tier**. The markup engine works for *you*
  instead: as the payments-account holder you can price labels at carrier rate + your own
  percentage and fixed fee, and that margin lands in your merchant account with every sale.
- **Frictionless + saved cards (optional).** On a Lifted merchant account you can opt into
  **frictionless checkout** (3-D Secure off) and **saved cards on file** (a tokenized customer
  vault for repeat / one-tap charges). It is a **server-side, account-level** capability —
  never a widget toggle. Self-host can't disable 3DS; see
  [forced 3-D Secure vs. frictionless mode](3d-secure.md#forced-3-d-secure-vs-frictionless-mode-account-gated).

> **Get started:** [Apply → liftedholdings.com/payments](https://liftedholdings.com/payments).

---

## Tier 3 · Fully managed (plug & play)

The turnkey option — **no merchant-account application.** You just **create a free account**
(name, email, company) and instantly get your **plug-and-play JS snippet and managed key**.
Drop that **one JavaScript snippet** on your page and everything runs on our rails — **our
3-D Secure merchant account and our EasyPost account.** Zero infrastructure, free hosting, no
API keys on your servers, no PCI scope for card data. No application, no infra — sign up, drop
in one script tag, done.

```html
<div id="ship"></div>
<script
  src="https://cdn.liftedholdings.com/shipkit.js"
  integrity="sha384-REPLACE_WITH_PUBLISHED_SRI_HASH"
  crossorigin="anonymous"
  data-managed-key="pk_live_your_publishable_key"></script>
```

**How it's priced — we earn on the shipping rate.** There is no per-label fee, no monthly
minimum, and the code stays free. Managed makes its margin on a **configurable markup over
the carrier's shipping rate** — the same markup engine used everywhere in ShipKit
(`percentage_markup` + `fixed_fee_cents`, set via
[`POST /api/config/markup`](api.md#markup-configuration)). The marked-up rate is shown at
checkout **before** the buyer pays — nothing hidden.

- Our 3-D Secure account + our EasyPost, fully managed.
- Free hosting, free tooling, free updates.
- We earn only when a label ships, on the shipping-rate markup.
- **Frictionless + saved cards (optional).** On our managed rails you can opt into
  **frictionless checkout** (3-D Secure off) and **saved cards on file** (tokenized customer
  vault) for repeat / one-tap charges — a **server-side, account-level** capability, never a
  widget toggle. Self-host stays forced-3DS; see
  [forced 3-D Secure vs. frictionless mode](3d-secure.md#forced-3-d-secure-vs-frictionless-mode-account-gated).

See **[managed.md](managed.md)** for the full managed walkthrough.

> **Get started:** [Create your free account → get the JS at liftedholdings.com/shipkit/start](https://liftedholdings.com/shipkit/start).

---

## Custom integration & software development

Want ShipKit woven into your own framework, a bespoke checkout, or a custom build on top of
the API? We do that. Tell us what you're building and we'll scope it — including enterprise
**frictionless checkout and saved-cards-on-file** (tokenized vault) tied to your account.

> **Talk to us:** **[support@liftedholdings.com](mailto:support@liftedholdings.com)**.

---

## Which tier should I pick?

- **You want total control and will do the work** → Tier 1 (self-host).
- **You want our processing but your own hosting/carriers** → Tier 2 (merchant account),
  and turn on the surcharge if you'd rather the buyer covers the processing fee.
- **You want it live in minutes with nothing to run** → Tier 3 (managed).
- **You want something none of these quite covers** → [custom development](#custom-integration--software-development).

You can start on one tier and move to another without rewriting your integration — the widget
and API are the same across all three.

---

Maintained by Daniel Wilson Kemp · Lifted Holdings.
