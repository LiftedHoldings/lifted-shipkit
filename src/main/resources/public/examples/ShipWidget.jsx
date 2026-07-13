/**
 * ShipKit · React example
 * ========================
 * A thin wrapper component. ShipKit is framework-agnostic and manages its own
 * DOM inside the mount node, so React just owns the container ref and the
 * widget lifecycle.
 *
 * Install the script one of two ways:
 *   - Self-host: copy shipkit.js into /public and load it once (see index.html),
 *     or `import '/js/shipkit.js'` if bundled — it attaches window.ShipKit.
 *   - Managed:   add the <script src="https://cdn.liftedholdings.com/shipkit.js"
 *                integrity="sha384-…" crossorigin="anonymous"> tag to index.html
 *                (without data-mount, so it does NOT auto-init).
 *
 * Then render <ShipWidget /> anywhere.
 */
import { useEffect, useRef } from 'react';

export default function ShipWidget({
  endpoint = '/api',
  apiKey,
  managedKey,
  theme = 'dark',
  from,
  parcel,
  onQuote,
  onPurchase,
  onError,
}) {
  const mountRef = useRef(null);

  useEffect(() => {
    if (!mountRef.current || !window.ShipKit) return;

    const instance = window.ShipKit.init({
      mount: mountRef.current,
      // Pass managedKey OR endpoint(+apiKey) — managedKey routes to Lifted's hosted
      // API. Both are ShipKit publishable pk_live_… keys (browser-safe) sent as the ShipKit-Api-Key header.
      ...(managedKey ? { managedKey } : { endpoint, apiKey }),
      theme,
      from,
      parcel,
      onQuote,
      onPurchase,
      onError,
    });

    // The widget owns its subtree; clear it on unmount.
    return () => {
      if (mountRef.current) mountRef.current.innerHTML = '';
    };
    // Re-init only when the connection identity changes.
  }, [endpoint, apiKey, managedKey, theme]);

  return <div ref={mountRef} aria-label="ShipKit shipping" />;
}

/*
 * Usage:
 *
 *   <ShipWidget
 *     endpoint="/api"
 *     theme="dark"
 *     onPurchase={(label) => router.push(label.trackingUrl)}
 *     onError={(err) => toast.error(err.message)}
 *   />
 *
 *   // Self-host with a key:
 *   <ShipWidget endpoint="/api" apiKey="pk_live_…" onPurchase={handleLabel} />
 *
 *   // Managed tier:
 *   <ShipWidget managedKey="pk_live_…" onPurchase={handleLabel} />
 */
