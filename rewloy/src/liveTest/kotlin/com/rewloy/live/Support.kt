package com.rewloy.live

import com.rewloy.RewloyException
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Runs [block], which must fail with a [RewloyException] of this status and code; returns it for more checks. */
inline fun expectError(status: Int, code: String, block: () -> Unit): RewloyException {
    try {
        block()
    } catch (e: RewloyException) {
        assertEquals(status, e.status, "status of $code (got ${e.status} ${e.code}: ${e.detail})")
        assertEquals(code, e.code, "code (status ${e.status}: ${e.detail})")
        return e
    }
    fail("expected $status $code, but the call succeeded")
}

/** Every API error carries the same three things (and the catalogue link). */
fun RewloyException.assertIsApiError() {
    assertTrue(code.isNotBlank(), "code")
    assertTrue(status in 400..599, "status $status")
    assertNotNull(requestId, "requestId")
    assertTrue(requestId!!.isNotBlank(), "requestId")
}
