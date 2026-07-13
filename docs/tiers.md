# Tiers & pricing

ShipKit ships in **three tiers**. They differ only in how much you run yourself and where
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
| Get started | [GitHub](https://github.com/Lifted-Holdings/shipkit) | [Apply →](https://liftedholdings.com/payments) | [Get a managed key →](https://liftedholdings.com/payments) |

¹ Surchargeable — see [Tier 2](#tier-2--lifted-3-d-secure-merchant-account) below.
² Configurable shipping-rate markup — see [Tier 3](#tier-3--fully-managed-plug--play) below.

---

## Tier 1 · Self-host (DIY, free)

Clone the repository and run the Kotlin backend on your own infrastructure. You plug in
**your own payments** (your own 3-D Secure merchant account) and **your own EasyPost**
account. You get full control of every layer — and you do the most work.

- **Cost:** free. MIT licensed. You pay only your own carrier and processing costs.
- **You bring:** an EasyPost API key and a 3-D Secure merchant account.
- **You run:** the backend, the database (optional), and the deployment.

```bash
git clone https://github.com/Lifted-Holdings/shipkit.git
cd shipkit
cp .env.example .env      # add EASYPOST_API_KEY + your own Lifted Payments 3DS keys
./gradlew run
```

Full walkthrough: **[quickstart.md](quickstart.md)**.

> **Get started:** [GitHub](https://github.com/Lifted-Holdings/shipkit) · need a hand?
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
- **Bring your own EasyPost.** Carrier rates settle to your EasyPost account; ShipKit adds no
  markup on the shipping rate in this tier.

> **Get started:** [Apply → liftedholdings.com/payments](https://liftedholdings.com/payments).

---

## Tier 3 · Fully managed (plug & play)

The turnkey option. Drop **one JavaScript snippet** on your page and everything runs on our
rails — **our 3-D Secure merchant account and our EasyPost account.** Zero infrastructure,
free hosting, no API keys on your servers, no PCI scope for card data. Best for teams that
just want it to work.

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

See **[managed.md](managed.md)** for the full managed walkthrough.

> **Get started:** [Get a managed key → liftedholdings.com/payments](https://liftedholdings.com/payments).

---

## Custom integration & software development

Want ShipKit woven into your own framework, a bespoke checkout, or a custom build on top of
the API? We do that. Tell us what you're building and we'll scope it.

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
