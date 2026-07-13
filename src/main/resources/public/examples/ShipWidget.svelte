<!--
  ShipKit · Svelte example
  ========================
  ShipKit manages its own DOM inside the mount element, so Svelte just provides
  the container and drives the lifecycle. shipkit.js is a UMD global
  (window.ShipKit), not an ES module — load it once in your app shell:

    Self-host:  <script src="/js/shipkit.js"></script>  in app.html
    Managed:    the CDN <script src="https://cdn.liftedholdings.com/shipkit.js"
                integrity="sha384-…" crossorigin="anonymous"> tag (no data-mount,
                so it does NOT auto-init).

  Then use <ShipWidget ... /> anywhere. Events are forwarded via createEventDispatcher.
-->
<script>
  import { onMount, onDestroy, createEventDispatcher } from 'svelte';

  export let endpoint = '/api';
  export let apiKey = undefined;
  export let managedKey = undefined;
  export let theme = 'dark';
  export let from = undefined;
  export let parcel = undefined;

  const dispatch = createEventDispatcher();
  let mount;
  let widget;

  onMount(() => {
    if (!mount || !window.ShipKit) return;
    widget = window.ShipKit.init({
      mount,
      // managedKey routes to Lifted's hosted API; otherwise talk to your endpoint.
      // Both are publishable pk_live_… keys (browser-safe, ShipKit-Api-Key header).
      ...(managedKey ? { managedKey } : { endpoint, apiKey }),
      theme,
      from,
      parcel,
      onQuote: (q) => dispatch('quote', q),
      onPurchase: (label) => dispatch('purchase', label),
      onError: (err) => dispatch('error', err),
    });
  });

  onDestroy(() => widget?.destroy());
</script>

<div bind:this={mount} aria-label="ShipKit shipping"></div>

<!--
  Usage:

    <script>
      import ShipWidget from './ShipWidget.svelte';
    </script>

    <ShipWidget
      endpoint="/api"
      apiKey="pk_live_your_publishable_key"
      on:purchase={(e) => console.log('bought', e.detail.trackingCode)}
      on:error={(e) => console.warn(e.detail.stage, e.detail.message)}
    />

    <!-- Managed tier (no backend): -->
    <ShipWidget managedKey="pk_live_your_publishable_key" on:purchase={onLabel} />
-->
