# ShipKit Managed — hosted shipping widget, no PCI scope

There are two ways to run ShipKit. This page is about the second one.

- **Self-host (free, MIT)** — run the backend yourself, bring your own EasyPost account and
  your own 3-D Secure merchant account. Full control, your infrastructure. See the
  [integration guide](integration.md) and [architecture](architecture.md).
- **ShipKit Managed (plug-and-play)** — a drop-in hosted widget, pre-funded and fully
  managed by Lifted. No backend, no API keys, no PCI scope. One script tag and a managed
  key. This page.

Both are legitimate. Self-host is for teams that want control; **managed is for teams that
just want it to work.**

## What Managed is

ShipKit Managed is the exact same widget, pointed at a backend that Lifted runs for you.
When you pass a `managedKey` instead of an `endpoint`, the widget routes every call —
address verification, rating, the 3-D Secure card step, and label purchase — to Lifted's
managed edge.

That means Lifted operates, on your behalf:

- the ShipKit backend (servers, updates, uptime),
- the EasyPost carrier account and label funding,
- the Lifted Payments 3-D Secure payment account,
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
| Cost | Free code; your carrier + processing costs | Per-label managed fee (below) |
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

## The managed fee — transparently

Managed adds a **flat per-label service fee** (a few cents) on top of the carrier's rate —
**no monthly minimum, and no markup on the carrier rate itself**. That fee is how the free,
MIT-licensed tooling stays free and how Lifted funds the carrier accounts, payment
processing, and infrastructure you no longer have to run.

- The fee is **per label** — you pay it only when a label is actually bought.
- It is disclosed on your managed account and shown at checkout before purchase.
- Carrier rates pass through untouched — the fee is the only add-on.
- There is no charge for the code, the widget, or self-hosting — those are and stay free.

If you would rather not pay a per-label fee, self-host: bring your own EasyPost account and
your own 3-D Secure merchant account, and you pay only your carrier and processing costs.

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
