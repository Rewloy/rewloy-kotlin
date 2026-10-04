package com.rewloy

import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * The one daemon thread that cancels attempts that run out of time. `HttpURLConnection` only has a timeout per
 * read, so the time allowed for a whole attempt (the call's `timeoutMs`) is kept here: when it passes, the
 * attempt's [CancelToken] is cancelled and the transport aborts the connection.
 */
internal object Timeouts {
    private val executor: ScheduledThreadPoolExecutor by lazy {
        val e = ScheduledThreadPoolExecutor(
            1,
            ThreadFactory { task ->
                Thread(task, "rewloy-timeouts").also { it.isDaemon = true }
            },
        )
        e.removeOnCancelPolicy = true
        e
    }

    fun schedule(millis: Long, task: Runnable): ScheduledFuture<*> = executor.schedule(task, millis, TimeUnit.MILLISECONDS)
}
