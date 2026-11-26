# Subscription and payment: integration boundary

## Plan (shown before any commitment)

- One month free for each eligible new subscriber, then **500,000 Iranian tomans (5,000,000 rials) per month**.
- The app shows the trial start and end dates, the first charge date and amount, and the cancellation rule:
  cancel before the free month ends and nothing is charged; after cancelling, access lasts to the end of the paid
  period.
- Terms are computed in `core/.../billing/Subscription.kt` (`Plan`, `OfferTerms`) and tested
  (`subscriptionTermsAndBoundary`). A trial starting 31 January ends on 28 February.

## Current status: no provider configured

**No payment provider, credentials or verification service exist in this repository, so none is integrated.**
`UnconfiguredBillingProvider` is the only implementation:

- it never reports an entitlement (`Entitlement.NotConfigured`), and never fakes a successful payment;
- the paywall shows both methods (online gateway, card-to-card) **disabled**, with an explanation;
- **no feature is locked** while billing isn't configured. The app doesn't enforce a subscription it can't verify;
- the Android apps have **no internet permission at all**, so they cannot reach a provider even by mistake.

## What a real integration needs

1. **Choose a provider and confirm it legally.** For example an Iranian Shaparak-connected PSP such as Zarinpal,
   IDPay, Pay.ir or NextPay, or an app store's billing (Cafe Bazaar, Myket). This needs a merchant contract,
   e-Namad/tax requirements as applicable, the merchant ID, and the provider's current API documentation. None
   of these were available here, and no endpoint or fee is assumed.
2. **Store rules.** Apple requires In-App Purchase for digital subscriptions sold in App Store apps; Google Play
   requires Play Billing. A sideloaded iOS build or an Iranian Android store has its own rules. Pick per channel.
3. **A small verification backend operated by you.** Gateway payments must be verified server-to-server (the
   provider's "verify" call with your merchant secret). Secrets can't live in the app, and a trial kept only
   on-device can be reset by reinstalling. The backend should keep only a random `billingAccountId`, plan,
   period dates and the provider's transaction reference.
4. **A separate, reviewed billing module.** Add a dedicated Gradle module / Swift target that implements
   `BillingProvider`, is the only code with network access, and adds `INTERNET` only in the build that includes
   it. Update `tools/check-privacy.sh` to allow network APIs only in that module.
5. **Checkout in the provider's own page.** Open it in the system browser or `ASWebAuthenticationSession`, not a
   web view the app controls, and return through a verified redirect. The app never sees card numbers, CVV2,
   online-banking passwords or OTP codes (پویا رمز).
6. **Card-to-card.** This can't be verified automatically without an authorised bank or PSP integration. The
   minimum proof is the **transfer reference (tracking) number** and the date and amount, validated by
   `TransferReference.normalize` (6–20 digits). The user is **never** asked for a card PIN, CVV2, OTP or a full
   card number. Entitlement stays `PendingVerification` (grants nothing) until the merchant confirms it on the
   backend. Say so plainly in the UI.
7. **Restore / verify.** `BillingProvider.refresh(billingAccountId)` returns the backend's current entitlement,
   for "Restore purchase" and periodic re-checks.

## Data that may cross the billing boundary

Only `BillingRequest` fields: `planId`, a random `billingAccountId` (not derived from IMEI, phone number, SIM or
any hardware id), `amountRials`, and for card-to-card the `transferReference`. The billing module has no access
to the link, messages, calls, contacts, history or keys. When billing ships, the privacy screens must be updated to
say that this limited payment information goes to the named provider.
