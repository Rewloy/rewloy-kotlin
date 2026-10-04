# Decisions

Choices made while building v0.1 without the owner (4 Oct 2026). The Node
library's decisions (`Rewloy/rewloy-node`, docs/DECISIONS.md, 24 of them) and
the .NET library's hold here unless one below replaces it. Each can be
revisited; most are a line to change.

**Reused as they are** (Node's numbers): own emitter (1), sunset dates read
from the platform's sentence (3), generation refuses what it does not
understand (5), English library text with the API's Turkish descriptions and a
bilingual README (8), a credential is left out where an operation does not take
its kind but works without one (13), construction is checked and the error
never carries the value (14), the retry rules and their numbers (15), timeouts
per attempt (16), UUID v4 idempotency keys sent for every operation that
declares the header (17), one error hierarchy with the same codes for what never
got an answer (18), streams reconnect by default (21), webhooks accept any `v1`
and any of several secrets with a ±300 s tolerance (22), and the regeneration
workflow (24). From .NET: fields the API adds are kept (4), enums stay strings
(5), required fields are not made up for an answer that lacks them (7, but see
11 below), `Optional<T>` where `null` means something (8), a twin method for the
whole answer (14).

## Targets

1. **JVM 8 bytecode, and Android API 21.** The library is compiled with
   `jvmTarget` 1.8 and `-Xjdk-release=8`, so that nothing newer than Java 8's
   class library can be called by mistake.
   - **Why 8:** POS terminals run old Android (5.0 to 9 are common on payment
     devices that were certified years ago), and Android's tooling understands
     Java 8 bytecode (desugaring) but not 11 or 17 without more settings. Java 8
     is also what many older back-office servers run. Nothing in the library
     wants anything newer, so there is no reason to cut off those users.
   - **Why API 21 (Android 5.0):** it is the oldest level the Kotlin standard
     library and OkHttp 4 support, it has TLS 1.2 on by default, and it is the
     oldest level `androidscents` publishes a signature for that this build can
     check. Below 21 (4.4 and older) TLS 1.2 is off by default and the platform
     is long out of any certification, so it is not promised.
   - **How it is checked, not just claimed:** the `animalsniffer` task checks the
     library's bytecode against the API 21 signature on every `check`; and the
     whole test suite also runs on a real Java 8 runtime (`-PtestJava=8`, in CI).
     Neither replaces trying a device; there is no Android SDK here.
   - **What that rules out:** `java.time` (API 26, or desugaring that the
     app must switch on), `java.util.Base64` (26), `Optional` and streams
     (24), `Map.getOrDefault` and `ConcurrentHashMap.newKeySet` (24), `java.net.http`
     (absent), `LambdaMetafactory` (26). The code uses Kotlin's own
     equivalents, and compiles lambdas to classes (`-Xlambdas=class`) so that no
     `invokedynamic` is left for the build tools to desugar.
2. **Kotlin 2.0 in the caller.** The library is written with `languageVersion`
   and `apiVersion` 2.0, so its metadata is readable by a Kotlin 2.0 or later
   compiler, and it depends on `kotlin-stdlib` 2.0.21 (Gradle takes the
   caller's newer one). A Java-only caller needs no Kotlin compiler at all.
   Older Kotlin (1.9) is not promised: a 1.9 compiler refuses newer metadata.
3. **Three artifacts, one repository.**
   - `com.rewloy:rewloy`: the library, depending on the standard library only;
   - `com.rewloy:rewloy-okhttp`: a `Transport` over OkHttp (4.12; a caller on 5.x
     gets it by resolution). A separate artifact, so that nobody who does not use it
     pays for it;
   - `com.rewloy:rewloy-coroutines`: `suspending` and `Flow` (kotlinx.coroutines
     1.8.1, the oldest that works on 2.0).
   There is no Android Gradle plugin anywhere: they are plain JVM jars, which
   Android apps consume as they do any library.

## Generation

5. **The generator is Kotlin, in the repository** (`generator/`, a console
   project), not a Node script.
   - **Who needs what:** a Kotlin or Java developer who wants to regenerate
     needs only a JDK (`./gradlew generate`).
   - **No other dependency:** it reads and writes JSON with the library's own
     reader and writer (the one package `com.rewloy.json` is compiled into both),
     so it runs before any generated code exists.
   - **The snapshot:** the writer's indented form is byte for byte
     `JSON.stringify(doc, null, 2)`, so `openapi/openapi.json` is the file the Node,
     PHP and .NET libraries keep (`cmp` agrees). A test pins the format.
   - **Generated files:** `RewloyApi.kt`, `RewloyOperations.kt`, `ErrorCode.kt`,
     and one `models/<Tag>.kt` per tag of the document (21), so that no file is
     tens of thousands of lines. Output is deterministic and a test compares the
     committed files with a fresh run.
6. **Classes for objects, `JsonValue` for what has no shape.** The document has
   about 800 inline object schemas (about 630 classes), 2 `oneOf` and 2 `anyOf`.
   - **Request bodies and queries** are mutable classes: required fields are
     constructor parameters (first, without a default), the rest `= null`, so Kotlin
     writes `ListCustomersQuery(limit = 50)` and Java gets `new X(required…)` and
     `setY(…)`. A field left `null` is not sent.
   - **Answers** are immutable classes with `val` properties and a constructor with
     all of them. They have no `equals`, `hashCode`, `copy` or `toString` (a data
     class has them, at the cost of dozens of methods in each of 630 classes, and a
     `toString` would print an answer's secrets into a log).
   - **`JsonValue`** is used for a union (`me`, a team grant's `locations`), for a
     free-form object and for a type the document does not give.
   - **A map** (`additionalProperties` with a schema) is `Map<String, T>`, a list `List<T>`.
7. **Ids and timestamps are `String`s; integers are `Int` when the schema bounds
   them inside 32 bits and `Long` otherwise; numbers are `Double`.** .NET has `Guid`
   and `DateTimeOffset`; here `java.util.UUID` buys nothing for ids that are only
   passed back, and `java.time` is missing before Android 8 (API 26) unless the app
   enables desugaring. A caller who wants an `Instant` parses the text.
8. **Required means required in an answer.** A property the document lists as
   required and non-null is a non-null Kotlin property, which cannot be left
   unset. When an answer lacks it, or has it with another type, the call throws
   `RewloyException` with code `INVALID_RESPONSE`, naming the path (`$.data.serial`),
   with the status, the request id and the raw body on it.
   - **The risk:** a documented field that the API forgets turns a successful call
     into an exception, for an operation the server has already done. The
     exception carries the body, and an `Idempotency-Key` retry replays the first
     answer, so nothing is lost, but it is a stricter choice than Node's (types are
     erased there) or .NET's (the field is just `null`). Kotlin cannot say "non-null
     but absent" honestly, and a nullable everything would give up the types. The
     contract is the document, which CI follows daily.
   - **Not an error:** an extra field (kept), a `null` for an optional field (the
     same as absent), an enum value the library has not heard of (enums are strings).
9. **Fields the API adds are kept.** Every model derives from `RewloyObject`, whose
   `additionalProperties` hold what the class has no property for, and
   `setAdditionalProperty` sends one in a request.
10. **`OptionalField<T>` where `null` means something.** In a request a property
    whose schema allows `null` (seven fields, on four operations) is
    `OptionalField<T>?`: `null` (not sent), `OptionalField.of(v)`, or
    `OptionalField.ofNull()` (sent as `null`).
11. **Method names and shapes.**
    - **The name** is the operationId as it is: `getPass`. Methods block, so there is
      no `Async` suffix; a paged list also has `…All`, and every operation except a
      stream has `…WithResponse` (14).
    - **The arguments:** path parameters, then the body, then the query, then
      `RequestOptions`, as in .NET. A body or a query is optional only when everything
      after it is; `@JvmOverloads` gives Java the shorter forms.
    - **Property names** are camelCase of the JSON name, in backticks when a Kotlin
      keyword, with `Value` added when the JVM getter would clash with `Object`'s
      or the model's own (`class` → `classValue`).
    - **Refusals:** two operations that make the same method name, a name the client
      has itself, a header parameter other than `Rewloy-Merchant` and `Idempotency-Key`,
      a body that is not JSON or not an object, `allOf`, an unknown `$ref`, a paged list
      without a `page` parameter or with a body.
12. **Deprecations are `@Deprecated`** on the method of a deprecated operation and on
    a deprecated field (five today, all until 5 April 2027), with the sunset and the
    replacement from `x-deprecation`. The compiler warns.

## Shape of the API: blocking, plus suspend where it fits

13. **Blocking methods are the API; `suspend` is an add-on, not a second copy.**
    - **Why blocking:** the audience writes Java as much as Kotlin (Android POS
      SDKs from the terminal vendors are Java), and a blocking method is what both
      can call, from a thread, an `Executor`, an `AsyncTask` or a coroutine.
      `HttpURLConnection` is blocking anyway; wrapping it in `suspendCoroutine` would
      only hide a thread.
    - **Why not 237 more `suspend` methods:** they would double the generated code
      and the public surface, and could not be written without a dependency on
      kotlinx.coroutines, which a Java caller must not pay for.
    - **What is offered instead** (`rewloy-coroutines`): `rewloy.suspending { getPass(s) }`
      runs a block of calls on `Dispatchers.IO` with a client tied to the coroutine
      (`withCancel`), so that cancelling the coroutine aborts the request in flight;
      and `EventStream.asFlow()`. One wrapper covers every operation, and a block may
      make several calls or walk a paged list.
14. **Results: the data, and a twin for the whole answer.** A method returns the
    answer's data: a class, `Page<T>` for a paged list, `RewloyFile` for a file,
    `JsonValue` for the OpenAPI document, `Unit` for a 204. `…WithResponse` returns
    `RewloyResponse<T>` with the status, headers, `requestId`, `mode`, `isTestMode`
    and `replayed`, so that the test mode (`Rewloy-Mode`) is read from the raw answer
    as Node's `request()` does. No `lastResponse` property, which would race.
15. **`Iterable` for pages and streams.** `…All` returns an `Iterable` (each
    `iterator()` is a new walk, and nothing is asked until the first `next`); a
    Kotlin caller adds `.asSequence()`. An `EventStream` is an `Iterable` and
    `Closeable` (Java's try-with-resources, Kotlin's `use`) that can be iterated
    once; `close()` from another thread aborts a blocked read.
16. **Exceptions are unchecked.** `RewloyException` is a `RuntimeException`, so Java
    callers need no `throws`. Cancelling is `java.util.concurrent.CancellationException`
    (never wrapped), as in coroutines.
17. **Options are builders.** `Rewloy { apiKey(…) }` for Kotlin and
    `RewloyOptions.builder()…build()` for Java; `RequestOptions` is a class with
    default arguments and a builder. Durations are milliseconds (`Long`): `Duration`
    is `java.time`.

## HTTP, JSON and concurrency

18. **`HttpURLConnection` behind `Transport`.** It is the one HTTP stack on every JVM
    and every Android version, with no dependency. `Transport` is one method:
    `execute(TransportRequest): TransportResponse`, with the body still unread, a
    `CancelToken` to watch and the timeouts for connecting and reading. The client
    keeps everything else (retries, the deadline of an attempt, errors, JSON), so an
    adapter is about forty lines.
    - **Redirects** are not followed and the connection cache is off.
    - **Gzip:** the client sends `Accept-Encoding: gzip` and decompresses itself, so
      the behaviour is the same on Android (which would decompress only what it asked
      for) and on a JVM (which never does), and with OkHttp.
    - **`PATCH`** is the one verb `HttpURLConnection` on a desktop JDK refuses. Up to
      Java 11 a reflective workaround sets the method (http and https); from Java 12
      it is blocked and the call throws `UnsupportedOperationException` saying to use
      OkHttp, and sending another verb instead (a method override header) was
      rejected: a server that ignored it would run a different operation. Android's
      `HttpURLConnection` accepts `PATCH`. The workaround was exercised on Java 8 over
      http; https relies on the same field (`delegate`) and is not tested here.
    - **A request that must not be sent twice is written in streaming mode.** The
      JDK re-sends a POST by itself when the connection drops before the answer
      (`sun.net.http.retryPost`, on by default), which would double a `createProgram`
      that has no `Idempotency-Key`; fixed-length streaming mode is what stops it
      (checked on JDK 21). `TransportRequest.repeatable` says which requests those are
      (the ones the retry rules would not repeat), and the OkHttp adapter turns its own
      `retryOnConnectionFailure` off for them. The price: in streaming mode the JDK does
      not give the body of a 401, so the transport asks again, buffered. That is safe,
      because a 401 did nothing. A setting that changed the JVM's property was
      rejected: it is global to the host program.
    - **Aborting.** `disconnect()` can wait for a read that is blocked on the same
      connection, so the transport calls it on a short-lived daemon thread, and
      the client reads a body in a loop that looks at the attempt's token between reads.
      A read that blocks for good is ended by the read timeout (the attempt's).

19. **The deadline of an attempt is the library's.** `HttpURLConnection` only has a
    timeout per read, so a single daemon thread (`Timeouts`) cancels an attempt's
    `CancelToken` when `timeoutMs` passes, the transport aborts the connection, and
    the failure is a retryable `RewloyTimeoutException`. For a stream the deadline
    covers the headers only, and the read timeout is the idle time.
20. **Own JSON reader and writer, no kotlinx.serialization.** About 400 lines,
    strict (RFC 8259, depth limit 512), numbers kept as text, written the way
    `JSON.stringify` writes.
    - **Why:** kotlinx.serialization needs a compiler plugin and a runtime
      (about 1 MB with the JSON module), puts a second dependency on every Java
      caller, and its reflection-free serializers for 630 classes would be generated
      anyway; our generator emits the reading and writing code directly, with no
      reflection (R8 and ProGuard need no rules) and no annotations to keep.
    - **The cost:** the reading code in the generated models is ours to test; the
      generator's tests and the answers the client tests read cover it.
    - **`JsonValue`** is public: it is the type of every free-form field.
21. **`CancelToken`.** A tiny thread-safe token with callbacks, in `RequestOptions`
    and in `withCancel`. Every attempt hangs off one token per call, which the
    caller's tokens cancel; the transport registers `disconnect()` on it. It also wakes
    a retry that is sleeping.
22. **A `Sleeper` for the waits** (retries, reconnections), public like Node's
    `sleep`, so that tests do not sleep.

## Client

23. **`User-Agent`:** `rewloy-kotlin/0.1.0 java/<version> [suffix]`, or
    `android/<SDK_INT>` on Android (read by reflection, so that no Android class is
    linked). Not the `Rewloy-Client` header, for Node's decision 12.
24. **Deprecation notices go to a listener, else to `java.util.logging`.**
    `DeprecationListener` is per client and called once per operation per client;
    without one, the notice is logged once per operation per process to the logger
    `com.rewloy` (which Android routes to Logcat). Node's `process.emitWarning` and
    .NET's static event are global; a per-client listener is easier to test and
    does not leak between clients. A listener that throws is ignored: the server has
    already acted.
25. **Webhooks.** `Webhook.verify(payload, header, secret…)` takes the body as
    `ByteArray` or `String`, one secret or a list, a tolerance in seconds and
    `nowSeconds` (a `Long`: no `java.time`); it returns a `WebhookEvent` with `root`
    (the body as `JsonObject`), `type`, `id`, `createdAt` (text), `data` and `passData`.
    A signed body that is not a JSON object is refused (`PAYLOAD`), as PHP and .NET do;
    an empty secret is an `IllegalArgumentException`. The comparison is
    `MessageDigest.isEqual`, constant-time since Java 6 update 17 and on every Android.
    The two fixed vectors are Node's. `sign` is public for testing a handler.
26. **Streams** are Node's 21 and .NET's 24: reconnect after the server's `retry:`
    with backoff to 30 s; 401, 403 and 404 end it with the exception; closing,
    cancelling and leaving the loop end it quietly; `Last-Event-ID` carries over;
    `Accept-Encoding: identity` and `Cache-Control: no-cache` on the wire. The bytes
    are decoded with a stateful UTF-8 decoder, so a character split between reads
    survives; the `SseParser` is public and takes text in pieces of any size.
    - **A reader thread per stream.** On a JVM a read blocked inside
      `HttpURLConnection` cannot be aborted (`disconnect()` leaves a keep-alive
      socket open; found by a test that waited 60 s for `close()`). So a daemon thread
      reads the connection into a small queue and the iterating thread waits on the
      queue: `close()`, a `CancelToken` and the idle check wake it at once on every
      transport. The abandoned reader ends at the read timeout (the idle time) or when
      the server closes. The cost is one idle thread per open stream.
27. **Query strings** are RFC 3986 (`%20`), booleans `true`/`false`, a list repeats its
    key, a `null` is left out. Path values are escaped as a whole (`/` too).

## Tests and CI

28. **Tests.** JUnit 5 and `kotlin.test` against a stub server on `com.sun.net.httpserver`:
    no network, no credential. They cover construction, requests, retries, errors,
    paging, the SSE parser and stream (pieces of one byte, a silent connection,
    closing from another thread), webhooks, deprecations, cancelling, JSON,
    the generated client against the document (every operationId is a method and a
    table row), a Java test class (builders, overloads, for-each, try-with-resources)
    and the generator (determinism, the committed output being current, a fixture of
    cases the live document has not got, refusals). 139 in all: 116 for the
    library, 6 for the OkHttp transport, 8 for coroutines and 9 for the generator. The
    README's examples are compiled, and the ones that can run, run. All run on JDK 21,
    and the three library modules' tests also on a Java 8 runtime. A JUnit method
    that returns a value is skipped silently, so the tests end in `Unit` (a helper,
    `Rig.test`, does it) and a count of `@Test` against the run was checked once.
29. **What is not tested:** a real Android device or emulator, any Android API level
    (the check is the animalsniffer signature plus Java 8), https `PATCH` on Java 8,
    and a real TLS handshake (the stub is plain http).
30. **`ci.yml`:** `actions/checkout@v7`, `actions/setup-java@v6` (Temurin 21; 8 and 21
    for the Java 8 job) and `gradle/actions/setup-gradle@v6`, on Ubuntu and Windows;
    a job that runs the tests on Java 8; and a package job that builds every artifact
    into a local directory and lists it. **`regenerate.yml`** is Node's decision 24,
    with `./gradlew check` as its check, at 06:17 UTC (Node's runs at 05:23, PHP's at
    05:41, .NET's at 05:59).

## Package

31. **Coordinates:** group `com.rewloy`, artifacts `rewloy`, `rewloy-okhttp` and
    `rewloy-coroutines`, version 0.1.0 (kept in step in `build.gradle.kts`,
    `RewloyVersion` and CHANGELOG.md; a test says so). Publishing uses the
    `com.vanniktech.maven.publish` plugin to Central Portal: sources jar, an empty
    javadoc jar, the POM (MIT, SCM, developer), signing from an in-memory key. It is
    configured and not run. Needed: the Portal account and the verified `com.rewloy`
    namespace (DNS TXT on rewloy.com), a GPG key, and the `Rewloy/rewloy-kotlin`
    repository the POM points to, which does not exist yet.
