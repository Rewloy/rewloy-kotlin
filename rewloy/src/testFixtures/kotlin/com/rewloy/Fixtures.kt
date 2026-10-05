package com.rewloy

/** The smallest answers the API's schemas accept. */
object Fixtures {
    const val PASS = """{"data":{"serial":"ABCD-EFGH-JKLM","programId":"0192f7c1-0000-7000-8000-000000000002","type":"stamp","status":"active","programName":"Kahve kartı","currency":"TRY","balance":3,"rewardReady":false,"rewardsReady":0,"updatedAt":"2026-10-04T10:00:00.000Z","actions":[],"sale":{"writes":"stamps"}}}"""

    fun customer(n: Int) =
        """{"personId":"p$n","displayName":"Ayşe $n","email":null,"identifiers":[],"marketingConsent":true,"passCount":1,"visits":$n,"lastSeen":null,"createdAt":"2026-10-01T10:00:00.000Z","blocked":false,"cards":[]}"""

    fun customers(from: Int, count: Int, page: Int, pageSize: Int, total: Int): String =
        """{"data":[${(from until from + count).joinToString(",") { customer(it) }}],"meta":{"page":$page,"pageSize":$pageSize,"total":$total}}"""

    const val ACTION = """{"data":{"balance":5,"duplicate":false,"reversed":false,"card":null}}"""
    const val RESTORED = """{"data":{"status":"active","cardsQueued":0}}"""
}

/** Helpers for the Java tests, which cannot use Kotlin's default arguments. */
object Answers {
    @JvmStatic
    fun json(status: Int, body: String): Answer = Answer(status, body)

    @JvmStatic
    fun events(vararg lines: String): Answer = Answer(chunks = lines.map { (it + "\n\n").toByteArray(Charsets.UTF_8) })
}
