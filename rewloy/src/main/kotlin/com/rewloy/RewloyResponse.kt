package com.rewloy

import com.rewloy.models.PageMeta
import java.util.Locale

/**
 * The whole answer to a call: what the `…WithResponse` methods return. The plain methods give only the data.
 * [T] is the answer's data: a model class, `Page<Item>` is spelled `List<Item>` here with [meta] beside it, a
 * [RewloyFile], a [com.rewloy.json.JsonValue], or `Unit` for a 204.
 */
public class RewloyResponse<out T> internal constructor(
    /** The HTTP status: 200, 201, 202 or 204. Some operations answer 200 when they found what they would have created. */
    public val statusCode: Int,
    /** The answer's headers. */
    public val headers: Headers,
    /** What the answer's `data` held. */
    public val data: T,
    /** Paging, on paged lists. */
    public val meta: PageMeta? = null,
) {
    /** `x-request-id`: quote it to Rewloy support. */
    public val requestId: String? get() = headers["x-request-id"]

    /** The `RateLimit-*` headers of the answer; `null` when it carries none (anonymous calls). */
    public val rateLimit: RewloyRateLimit? get() = RewloyRateLimit.from(headers)

    /**
     * `Rewloy-Mode`: which mode answered (`test` for a test key's calls, which reach no customer); `null` when
     * the answer does not say.
     */
    public val mode: String? get() = headers["rewloy-mode"]

    /** The answer came from a test key (`Rewloy-Mode: test`). */
    public val isTestMode: Boolean get() = mode?.lowercase(Locale.ROOT) == "test"

    /** `Idempotent-Replayed: true`: the API replayed the first answer to this `Idempotency-Key`. */
    public val replayed: Boolean get() = headers["idempotent-replayed"]?.lowercase(Locale.ROOT) == "true"
}

/** One page of a paged list. */
public class Page<out T> internal constructor(
    /** This page's items. */
    public val data: List<T>,
    /** Which page this is, its size and the total number of items. */
    public val meta: PageMeta,
) {
    internal companion object {
        fun <T> from(response: RewloyResponse<List<T>>): Page<T> =
            Page(response.data, response.meta ?: PageMeta(1L, response.data.size.toLong(), response.data.size.toLong()))
    }
}

/** A file the API answers with: a QR image, a map, a CSV, a `.pkpass`. */
public class RewloyFile(
    /** The file's bytes. */
    public val content: ByteArray,
    /** The media type, such as `image/png`. */
    public val contentType: String?,
    /** The name the answer suggests (`Content-Disposition`), when it does. */
    public val fileName: String?,
) {
    override fun toString(): String = "RewloyFile(${content.size} bytes, $contentType${if (fileName != null) ", $fileName" else ""})"
}
