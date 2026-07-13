/*!
 * ShipKit landing page — theme toggle + interactive hero demo.
 * License: MIT © 2026 Daniel Wilson Kemp / Lifted Holdings.
 *
 * The hero embeds the REAL ShipKit widget. On this marketing page it runs in a
 * self-contained "interactive demo" mode: instead of a live backend or a real
 * payment, the widget is initialized with a custom `fetch` that returns
 * responses in the EXACT canonical shapes the real backend emits (§2 REST
 * contract) — snake_case keys, string rates, a server-computed amount, and a
 * 3-D Secure liability shift on the payment status. No cards are charged; no
 * keys are needed. A self-hoster embeds the same widget pointed at their own
 * `/api` endpoint — see docs/integration.md.
 */
(function () {
  'use strict';

  // --------------------------------------------------------------------------
  // Theme toggle (persisted)
  // --------------------------------------------------------------------------
  var root = document.documentElement;
  var stored = null;
  try { stored = localStorage.getItem('shipkit-theme'); } catch (e) {}
  if (stored === 'light' || stored === 'dark') root.setAttribute('data-theme', stored);

  var toggle = document.getElementById('themeToggle');
  if (toggle) {
    toggle.addEventListener('click', function () {
      var next = root.getAttribute('data-theme') === 'light' ? 'dark' : 'light';
      root.setAttribute('data-theme', next);
      try { localStorage.setItem('shipkit-theme', next); } catch (e) {}
      // Keep the embedded widget's theme in step with the page.
      var w = document.querySelector('#ship .sk');
      if (w) w.setAttribute('data-theme', next);
    });
  }

  // --------------------------------------------------------------------------
  // Interactive demo backend (a fetch shim scoped to the demo endpoint)
  // --------------------------------------------------------------------------
  var sessions = Object.create(null);
  var ratePrices = Object.create(null); // rate_id -> base rate (number), for server-side amount compute

  function json(body, status) {
    return new Response(JSON.stringify(body), {
      status: status || 200,
      headers: { 'Content-Type': 'application/json' }
    });
  }

  // A minimal, self-contained "3-D Secure" hosted-form stand-in. It renders a
  // card entry summary and, on submit, tells the parent page to approve the
  // demo session (mirrors how a real hosted form completes out-of-band).
  function demoFormHtml(sessionId, amount) {
    var css = 'body{margin:0;font:15px "Inter",ui-sans-serif,-apple-system,"Segoe UI",Roboto,sans-serif;' +
      'background:#141D31;color:#AEB8D0;padding:22px}h3{margin:0 0 4px;color:#EFF3F9;font-size:1rem}' +
      '.m{color:#6B7C9C;font-size:.82rem;margin-bottom:16px}label{display:block;font-size:.78rem;margin:12px 0 6px}' +
      'input{width:100%;box-sizing:border-box;padding:10px 12px;border:1px solid rgba(174,184,208,.16);' +
      'border-radius:8px;background:#0B1020;color:#EFF3F9;font:inherit}.row{display:flex;gap:12px}' +
      '.row>div{flex:1}button{margin-top:18px;width:100%;padding:12px;border:0;border-radius:8px;' +
      'background:#19C7F5;color:#05141C;font-weight:700;font-size:.95rem;cursor:pointer}' +
      '.s{display:flex;align-items:center;gap:7px;color:#19C7F5;font-size:.76rem;margin-top:14px;justify-content:center}';
    var body =
      '<h3>Card payment</h3>' +
      '<div class="m">Authenticated by your bank · 3-D Secure · demo</div>' +
      '<label>Card number</label><input value="4242 4242 4242 4242" inputmode="numeric" aria-label="Card number">' +
      '<div class="row"><div><label>Expiry</label><input value="12 / 28" aria-label="Expiry"></div>' +
      '<div><label>CVC</label><input value="123" aria-label="CVC"></div></div>' +
      '<button id="pay" type="button">Authenticate &amp; pay ' + amount + '</button>' +
      '<div class="s">Secured by Lifted Payments · 3-D Secure</div>';
    var js = 'document.getElementById("pay").addEventListener("click",function(){' +
      'this.textContent="Authenticating with issuer\\u2026";this.disabled=true;' +
      'setTimeout(function(){parent.postMessage({type:"shipkit-demo-pay",sessionId:"' +
      sessionId + '"},"*");},900);});';
    return '<!doctype html><html><head><meta charset="utf-8"><style>' + css +
      '</style></head><body>' + body + '<script>' + js + '<\/script></body></html>';
  }

  // Deterministic, self-contained QR-style graphic (data URI SVG). This is a
  // representative visual for the demo, not a scannable code.
  function demoQr(seed) {
    var n = 21, cell = 8, pad = 10, size = n * cell + pad * 2, rects = '';
    var h = 2166136261;
    for (var i = 0; i < seed.length; i++) { h ^= seed.charCodeAt(i); h = (h * 16777619) >>> 0; }
    function rnd() { h ^= h << 13; h ^= h >>> 17; h ^= h << 5; h >>>= 0; return h / 4294967296; }
    function finder(x, y) {
      rects += '<rect x="' + (pad + x * cell) + '" y="' + (pad + y * cell) + '" width="' + (cell * 7) +
        '" height="' + (cell * 7) + '" fill="#0b0f14"/>';
      rects += '<rect x="' + (pad + (x + 1) * cell) + '" y="' + (pad + (y + 1) * cell) + '" width="' + (cell * 5) +
        '" height="' + (cell * 5) + '" fill="#fff"/>';
      rects += '<rect x="' + (pad + (x + 2) * cell) + '" y="' + (pad + (y + 2) * cell) + '" width="' + (cell * 3) +
        '" height="' + (cell * 3) + '" fill="#0b0f14"/>';
    }
    for (var y = 0; y < n; y++) {
      for (var x = 0; x < n; x++) {
        var inFinder = (x < 8 && y < 8) || (x > n - 9 && y < 8) || (x < 8 && y > n - 9);
        if (inFinder) continue;
        if (rnd() > 0.5) {
          rects += '<rect x="' + (pad + x * cell) + '" y="' + (pad + y * cell) +
            '" width="' + cell + '" height="' + cell + '" fill="#0b0f14"/>';
        }
      }
    }
    finder(0, 0); finder(n - 7, 0); finder(0, n - 7);
    var svg = '<svg xmlns="http://www.w3.org/2000/svg" width="' + size + '" height="' + size +
      '" viewBox="0 0 ' + size + ' ' + size + '"><rect width="' + size + '" height="' + size +
      '" fill="#fff"/>' + rects + '</svg>';
    return 'data:image/svg+xml,' + encodeURIComponent(svg);
  }

  function demoLabelSvg(tracking, carrier) {
    var svg = '<svg xmlns="http://www.w3.org/2000/svg" width="400" height="240" viewBox="0 0 400 240">' +
      '<rect width="400" height="240" fill="#fff"/><rect x="8" y="8" width="384" height="224" fill="none" stroke="#0b0f14" stroke-width="2"/>' +
      '<text x="20" y="40" font-family="sans-serif" font-size="20" font-weight="700" fill="#0b0f14">' + carrier + ' — Ground</text>' +
      '<line x1="20" y1="56" x2="380" y2="56" stroke="#0b0f14"/>' +
      '<text x="20" y="86" font-family="sans-serif" font-size="12" fill="#333">SHIP TO</text>' +
      '<text x="20" y="108" font-family="sans-serif" font-size="16" fill="#0b0f14">Ada Lovelace</text>' +
      '<text x="20" y="128" font-family="sans-serif" font-size="14" fill="#0b0f14">1600 Pennsylvania Ave NW</text>' +
      '<text x="20" y="146" font-family="sans-serif" font-size="14" fill="#0b0f14">Washington, DC 20500</text>';
    // simple barcode
    var bx = 20, bar = '';
    for (var i = 0; i < 60; i++) { var w = (i % 3) + 1; bar += '<rect x="' + bx + '" y="170" width="' + w + '" height="44" fill="#0b0f14"/>'; bx += w + 2; }
    svg += bar + '<text x="20" y="228" font-family="monospace" font-size="12" fill="#0b0f14">' + tracking + '</text></svg>';
    return 'data:image/svg+xml,' + encodeURIComponent(svg);
  }

  // The demo is intentionally KEYLESS: it is initialized without apiKey/managedKey,
  // so the widget sends no `ShipKit-Api-Key` header, and this mock ignores headers
  // entirely (it authorizes nothing and charges nothing). A real self-host points
  // the widget at `/api` with `apiKey: 'pk_live_…'`; managed uses `managedKey`.
  function demoFetch(url, options) {
    options = options || {};
    var path = String(url).replace(/^.*\/demo-api/, '');
    var method = (options.method || 'GET').toUpperCase();

    // Simulate a little network latency so states are visible.
    return new Promise(function (resolve) {
      setTimeout(function () { resolve(route(path, method)); }, 420);
    });

    function route(p, m) {
      if (p === '/address/verify' && m === 'POST') {
        var a = JSON.parse(options.body || '{}');
        return json({
          verified: true,
          address: {
            name: a.name, street1: a.street1, street2: a.street2 || '',
            city: a.city, state: (a.state || '').toUpperCase(),
            zip: a.zip, country: a.country || 'US'
          },
          messages: []
        });
      }
      if (p === '/shipment/create' && m === 'POST') {
        // §2: rate.rate is a STRING; delivery_days is a nullable int.
        var rates = [
          { id: 'rate_usps_ga', carrier: 'USPS', service: 'GroundAdvantage', rate: '7.68', currency: 'USD', delivery_days: 3 },
          { id: 'rate_usps_pm', carrier: 'USPS', service: 'Priority', rate: '9.45', currency: 'USD', delivery_days: 2 },
          { id: 'rate_ups_g', carrier: 'UPS', service: 'Ground', rate: '11.20', currency: 'USD', delivery_days: 3 },
          { id: 'rate_fedex_2d', carrier: 'FedEx', service: '2Day', rate: '18.90', currency: 'USD', delivery_days: 2, delivery_date_guaranteed: true },
          { id: 'rate_ups_na', carrier: 'UPS', service: 'NextDayAir', rate: '42.15', currency: 'USD', delivery_days: 1, delivery_date_guaranteed: true }
        ];
        rates.forEach(function (r) { ratePrices[r.id] = Number(r.rate); });
        return json({ id: 'shp_demo_' + Date.now(), rates: rates, messages: [] });
      }
      if (p === '/payment/session' && m === 'POST') {
        var body = JSON.parse(options.body || '{}');
        // §2: amount is SERVER-COMPUTED from the selected rate + markup — the
        // client-sent amount (if any) is ignored. Here: base rate + a small
        // managed markup, mirroring the real backend's applyMarkup().
        var base = ratePrices[body.rate_id];
        if (base == null) return json({ error: 'Unknown rate_id' }, 422);
        var amountNum = Math.round((base * 1.09 + 0.30) * 100) / 100; // 9% + $0.30
        var amountStr = amountNum.toFixed(2);
        var sid = 'sess_demo_' + Math.random().toString(36).slice(2, 10);
        sessions[sid] = { status: 'pending', amount: amountStr };
        var display = new Intl.NumberFormat(undefined, { style: 'currency', currency: 'USD' }).format(amountNum);
        var formUrl = 'data:text/html;charset=utf-8,' + encodeURIComponent(demoFormHtml(sid, display));
        return json({
          session_id: sid,
          form_url: formUrl,
          amount: amountStr,
          currency: 'USD',
          expires_at: new Date(Date.now() + 15 * 60 * 1000).toISOString()
        });
      }
      var statusMatch = p.match(/^\/payment\/status\/(.+)$/);
      if (statusMatch && m === 'GET') {
        var s = sessions[decodeURIComponent(statusMatch[1])];
        if (!s) return json({ status: 'failed' });
        // §2: purchase is allowed ONLY when status==="approved" AND
        // three_ds.liability_shift===true. Frictionless Visa result: ECI 05.
        // (CAVV is never returned to the client — it stays server-side; see Handlers.)
        if (s.status === 'approved') {
          return json({ status: 'approved', three_ds: { eci: '05', liability_shift: true } });
        }
        return json({ status: s.status, three_ds: null });
      }
      var buyMatch = p.match(/^\/payment\/purchase-label\/(.+)$/);
      if (buyMatch && m === 'POST') {
        var tracking = '9400 1000 0000 ' + String(Math.floor(1000 + Math.random() * 8999)) + ' ' + String(Math.floor(1000 + Math.random() * 8999)) + ' 00';
        // §2: { label_url, qr_code_url, tracking_code, carrier, service }.
        return json({
          label_url: demoLabelSvg(tracking, 'USPS'),
          qr_code_url: demoQr(tracking),
          tracking_code: tracking,
          tracking_url: 'https://tools.usps.com/go/TrackConfirmAction?tLabels=' + tracking.replace(/\s/g, ''),
          carrier: 'USPS', service: 'GroundAdvantage'
        });
      }
      return json({ error: 'Unknown demo route: ' + p }, 404);
    }
  }

  // Flip a demo session to approved when the sandboxed form posts back.
  window.addEventListener('message', function (ev) {
    var d = ev.data;
    if (d && d.type === 'shipkit-demo-pay' && sessions[d.sessionId]) {
      sessions[d.sessionId].status = 'approved';
    }
  });

  // --------------------------------------------------------------------------
  // Mount the widget
  // --------------------------------------------------------------------------
  function mountWidget() {
    if (!window.ShipKit) return;
    var mount = document.getElementById('ship');
    if (!mount) return;

    // "Interactive demo" caption above the widget.
    var cap = document.createElement('div');
    cap.style.cssText = 'display:flex;align-items:center;gap:8px;margin-bottom:10px;font-size:.76rem;color:var(--muted)';
    cap.innerHTML = '<span style="width:7px;height:7px;border-radius:50%;background:#19C7F5;display:inline-block"></span>' +
      'Interactive demo — no card is charged';
    mount.parentNode.insertBefore(cap, mount);

    window.ShipKit.init({
      mount: '#ship',
      endpoint: '/demo-api',
      theme: root.getAttribute('data-theme') || 'dark',
      fetch: demoFetch,
      from: { name: 'ShipKit Store', street1: '410 Terry Ave N', city: 'Seattle', state: 'WA', zip: '98109', country: 'US' },
      onQuote: function (q) { if (window.console) console.log('[ShipKit demo] onQuote', q); },
      onPurchase: function (l) { if (window.console) console.log('[ShipKit demo] onPurchase', l); },
      onError: function (e) { if (window.console) console.warn('[ShipKit demo] onError', e); }
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', mountWidget);
  } else {
    mountWidget();
  }
})();
