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
- `GET /v1/meta` carries `environment` since 1.2.2; 0.2.4 had no property for
  it and the suite read it from `additionalProperties`; 0.3.0 has `environment`.

## Added in 0.3.0 (API 1.3.0)

Areas 13 to 15 run after the older areas and before the clean-up:

- **earn rules and receipt lines:** a group (`createEarnGroup`, `getEarnGroup`,
  `listEarnGroups`, `updateEarnGroup`), templates and sources, rules (`getEarnRules`,
  `putEarnRules` with `409 REVISION_CONFLICT` on a stale revision, `createEarnRule`,
  `updateEarnRule`, `deleteEarnRule`, `listEarnRuleRevisions`), `previewEarn` (saved
  rules and a draft `ruleSet`), `previewSale` (writes nothing), `recordSale` with
  lines and the `earn` explanation (and its replay), `422 LINES_TOTAL_MISMATCH`, a line
  refund (`reverseSale` with `lines`, `linesLeft`), `listSeenLines` with
  `ignoreSeenLine` / `unignoreSeenLine`, `409 GROUP_IN_USE` and the group's deletion.
- **branch QR and branch freeze:** a branch's `qr` and the public page
  (`publicBranch`, no credential; `404 BRANCH_NOT_FOUND`), the PNG / SVG / PDF sheet / SVG sheet
  downloads, `getLocationQrItems`, `previewLocationQr`; `freezeLocation` with a key is
  refused (`403 CREDENTIAL_NOT_ALLOWED`). **With `REWLOY_STAFF_SESSION` and
  `REWLOY_STAFF_PASSWORD`** (the person's own password, asked again by the API; read from the
  environment, never printed) a branch made for the run is frozen: `409 LOCATION_FROZEN` on
  `recordSale` and `previewSale`, the public page says `frozen` with its note,
  `updateLocationFreeze`, `listLocationFreezes`; then every branch is frozen for
  `409 BUSINESS_FROZEN`, and all are opened again with `unfreezeLocation` (in a `finally`).
  Without them a NOTE says the freeze test was skipped.
- **gift card copy, code update, webhook events:** `copyProgram` of a loyalty card
  (`422 NOT_AN_INSTRUMENT`) and of a gift card with another value (and `422 INVALID_CONFIG`
  for an unknown override), `updateBatch`, the five new webhook events in `webhookEvents`
  and in `createWebhook`. `holderBranch` is checked only for its refusal of an API key.
- `PATCH` operations (`updateEarnGroup`, `updateEarnRule`, `updateLocationFreeze`,
  `updateBatch`) run over the OkHttp transport (`Live.okhttp`).
- `environment` is a typed property of `getMeta`; the guard and the java consumer read it.

## Still not covered

- `holderBranch` / `joinHolderBranch` (the Rewloy Cüzdan side of the branch QR) need a
  holder session, which an API key does not have.
- Webhook delivery to a reachable https address (needs a tunnel; the suite only
  creates, rotates and deletes webhooks) and `Webhook` signature verification
  against a real delivery.
- Live feed (`liveFeed`, SSE) against the dev server.
- Earn rules for points, cashback and VIP programmes, the shops' lines (store
  platforms), `extendProgramCards`, `putLocationQrItems` / `addQrItems`, and a card issued
  through a branch QR (`joinProgram` with `locationId`).
- English API messages (1.4.0): the error `message` assertions, if any are added,
  must not depend on the language.
