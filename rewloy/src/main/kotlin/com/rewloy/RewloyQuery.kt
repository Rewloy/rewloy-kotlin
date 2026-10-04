package com.rewloy

/** The base of the query classes (`ListCustomersQuery`…): the parameters of the URL's query string. */
public abstract class RewloyQuery internal constructor() {
    internal abstract fun writeTo(writer: QueryWriter)
}
