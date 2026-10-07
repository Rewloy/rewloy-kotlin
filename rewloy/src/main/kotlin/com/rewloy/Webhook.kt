package com.rewloy

import com.rewloy.json.JsonObject
import com.rewloy.json.JsonParseException
import com.rewloy.json.JsonValue
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * What a webhook's `data` holds for the `pass.*` events: only business facts, never contact details. Read the
 * person behind [customerId] from the API.
 */
public class PassEventData internal constructor(
    /** What happened on the card: `join`, `earn`, `redeem`, `spend`, `visit_credit`, `load`, `void`… */
    public val kind: String,
    /** The card's serial number, XXXX-XXXX-XXXX. */
    public val card: String?,
    /** The programme's id. */
    public val programId: String?,
    /** The branch's id. */
    public val locationId: String?,
    /** The customer's id: read the person from the API. */
    public val customerId: String?,
    /** What [delta] counts: `stamp`, `point`, `try_minor` (kuruş)… */
    public val unit: String?,
    /** How much the card changed, in [unit]. */
    public val delta: Double?,
    /** Why, for a void. */
    public val reason: String?,
)

/**
 * A webhook delivery's body. Read the person behind `customer_id` from the API; new event types may appear, so
 * keep a default branch on [type].
 */
public class WebhookEvent internal constructor(
    /** The whole body, as JSON. */
    public val root: JsonObject,
) {
    /** The event's id; `null` for the test delivery. */
    public val id: String? get() = root["id"]?.asString()

    /**
     * The event type: `pass.issued`, `pass.activity`, `pass.voided`, `pass.extended`, `location.frozen`,
     * `location.unfrozen`, `business.paused`, `business.resumed` (the last five since API 1.3.0; `webhookEvents` lists
     * them) or `webhook.test` (the panel's or `testWebhook`'s test delivery). The same as the `Rewloy-Event` header.
     */
    public val type: String get() = root["type"]?.asString() ?: ""

    /** When it happened (`created_at`, ISO 8601), when the body says. */
    public val createdAt: String? get() = root["created_at"]?.asString()

    /** The event's `data`, or JSON `null` when it has none. */
    public val data: JsonValue get() = root["data"] ?: com.rewloy.json.JsonNull

    /** The `data` of a `pass.*` event; `null` for any other type or a `data` that is not an object. */
    public val passData: PassEventData?
        get() {
            val d = data as? JsonObject ?: return null
            if (!type.startsWith("pass.")) return null
            return PassEventData(
                kind = d["kind"]?.asString() ?: "",
                card = d["card"]?.asString(),
                programId = d["program_id"]?.asString(),
                locationId = d["location_id"]?.asString(),
                customerId = d["customer_id"]?.asString(),
                unit = d["unit"]?.asString(),
                delta = d["delta"]?.asDouble(),
                reason = d["reason"]?.asString(),
            )
        }
}

/** Why a delivery was refused. */
public enum class WebhookFailure {
    /** There is no `Rewloy-Signature` header. */
    MISSING,

    /** The header is not `t=<unix seconds>,v1=<hex>`. */
    MALFORMED,

    /** `t` is further from now than the tolerance. */
    EXPIRED,

    /** No `v1` matches the body and the secret. */
    MISMATCH,

    /** The body is correctly signed but is not a JSON object. */
    PAYLOAD,
}

/** The delivery is not a genuine one: answer it with 400 and do not act on it. */
public class WebhookSignatureException(
    /** Why it was refused. */
    public val reason: WebhookFailure,
    message: String,
) : RuntimeException(message)

/**
 * Webhook signatures, exactly as the platform signs a delivery:
 *
 * `Rewloy-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, "<t>.<raw body>")>`
 *
 * The key is the whole secret as shown once when the webhook was created (`whsec_…`, prefix included); the
 * message is the timestamp, a dot and the body's bytes as they arrived. Each delivery attempt is signed anew, so
 * a retry carries a fresh `t`.
 *
 * Every delivery also carries `Rewloy-Event` (the event type) and `Rewloy-Delivery` (the delivery's id: the same
 * on every retry of one delivery; deliveries are at least once, so skip an id already handled).
 *
 * ```kotlin
 * val event = try {
 *     Webhook.verify(rawBody, request.getHeader("Rewloy-Signature"), System.getenv("REWLOY_WEBHOOK_SECRET"))
 * } catch (e: WebhookSignatureException) {
 *     return respond(400)
 * }
 * ```
 */
public object Webhook {
    private const val DEFAULT_TOLERANCE_SECONDS = 300L

    /**
     * Checks a delivery's `Rewloy-Signature` and returns its parsed body. Throws [WebhookSignatureException] when
     * the header is missing or malformed, `t` is further than [toleranceSeconds] from now, or no `v1` matches; the
     * comparison takes constant time.
     *
     * @param payload the body exactly as it arrived, as bytes (read the request's input stream, not a parsed
     * model: parsing and writing JSON again changes the bytes the signature covers).
     * @param header the `Rewloy-Signature` header (several values are joined with commas).
     * @param secret the webhook's secret (`whsec_…`).
     * @param toleranceSeconds how far `t` may be from now, in seconds. Default 300.
     * @param nowSeconds the current time as Unix seconds, for tests; the clock when `null`.
     * @throws IllegalArgumentException when the secret is empty: a missing setting, not a bad delivery.
     */
    @JvmStatic
    @JvmOverloads
    public fun verify(
        payload: ByteArray,
        header: String?,
        secret: String,
        toleranceSeconds: Long = DEFAULT_TOLERANCE_SECONDS,
        nowSeconds: Long? = null,
    ): WebhookEvent = verify(payload, header, listOf(secret), toleranceSeconds, nowSeconds)

    /** Like [verify] with the body as text (UTF-8). */
    @JvmStatic
    @JvmOverloads
    public fun verify(
        payload: String,
        header: String?,
        secret: String,
        toleranceSeconds: Long = DEFAULT_TOLERANCE_SECONDS,
        nowSeconds: Long? = null,
    ): WebhookEvent = verify(payload.toByteArray(Charsets.UTF_8), header, listOf(secret), toleranceSeconds, nowSeconds)

    /** Like [verify] with several secrets, any of which may match: while you move from one webhook to another. */
    @JvmStatic
    @JvmOverloads
    public fun verify(
        payload: String,
        header: String?,
        secrets: List<String>,
        toleranceSeconds: Long = DEFAULT_TOLERANCE_SECONDS,
        nowSeconds: Long? = null,
    ): WebhookEvent = verify(payload.toByteArray(Charsets.UTF_8), header, secrets, toleranceSeconds, nowSeconds)

    /** Like [verify] with the body as bytes and several secrets, any of which may match. */
    @JvmStatic
    @JvmOverloads
    public fun verify(
        payload: ByteArray,
        header: String?,
        secrets: List<String>,
        toleranceSeconds: Long = DEFAULT_TOLERANCE_SECONDS,
        nowSeconds: Long? = null,
    ): WebhookEvent {
        val keys = secrets.filter { it.isNotEmpty() }
        require(keys.isNotEmpty()) { "The secret is empty: a missing setting, not a bad delivery." }
        if (header == null || header.trim().isEmpty()) throw WebhookSignatureException(WebhookFailure.MISSING, "No Rewloy-Signature header")

        var t: String? = null
        val candidates = ArrayList<ByteArray>(1)
        for (part in header.split(',')) {
            val eq = part.indexOf('=')
            if (eq == -1) continue
            val k = part.substring(0, eq).trim()
            val v = part.substring(eq + 1).trim()
            if (k == "t" && t == null) t = v else if (k == "v1" && isHex64(v)) candidates.add(fromHex(v))
        }
        val signedAt = t?.let { if (it.isNotEmpty() && it.all { c -> c in '0'..'9' }) it.toLongOrNull() else null }
        if (t == null || signedAt == null || candidates.isEmpty()) {
            throw WebhookSignatureException(WebhookFailure.MALFORMED, "Rewloy-Signature is not \"t=<unix seconds>,v1=<hex>\"")
        }

        val now = nowSeconds?.toDouble() ?: (System.currentTimeMillis() / 1000.0)
        if (Math.abs(now - signedAt) > toleranceSeconds) {
            throw WebhookSignatureException(WebhookFailure.EXPIRED, "The signature's time (t=$t) is more than $toleranceSeconds seconds from now")
        }

        var matched = false
        for (secret in keys) {
            val expected = mac(secret, t, payload)
            for (candidate in candidates) {
                // Every pair is compared, in constant time, whether or not one matched already.
                if (MessageDigest.isEqual(candidate, expected)) matched = true
            }
        }
        if (!matched) throw WebhookSignatureException(WebhookFailure.MISMATCH, "No v1 signature matches the body and the secret")

        val parsed = try {
            JsonValue.parse(payload)
        } catch (e: JsonParseException) {
            throw WebhookSignatureException(WebhookFailure.PAYLOAD, "The signed body is not JSON")
        }
        if (parsed !is JsonObject) throw WebhookSignatureException(WebhookFailure.PAYLOAD, "The signed body is not a JSON object")
        return WebhookEvent(parsed)
    }

    /**
     * The `Rewloy-Signature` header the platform would send for this body: for testing your own webhook handler.
     *
     * @param timestampSeconds the time to sign with, as Unix seconds; now when `null`.
     */
    @JvmStatic
    @JvmOverloads
    public fun sign(payload: ByteArray, secret: String, timestampSeconds: Long? = null): String {
        val t = (timestampSeconds ?: (System.currentTimeMillis() / 1000)).toString()
        return "t=$t,v1=${toHex(mac(secret, t, payload))}"
    }

    /** Like [sign] with the body as text (UTF-8). */
    @JvmStatic
    @JvmOverloads
    public fun sign(payload: String, secret: String, timestampSeconds: Long? = null): String =
        sign(payload.toByteArray(Charsets.UTF_8), secret, timestampSeconds)

    private fun mac(secret: String, t: String, body: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        mac.update("$t.".toByteArray(Charsets.UTF_8))
        mac.update(body)
        return mac.doFinal()
    }

    private fun isHex64(v: String): Boolean = v.length == 64 && v.all { Character.digit(it, 16) >= 0 && it.code < 128 }

    private fun fromHex(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) out[i] = ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
        return out
    }

    private const val HEX = "0123456789abcdef"

    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v shr 4]).append(HEX[v and 15])
        }
        return sb.toString()
    }
}
