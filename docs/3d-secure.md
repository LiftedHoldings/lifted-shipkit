# 3-D Secure, explained

ShipKit captures cards on a hosted **3-D Secure** form, branded *Secured by Lifted
Payments · 3-D Secure*. This page explains what 3-D Secure is, what it changes for you as
a merchant, and why it is the default here. It is written for developers, not compliance
lawyers — accurate, no fear-mongering.

## Why shipping needs this

Shipping labels are a favorite target for stolen cards: a fraudster buys a label, ships
real goods fast, and the "unauthorized transaction" chargeback lands on the merchant weeks
later. That makes label purchases one of the higher fraud-and-chargeback-risk categories in
card-not-present commerce — which is exactly why ShipKit **forces** 3-D Secure on every card
charge. The issuer authenticates the real cardholder before the label is ever purchased, so
that fraud never becomes your chargeback. (3DS shifts liability for *stolen-card /
unauthorized* disputes — not "item not received" or service disputes; see the FAQ below.)

## What 3-D Secure is

3-D Secure (3DS) is an authentication step that runs **before** a card is authorized. The
"three domains" are the merchant's bank, the card network, and the card issuer. During
checkout the issuer verifies that the person using the card is the real cardholder —
usually with a device biometric, a one-time passcode, or a silent risk assessment. Only
then does the transaction go to authorization.

The current standard is **EMV 3-D Secure (3DS2)**. It supports a **frictionless flow**:
for low-risk transactions the issuer authenticates in the background using device and
transaction signals, and the cardholder sees nothing extra. A challenge (biometric/OTP) is
requested only when the issuer wants more assurance.

## Why it matters — four pillars

### 1. Liability shift

On an authenticated 3-D Secure transaction, liability for fraud-related chargebacks
**shifts from the merchant to the card issuer**. If a transaction you authenticated is
later disputed as fraudulent, the issuer — not your business — generally absorbs the loss.
Without 3DS, that liability sits with you.

### 2. Fraud and chargeback reduction

Because the issuer authenticates the cardholder *before* authorization — via biometric,
OTP, or risk-based checks — stolen-card and card-testing attacks are stopped at the door
instead of surfacing weeks later as disputes. Fewer fraudulent authorizations means fewer
chargebacks to fight and fewer fees to eat.

### 3. Compliance — SCA / PSD2

3-D Secure is how card-not-present payments meet **Strong Customer Authentication (SCA)**
expectations, including the EU's **PSD2** requirements. Authentication uses two independent
factors (something the cardholder has, is, or knows). The frictionless flow keeps low-risk
checkouts smooth while still satisfying the standard.

### 4. Better approval rates

Issuers **approve authenticated transactions at higher rates**. When the issuer has already
confirmed the cardholder, it has far less reason to decline for suspected fraud. For you
that means more good orders go through and fewer legitimate customers get a false decline.
The scheme-published approval and fraud-reduction figures (Visa Secure, Mastercard Identity
Check) are reported benefits, not guarantees — they vary by portfolio, and an
over-challenging 3DS configuration can *lower* conversion, so tuning matters.

## What this means for ShipKit

In ShipKit the card is entered on Lifted Payments' hosted 3-D Secure form, not on your
page or your server. Concretely:

- The card number never touches ShipKit or your infrastructure, which keeps card data out
  of your PCI scope.
- The authentication and authorization happen on the payment side; ShipKit only holds an
  opaque `session_id` and the server-verified result.
- ShipKit reads the outcome **directly from Lifted Payments**, never from a redirect or
  return-URL parameter. It buys the label only when
  `GET /api/payment/status/{session_id}` reports `status == "approved"` **and**
  `three_ds.liability_shift == true` — an authenticated result (transaction status `Y` or
  `A`) with a valid cryptogram. A `U` (unable), `N` (not authenticated), or `R` (rejected)
  result yields no shift, and ShipKit does not purchase a label.

See the [payment flow in the API reference](api.md#payment--lifted-payments-3-d-secure)
and the [session lifecycle in the architecture doc](architecture.md#payment-session-lifecycle)
for the mechanics.

## Frictionless vs. challenge — a quick model

| Signal profile | Issuer decision | Cardholder experience |
|---|---|---|
| Low risk (known device, normal amount) | Frictionless — authenticate silently | No extra step |
| Elevated risk or issuer policy | Challenge — biometric / OTP | One quick prompt |
| Failed / abandoned challenge | Not authenticated | Payment does not proceed |

You do not implement any of this branching — the issuer and the hosted form handle it. Your
code simply polls `GET /api/payment/status/{sessionId}` until the result is terminal.

## Common questions

**Does 3DS add friction to every checkout?**
No. With 3DS2, most low-risk transactions are authenticated frictionlessly and the buyer
sees no extra step. Challenges appear only when the issuer wants more assurance.

**Is the liability shift absolute?**
No. It covers **fraud-related** chargebacks (Visa reason codes 10.1–10.5, Mastercard
4837 / 4840 / 4849 / 4863 / 4870 / 4871) on **authenticated** transactions only. Disputes
about service, delivery, "item not received", or "not as described" are a different category
and are **not** shifted by 3DS. And note that an SCA *exemption* (e.g. a low-value or TRA
exemption that skips the challenge) is not the same as an authentication — only a genuine
`Y`/`A` authentication with a valid cryptogram shifts liability.

**Do I need 3DS if I only sell domestically?**
It is required in SCA/PSD2 regions, and it is a net win everywhere else too: less fraud,
fewer chargebacks, higher approval rates, and the liability shift.

## Getting a 3-D Secure merchant account

To run ShipKit self-hosted you bring your own 3-D Secure merchant account so payments settle
to you. Lifted Payments provisions accounts with 3-D Secure enabled out of the box.

**Apply for a 3-D Secure merchant account → [liftedholdings.com/payments](https://liftedholdings.com/payments)**

---

Maintained by Daniel Wilson Kemp · Lifted Holdings.
