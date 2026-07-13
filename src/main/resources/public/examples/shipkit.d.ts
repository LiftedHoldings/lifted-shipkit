/**
 * ShipKit widget — TypeScript type definitions
 * ============================================
 * `shipkit.js` is a dependency-free UMD global (`window.ShipKit`). These types
 * give you autocomplete and type-checking for `ShipKit.init(...)`, the config
 * union, the `Rate` / purchase payloads, and the widget instance.
 *
 * Use it one of two ways:
 *
 *   1. Ambient global (script-tag / managed CDN). Drop this file anywhere your
 *      tsconfig `include`s (e.g. `types/shipkit.d.ts`). `window.ShipKit` is then
 *      typed with no import.
 *
 *   2. As a module. `import type { ShipKitConfig, Rate } from './shipkit';`
 *      to type your own wrapper props.
 *
 * These mirror docs/integration.md and the JSDoc in js/shipkit.js. If the widget
 * config changes, update both.
 */

/** A US/international address. `street1`, `city`, `state`, `zip` are required. */
export interface ShipKitAddress {
  name?: string;
  company?: string;
  street1: string;
  street2?: string;
  city: string;
  state: string;
  zip: string;
  /** ISO 3166-1 alpha-2, e.g. "US". Defaults to `defaultCountry` / "US". */
  country?: string;
  phone?: string;
}

/** Parcel dimensions. Lengths in inches, `weight` in ounces. */
export interface ShipKitParcel {
  length: number;
  width: number;
  height: number;
  /** Weight in OUNCES (not grams or pounds). */
  weight: number;
}

/** A single carrier rate. `rate` is a decimal STRING — never float-compare it. */
export interface Rate {
  id: string;
  carrier: string;
  service: string;
  /** Decimal string, e.g. "8.42". Parse as a decimal; sort by value, not text. */
  rate: string;
  currency: string;
  /** Estimated transit days; may be null. */
  delivery_days: number | null;
}

/** Payload for the `quote` event / `onQuote` callback. */
export interface QuoteResult {
  shipmentId: string;
  rates: Rate[];
}

/** Payload for the `purchase` event / `onPurchase` callback. */
export interface PurchaseResult {
  carrier: string;
  service: string;
  trackingCode: string;
  labelUrl: string;
  qrCodeUrl: string;
  /** Carrier tracking page; may be null. */
  trackingUrl: string | null;
}

/** Known error stages. Widen with `string` for forward-compat. */
export type ShipKitErrorStage =
  | 'init'
  | 'address'
  | 'rates'
  | 'payment'
  | 'purchase'
  | 'http'
  | (string & {});

/** Error passed to the `error` event / `onError` callback. */
export interface ShipKitError extends Error {
  stage: ShipKitErrorStage;
  message: string;
}

export type ShipKitTheme = 'auto' | 'light' | 'dark';

/** Event names accepted by `widget.on(...)`. */
export type ShipKitEvent = 'quote' | 'purchase' | 'error';

/**
 * Base config shared by both modes. Provide EXACTLY ONE of:
 *   - `endpoint` (+ optional `apiKey`) for self-host, or
 *   - `managedKey` for the managed Lifted edge.
 */
interface ShipKitConfigBase {
  /** CSS selector or element to render into (required). */
  mount: string | Element;
  theme?: ShipKitTheme;
  /** ISO country pre-filled in the address form (default "US"). */
  defaultCountry?: string;
  from?: ShipKitAddress;
  parcel?: ShipKitParcel;
  /** Restrict the rate list to these carriers, e.g. ["USPS", "UPS"]. */
  carriers?: string[];
  onQuote?: (quote: QuoteResult) => void;
  onPurchase?: (label: PurchaseResult) => void;
  onError?: (error: ShipKitError) => void;
  /** Custom fetch (SSR / testing / demos). */
  fetch?: typeof fetch;
}

/** Self-host: talk to your own ShipKit backend. */
export interface ShipKitSelfHostConfig extends ShipKitConfigBase {
  /** Base path of your ShipKit backend (default "/api"). */
  endpoint?: string;
  /** Publishable pk_… key, browser-safe. Sent as the `ShipKit-Api-Key` header. */
  apiKey?: string;
  managedKey?: never;
}

/** Managed: talk to the Lifted managed edge; run no backend. */
export interface ShipKitManagedConfig extends ShipKitConfigBase {
  /** Publishable pk_… key Lifted issues. Sent as the `ShipKit-Api-Key` header. */
  managedKey: string;
  endpoint?: never;
  apiKey?: never;
}

export type ShipKitConfig = ShipKitSelfHostConfig | ShipKitManagedConfig;

/** The widget instance returned by `ShipKit.init(...)`. */
export interface ShipKitWidget {
  /** Subscribe to an event; chainable. */
  on(event: ShipKitEvent, handler: (payload: unknown) => void): ShipKitWidget;
  /** Stop polling, empty the mount, drop listeners. */
  destroy(): void;
  /** Return to a fresh flow (keeps ship-from + parcel). */
  reset(): void;
  /** Current flow state (read-only in practice). */
  readonly state: unknown;
}

/** The `window.ShipKit` global. */
export interface ShipKitStatic {
  readonly version: string;
  init(config: ShipKitConfig): ShipKitWidget;
  Error: new (message: string, stage?: ShipKitErrorStage) => ShipKitError;
}

declare global {
  interface Window {
    ShipKit: ShipKitStatic;
  }
}

declare const ShipKit: ShipKitStatic;
export default ShipKit;
