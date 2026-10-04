package com.rewloy

/** The kinds of credential the API knows, by what they are for. */
public enum class CredentialKind {
    /** An API key, `rwk_…`: a till, a shop, your own system. */
    KEY,

    /** A staff session, `rws_…`: a person's business app. */
    STAFF,

    /** A card holder's session, `rwh_…`: a Rewloy Cüzdan-style customer app. */
    HOLDER,

    /** No credential at all: the endpoints that open sessions, join programmes and so on. */
    PUBLIC,
}

/** Whether an operation takes an `Idempotency-Key`. */
public enum class IdempotencyMode {
    /** The operation takes none. */
    NONE,

    /** The operation takes one and works without. */
    OPTIONAL,

    /** The operation needs one. The client generates one when the call gives none. */
    REQUIRED,
}

/** How an operation's answer is read. */
public enum class ResponseKind {
    /** `{ "data": … }`, or `{ "data": […], "meta": … }` for a paged list. */
    JSON,

    /** No body (204). */
    NONE,

    /** A file (an image, a CSV, a `.pkpass`). */
    FILE,

    /** JSON without the `data` envelope (the OpenAPI document). */
    RAW_JSON,

    /** Server-sent events. */
    STREAM,
}

/** When the API stops answering a deprecated operation, and what replaces it. */
public class Deprecation internal constructor(
    /** The date, `YYYY-MM-DD`, when the platform's text gives one. */
    public val sunset: String?,
    /** The operationId to use instead, when the text names one. */
    public val use: String?,
)

/**
 * What the client knows about one operation: its method and path, the credential kinds it accepts, whether it
 * takes `Rewloy-Merchant` and `Idempotency-Key`, how its answer is read and whether it is paged or deprecated.
 * The table of all of them is [RewloyOperations.all].
 */
public class OperationInfo internal constructor(
    /** The operationId: the method's name. */
    public val id: String,
    /** The HTTP method. */
    public val method: String,
    /** The path, with `{placeholders}`, from the origin (`/v1/passes/{serial}`). */
    public val path: String,
    /** The credential kinds the operation accepts; [CredentialKind.PUBLIC] means it also works without one. */
    public val credentials: Set<CredentialKind>,
    /** Whether it takes `Rewloy-Merchant`. */
    public val acceptsMerchant: Boolean,
    /** Whether it takes an `Idempotency-Key`. */
    public val idempotency: IdempotencyMode,
    /** Whether it takes a JSON body. */
    public val hasBody: Boolean,
    /** How its answer is read. */
    public val response: ResponseKind,
    /** Whether it is a paged list (`page`, `limit`, `meta`). */
    public val isPaged: Boolean,
    /** Set when the operation is deprecated. */
    public val deprecation: Deprecation?,
) {
    /** Whether the answer is a server-sent event stream. */
    public val isStream: Boolean get() = response == ResponseKind.STREAM

    override fun toString(): String = "$id ($method $path)"
}
