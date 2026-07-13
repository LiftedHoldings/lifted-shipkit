'use client';
/**
 * ShipKit · Next.js (App Router) example
 * ======================================
 * The widget is a UMD global (`window.ShipKit`), not an ES module, so you load
 * it with `next/script` rather than `import`. Two things matter in Next:
 *
 *   1. `'use client'` — the widget touches the DOM, so it only runs in a Client
 *      Component. A Server Component can render <ShipWidget /> as a child.
 *   2. `next/script` with `strategy="afterInteractive"` — loads shipkit.js once,
 *      after hydration, and fires `onLoad` when `window.ShipKit` is ready.
 *
 * Self-host serves shipkit.js from your ShipKit backend at /js/shipkit.js.
 * Managed loads it from the CDN (add integrity + crossorigin; see cdn.html).
 */
import { useEffect, useRef, useState } from 'react';
import Script from 'next/script';

export default function ShipWidget({
  endpoint = '/api',
  apiKey,
  managedKey,
  theme = 'dark',
  from,
  parcel,
  onPurchase,
  onError,
  // Point this at your own backend's copy of the script, or the managed CDN URL.
  src = '/js/shipkit.js',
}) {
  const mountRef = useRef(null);
  const widgetRef = useRef(null);
  const [ready, setReady] = useState(
    typeof window !== 'undefined' && !!window.ShipKit,
  );

  useEffect(() => {
    if (!ready || !mountRef.current || !window.ShipKit) return;

    widgetRef.current = window.ShipKit.init({
      mount: mountRef.current,
      // Pass managedKey OR endpoint(+apiKey). Both are publishable pk_… keys.
      ...(managedKey ? { managedKey } : { endpoint, apiKey }),
      theme,
      from,
      parcel,
      onPurchase,
      onError,
    });

    return () => widgetRef.current?.destroy();
    // Re-init only when the connection identity changes.
  }, [ready, endpoint, apiKey, managedKey, theme]);

  return (
    <>
      <Script
        src={src}
        strategy="afterInteractive"
        onLoad={() => setReady(true)}
      />
      <div ref={mountRef} aria-label="ShipKit shipping" />
    </>
  );
}

/*
 * Usage (in any page/component):
 *
 *   // app/checkout/page.jsx  (a Server Component can render this client child)
 *   import ShipWidget from '@/components/ShipWidget';
 *
 *   export default function Checkout() {
 *     return (
 *       <ShipWidget
 *         endpoint="/api"
 *         apiKey="pk_live_your_publishable_key"
 *         onPurchase={(label) => console.log('bought', label.trackingCode)}
 *       />
 *     );
 *   }
 *
 *   // Managed tier (no backend): load from the CDN and pass a managed key.
 *   <ShipWidget
 *     src="https://cdn.liftedholdings.com/shipkit.js"
 *     managedKey="pk_live_your_publishable_key"
 *   />
 */
