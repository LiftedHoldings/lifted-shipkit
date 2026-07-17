# Drop-in JavaScript shipping widget — integration guide

**Import Lifted ShipKit shipping into your own app or checkout.** `shipkit.js` is a dependency-free
drop-in widget: address entry, live multi-carrier rate compare, a 3-D Secure card step, and a
label/QR result — one script, no build step. It loads as a UMD global (`window.ShipKit`) via a
plain `<script>` tag — no bundler and no ES `import` required. Keep your app, your checkout, and
your stack; ShipKit owns only the rate → pay → label step, handing the finished label back
through callbacks. Copy-paste [framework snippets](#framework-snippets) for React, Vue, and
plain HTML are below.

There are two ways to run it, one API:

- **Self-host** — the widget talks to your own ShipKit backend.
- **ShipKit Managed** — the widget talks to Lifted's managed edge; you run no backend and
  hold no API keys. See [Managed tier](managed.md).

## Quickstart — 60 seconds

### Managed (no backend)

Drop one tag on the page. The `data-managed-key` attribute auto-initializes the widget.

```html
<div id="ship"></div>
<script
  src="https://cdn.liftedholdings.com/shipkit.js"
  integrity="sha384-REPLACE_WITH_PUBLISHED_SRI_HASH"
  crossorigin="anonymous"
  data-managed-key="pk_live_your_publishable_key"
  data-mount="#ship"></script>
```

The `data-managed-key` is a **publishable** ShipKit `pk_live_…` key Lifted issues — safe to
embed in page source because the backend confines it to the customer flow. The widget sends
it as the `ShipKit-Api-Key` header on every call. Never put a **secret** `sk_…` key in the
browser. See [Authentication](authentication.md).

The `integrity` and `crossorigin` attributes pin the script to a published, verified build —
copy the exact Subresource Integrity hash for your version from the
[releases page](https://github.com/LiftedHoldings/lifted-shipkit/releases). Create a free account and
get a managed key at **[liftedholdings.com/shipkit/start](https://liftedholdings.com/shipkit/start)** — no
merchant-account application.

### Self-host (your backend)

Serve `shipkit.js` from your ShipKit deployment (`/js/shipkit.js`) and point it at your API.

```html
<div id="ship"></div>
<script src="/js/shipkit.js"></script>
<script>
  ShipKit.init({
    mount: '#ship',
    endpoint: '/api',
    apiKey: 'pk_live_your_publishable_key'   // publishable widget key — sent as the ShipKit-Api-Key header
  });
</script>
```

That's it. The widget renders, verifies addresses, compares live rates, runs the 3-D
Secure card step, and shows the label with a scannable QR code. The browser widget uses a
**publishable** `pk_…` key (safe to expose); mint one with `KeygenKt --label=web-widget
--publishable`. Keep your **secret** `sk_…` key (the default scope) on the server only — see
[Authentication](authentication.md).

## Configuration

Pass options to `ShipKit.init(options)`, or set the `data-*` equivalents on the script tag.

| Option | `data-*` attribute | Type | Default | Description |
|---|---|---|---|---|
| `mount` | `data-mount` | string \| Element | — (required) | CSS selector or element to render into. |
| `endpoint` | `data-endpoint` | string | `/api` | Base path of your ShipKit backend. Self-host only. |
| `apiKey` | `data-api-key` | string | — | Self-host **publishable** ShipKit key (`pk_live_…`/`pk_test_…`) — safe in the browser. Sent as the `ShipKit-Api-Key` header. |
| `managedKey` | `data-managed-key` | string | — | Managed **publishable** ShipKit key (`pk_live_…`). Routes to the Lifted managed edge; omit `endpoint`. Also sent as `ShipKit-Api-Key`. |
| `theme` | `data-theme` | `'auto' \| 'light' \| 'dark'` | `'auto'` | Color theme. `auto` follows `prefers-color-scheme`. |
| `defaultCountry` | `data-default-country` | string | `'US'` | ISO country pre-filled in the address form. |
| `from` | — | object | — | Pre-fill the from-address (name, company, street1, city, state, zip, country, phone). |
| `parcel` | — | object | — | Pre-fill parcel dimensions (`length`, `width`, `height` in inches; `weight` in ounces). |
| `carriers` | `data-carriers` | string[] | all | Restrict the rate list to these carriers (e.g. `["USPS","UPS"]`). |
| `onQuote` | — | function | — | Called when rates are returned. See callbacks. |
| `onPurchase` | — | function | — | Called when a label is successfully purchased. |
| `onError` | — | function | — | Called on any error. |

Provide exactly one of `endpoint` (self-host) or `managedKey` (managed).

## Callbacks

Register callbacks in `init`, or add them later with `widget.on(event, handler)`.
`ShipKit.init` returns the widget instance.

```js
const widget = ShipKit.init({
  mount: '#ship',
  endpoint: '/api',

  // Fired each time rates are quoted for the entered shipment.
  onQuote({ shipmentId, rates }) {
    console.log(`${rates.length} rates for ${shipmentId}`);
  },

  // Fired once a label is bought (after 3-D Secure authorizes the card).
  onPurchase({ carrier, service, trackingCode, labelUrl, qrCodeUrl, trackingUrl }) {
    console.log('Bought', carrier, service, '→', trackingCode);
  },

  // Fired on any failure: address, rating, payment, or purchase.
  onError({ stage, message }) {
    console.warn('ShipKit error at', stage, '-', message);
  }
});
```

### Callback payloads

| Event | Payload | Fired when |
|---|---|---|
| `onQuote` | `{ shipmentId, rates: Rate[] }` | Rates are returned for the entered shipment. |
| `onPurchase` | `{ carrier, service, trackingCode, labelUrl, qrCodeUrl, trackingUrl }` | Payment is authorized and the label is bought. `trackingUrl` may be `null`. |
| `onError` | `{ stage: 'address' \| 'rates' \| 'payment' \| 'purchase', message }` | Any step fails. |

A `Rate` is `{ id, carrier, service, rate, currency, delivery_days }`.

## Instance methods

`ShipKit.init(...)` returns the widget instance. It exposes:

```js
const widget = ShipKit.init({ mount: '#ship', endpoint: '/api' });

widget.on('purchase', handler);   // subscribe to an event: 'quote' | 'purchase' | 'error'
widget.destroy();                 // unmount, remove listeners, and release the mount node
```

## Theming

The widget is styled entirely with CSS custom properties (the `--sk-*` variables), scoped to
its root. Override any of them to match your brand — no build step, no `!important`. The
defaults map to the Lifted design system's blue brand.

```css
#ship {
  --sk-accent: #2E6BFF;         /* primary actions (Lifted blue) */
  --sk-accent-hover: #5AA6FF;
  --sk-accent-deep: #1E4FD6;
  --sk-secure: #19C7F5;         /* 3-D Secure trust / secured accent (cyan) */
  --sk-success: #00E18C;
  --sk-bg: #0B1020;             /* page (ink-900) */
  --sk-surface: #141D31;        /* card (ink-800) */
  --sk-border: #202C46;         /* raised / border (ink-700) */
  --sk-text: #EFF3F9;           /* strong body (mist-100) */
  --sk-muted: #6B7C9C;          /* muted (slate-400) */
  --sk-radius: 12px;
  --sk-font: 'Inter', ui-sans-serif, -apple-system, "Segoe UI", Roboto, sans-serif;
  --sk-font-mono: 'JetBrains Mono', ui-monospace, "SF Mono", Menlo, monospace;
}
```

The widget is accessible out of the box: labeled fields, visible focus states, and full
keyboard operation.

## Framework snippets

The widget is framework-agnostic — it renders into a plain DOM node. Mount it wherever your
framework gives you a stable element.

### Plain HTML

```html
<div id="ship"></div>
<script src="/js/shipkit.js"></script>
<script>
  ShipKit.init({ mount: '#ship', endpoint: '/api', apiKey: 'pk_live_your_publishable_key' });
</script>
```

### React

`shipkit.js` is a UMD global, not an ES module — load it once with a `<script src="/js/shipkit.js">`
tag in your HTML (or inject it), then use `window.ShipKit`.

```jsx
import { useEffect, useRef } from 'react';
// Load shipkit.js via <script src="/js/shipkit.js"> in your page; it attaches window.ShipKit.

export function ShipWidget({ onPurchase }) {
  const ref = useRef(null);

  useEffect(() => {
    const widget = window.ShipKit.init({
      mount: ref.current,
      endpoint: '/api',
      apiKey: 'pk_live_your_publishable_key',
      onPurchase
    });
    return () => widget.destroy();
  }, [onPurchase]);

  return <div ref={ref} />;
}
```

### Vue 3

Load `shipkit.js` with a `<script src="/js/shipkit.js">` tag in your page (it attaches
`window.ShipKit`), then:

```vue
<script setup>
import { onMounted, onBeforeUnmount, ref } from 'vue';

const el = ref(null);
let widget;

onMounted(() => {
  widget = window.ShipKit.init({
    mount: el.value,
    endpoint: '/api',
    apiKey: 'pk_live_your_publishable_key',
    onPurchase: (r) => console.log('bought', r.trackingCode)
  });
});

onBeforeUnmount(() => widget?.destroy());
</script>

<template>
  <div ref="el"></div>
</template>
```

### Next.js (App Router)

The widget is a UMD global, so load it with `next/script` (not `import`) inside a
Client Component. `strategy="afterInteractive"` loads it after hydration and
fires `onLoad` when `window.ShipKit` is ready.

```jsx
'use client';
import { useEffect, useRef, useState } from 'react';
import Script from 'next/script';

export default function ShipWidget({ managedKey }) {
  const ref = useRef(null);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    if (!ready || !ref.current) return;
    const widget = window.ShipKit.init({ mount: ref.current, managedKey });
    return () => widget.destroy();
  }, [ready, managedKey]);

  return (
    <>
      <Script src="https://cdn.liftedholdings.com/shipkit.js"
              strategy="afterInteractive" onLoad={() => setReady(true)} />
      <div ref={ref} />
    </>
  );
}
```

### Svelte

```svelte
<script>
  import { onMount, onDestroy } from 'svelte';
  let mount, widget;
  onMount(() => { widget = window.ShipKit.init({ mount, endpoint: '/api', apiKey: 'pk_live_…' }); });
  onDestroy(() => widget?.destroy());
</script>

<div bind:this={mount}></div>
```

### TypeScript

Copy [`shipkit.d.ts`](../src/main/resources/public/examples/shipkit.d.ts) into
your project (e.g. `types/shipkit.d.ts`) to get autocomplete and type-checking on
`ShipKit.init(...)`, the config union (`endpoint`+`apiKey` **xor** `managedKey`),
and the `Rate` / `PurchaseResult` / `QuoteResult` payloads. `window.ShipKit` is
then typed with no import; a full typed React wrapper is in
[`ShipWidget.tsx`](../src/main/resources/public/examples/ShipWidget.tsx).

### Server-side (Node / Express)

Buying labels from your own backend — order webhooks, an admin tool, a batch job
— uses the same [REST API](api.md) server-to-server with a **secret** `sk_…` key
(never the browser's publishable key). A complete worked example
([`server-node.js`](../src/main/resources/public/examples/server-node.js)) does
verify → create shipment → buy the cheapest rate:

```js
const res = await fetch(`${SHIPKIT_URL}/api/shipment/buy`, {
  method: 'POST',
  headers: { 'content-type': 'application/json', 'ShipKit-Api-Key': process.env.SHIPKIT_SECRET_KEY },
  body: JSON.stringify({ shipment_id: shipmentId, rate_id: rateId }),
});
```

For a card-secured, 3-D Secure purchase, drive the `/api/payment/*` flow from the
browser widget instead — 3DS needs the cardholder present.

### Copy-paste example files

Full, commented versions of every snippet above live in the repo — copy the one
you need:

| Framework | File |
|---|---|
| Plain HTML | [`examples/plain.html`](../src/main/resources/public/examples/plain.html) |
| Managed CDN | [`examples/cdn.html`](../src/main/resources/public/examples/cdn.html) |
| React | [`examples/ShipWidget.jsx`](../src/main/resources/public/examples/ShipWidget.jsx) |
| React + TypeScript | [`examples/ShipWidget.tsx`](../src/main/resources/public/examples/ShipWidget.tsx) |
| Next.js (App Router) | [`examples/ShipWidget.next.jsx`](../src/main/resources/public/examples/ShipWidget.next.jsx) |
| Vue 3 | [`examples/ShipWidget.vue`](../src/main/resources/public/examples/ShipWidget.vue) |
| Svelte | [`examples/ShipWidget.svelte`](../src/main/resources/public/examples/ShipWidget.svelte) |
| Node / Express (server-side) | [`examples/server-node.js`](../src/main/resources/public/examples/server-node.js) |
| TypeScript definitions | [`examples/shipkit.d.ts`](../src/main/resources/public/examples/shipkit.d.ts) |

## How it maps to the API

Under the hood the widget calls the [REST API](api.md) in order: `address/verify` →
`shipment/create` (+ optional `shipment/smartrates`) → `payment/session` → 3-D Secure
tokenized hosted fields → poll `payment/status` (server-verified) → `payment/purchase-label`.
You never touch card data — it is entered in Lifted Payments' hosted, tokenized fields. See
[3-D Secure explained](3d-secure.md).

## Next steps

- No backend? Use **[ShipKit Managed](managed.md)** and skip infrastructure entirely.
- Self-hosting? You need a 3-D Secure merchant account so payments settle to you —
  **apply at [liftedholdings.com/payments](https://liftedholdings.com/payments)**.

---

Maintained by Daniel Wilson Kemp · Lifted Holdings.
