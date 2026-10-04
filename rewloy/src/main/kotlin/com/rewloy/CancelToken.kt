package com.rewloy

import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Cancels calls that are under way, from any thread: pass one in [RequestOptions.cancel], or to
 * [Rewloy.withCancel], and call [cancel]. A blocked request is aborted (the connection is closed), a retry that
 * is waiting stops waiting, and the call throws [java.util.concurrent.CancellationException].
 *
 * ```kotlin
 * val cancel = CancelToken()
 * thread { for (event in rewloy.liveFeed(options = RequestOptions(cancel = cancel))) { … } }
 * // later, from another thread:
 * cancel.cancel()
 * ```
 */
public class CancelToken {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var cancelled = false
    private var callbacks: MutableList<Registration>? = null

    /** Whether [cancel] has been called. */
    public val isCancelled: Boolean
        get() {
            lock.lock()
            try {
                return cancelled
            } finally {
                lock.unlock()
            }
        }

    /** Cancels: runs what was registered with [onCancel], and wakes whoever sleeps on this token. Calling it again does nothing. */
    public fun cancel() {
        var toRun: List<Registration> = emptyList()
        lock.lock()
        try {
            if (cancelled) return
            cancelled = true
            toRun = callbacks?.toList() ?: emptyList()
            callbacks = null
            changed.signalAll()
        } finally {
            lock.unlock()
        }
        for (registration in toRun) {
            try {
                registration.action.run()
            } catch (_: RuntimeException) {
                // One failing callback must not keep the others from running.
            }
        }
    }

    /** @throws java.util.concurrent.CancellationException when this token is cancelled. */
    public fun throwIfCancelled() {
        if (isCancelled) throw CancellationException("the call was cancelled")
    }

    /**
     * Runs [action] when this token is cancelled, at once if it already is. Close the returned [Registration]
     * when the action is no longer needed.
     */
    public fun onCancel(action: Runnable): Registration {
        val registration = Registration(this, action)
        var runNow = false
        lock.lock()
        try {
            if (cancelled) {
                runNow = true
            } else {
                val list = callbacks ?: ArrayList<Registration>().also { callbacks = it }
                list.add(registration)
            }
        } finally {
            lock.unlock()
        }
        if (runNow) action.run()
        return registration
    }

    /**
     * Waits up to [millis], or until the token is cancelled. Returns `true` when it was cancelled.
     * @throws java.util.concurrent.CancellationException when the thread is interrupted (its flag is set again).
     */
    internal fun sleep(millis: Long): Boolean {
        var remaining = TimeUnit.MILLISECONDS.toNanos(millis)
        lock.lock()
        try {
            while (!cancelled && remaining > 0) {
                try {
                    remaining = changed.awaitNanos(remaining)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw CancellationException("the thread was interrupted")
                }
            }
            return cancelled
        } finally {
            lock.unlock()
        }
    }

    internal fun remove(registration: Registration) {
        lock.lock()
        try {
            callbacks?.remove(registration)
        } finally {
            lock.unlock()
        }
    }

    /** What [onCancel] hands back: closing it withdraws the action. */
    public class Registration internal constructor(private val token: CancelToken, internal val action: Runnable) : AutoCloseable {
        /** Withdraws the action. */
        override fun close() {
            token.remove(this)
        }
    }
}
