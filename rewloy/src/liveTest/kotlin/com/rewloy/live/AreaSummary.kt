package com.rewloy.live

import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.support.descriptor.ClassSource
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.TestPlan
import java.util.LinkedHashMap

/**
 * Prints the short summary at the end of a live run: passed, failed and skipped per area (a test class). Loaded
 * by the JUnit launcher through META-INF/services, so it needs no wiring in the build.
 */
class AreaSummary : TestExecutionListener {
    private class Counts {
        var passed = 0
        var failed = 0
        var skipped = 0
        val failures = ArrayList<String>()
    }

    private val areas = LinkedHashMap<String, Counts>()
    private var refused: String? = null
    private var plan: TestPlan? = null

    override fun testPlanExecutionStarted(testPlan: TestPlan) {
        plan = testPlan
    }

    private fun area(id: TestIdentifier): String? {
        var cur: TestIdentifier? = id
        while (cur != null) {
            if (cur.source.orElse(null) is ClassSource) return cur.displayName
            cur = plan?.getParent(cur)?.orElse(null)
        }
        return null
    }

    private fun counts(id: TestIdentifier): Counts? = area(id)?.let { areas.getOrPut(it) { Counts() } }

    override fun executionSkipped(testIdentifier: TestIdentifier, reason: String) {
        val c = counts(testIdentifier) ?: return
        val n = if (testIdentifier.isTest) 1 else plan?.getChildren(testIdentifier)?.count { it.isTest } ?: 1
        c.skipped += maxOf(n, 1)
    }

    override fun executionFinished(testIdentifier: TestIdentifier, result: TestExecutionResult) {
        val c = counts(testIdentifier) ?: return
        if (testIdentifier.isTest) {
            when (result.status) {
                TestExecutionResult.Status.SUCCESSFUL -> c.passed++
                TestExecutionResult.Status.ABORTED -> c.skipped++
                TestExecutionResult.Status.FAILED -> {
                    c.failed++
                    c.failures.add("${testIdentifier.displayName}: ${firstLine(result.throwable.orElse(null))}")
                }
            }
        } else if (result.status == TestExecutionResult.Status.FAILED) {
            // The area itself failed (the guard refused it, or its set-up did): count it once.
            c.failed++
            val t = result.throwable.orElse(null)
            if (t is LiveRefused) refused = t.message else c.failures.add(firstLine(t))
        }
    }

    private fun firstLine(t: Throwable?): String = (t?.message ?: t?.javaClass?.simpleName ?: "failed").lineSequence().first().take(300)

    override fun testPlanExecutionFinished(testPlan: TestPlan) {
        if (areas.isEmpty()) return
        val out = StringBuilder()
        out.append("\n=== Rewloy live tests (").append(Live.baseUrl ?: "no server").append(") ===\n")
        var passed = 0
        var failed = 0
        var skipped = 0
        for ((name, c) in areas) {
            passed += c.passed; failed += c.failed; skipped += c.skipped
            val state = if (c.failed > 0) "FAILED" else if (c.passed == 0) "skipped" else "ok"
            out.append(String.format("  %-34s %-8s passed %-3d failed %-3d skipped %d%n", name, state, c.passed, c.failed, c.skipped))
            for (f in c.failures) out.append("      - ").append(f).append('\n')
        }
        if (passed == 0 && failed == 0) {
            out.append("  Skipped: set REWLOY_BASE_URL and REWLOY_API_KEY (a rwk_test_ key of a development server) to run them.\n")
        } else {
            out.append(String.format("  total: passed %d, failed %d, skipped %d%n", passed, failed, skipped))
            out.append(if (failed == 0) "  RESULT: PASSED\n" else "  RESULT: FAILED\n")
        }
        for (n in Live.notes()) out.append("  NOTE: ").append(n).append('\n')
        refused?.let { out.append("\n  ").append(it).append("\n  Nothing was sent to the server except the checks that decide this.\n") }
        println(out)
    }
}
