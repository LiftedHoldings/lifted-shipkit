# ShipKit Managed — hosted shipping widget, no PCI scope

ShipKit ships in [three tiers](tiers.md). This page is about the third — **fully managed**.

- **Tier 1 · Self-host (DIY, free)** — run the backend yourself, bring your own EasyPost
  account and your own 3-D Secure merchant account. Full control, your infrastructure. See
  the [integration guide](integration.md) and [architecture](architecture.md).
- **Tier 2 · Lifted 3-D Secure merchant account** — host it yourself or let us host it (both
  free), and run your payments on our 3DS merchant account. See [tiers.md](tiers.md#tier-2--lifted-3-d-secure-merchant-account).
- **Tier 3 · ShipKit Managed (plug & play)** — a drop-in hosted widget on our rails, fully
  managed by Lifted. No backend, no API keys, no PCI scope. One script tag and a managed key.
  This page.

All three are legitimate. Self-host is for teams that want control; **managed is for teams
that just want it to work.**

## What Managed is

ShipKit Managed is the exact same widget, pointed at a backend that Lifted runs for you.
When you pass a `managedKey` instead of an `endpoint`, the widget routes every call —
address verification, rating, the 3-D Secure card step, and label purchase — to Lifted's
managed edge.

That means Lifted operates, on your behalf, **our rails end to end**:

- the ShipKit backend (servers, updates, uptime, free hosting),
- **our** EasyPost carrier account and label funding,
- **our** Lifted Payments 3-D Secure payment account,
- and all card handling, so **card data never touches your systems** — you carry no PCI
  scope for it.

You ship one script tag. Everything behind it is someone else's pager.

## Self-host vs. Managed

| | Self-host (free, MIT) | ShipKit Managed |
|---|---|---|
| Backend to run | Yours | None — Lifted runs it |
| EasyPost account | Yours | Included |
| Payment account | Your 3-D Secure merchant account | Included (Lifted Payments 3-D Secure) |
| Label funding | You pre-fund EasyPost | Pre-funded |
| PCI scope for card data | Out of scope (hosted 3DS form) | Out of scope |
| API keys on your servers | Yes | None |
| Cost | Free code; your carrier + processing costs | Free — we earn on the shipping-rate markup (below) |
| Best for | Teams wanting control | Teams that want it live in minutes |
| Get started | Run the backend | Get a managed key |

## Quickstart

Drop one tag on the page. The `data-managed-key` attribute auto-initializes the widget —
no `init` call, no backend.

```html
<div id="ship"></div>
<script
  src="https://cdn.liftedholdings.com/shipkit.js"
  integrity="sha384-REPLACE_WITH_PUBLISHED_SRI_HASH"
  crossorigin="anonymous"
  data-managed-key="pk_live_your_publishable_key"
  data-mount="#ship"></script>
```

The managed key is a **publishable** `pk_live_…` key — safe to embed in page source because
the backend confines it to the customer flow (it returns `403` on any secret-only action).
The `integrity` and `crossorigin` attributes pin the script to a published, verified build.
Copy the exact Subresource Integrity hash for your version from the
[releases page](https://github.com/Lifted-Holdings/shipkit/releases).

> **Placeholders:** the CDN host and `integrity` hash shown here are placeholders until the
> first published release. A browser refuses to run a script whose SRI hash doesn't match, so
> the tag stays inert until you drop in the real host and hash from your managed account —
> expected before launch, not a mistake on your end.

Prefer to initialize in code? Pass `managedKey` instead of `endpoint`:

```html
<div id="ship"></div>
<script
  src="https://cdn.liftedholdings.com/shipkit.js"
  integrity="sha384-REPLACE_WITH_PUBLISHED_SRI_HASH"
  crossorigin="anonymous"></script>
<script>
  ShipKit.init({
    mount: '#ship',
    managedKey: 'pk_live_your_publishable_key',
    onPurchase({ trackingCode, labelUrl }) {
      console.log('Label bought:', trackingCode);
    }
  });
</script>
```

Everything else — configuration, callbacks, theming, framework snippets — is identical to
self-host. See the [integration guide](integration.md). The only difference is `managedKey`
in place of `endpoint`.

## How managed is priced — transparently

Managed makes its margin on a **markup over the carrier's shipping rate** — **no per-label
fee, no monthly minimum, and no charge for the code, the widget, or the hosting.** That
shipping-rate markup is how the free, MIT-licensed tooling stays free and how Lifted funds the
carrier accounts, payment processing, and infrastructure you no longer have to run.

- It's the **same markup engine** ShipKit uses everywhere — a percentage of the carrier rate
  plus an optional flat add-on (`percentage_markup` + `fixed_fee_cents`, see
  [`POST /api/config/markup`](api.md#markup-configuration)). On managed, Lifted configures it.
- The charge amount is always **computed server-side** from the carrier rate plus that markup —
  a client can never underpay for a label.
- The marked-up rate is **shown at checkout before the buyer pays** — nothing hidden.
- We earn **only when a label ships.** No label, no cost.
- The code, the widget, and self-hosting are and stay **free**.

If you'd rather not run on our shipping markup, use another tier: **[self-host](tiers.md#tier-1--self-host-diy-free)**
with your own EasyPost and merchant account, or the **[Lifted 3-D Secure merchant account](tiers.md#tier-2--lifted-3-d-secure-merchant-account)**
tier where you bring your own EasyPost and pay only the merchant-account pricing.

## How to get a managed key

Managed keys are issued with a Lifted Payments 3-D Secure account.

1. Apply at **[liftedholdings.com/payments](https://liftedholdings.com/payments)**.
2. Receive your managed account key — a **publishable** `pk_live_…` key, safe to embed in the browser.
3. Paste it into the script tag above. You're live.

**Get a managed key → [liftedholdings.com/payments](https://liftedholdings.com/payments)**

Questions about setup? Reach the team at
[support@liftedholdings.com](mailto:support@liftedholdings.com).

---

Maintained by Daniel Wilson Kemp · Lifted Holdings.
