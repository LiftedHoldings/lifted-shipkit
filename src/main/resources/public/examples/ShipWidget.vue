<!--
  ShipKit · Vue 3 example (<script setup>)
  ========================================
  ShipKit manages its own DOM inside the mount element, so Vue just provides the
  container and drives the lifecycle.

  Load the script first (self-host: /js/shipkit.js in index.html; managed: the
  CDN <script src="https://cdn.liftedholdings.com/shipkit.js" integrity="sha384-…"
  crossorigin="anonymous"> without data-mount). It attaches window.ShipKit.

  Then use <ShipWidget /> anywhere.
-->
<template>
  <div ref="mount" aria-label="ShipKit shipping"></div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue';

const props = defineProps({
  endpoint: { type: String, default: '/api' },
  apiKey: { type: String, default: undefined },
  managedKey: { type: String, default: undefined },
  theme: { type: String, default: 'dark' },
  from: { type: Object, default: undefined },
  parcel: { type: Object, default: undefined },
});

const emit = defineEmits(['quote', 'purchase', 'error']);
const mount = ref(null);

onMounted(() => {
  if (!mount.value || !window.ShipKit) return;
  window.ShipKit.init({
    mount: mount.value,
    // managedKey routes to Lifted's hosted API; otherwise talk to your endpoint with
    // a self-host apiKey. Both are ShipKit publishable pk_live_… keys, browser-safe (ShipKit-Api-Key header).
    ...(props.managedKey ? { managedKey: props.managedKey } : { endpoint: props.endpoint, apiKey: props.apiKey }),
    theme: props.theme,
    from: props.from,
    parcel: props.parcel,
    onQuote: (q) => emit('quote', q),
    onPurchase: (label) => emit('purchase', label),
    onError: (err) => emit('error', err),
  });
});

onBeforeUnmount(() => {
  if (mount.value) mount.value.innerHTML = '';
});
</script>

<!--
  Usage:

    <ShipWidget
      endpoint="/api"
      theme="dark"
      @purchase="(label) => (window.location = label.labelUrl)"
      @error="(err) => notify(err.message)"
    />

    <!-- Self-host with a key: -->
    <ShipWidget endpoint="/api" api-key="pk_live_…" @purchase="onLabel" />

    <!-- Managed tier: -->
    <ShipWidget managed-key="pk_live_…" @purchase="onLabel" />
-->
