# Değişiklik günlüğü / Changelog

Bu kütüphanenin sürümleri. API'nin kendi değişiklikleri:
https://rewloy.com/gelistiriciler/degisiklikler

This library's releases. The API's own changes are listed at the link above.

## 0.2.0 (2026-10-05)

İlk yayımlanan sürüm (GitHub Release; Maven Central'a henüz çıkmadı). Rewloy
API 1.0.5'e göre yeniden üretildi: 211 yol, 255 işlem (0.1.0 etiketlenmedi).
Kasa için `recordSale` ve `reverseSale`; README'de yeni bir kasa örneği, test
modu ve `baseUrl`.

The first tagged release (a GitHub Release; not on Maven Central yet).
Regenerated from Rewloy API 1.0.5: 211 paths, 255 operations (237 in the
untagged 0.1.0).

- **New operations.**
  - *Till:* `recordSale` (`POST /v1/passes/{serial}/sale`: write a completed
    sale to a card; the card type decides what is written) and `reverseSale`
    (`POST /v1/passes/{serial}/sale/reverse`: take a refunded sale back).
  - *Checkout codes and shop connections:* `quoteCheckoutCode`,
    `holdCheckoutCode`, `captureCheckoutOrder`, `releaseCheckoutOrder`,
    `refundCheckoutOrder`, `listOrderRedemptions`, `listShopRedemptions`,
    `releaseShopRedemption`, `refundShopRedemption`, `setShopSettings`,
    `setShopCeiling`, `setShopPluginAbilities`, and for the card holder
    `holderCheckoutCodes`, `mintHolderCheckoutCode`, `cancelHolderCheckoutCode`.
  - `getMeta` (`GET /v1/meta`): the API's version.
- **`getPass`** now also returns `programName`, `currency`, `stamps`
  (`count`, `max`), `points`, `money` (`amountMinor`, `currency`), `customer`
  (with `customers.read`), `actions` and `sale`.
- **Webhooks.** `webhooks.manage` API keys manage webhooks (`createWebhook`,
  `listWebhooks`, `getWebhook`, `setWebhookStatus`, `testWebhook`,
  `listWebhookDeliveries`, `webhookEvents`); a webhook reports `createdByKey`.
- **Other fields.** `issuePass` returns `created`; business lists and `me`
  carry `currency`; programs carry `sale`; batches `onlineValue`; shops
  `accepts`, `settings`, `shopName`, `unbacked` and the plugin key's
  `abilities`.
- **Tests.** The shared answer fixtures follow the schemas of 1.0.5 (the reader
  rejects an answer without a required field, which is what failed the
  Regenerate check).
- **README.**
  - A till example with `recordSale`, the structured fields of `getPass` and
    a refund with `reverseSale`.
  - `Idempotency-Key`: a key is unique for good per credential. The
    receipt number alone is not a key (fiscal receipt numbers restart after
    the Z report): use register + Z number + receipt number, or a UUID
    stored with the sale. The receipt number goes in `reference`.
  - Test mode exists: `rwk_test_` keys and a test business.
  - How to set a custom base URL (staging), and a link to the developer
    docs, https://rewloy.com/gelistiriciler.

## 0.1.0 (etiketlenmedi / never tagged)

İlk önizleme. Rewloy API 1.0.0'a göre üretildi: 195 yol, 237 işlem.

First preview, generated from Rewloy API 1.0.0 (195 paths, 237 operations):

- **Artifacts.** `com.rewloy:rewloy` (JVM 8 bytecode, Android API 21+, the only
  dependency is the Kotlin standard library), and the optional
  `com.rewloy:rewloy-okhttp` (an OkHttp transport) and `com.rewloy:rewloy-coroutines`
  (`suspend` calls and a `Flow` of stream events).
- **Client.** `Rewloy { apiKey("rwk_…") }`, or `staffSession` with `merchant`, or
  `holderSession`, with `baseUrl`, `timeoutMs`, `maxRetries`, `userAgent`, `transport`,
  `sleeper` and `deprecationListener`. Blocking calls, usable from Java and Kotlin.
- **Methods.** One method per operation, named by its operationId, with a class for
  each request body, query and answer. `…WithResponse` returns the whole answer
  (`statusCode`, `headers`, `requestId`, `mode`, `isTestMode`, `replayed`).
- **Retries** on network errors, timeouts, 429, 502-504 and Cloudflare's 520-524, with
  exponential backoff, jitter and `Retry-After` (up to 60 s). Only safe requests are retried.
- **`Idempotency-Key`** for till actions and campaigns: generated when omitted, reused
  across retries; `409 IDEMPOTENCY_IN_PROGRESS` is waited out.
- **Paging** with `…All`, an `Iterable` over every page's items.
- **Server-sent events** with `liveFeed` and `holderCardEvents` (`EventStream`, an
  `Iterable` and `Closeable`): reconnection with `Last-Event-ID`, an idle check, and the
  connection closes when the loop ends.
- **Webhooks:** `Webhook.verify` and `Webhook.sign`.
- **Errors:** `RewloyException`, `RateLimitException`, `RewloyConnectionException` and
  `RewloyTimeoutException`; `ErrorCode` holds every code and its title.
- **Cancelling:** `CancelToken`, per call or for a client (`withCancel`).
- **Deprecations:** a `DeprecationListener`, one notice per deprecated operation, and
  `@Deprecated` on the method or field.
- **Test mode:** `RewloyResponse.mode` (the `Rewloy-Mode` header) and `isTestMode`, and
  `EventStream.mode`.
- **Regeneration:** `./gradlew generate`, plus a daily workflow that opens a pull
  request when the live document changes.
