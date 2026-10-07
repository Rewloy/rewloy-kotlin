package com.rewloy.live

import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * On every area: skips it when REWLOY_BASE_URL or REWLOY_API_KEY is not set (so the plain test run is never
 * affected), and refuses it, as a failure with a clear message, when the server is not a dev Rewloy or the key
 * is not a test key. No request that changes anything is sent before the guard has passed.
 */
class LiveGuard : ExecutionCondition, BeforeAllCallback {
    override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult =
        if (Live.configured) ConditionEvaluationResult.enabled("REWLOY_BASE_URL and REWLOY_API_KEY are set")
        else ConditionEvaluationResult.disabled("REWLOY_BASE_URL and REWLOY_API_KEY are not set")

    override fun beforeAll(context: ExtensionContext) {
        Live.guard()
    }
}
