/**
 * ShipKit · React + TypeScript example
 * ====================================
 * A typed wrapper component. Pairs with the type definitions in `shipkit.d.ts`
 * (copy it into your project, e.g. `types/shipkit.d.ts`, so `window.ShipKit`
 * and the payloads below are typed with no import).
 *
 * shipkit.js is a UMD global — load it once with a <script src="/js/shipkit.js">
 * tag in your HTML (self-host) or the managed CDN tag. It attaches window.ShipKit.
 */
import { useEffect, useRef } from 'react';
import type {
  ShipKitAddress,
  ShipKitParcel,
  ShipKitTheme,
  ShipKitWidget,
  PurchaseResult,
  QuoteResult,
  ShipKitError,
} from './shipkit';

export interface ShipWidgetProps {
  endpoint?: string;
  /** Publishable pk_… key (self-host). Never a secret sk_… key. */
  apiKey?: string;
  /** Publishable pk_… key (managed). Provide this OR endpoint+apiKey. */
  managedKey?: string;
  theme?: ShipKitTheme;
  from?: ShipKitAddress;
  parcel?: ShipKitParcel;
  onQuote?: (quote: QuoteResult) => void;
  onPurchase?: (label: PurchaseResult) => void;
  onError?: (error: ShipKitError) => void;
}

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
}: ShipWidgetProps) {
  const mountRef = useRef<HTMLDivElement>(null);
  const widgetRef = useRef<ShipKitWidget | null>(null);

  useEffect(() => {
    if (!mountRef.current || !window.ShipKit) return;

    widgetRef.current = window.ShipKit.init({
      mount: mountRef.current,
      // Provide EXACTLY ONE of managedKey OR endpoint(+apiKey) — the config union
      // in shipkit.d.ts enforces this at compile time.
      ...(managedKey ? { managedKey } : { endpoint, apiKey }),
      theme,
      from,
      parcel,
      onQuote,
      onPurchase,
      onError,
    });

    return () => widgetRef.current?.destroy();
    // Re-init only when the connection identity changes.
  }, [endpoint, apiKey, managedKey, theme]);

  return <div ref={mountRef} aria-label="ShipKit shipping" />;
}

/*
 * Usage:
 *
 *   <ShipWidget
 *     endpoint="/api"
 *     apiKey="pk_live_your_publishable_key"
 *     onPurchase={(label) => router.push(label.trackingUrl ?? '/orders')}
 *     onError={(err) => toast.error(err.message)}
 *   />
 *
 *   // Managed tier (no backend):
 *   <ShipWidget managedKey="pk_live_your_publishable_key" onPurchase={handleLabel} />
 */
