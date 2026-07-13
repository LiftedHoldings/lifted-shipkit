# How ShipKit compares

Most teams that need shipping labels end up wiring two vendors together: a
multi-carrier rating API for the labels, and a payment processor for the card
charge — plus the PCI scope and fraud exposure that come with taking a card for
physical goods. ShipKit is the piece in the middle: a **multi-carrier shipping
backend and a drop-in card-payment widget in one MIT-licensed toolkit, with
3-D Secure forced on every charge.**

> **The one-line moat:** ShipKit is the only open-source, self-hostable
> multi-carrier shipping kit with **forced 3-D Secure** and a **drop-in
> checkout widget**. Shipping is where stolen-card fraud cashes out — real goods
> shipped fast, disputed weeks later — so ShipKit authenticates the cardholder
> at the issuer *before the label prints*, and the fraud/chargeback liability
> shifts off you.

## At a glance

| | **ShipKit** | Raw EasyPost | Shippo | ShipEngine | EasyPost + Stripe (roll your own) |
|---|---|---|---|---|---|
| Multi-carrier rates & labels | ✅ (via EasyPost) | ✅ | ✅ | ✅ | ✅ |
| Card payment built in | ✅ | ❌ | ❌ | ❌ | You wire Stripe yourself |
| **Forced 3-D Secure / liability shift** | ✅ **always on** | ❌ | ❌ | ❌ | Only if you build it |
| Drop-in checkout widget (no build step) | ✅ `window.ShipKit` | ❌ | ❌ | ❌ | ❌ — you build the UI |
| Framework examples (React/Vue/Svelte/Next/TS) | ✅ | n/a | n/a | n/a | You write them |
| Open-source & self-hostable | ✅ MIT | ❌ SaaS | ❌ SaaS | ❌ SaaS | Partly (your glue code) |
| Per-label / SaaS fee to use the code | **None** (self-host) | Per-label | Per-label | Per-label | Two vendors' fees |
| PCI scope for card data | Out of scope (hosted, tokenized fields) | n/a | n/a | n/a | **Yours to manage** |
| Vendor lock-in | None — read it, fork it | API lock-in | API lock-in | API lock-in | Two APIs to track |

Feature parity on the shipping side comes from EasyPost, which ShipKit uses as
its carrier backend — so you get the same USPS/UPS/FedEx coverage, with the
payment + 3DS + widget layer added on top and none of it behind a SaaS meter
when you self-host.

## vs. raw EasyPost

EasyPost is an excellent multi-carrier rating and label API — and it's what
ShipKit calls under the hood. What EasyPost does **not** give you is the money
half: no card capture, no 3-D Secure, no checkout UI. With raw EasyPost you
still have to stand up a payment processor, build the card form, and own the
fraud problem yourself. ShipKit adds **forced 3-D Secure card payment + a
drop-in widget** on top of EasyPost, so "compare rates → pay → print label" is a
few lines instead of a project.

## vs. Shippo / ShipEngine

Shippo and ShipEngine are hosted, per-label shipping SaaS. They rate and buy
labels well, but they are closed platforms you rent — you can't self-host them,
read the code, or fork them, and none of them handle the **card payment** or
force **3-D Secure**. ShipKit is **open-source and self-hostable** (MIT): run
the whole thing on your own infra with no per-label fee, or take the managed
tier if you'd rather not run anything. Either way you get the payment + 3DS layer
they don't offer.

## vs. rolling your own (EasyPost + Stripe)

The DIY route — EasyPost for labels, Stripe (or another processor) for the
charge, your own checkout UI, your own 3-D Secure integration — is a real
multi-week build, and at the end of it **you** own PCI scope and the
stolen-card chargeback exposure that shipping attracts. ShipKit is that stack,
already assembled and hardened: server-authoritative pricing, atomic idempotent
purchases, tokenized hosted card fields (card data never touches your server),
and 3-D Secure that **cannot be turned off** on self-host. Read it, fork it,
ship it.

## Choosing ShipKit

- **You want labels + card payment in one drop-in, and you self-host.** ShipKit,
  tier 1 (free, MIT). See the [Quickstart](quickstart.md).
- **You want your own processing but not the fraud/PCI burden.** ShipKit tier 2
  on a Lifted Payments 3-D Secure merchant account. See [Tiers](tiers.md).
- **You want zero infrastructure — one script tag.** ShipKit tier 3, fully
  managed. See [Managed](managed.md).

Exact pricing for all three tiers is in **[docs/tiers.md](tiers.md)**. Why the
3-D Secure angle matters for shipping specifically is in
**[docs/3d-secure.md](3d-secure.md)**.

---

Maintained by Daniel Wilson Kemp · Lifted Holdings. Take live payments through a
3-D Secure merchant account — [apply at liftedholdings.com/payments](https://liftedholdings.com/payments).
