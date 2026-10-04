package com.rewloy

/**
 * A request field that can be left out, set, or set to `null`. A plain nullable property cannot say "clear this":
 * `null` there means "not sent". A few fields of the API (a holder's profile corrections, a segment's note, an
 * automation's programme) take an explicit `null` to clear a value; those are `OptionalField<T>?`:
 *
 * - `null` (the default): the field is not sent;
 * - [of]: the field is sent with a value;
 * - [ofNull]: the field is sent as JSON `null`.
 */
public class OptionalField<T : Any> private constructor(
    /** The value; `null` for an explicit JSON `null`. */
    public val value: T?,
) {
    /** Whether this sends JSON `null`. */
    public val isNull: Boolean get() = value == null

    override fun equals(other: Any?): Boolean = other is OptionalField<*> && other.value == value

    override fun hashCode(): Int = value?.hashCode() ?: 0

    override fun toString(): String = "OptionalField($value)"

    public companion object {
        /** A field sent with [value]. */
        @JvmStatic
        public fun <T : Any> of(value: T): OptionalField<T> = OptionalField(value)

        /** A field sent as JSON `null`. */
        @JvmStatic
        public fun <T : Any> ofNull(): OptionalField<T> = OptionalField(null)
    }
}
