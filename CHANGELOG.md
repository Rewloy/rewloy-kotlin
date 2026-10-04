# Değişiklik günlüğü / Changelog

Bu kütüphanenin sürümleri. API'nin kendi değişiklikleri:
https://rewloy.com/gelistiriciler/degisiklikler

This library's releases. The API's own changes are listed at the link above.

## 0.1.0 (yayımlanmadı / unreleased)

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
