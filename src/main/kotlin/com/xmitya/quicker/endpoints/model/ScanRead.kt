package com.xmitya.quicker.endpoints.model

import com.intellij.openapi.application.ReadAction
import java.util.concurrent.Callable

/**
 * How the scan takes read locks.
 *
 * [YIELD_TO_WRITES] is what the IDE uses: the read action is cancelled and retried whenever a write
 * action wants the lock, so the EDT never waits on the scan. A plain read action — even a short one
 * — makes every write queue behind it, and with searches that take seconds on a large repository
 * that shows up as a stutter each time.
 *
 * [BLOCKING] exists for tests, which run on the EDT holding the write-intent lock; a
 * write-prioritised read from a background thread would be cancelled forever there.
 */
enum class ScanRead {
    YIELD_TO_WRITES,
    BLOCKING,
    ;

    fun <T> compute(block: () -> T): T = when (this) {
        YIELD_TO_WRITES -> ReadAction.nonBlocking(Callable { block() }).executeSynchronously()
        BLOCKING -> ReadAction.compute<T, RuntimeException> { block() }
    }

    fun run(block: () -> Unit) {
        compute(block)
    }
}
