/*!
 * ShipKit Widget v1.0.0
 * The open shipping toolkit — secured by Lifted Payments 3-D Secure.
 *
 * Dependency-free drop-in widget: address entry + verify, live multi-carrier
 * rate compare, buy-label, 3-D Secure card payment, and label/QR result.
 *
 * Two init modes, one API:
 *   Self-host : ShipKit.init({ mount: '#ship', endpoint: '/api', apiKey: 'pk_live_…' })
 *   Managed   : ShipKit.init({ mount: '#ship', managedKey: 'pk_live_…' })
 *
 * Both keys are ShipKit API keys sent in the `ShipKit-Api-Key` request header.
 *
 * Managed via a single <script> tag (example — the SRI hash is illustrative):
 *   <script
 *     src="https://cdn.liftedholdings.com/shipkit.js"
 *     integrity="sha384-PLACEHOLDER_REPLACE_WITH_REAL_HASH_FROM_RELEASE_ASSET"
 *     crossorigin="anonymous"
 *     data-managed-key="pk_live_xxx"
 *     data-mount="#ship"></script>
 *
 * Themeable via CSS custom properties (prefix --sk-, mapped to the Lifted blue
 * brand). Accessible by default. The instance returned by `ShipKit.init(...)`
 * exposes init-time callbacks (onQuote/onPurchase/onError) plus `.on(event,
 * handler)` (events: quote|purchase|error) and `.destroy()`.
 *
 * License: MIT © 2026 Daniel Wilson Kemp / Lifted Holdings.
 * Docs: https://github.com/LiftedHoldings/lifted-shipkit  ·  support@liftedholdings.com
 */
(function (root, factory) {
  'use strict';
  // UMD: CommonJS, AMD, then browser global `ShipKit`.
  if (typeof module === 'object' && module.exports) {
    module.exports = factory();
  } else if (typeof define === 'function' && define.amd) {
    define([], factory);
  } else {
    root.ShipKit = factory();
  }
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  var VERSION = '1.0.0';

  // Managed tier default base. Illustrative placeholder host under
  // liftedholdings.com — contains no secrets. Get a key at
  // https://liftedholdings.com/payments.
  var MANAGED_ENDPOINT = 'https://api.liftedholdings.com/shipkit/v1';

  // ---------------------------------------------------------------------------
  // Small DOM + utility helpers (no framework, no dependencies)
  // ---------------------------------------------------------------------------

  function el(tag, attrs, children) {
    var node = document.createElement(tag);
    attrs = attrs || {};
    Object.keys(attrs).forEach(function (k) {
      var v = attrs[k];
      if (v == null || v === false) return;
      if (k === 'class') node.className = v;
      else if (k === 'text') node.textContent = v;
      else if (k === 'html') node.innerHTML = v;
      else if (k === 'dataset') Object.keys(v).forEach(function (d) { node.dataset[d] = v[d]; });
      else if (k.slice(0, 2) === 'on' && typeof v === 'function') {
        node.addEventListener(k.slice(2).toLowerCase(), v);
      } else if (v === true) {
        node.setAttribute(k, '');
      } else {
        node.setAttribute(k, v);
      }
    });
    (children || []).forEach(function (c) {
      if (c == null) return;
      node.appendChild(typeof c === 'string' ? document.createTextNode(c) : c);
    });
    return node;
  }

  function money(amount, currency) {
    var n = Number(amount);
    if (!isFinite(n)) return '—';
    try {
      return new Intl.NumberFormat(undefined, {
        style: 'currency',
        currency: currency || 'USD'
      }).format(n);
    } catch (e) {
      return (currency || 'USD') + ' ' + n.toFixed(2);
    }
  }

  function delay(ms) {
    return new Promise(function (resolve) { setTimeout(resolve, ms); });
  }

  // A typed error so callers can branch on `err.stage`.
  function ShipKitError(message, stage, detail) {
    var e = new Error(message);
    e.name = 'ShipKitError';
    e.stage = stage || 'unknown';
    e.detail = detail;
    return e;
  }

  // ---------------------------------------------------------------------------
  // API client — the only place that knows self-host vs managed routing
  // ---------------------------------------------------------------------------

  function ApiClient(config) {
    this.base = (config.managedKey ? (config.endpoint || MANAGED_ENDPOINT) : (config.endpoint || '/api'))
      .replace(/\/+$/, '');
    this.managedKey = config.managedKey || null;
    // Self-host API key. Both the managed key and a self-host key are ShipKit
    // `pk_live_…`/`pk_test_…` keys sent in the SAME `ShipKit-Api-Key` header; the
    // managed key is simply one Lifted issues and routes to its edge.
    this.apiKey = config.apiKey || null;
    this.authKey = this.managedKey || this.apiKey || null;
    this.fetchImpl = config.fetch || (typeof fetch !== 'undefined' ? fetch.bind(window) : null);
    if (!this.fetchImpl) {
      throw ShipKitError('fetch is unavailable; pass config.fetch', 'init');
    }
  }

  ApiClient.prototype.request = function (method, path, body) {
    var self = this;
    var headers = { 'Accept': 'application/json' };
    if (body) headers['Content-Type'] = 'application/json';
    // Every /api/* call (except health) requires the ShipKit API key. It comes
    // from opts.apiKey (self-host) or opts.managedKey (managed) — same header.
    if (this.authKey) headers['ShipKit-Api-Key'] = this.authKey;

    return this.fetchImpl(this.base + path, {
      method: method,
      headers: headers,
      body: body ? JSON.stringify(body) : undefined,
      credentials: this.managedKey ? 'omit' : 'same-origin'
    }).then(function (res) {
      var isJson = (res.headers.get('content-type') || '').indexOf('application/json') !== -1;
      return (isJson ? res.json() : res.text()).then(function (payload) {
        if (!res.ok) {
          var msg = (payload && payload.error) || (payload && payload.message) ||
            ('Request failed (' + res.status + ')');
          throw ShipKitError(msg, 'http', { status: res.status, path: path, payload: payload });
        }
        return payload;
      });
    }, function (networkErr) {
      throw ShipKitError('Network error: ' + networkErr.message, 'network', networkErr);
    });
  };

  ApiClient.prototype.verifyAddress = function (address) {
    return this.request('POST', '/address/verify', address);
  };

  ApiClient.prototype.createShipment = function (payload) {
    return this.request('POST', '/shipment/create', payload);
  };

  ApiClient.prototype.generatePaymentForm = function (payload) {
    return this.request('POST', '/payment/session', payload);
  };

  ApiClient.prototype.paymentStatus = function (sessionId) {
    return this.request('GET', '/payment/status/' + encodeURIComponent(sessionId));
  };

  ApiClient.prototype.purchaseLabel = function (sessionId) {
    return this.request('POST', '/payment/purchase-label/' + encodeURIComponent(sessionId), {});
  };

  // ---------------------------------------------------------------------------
  // Scoped base stylesheet — injected once so the widget is truly drop-in.
  // Everything is a CSS custom property (prefix --sk-) so hosts can retheme
  // without touching this file. Values here are only defaults.
  // ---------------------------------------------------------------------------

  var STYLE_ID = 'shipkit-base-style';

  function injectBaseStyle() {
    if (document.getElementById(STYLE_ID)) return;
    var css = [
      '.sk{',
      '  --sk-bg:#141D31; --sk-ground:#0B1020; --sk-surface:#202C46;',
      '  --sk-text:#AEB8D0; --sk-heading:#EFF3F9; --sk-muted:#6B7C9C;',
      '  --sk-border:rgba(174,184,208,.16);',
      '  --sk-primary:#2E6BFF; --sk-primary-hover:#5AA6FF; --sk-on-primary:#fff;',
      '  --sk-secure:#19C7F5; --sk-warning:#FFB920; --sk-danger:#FF4D6A;',
      '  --sk-radius:12px; --sk-radius-sm:8px; --sk-gap:16px;',
      '  --sk-font:"Inter",ui-sans-serif,-apple-system,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;',
      '  --sk-mono:"JetBrains Mono",ui-monospace,"SF Mono",Consolas,monospace;',
      '  --sk-shadow:0 18px 48px -14px rgba(0,0,0,.6);',
      '  color:var(--sk-text); font-family:var(--sk-font); line-height:1.5;',
      '  box-sizing:border-box; -webkit-font-smoothing:antialiased;',
      '}',
      '.sk *,.sk *::before,.sk *::after{box-sizing:border-box;}',
      '.sk[data-theme="light"]{',
      '  --sk-bg:#FFFFFF; --sk-ground:#EFF3F9; --sk-surface:#EFF3F9;',
      '  --sk-text:#202C46; --sk-heading:#0B1020; --sk-muted:#4A5A78;',
      '  --sk-border:rgba(11,16,32,.14); --sk-shadow:0 14px 34px -16px rgba(11,16,32,.24);',
      '}',
      '.sk__card{background:var(--sk-bg); border:1px solid var(--sk-border);',
      '  border-radius:var(--sk-radius); box-shadow:var(--sk-shadow); overflow:hidden;}',
      '.sk__head{padding:20px 22px 0;}',
      '.sk__body{padding:20px 22px 22px; display:flex; flex-direction:column; gap:var(--sk-gap);}',
      '.sk__title{font-size:1.05rem; font-weight:650; color:var(--sk-heading); margin:0; text-wrap:balance;}',
      '.sk__sub{margin:4px 0 0; font-size:.86rem; color:var(--sk-muted);}',
      '.sk__steps{display:flex; gap:8px; margin:16px 0 0; padding:0; list-style:none;}',
      '.sk__step{flex:1; font-size:.72rem; letter-spacing:.02em; text-transform:uppercase;',
      '  color:var(--sk-muted); padding-bottom:8px; border-bottom:2px solid var(--sk-border);}',
      '.sk__step[aria-current="step"]{color:var(--sk-heading); border-color:var(--sk-primary);}',
      '.sk__step[data-done="1"]{color:var(--sk-secure); border-color:var(--sk-secure);}',
      '.sk__grid{display:grid; grid-template-columns:1fr 1fr; gap:12px;}',
      '.sk__field{display:flex; flex-direction:column; gap:6px; min-width:0;}',
      '.sk__field--full{grid-column:1 / -1;}',
      '.sk__label{font-size:.78rem; font-weight:550; color:var(--sk-text);}',
      '.sk__label span{color:var(--sk-muted); font-weight:400;}',
      '.sk__input{width:100%; padding:10px 12px; font:inherit; font-size:.9rem;',
      '  color:var(--sk-heading); background:var(--sk-surface); border:1px solid var(--sk-border);',
      '  border-radius:var(--sk-radius-sm); transition:border-color .15s,box-shadow .15s;}',
      '.sk__input::placeholder{color:var(--sk-muted);}',
      '.sk__input:focus{outline:none; border-color:var(--sk-primary);',
      '  box-shadow:0 0 0 3px color-mix(in srgb,var(--sk-primary) 25%,transparent);}',
      '.sk__input[aria-invalid="true"]{border-color:var(--sk-danger);}',
      '.sk__err{font-size:.75rem; color:var(--sk-danger); min-height:0;}',
      '.sk__btn{appearance:none; border:1px solid transparent; cursor:pointer;',
      '  font:inherit; font-weight:600; font-size:.9rem; padding:11px 18px;',
      '  border-radius:var(--sk-radius-sm); background:var(--sk-primary); color:var(--sk-on-primary);',
      '  transition:background .15s,opacity .15s,transform .05s; display:inline-flex;',
      '  align-items:center; justify-content:center; gap:8px;}',
      '.sk__btn:hover{background:var(--sk-primary-hover);}',
      '.sk__btn:active{transform:translateY(1px);}',
      '.sk__btn:focus-visible{outline:2px solid var(--sk-primary-hover); outline-offset:2px;}',
      '.sk__btn[disabled]{opacity:.5; cursor:not-allowed;}',
      '.sk__btn--ghost{background:transparent; color:var(--sk-text); border-color:var(--sk-border);}',
      '.sk__btn--ghost:hover{background:var(--sk-surface); color:var(--sk-heading);}',
      '.sk__actions{display:flex; gap:10px; align-items:center;}',
      '.sk__actions .sk__spacer{flex:1;}',
      '.sk__rates{display:flex; flex-direction:column; gap:10px; margin:0; padding:0; list-style:none;}',
      '.sk__rate{display:flex; align-items:center; gap:14px; width:100%; text-align:left;',
      '  padding:14px 16px; background:var(--sk-surface); border:1px solid var(--sk-border);',
      '  border-radius:var(--sk-radius-sm); cursor:pointer; font:inherit; color:var(--sk-text);',
      '  transition:border-color .15s,background .15s;}',
      '.sk__rate:hover{border-color:var(--sk-primary);}',
      '.sk__rate[aria-checked="true"]{border-color:var(--sk-primary);',
      '  box-shadow:0 0 0 3px color-mix(in srgb,var(--sk-primary) 22%,transparent);}',
      '.sk__rate:focus-visible{outline:2px solid var(--sk-primary); outline-offset:2px;}',
      '.sk__rate-main{flex:1; min-width:0;}',
      '.sk__rate-carrier{font-weight:600; color:var(--sk-heading);}',
      '.sk__rate-svc{font-size:.8rem; color:var(--sk-muted);}',
      '.sk__rate-eta{font-size:.78rem; color:var(--sk-muted); white-space:nowrap;}',
      '.sk__rate-price{font-weight:650; color:var(--sk-heading);',
      '  font-variant-numeric:tabular-nums; white-space:nowrap;}',
      '.sk__note{display:flex; gap:10px; align-items:flex-start; font-size:.8rem;',
      '  color:var(--sk-muted); background:var(--sk-surface); border:1px solid var(--sk-border);',
      '  border-left:3px solid var(--sk-secure); border-radius:var(--sk-radius-sm); padding:12px 14px;}',
      '.sk__note strong{color:var(--sk-heading); font-weight:600;}',
      '.sk__shield{width:18px; height:18px; flex:none; color:var(--sk-secure); margin-top:1px;}',
      '.sk__iframe{width:100%; min-height:420px; border:1px solid var(--sk-border);',
      '  border-radius:var(--sk-radius-sm); background:#fff;}',
      '.sk__spin{width:16px; height:16px; border:2px solid rgba(255,255,255,.35);',
      '  border-top-color:currentColor; border-radius:50%; animation:sk-spin .7s linear infinite;}',
      '@keyframes sk-spin{to{transform:rotate(360deg);}}',
      '.sk__status{display:flex; align-items:center; gap:10px; font-size:.85rem; color:var(--sk-muted);}',
      '.sk__result{display:flex; flex-direction:column; align-items:center; gap:16px; text-align:center;}',
      '.sk__qr{width:180px; height:180px; padding:10px; background:#fff; border-radius:var(--sk-radius-sm);}',
      '.sk__track{font-family:var(--sk-mono); font-size:.9rem; color:var(--sk-heading);',
      '  background:var(--sk-surface); border:1px solid var(--sk-border);',
      '  border-radius:var(--sk-radius-sm); padding:8px 12px;}',
      '.sk__alert{font-size:.85rem; color:var(--sk-danger); background:',
      '  color-mix(in srgb,var(--sk-danger) 12%,transparent); border:1px solid',
      '  color-mix(in srgb,var(--sk-danger) 40%,transparent); border-radius:var(--sk-radius-sm);',
      '  padding:11px 14px;}',
      '.sk__foot{padding:14px 22px; border-top:1px solid var(--sk-border);',
      '  display:flex; align-items:center; gap:8px; font-size:.76rem; color:var(--sk-muted);}',
      '.sk__foot a{color:var(--sk-secure); text-decoration:none; font-weight:600;}',
      '.sk__foot a:hover{text-decoration:underline;}',
      '.sk__sr{position:absolute; width:1px; height:1px; padding:0; margin:-1px;',
      '  overflow:hidden; clip:rect(0,0,0,0); border:0;}',
      '@media (max-width:480px){.sk__grid{grid-template-columns:1fr;}}',
      '@media (prefers-reduced-motion:reduce){.sk__spin{animation:none;}}'
    ].join('\n');
    document.head.appendChild(el('style', { id: STYLE_ID, text: css }));
  }

  // Cyan shield glyph used beside every 3-D Secure mention.
  function shieldIcon() {
    var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('class', 'sk__shield');
    svg.setAttribute('aria-hidden', 'true');
    svg.innerHTML = '<path fill="none" stroke="currentColor" stroke-width="1.8" ' +
      'stroke-linejoin="round" d="M12 3l7 3v5c0 4.4-3 7.6-7 9-4-1.4-7-4.6-7-9V6l7-3z"/>' +
      '<path fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" ' +
      'stroke-linejoin="round" d="M9 12l2 2 4-4"/>';
    return svg;
  }

  // ---------------------------------------------------------------------------
  // Widget instance — orchestrates the four-step flow
  // ---------------------------------------------------------------------------

  var DEFAULT_PARCEL = { length: 9, width: 6, height: 2, weight: 16 }; // in, in, in, oz

  function Widget(config) {
    this.config = config;
    this.api = new ApiClient(config);
    this.mount = resolveMount(config.mount);
    if (!this.mount) throw ShipKitError('mount element not found: ' + config.mount, 'init');

    // Event registry. Init-time callbacks (onQuote/onPurchase/onError) are just
    // the first-registered handler for each event; `.on(event, fn)` adds more.
    this.handlers = { quote: [], purchase: [], error: [] };
    if (typeof config.onQuote === 'function') this.handlers.quote.push(config.onQuote);
    if (typeof config.onPurchase === 'function') this.handlers.purchase.push(config.onPurchase);
    if (typeof config.onError === 'function') this.handlers.error.push(config.onError);
    this.destroyed = false;

    this.state = {
      from: config.from || null,   // optional pre-filled ship-from
      to: null,
      parcel: Object.assign({}, DEFAULT_PARCEL, config.parcel || {}),
      shipmentId: null,
      rates: [],
      selectedRate: null,
      sessionId: null,
      label: null
    };

    injectBaseStyle();
    this.root = el('div', { class: 'sk sk__card', role: 'region', 'aria-label': 'ShipKit shipping' });
    if (config.theme) this.root.dataset.theme = config.theme;
    this.mount.innerHTML = '';
    this.mount.appendChild(this.root);
    this.renderAddress();
  }

  function resolveMount(m) {
    if (!m) return null;
    return typeof m === 'string' ? document.querySelector(m) : m;
  }

  /**
   * Subscribe to a widget lifecycle event. Chainable; may be called any number
   * of times per event. Handlers registered here fire alongside the init-time
   * `onQuote`/`onPurchase`/`onError` callbacks.
   *
   * @param {'quote'|'purchase'|'error'} event  Event name.
   * @param {Function} handler                  Called with the event payload:
   *   `quote`    → `{ shipmentId:string, rates:Rate[] }`
   *   `purchase` → `Label` (`{ labelUrl, qrCodeUrl, trackingCode, carrier, service }`)
   *   `error`    → `ShipKitError` (has `.stage` and `.detail`).
   * @returns {Widget} this (for chaining).
   */
  Widget.prototype.on = function (event, handler) {
    if (this.handlers[event] && typeof handler === 'function') {
      this.handlers[event].push(handler);
    }
    return this;
  };

  // Dispatch an event to every registered handler. A throwing handler is logged
  // and never breaks the flow or the other handlers.
  Widget.prototype.emit = function (event, payload) {
    var list = this.handlers[event] || [];
    for (var i = 0; i < list.length; i++) {
      try { list[i](payload); }
      catch (e) { if (typeof window !== 'undefined' && window.console) console.error('[ShipKit] ' + event + ' handler threw:', e); }
    }
  };

  /**
   * Tear the widget down: stop any in-flight status polling, empty the mount
   * node, and drop all event handlers. Safe to call more than once. After
   * `.destroy()` the instance no longer renders or emits.
   * @returns {void}
   */
  Widget.prototype.destroy = function () {
    this.destroyed = true;
    this._cancelled = true; // halts pollPayment on its next tick
    if (this.mount) this.mount.innerHTML = '';
    this.root = null;
    this.handlers = { quote: [], purchase: [], error: [] };
  };

  // Chrome shared by every step: title, sub, step tracker, body container, footer.
  Widget.prototype.frame = function (stepIndex, bodyNodes) {
    var steps = ['Address', 'Rates', 'Payment', 'Label'];
    var head = el('div', { class: 'sk__head' }, [
      el('h2', { class: 'sk__title', text: 'Buy a shipping label' }),
      el('p', { class: 'sk__sub', text: 'Compare live carrier rates. Pay securely with 3-D Secure.' }),
      el('ol', { class: 'sk__steps' }, steps.map(function (name, i) {
        return el('li', {
          class: 'sk__step',
          'aria-current': i === stepIndex ? 'step' : null,
          dataset: { done: i < stepIndex ? '1' : '0' }
        }, [name]);
      }))
    ]);

    var foot = el('div', { class: 'sk__foot' }, [
      shieldIcon(),
      el('span', {}, [
        'Secured by ',
        el('a', {
          href: 'https://liftedholdings.com/payments',
          target: '_blank',
          rel: 'noopener'
        }, ['Lifted Payments · 3-D Secure'])
      ])
    ]);

    var body = el('div', { class: 'sk__body' }, bodyNodes);
    this.root.innerHTML = '';
    this.root.appendChild(head);
    this.root.appendChild(body);
    this.root.appendChild(foot);
    return body;
  };

  // --- Step 1: address entry + verify -----------------------------------------

  Widget.prototype.renderAddress = function () {
    var self = this;
    var to = this.state.to || {};
    var fields = {};

    function field(key, label, opts) {
      opts = opts || {};
      var id = 'sk-to-' + key;
      var input = el('input', {
        id: id, class: 'sk__input', name: key,
        value: to[key] || '', placeholder: opts.placeholder || '',
        autocomplete: opts.autocomplete || 'off',
        inputmode: opts.inputmode || null,
        'aria-describedby': id + '-err'
      });
      fields[key] = input;
      return el('div', { class: 'sk__field' + (opts.full ? ' sk__field--full' : '') }, [
        el('label', { class: 'sk__label', for: id }, [
          label, opts.optional ? el('span', { text: ' (optional)' }) : null
        ]),
        input,
        el('div', { class: 'sk__err', id: id + '-err', 'aria-live': 'polite' })
      ]);
    }

    var alert = el('div', { class: 'sk__alert', hidden: true, role: 'alert' });

    var grid = el('div', { class: 'sk__grid' }, [
      field('name', 'Recipient name', { full: true, autocomplete: 'name', placeholder: 'Ada Lovelace' }),
      field('street1', 'Street address', { full: true, autocomplete: 'address-line1', placeholder: '1600 Pennsylvania Ave NW' }),
      field('street2', 'Apt, suite, unit', { full: true, optional: true, autocomplete: 'address-line2' }),
      field('city', 'City', { autocomplete: 'address-level2', placeholder: 'Washington' }),
      field('state', 'State / Province', { autocomplete: 'address-level1', placeholder: 'DC' }),
      field('zip', 'Postal code', { autocomplete: 'postal-code', inputmode: 'numeric', placeholder: '20500' }),
      field('country', 'Country', { autocomplete: 'country', placeholder: 'US' })
    ]);

    var submit = el('button', { class: 'sk__btn', type: 'submit' }, ['Verify & get rates']);

    var form = el('form', { novalidate: true }, [
      alert,
      grid,
      el('div', { class: 'sk__actions' }, [el('span', { class: 'sk__spacer' }), submit])
    ]);

    form.addEventListener('submit', function (ev) {
      ev.preventDefault();
      alert.hidden = true;
      var address = {}, valid = true;
      var required = ['name', 'street1', 'city', 'state', 'zip'];
      Object.keys(fields).forEach(function (k) {
        var v = fields[k].value.trim();
        address[k] = v;
        var errNode = document.getElementById('sk-to-' + k + '-err');
        var missing = required.indexOf(k) !== -1 && !v;
        fields[k].setAttribute('aria-invalid', missing ? 'true' : 'false');
        errNode.textContent = missing ? 'Required' : '';
        if (missing) valid = false;
      });
      if (!address.country) address.country = 'US';
      if (!valid) { fields[firstEmptyRequired(fields, required)].focus(); return; }

      setBusy(submit, true, 'Verifying…');
      self.api.verifyAddress(address).then(function (res) {
        // Verified address (normalized) becomes the ship-to; fall back to input.
        var verified = res && res.address ? res.address : (res && res.id ? res : address);
        self.state.to = Object.assign({}, address, verified);
        var warnings = (res && res.messages) || [];
        return self.loadRates(warnings);
      }).catch(function (err) {
        setBusy(submit, false, 'Verify & get rates');
        alert.hidden = false;
        alert.textContent = friendly(err, 'We could not verify that address.');
        self.emit('error', err);
      });
    });

    this.frame(0, [form]);
    (fields.name).focus();
  };

  function firstEmptyRequired(fields, required) {
    for (var i = 0; i < required.length; i++) {
      if (!fields[required[i]].value.trim()) return required[i];
    }
    return required[0];
  }

  // --- Step 2: create shipment + rate compare ---------------------------------

  Widget.prototype.loadRates = function (warnings) {
    var self = this;
    var from = this.state.from || this.config.from;
    var payload = {
      to_address: this.state.to,
      parcel: this.state.parcel
    };
    // Self-host backends may hold the ship-from server-side; only send if given.
    if (from) payload.from_address = from;

    // POST /api/shipment/create → { id:"shp_…", rates:[…], messages:[…] } (§2).
    return this.api.createShipment(payload).then(function (res) {
      self.state.shipmentId = res.id;
      var rates = normalizeRates(res.rates || []);
      if (!rates.length) {
        // Empty rates + populated messages = a carrier error (bad account, over
        // weight limit, lane not served). Surface it — never a blank "$0/no
        // options" screen.
        var carrierMsgs = (res.messages || [])
          .map(function (m) { return m && m.message; })
          .filter(Boolean);
        throw ShipKitError(
          carrierMsgs.length ? carrierMsgs.join(' ') : 'No rates available for this shipment.',
          'rates', res
        );
      }
      self.state.rates = rates;
      self.emit('quote', { shipmentId: self.state.shipmentId, rates: rates });
      self.renderRates(warnings);
    });
  };

  // Normalize the §2 rate shape { id, carrier, service, rate (STRING), currency,
  // delivery_days (nullable int) }. `rate` is parsed as a decimal number for
  // comparison/sort — never string-sorted.
  function normalizeRates(raw) {
    return raw.map(function (r) {
      return {
        id: r.id,
        carrier: r.carrier || 'Carrier',
        service: r.service || 'Service',
        rate: Number(r.rate),
        currency: r.currency || 'USD',
        deliveryDays: r.delivery_days != null ? Number(r.delivery_days) : null,
        guaranteed: r.delivery_date_guaranteed || false
      };
    }).filter(function (r) {
      return r.id && isFinite(r.rate);
    }).sort(function (a, b) {
      return a.rate - b.rate;
    });
  }

  Widget.prototype.renderRates = function (warnings) {
    var self = this;
    var selectedId = this.state.selectedRate ? this.state.selectedRate.id : this.state.rates[0].id;

    // A radiogroup must contain its radios directly — a <ul>/<li> wrapper would
    // orphan the list items (role override) and fail a11y. Use a plain container.
    var list = el('div', { class: 'sk__rates', role: 'radiogroup', 'aria-label': 'Available shipping rates' });
    this.state.rates.forEach(function (rate) {
      var eta = rate.deliveryDays != null
        ? (rate.deliveryDays === 1 ? '1 day' : rate.deliveryDays + ' days')
        : 'See carrier';
      var btn = el('button', {
        // role="radio" takes aria-checked only — aria-pressed is a toggle-button
        // attribute and is not allowed here.
        type: 'button', class: 'sk__rate', role: 'radio',
        'aria-checked': rate.id === selectedId ? 'true' : 'false',
        dataset: { rateId: rate.id }
      }, [
        el('div', { class: 'sk__rate-main' }, [
          el('div', { class: 'sk__rate-carrier', text: rate.carrier }),
          el('div', { class: 'sk__rate-svc', text: rate.service + (rate.guaranteed ? ' · guaranteed' : '') })
        ]),
        el('div', { class: 'sk__rate-eta', text: eta }),
        el('div', { class: 'sk__rate-price', text: money(rate.rate, rate.currency) })
      ]);
      btn.addEventListener('click', function () {
        self.state.selectedRate = rate;
        Array.prototype.forEach.call(list.children, function (b) {
          var on = b.dataset.rateId === rate.id;
          b.setAttribute('aria-checked', on ? 'true' : 'false');
        });
      });
      list.appendChild(btn);
    });

    // Default selection is the cheapest rate.
    this.state.selectedRate = this.state.rates.find(function (r) { return r.id === selectedId; });

    var back = el('button', { type: 'button', class: 'sk__btn sk__btn--ghost' }, ['Back']);
    back.addEventListener('click', function () { self.renderAddress(); });

    var pay = el('button', { type: 'button', class: 'sk__btn' }, ['Continue to secure payment']);
    pay.addEventListener('click', function () {
      if (!self.state.selectedRate) return;
      self.startPayment(pay);
    });

    var body = [];
    if (warnings && warnings.length) {
      body.push(el('div', { class: 'sk__note' }, [
        shieldIcon(),
        el('div', {}, [el('strong', { text: 'Address note: ' }), warnings.join(' ')])
      ]));
    }
    body.push(list);
    body.push(el('div', { class: 'sk__note' }, [
      shieldIcon(),
      el('div', {}, [
        el('strong', { text: '3-D Secure checkout. ' }),
        'The next step authenticates the cardholder with their bank, shifting fraud ' +
        'chargeback liability to the issuer on authenticated transactions.'
      ])
    ]));
    body.push(el('div', { class: 'sk__actions' }, [back, el('span', { class: 'sk__spacer' }), pay]));

    this.frame(1, body);
  };

  // --- Step 3: 3-D Secure card payment ----------------------------------------

  Widget.prototype.startPayment = function (trigger) {
    var self = this;
    var rate = this.state.selectedRate;
    setBusy(trigger, true, 'Preparing secure form…');

    // POST /api/payment/session { shipment_id, rate_id } → { session_id, form_url,
    // amount, currency, expires_at } (§2). The amount is SERVER-COMPUTED from the
    // rate + markup; any client-sent amount is ignored, so we never send one.
    this.api.generatePaymentForm({
      shipment_id: this.state.shipmentId,
      rate_id: rate.id
    }).then(function (res) {
      self.state.sessionId = res.session_id;
      if (!self.state.sessionId) throw ShipKitError('Payment session was not created.', 'payment', res);
      self.renderPayment(res);
    }).catch(function (err) {
      setBusy(trigger, false, 'Continue to secure payment');
      self.showError('payment', err, 'We could not start the secure payment.');
      self.emit('error', err);
    });
  };

  Widget.prototype.renderPayment = function (formRes) {
    var self = this;

    var frame = el('iframe', {
      class: 'sk__iframe',
      title: '3-D Secure card payment',
      // Sandbox: allow the hosted form to run scripts, submit, and complete the
      // issuer's 3DS challenge, but keep it isolated from the host page.
      sandbox: 'allow-scripts allow-forms allow-same-origin allow-top-navigation-by-user-activation',
      referrerpolicy: 'no-referrer'
    });
    // The session's `form_url` is the CANONICAL hosted-form key (§2) — the
    // tokenized 3-D Secure card form. Card data enters that framed origin, never
    // this page or the merchant's server.
    if (!formRes.form_url) {
      throw ShipKitError('Payment session returned no form_url.', 'payment', formRes);
    }
    // Only frame a form URL whose origin is safe to run under this sandbox. The
    // sandbox pairs allow-scripts with allow-same-origin, so its isolation only
    // holds while the framed content is NOT same-origin as this page (an opaque
    // data:/blob: origin, or a cross-origin https host like the hosted 3-DS form).
    // Refuse a same-origin http(s) URL (which would void the sandbox) or an active
    // scheme like javascript:. See findings/#7.
    if (!isFramableFormUrl(formRes.form_url)) {
      throw ShipKitError('Refusing to load an untrusted payment form URL.', 'payment', formRes);
    }
    frame.setAttribute('src', formRes.form_url);

    var status = el('div', { class: 'sk__status', 'aria-live': 'polite' }, [
      el('span', { class: 'sk__spin', 'aria-hidden': 'true' }),
      el('span', { text: 'Waiting for 3-D Secure authentication…' })
    ]);

    var back = el('button', { type: 'button', class: 'sk__btn sk__btn--ghost' }, ['Cancel']);
    back.addEventListener('click', function () {
      self._cancelled = true;
      self.renderRates();
    });

    this.frame(2, [
      el('div', { class: 'sk__note' }, [
        shieldIcon(),
        el('div', {}, [
          el('strong', { text: 'Enter your card below. ' }),
          'Your bank may ask you to confirm this purchase. Card data is handled by ' +
          'Lifted Payments — it never touches this page or your server.'
        ])
      ]),
      frame,
      status,
      el('div', { class: 'sk__actions' }, [back])
    ]);

    this._cancelled = false;
    this.pollPayment(status);
  };

  // Poll payment status until the issuer approves (or declines / times out),
  // then buy the label.
  Widget.prototype.pollPayment = function (statusNode) {
    var self = this;
    var attempts = 0;
    var MAX_ATTEMPTS = 150; // ~5 min at 2s
    var INTERVAL = 2000;

    function tick() {
      if (self._cancelled) return;
      if (attempts++ >= MAX_ATTEMPTS) {
        return self.showError('payment',
          ShipKitError('Timed out waiting for payment confirmation.', 'payment'),
          'Payment authentication timed out. Please try again.');
      }
      // GET /api/payment/status/{id} → { status, three_ds:{eci,cavv,liability_shift} }
      // (§2). The label may be bought ONLY when the SERVER reports status
      // "approved" AND a completed 3-D Secure liability shift. The status is
      // verified server-side against Lifted Payments — never trusted from a
      // return URL — so the widget simply honors the authorization rule.
      self.api.paymentStatus(self.state.sessionId).then(function (res) {
        if (self._cancelled) return; // destroyed / cancelled mid-request
        var st = (res.status || '').toLowerCase();
        var shifted = !!(res.three_ds && res.three_ds.liability_shift === true);
        if (st === 'approved' && shifted) {
          setStatusText(statusNode, 'Payment authenticated. Purchasing your label…');
          return self.buyLabel(statusNode);
        }
        if (st === 'declined' || st === 'failed' || st === 'error') {
          return self.showError('payment',
            ShipKitError('Payment was declined.', 'payment', res),
            'Your card was declined or authentication failed. Please try another card.');
        }
        // pending / authenticated (3DS done, sale not yet approved) → keep polling.
        return delay(INTERVAL).then(tick);
      }).catch(function (err) {
        // Transient poll failures shouldn't kill the flow AFTER the card was
        // charged: keep polling on a network drop OR a gateway 502/503/504 (the
        // status route returns 502 when the gateway lookup itself blips). A hard
        // 4xx or exhausted budget still terminates.
        var status = err.detail && err.detail.status;
        var transient = err.stage === 'network' ||
          (err.stage === 'http' && (status === 502 || status === 503 || status === 504));
        if (transient && attempts < MAX_ATTEMPTS) {
          return delay(INTERVAL).then(tick);
        }
        self.showError('payment', err, 'We lost contact while confirming payment.');
        self.emit('error', err);
      });
    }
    tick();
  };

  // --- Step 4: buy label + result ---------------------------------------------

  Widget.prototype.buyLabel = function (statusNode) {
    var self = this;
    setStatusText(statusNode, 'Purchasing your label…');
    return this.api.purchaseLabel(this.state.sessionId).then(function (res) {
      if (self._cancelled) return; // destroyed / cancelled mid-purchase
      var label = normalizeLabel(res);
      self.state.label = label;
      self.emit('purchase', label);
      self.renderResult(label);
    }).catch(function (err) {
      self.showError('purchase', err, 'Payment succeeded but the label could not be created. Contact support@liftedholdings.com — you have not lost your payment.');
      self.emit('error', err);
    });
  };

  // Map the §2 purchase response { label_url, qr_code_url, tracking_code,
  // carrier, service }. `tracking_url` is optional (some backends synthesize a
  // carrier tracking link) and passed through when present.
  function normalizeLabel(res) {
    return {
      labelUrl: res.label_url || null,
      qrCodeUrl: res.qr_code_url || null,
      trackingCode: res.tracking_code || null,
      trackingUrl: res.tracking_url || null,
      carrier: res.carrier || null,
      service: res.service || null
    };
  }

  Widget.prototype.renderResult = function (label) {
    var self = this;
    var kids = [
      el('div', { class: 'sk__note' }, [
        shieldIcon(),
        el('div', {}, [el('strong', { text: 'Paid & authenticated. ' }), 'Your label is ready.'])
      ])
    ];

    var result = el('div', { class: 'sk__result' });
    var qrSrc = safeUrl(label.qrCodeUrl);
    if (qrSrc) {
      result.appendChild(el('img', {
        class: 'sk__qr', src: qrSrc, alt: 'QR code — scan to present this label at drop-off',
        loading: 'lazy'
      }));
    }
    if (label.trackingCode) {
      result.appendChild(el('div', {}, [
        el('div', { class: 'sk__sub', text: 'Tracking number' }),
        el('div', { class: 'sk__track', text: label.trackingCode })
      ]));
    }
    kids.push(result);

    var actions = el('div', { class: 'sk__actions' }, []);
    var labelHref = safeUrl(label.labelUrl);
    if (labelHref) {
      actions.appendChild(el('a', {
        class: 'sk__btn', href: labelHref, target: '_blank', rel: 'noopener', download: ''
      }, ['Download label']));
    }
    var trackHref = safeUrl(label.trackingUrl);
    if (trackHref) {
      actions.appendChild(el('a', {
        class: 'sk__btn sk__btn--ghost', href: trackHref, target: '_blank', rel: 'noopener'
      }, ['Track shipment']));
    }
    var again = el('button', { type: 'button', class: 'sk__btn sk__btn--ghost' }, ['Ship another']);
    again.addEventListener('click', function () { self.reset(); });
    actions.appendChild(el('span', { class: 'sk__spacer' }));
    actions.appendChild(again);
    kids.push(actions);

    this.frame(3, kids);
  };

  // Reset to a fresh flow, keeping ship-from + parcel defaults.
  Widget.prototype.reset = function () {
    this.state.to = null;
    this.state.shipmentId = null;
    this.state.rates = [];
    this.state.selectedRate = null;
    this.state.sessionId = null;
    this.state.label = null;
    this._cancelled = false;
    this.renderAddress();
  };

  Widget.prototype.showError = function (stage, err, friendlyMsg) {
    var self = this;
    var retry = el('button', { type: 'button', class: 'sk__btn' }, ['Start over']);
    retry.addEventListener('click', function () { self.reset(); });
    this.frame(stage === 'purchase' ? 3 : 2, [
      el('div', { class: 'sk__alert', role: 'alert' }, [friendly(err, friendlyMsg)]),
      el('div', { class: 'sk__actions' }, [el('span', { class: 'sk__spacer' }), retry])
    ]);
  };

  // ---------------------------------------------------------------------------
  // Shared small helpers
  // ---------------------------------------------------------------------------

  function setBusy(btn, busy, label) {
    btn.disabled = busy;
    btn.setAttribute('aria-busy', busy ? 'true' : 'false');
    btn.innerHTML = '';
    if (busy) btn.appendChild(el('span', { class: 'sk__spin', 'aria-hidden': 'true' }));
    btn.appendChild(document.createTextNode(label));
  }

  function setStatusText(node, text) {
    var span = node.querySelector('span:last-child');
    if (span) span.textContent = text;
  }

  // True when `raw` is safe to load as the hosted 3-D Secure form src. data:/blob:
  // get an opaque origin (fully sandboxed); http(s) must be CROSS-origin so the
  // allow-scripts+allow-same-origin sandbox stays a real boundary; active schemes
  // (javascript:, etc.) and same-origin http(s) are refused. See finding #7.
  function isFramableFormUrl(raw) {
    var u;
    try { u = new URL(raw, (typeof window !== 'undefined' ? window.location.href : undefined)); }
    catch (e) { return false; }
    var proto = u.protocol;
    if (proto === 'data:' || proto === 'blob:') return true;
    if (proto !== 'https:' && proto !== 'http:') return false;
    // Same-origin http(s) content would neutralize the sandbox — reject it.
    return typeof window === 'undefined' || u.origin !== window.location.origin;
  }

  // Guard a SERVER-supplied label/tracking/QR URL before it is placed in an
  // href/src. The widget runs against an operator-supplied `endpoint`, so the
  // response origin is not always trusted: a `javascript:` (or `data:text/html`)
  // value would otherwise become a clickable script execution or an XSS in the
  // host page. Allows http(s) and image `data:` URIs only (the self-contained
  // demo renders its SVG label/QR as `data:image/...`); returns the URL when
  // safe, else null so the caller skips the element. See finding #3.
  function safeUrl(u) {
    if (!u) return null;
    var s = String(u);
    try {
      var proto = new URL(s, (typeof window !== 'undefined' ? window.location.href : undefined)).protocol;
      if (proto === 'https:' || proto === 'http:') return s;
      if (proto === 'data:' && /^data:image\//i.test(s)) return s;
      return null;
    } catch (e) { return null; }
  }

  function friendly(err, fallback) {
    if (!err) return fallback;
    // Show the server message when it's user-safe; otherwise the fallback.
    if (err.stage === 'http' && err.message && err.message.length < 160) return err.message;
    return fallback || err.message || 'Something went wrong.';
  }

  // ---------------------------------------------------------------------------
  // Public surface + script-tag auto-init
  // ---------------------------------------------------------------------------

  var ShipKit = {
    version: VERSION,

    /**
     * Initialize a widget and render the four-step flow (address → rates →
     * 3-D Secure payment → label) into `config.mount`.
     *
     * @param {Object} config
     * @param {string|Element} config.mount     Selector or element to render into (required).
     * @param {string} [config.endpoint]        Self-host API base (default '/api').
     * @param {string} [config.apiKey]          Self-host ShipKit key (pk_live_…/pk_test_…); sent as `ShipKit-Api-Key`.
     * @param {string} [config.managedKey]      Managed ShipKit key (pk_live_…); routes to Lifted and sent as `ShipKit-Api-Key`.
     * @param {Object} [config.from]            Optional pre-filled ship-from address.
     * @param {Object} [config.parcel]          Parcel dims/weight { length,width,height,weight } (weight in oz).
     * @param {'dark'|'light'} [config.theme]   Force a theme; omit to inherit the page.
     * @param {Function} [config.onQuote]       ({ shipmentId, rates }) => void — first `quote` handler.
     * @param {Function} [config.onPurchase]    (label) => void — first `purchase` handler.
     * @param {Function} [config.onError]       (error) => void — first `error` handler.
     * @param {Function} [config.fetch]         Custom fetch (e.g. for SSR/testing/demos).
     * @returns {Widget} The widget instance. Public members:
     *   `.on(event, handler)` — add a `quote|purchase|error` listener (chainable);
     *   `.destroy()` — stop polling, empty the mount, drop listeners;
     *   `.reset()` — return to a fresh flow (keeps ship-from + parcel);
     *   `.state` — the current flow state (read-only in practice).
     * @throws {ShipKitError} If `config.mount` is missing or resolves to no element.
     */
    init: function (config) {
      config = config || {};
      if (!config.mount) throw ShipKitError('config.mount is required', 'init');
      return new Widget(config);
    },

    Error: ShipKitError
  };

  // Auto-init when loaded via a managed <script> tag carrying data- attributes.
  if (typeof document !== 'undefined') {
    var run = function () {
      var tag = document.currentScript ||
        document.querySelector('script[data-managed-key],script[data-endpoint]');
      if (!tag) return;
      var mount = tag.getAttribute('data-mount');
      if (!mount) return; // opt-in: only auto-init when a mount is declared
      try {
        ShipKit.init({
          mount: mount,
          managedKey: tag.getAttribute('data-managed-key') || undefined,
          endpoint: tag.getAttribute('data-endpoint') || undefined,
          apiKey: tag.getAttribute('data-api-key') || undefined,
          theme: tag.getAttribute('data-theme') || undefined
        });
      } catch (e) {
        if (window.console) console.error('[ShipKit] auto-init failed:', e);
      }
    };
    // currentScript is only valid during synchronous execution; capture now.
    var boot = document.currentScript;
    if (boot && boot.getAttribute('data-mount')) {
      if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function () {
          try {
            ShipKit.init({
              mount: boot.getAttribute('data-mount'),
              managedKey: boot.getAttribute('data-managed-key') || undefined,
              endpoint: boot.getAttribute('data-endpoint') || undefined,
              apiKey: boot.getAttribute('data-api-key') || undefined,
              theme: boot.getAttribute('data-theme') || undefined
            });
          } catch (e) { if (window.console) console.error('[ShipKit] auto-init failed:', e); }
        });
      } else {
        run();
      }
    }
  }

  return ShipKit;
});
