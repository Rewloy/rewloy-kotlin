# Live tests

The live suite runs this library against a **development Rewloy**, end to end.
It is part of the release pipeline: every release candidate is tried on the dev
server through every client library before it goes live. How to run it is in
the README ("Canlı testler" / "Live tests"):

```sh
REWLOY_BASE_URL=https://<dev server> REWLOY_API_KEY=rwk_test_… ./gradlew liveTest
```

## Design

- A source set of its own (`rewloy/src/liveTest`, Kotlin and one Java class)
  and a Gradle task of its own (`liveTest`), JUnit 5 like the unit tests.
  `test` and `check` do not run it; `check` only compiles it.
- Skips (not a failure) without `REWLOY_BASE_URL` and `REWLOY_API_KEY`.
- The guard (`Live.guard()`, run before every area): the key starts with
  `rwk_test_`; `GET /v1/meta`, sent with no credential, says
  `"environment": "dev"`; the key's answers carry `Rewloy-Mode: test` and its
  business is named "… · Test". Otherwise every area fails with `REFUSED: …`
  and nothing that changes data was sent.
- Areas run in order (`@Order`): guard, meta and business, programs, passes,
  sales and till actions, customers, batches, webhooks, idempotency, rate limit
  headers, errors, pagination, java consumer, clean-up and test reset. Each
  builds what it needs on demand (`Live`), so one area also runs alone.
- Everything it creates carries the run id (`live-kt-<run>`) in its name,
  address or key. `example.com` addresses only; a test business sends nothing.
- The summary (`AreaSummary`, a JUnit listener) prints passed / failed / skipped
  per area, the notes, and RESULT; any failure fails the Gradle task.

## What cannot be cleaned, and why

- Customers and cards cannot be deleted with an API key (`eraseCustomer` takes a
  person's session). Programs are archived with their cards and codes at the end.
- `resetTestEnvironment` (and `getTestEnvironment`) are a person's operations:
  with an API key the API answers `403 CREDENTIAL_NOT_ALLOWED`. The last area
  therefore resets only when `REWLOY_STAFF_SESSION` (an `rws_…` session of a
  person with a seat in the real business) is set; without it the area checks
  the refusal and the summary carries a NOTE. The reset is limited to 5 a day per
  business (`429 RATE_LIMITED`, also a NOTE).

## Things the suite showed about 1.2.2 (not failures; read them)

- `sendBatchLink` for an archived program's code answers `410 BATCH_CLOSED`, not
  the documented `409 PROGRAM_ARCHIVED`: archiving closes the program's open
  codes and a closed code is answered first. The suite accepts either refusal
  (no e-mail goes); `createBatch` on an archived program answers
  `409 PROGRAM_ARCHIVED`.
- A till write (`recordSale`, `passAction`) replayed with the same
  `Idempotency-Key` answers `duplicate: true` and the card as it is now, but
  without the `Idempotent-Replayed` header; `issuePass` sends the header.
- `maxStamps` is at least 4 (a validation example the errors area uses).
- `GET /v1/meta` carries `environment` since 1.2.2; 0.2.4 has no property for
  it, the suite reads it from `additionalProperties`.

## TODO for the 0.3.0 regeneration (API 1.3.0 and later)

Out of scope while the library is 0.2.4; add to the suite when it is
regenerated:

- `recordSale` and `reverseSale` with receipt `lines` (the universal line-item
  schema), the with/without comparison, and `billMinor` on `spend`
  (`422 BILL_REQUIRED`).
- Earn rules (`docs/EARN-RULES.md` of the core): `listEarnGroups`,
  `createEarnGroup`, `getEarnGroup`, `updateEarnGroup`, `deleteEarnGroup`
  (`409 GROUP_IN_USE`), `listSeenLines`, `listEarnSources`, `getEarnRules`,
  `putEarnRules` (`409 REVISION_CONFLICT`), `createEarnRule`, `updateEarnRule`,
  `deleteEarnRule`, and the two dry-run operations.
- Branch QR (`docs/BRANCH-QR.md`): a branch's QR with its curated and seasonal
  programmes, multi-join, the single-entry flow for code cards, branch freeze
  (`frozen` on a location, `LOCATION_FROZEN` at the till).
- `environment` as a typed property of `getMeta`'s answer.
- English API messages (1.4.0): the error `message` assertions, if any are added,
  must not depend on the language.
- Webhook delivery to a reachable https address (needs a tunnel; the suite only
  creates, rotates and deletes webhooks) and `Webhook` signature verification
  against a real delivery.
- PATCH operations (`setWebhookStatus`, `updateProgram` …) with the OkHttp
  transport (the default transport cannot send PATCH on Java 12 and later).
- Live feed (`liveFeed`, SSE) against the dev server.
