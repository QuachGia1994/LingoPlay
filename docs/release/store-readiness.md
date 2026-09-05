# Store readiness

## Current account state

Stage 21 engineering is designed to be completed before store accounts exist. The project owner does not yet have Apple Developer / App Store Connect or Google Play Console developer accounts. Repository tests, unsigned iOS CI artifacts, Android debug/release test artifacts, billing client wiring, and server-side verification adapters can be completed now. Store products, production signing identities, provider credentials, sandbox/license-tester purchase evidence, TestFlight/Play Internal publication, refund/revocation notification delivery, and store review cannot be claimed until those accounts exist.

Production Plus is fail-closed. A verified StoreKit transaction or Google Play `PURCHASED` state is necessary but not sufficient: a Release build unlocks Plus only after the LingoPlay backend confirms an active eligible subscription against the store provider. Xcode `Products.storekit` remains an explicit DEBUG-only local authority and is never accepted as Release authority.

## Backend secrets to configure later

Configure these as Cloudflare Worker secrets/variables. Never commit their values.

Apple:
- `APPLE_APP_STORE_ISSUER_ID`
- `APPLE_APP_STORE_KEY_ID`
- `APPLE_APP_STORE_PRIVATE_KEY_P8`
- `APPLE_BUNDLE_ID=com.lingoplay.app`

Google Play:
- `GOOGLE_PLAY_SERVICE_ACCOUNT_EMAIL`
- `GOOGLE_PLAY_PRIVATE_KEY_PEM`
- `GOOGLE_PLAY_PACKAGE_NAME=com.lingoplay.app`

Without the required provider configuration, `POST /v1/entitlements/verify` returns a failure and clients keep Plus locked.

## Apple setup when an account exists

1. Enroll in Apple Developer and create the App Store Connect app with bundle ID `com.lingoplay.app`.
2. Create the subscription group and products using the existing IDs exactly: `com.lingoplay.plus.weekly` and `com.lingoplay.plus.monthly`.
3. Create an App Store Connect server API key and place its issuer ID, key ID and `.p8` private key into Worker secrets above.
4. Configure App Store Server Notifications v2 to a dedicated production notification endpoint when that endpoint is enabled. Notification delivery is complementary to client refresh/restore verification and must never directly trust unsigned client data.
5. Produce a distribution-signed archive, upload TestFlight, and test purchase, pending/Ask to Buy where available, renewal, expiry, billing retry/grace, restore, refund/revocation and account switching in Apple sandbox/TestFlight.
6. Verify that revoked/expired transactions remove Plus after refresh and that backend/provider outages never create a local unlock.

## Google Play setup when an account exists

1. Create the Play Console app for package `com.lingoplay.app`.
2. Create weekly/monthly subscription products with the existing IDs and configure base plans/offers deliberately.
3. Create a Google Cloud service account, grant the minimum Play Console/API access needed for Android Publisher subscription reads, and configure its email/private key in Worker secrets above.
4. Configure Real-time Developer Notifications through Pub/Sub and a dedicated verified backend notification endpoint when that endpoint is enabled. RTDN should trigger authoritative re-query of the Play Developer API rather than trusting notification payloads as entitlement state.
5. Upload a production-signed AAB to Internal testing, register license testers, then exercise purchase, pending, grace period, canceled-until-expiry, on-hold, expiry, refund/revoke and restore/reinstall flows.
6. Verify that only an active server response unlocks Plus and that acknowledgement occurs only after server verification.

## Data and privacy boundary

Video and audio remain on-device. Cloud translation sends transcript JSON to the LingoPlay backend. Production billing verification sends only the store transaction identifier on iOS or Google Play purchase token on Android, plus the platform field; the backend uses those identifiers to query the corresponding store API. Provider credentials remain server-side. No media is attached to entitlement requests.

## Release evidence still externally blocked

Until the developer accounts exist, the following cannot be closed: App Store/Play product availability, production signing, TestFlight/Play Internal upload, real store sandbox/license-tester purchases, provider-side refund/revocation notifications, store privacy forms/review submissions, and final store review. Physical iPhone Stage 20.1/21 runtime evidence is also tracked separately and must not be inferred from CI.
