/**
 * ShipKit · server-side Node example (Express)
 * ============================================
 * The drop-in widget covers the browser flow. When you buy labels from your
 * OWN backend — order webhooks, an admin tool, a batch job — you call the same
 * ShipKit REST API server-to-server with a SECRET key.
 *
 * Key rule:
 *   - Browser widget → PUBLISHABLE pk_… key (safe in page source).
 *   - Server code    → SECRET sk_… key. Keep it in an env var; never ship it
 *     to a client. It reaches every /api/* route (see docs/authentication.md).
 *
 * This example shows the non-interactive direct-buy path (payment handled out
 * of band): verify → create shipment → buy the cheapest rate. For a card-secured,
 * 3-D Secure purchase, drive the /api/payment/* flow from the browser widget
 * instead — 3DS needs the cardholder present.
 *
 * Run:  SHIPKIT_URL=http://localhost:8080 SHIPKIT_SECRET_KEY=sk_live_… node server-node.js
 * Then: curl -X POST localhost:3000/orders/ship -H 'content-type: application/json' \
 *         -d '{"to":{"name":"Ada Lovelace","street1":"1600 Pennsylvania Ave NW","city":"Washington","state":"DC","zip":"20500","country":"US"}}'
 *
 * Uses only Node 18+ built-ins (global fetch + node:http via express). No SDK.
 */
const express = require('express');

const SHIPKIT_URL = process.env.SHIPKIT_URL || 'http://localhost:8080';
const SECRET_KEY = process.env.SHIPKIT_SECRET_KEY; // sk_live_… / sk_test_…

if (!SECRET_KEY) {
  console.error('Set SHIPKIT_SECRET_KEY to a secret sk_… key.');
  process.exit(1);
}

// Your warehouse / ship-from. USPS requires a name or company at label purchase.
const SHIP_FROM = {
  name: 'Your Store',
  street1: '410 Terry Ave N',
  city: 'Seattle',
  state: 'WA',
  zip: '98109',
  country: 'US',
  phone: '2065550100',
};

/** Thin JSON caller that attaches the ShipKit-Api-Key header and throws on non-2xx. */
async function shipkit(path, body) {
  const res = await fetch(`${SHIPKIT_URL}/api${path}`, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      'ShipKit-Api-Key': SECRET_KEY, // secret key — server-side only
    },
    body: JSON.stringify(body),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok || data.success === false) {
    throw new Error(data.error || `ShipKit ${path} failed (${res.status})`);
  }
  return data;
}

const app = express();
app.use(express.json());

app.post('/orders/ship', async (req, res) => {
  try {
    const { to, parcel } = req.body;
    if (!to) return res.status(400).json({ error: 'to address is required' });

    // 1. Verify + normalize the destination address.
    const verify = await shipkit('/address/verify', to);
    if (!verify.verified) {
      return res.status(422).json({ error: 'Address failed verification', details: verify.errors });
    }

    // 2. Create the shipment and get live carrier rates.
    const shipment = await shipkit('/shipment/create', {
      from: SHIP_FROM,
      to: verify.address,
      // Dimensions in inches; weight in OUNCES (the #1 silent rating bug is grams/lbs).
      parcel: parcel || { weight_oz: 16, length_in: 9, width_in: 6, height_in: 2 },
    });

    const rates = shipment.rates || [];
    if (rates.length === 0) {
      // An empty rates array with messages is a carrier error, not "no options".
      return res.status(422).json({ error: 'No rates', messages: shipment.messages });
    }

    // 3. Pick the cheapest. Rates are decimal STRINGS — parse, never string-sort.
    const cheapest = rates.reduce((a, b) =>
      parseFloat(a.rate) <= parseFloat(b.rate) ? a : b,
    );

    // 4. Buy the label for that rate (payment handled out of band).
    const label = await shipkit('/shipment/buy', {
      shipment_id: shipment.id,
      rate_id: cheapest.id,
    });

    res.json({
      carrier: label.carrier,
      service: label.service,
      amount: label.rate, // string, e.g. "8.42"
      tracking_code: label.tracking_code,
      label_url: label.label_url,
    });
  } catch (err) {
    // Log the detail server-side; return a generic message to the caller.
    console.error('[ship] error:', err);
    res.status(502).json({ error: 'Could not buy shipping label' });
  }
});

const port = process.env.PORT || 3000;
app.listen(port, () => console.log(`ship server on :${port} → ShipKit at ${SHIPKIT_URL}`));
