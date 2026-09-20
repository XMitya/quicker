package com.xmitya.quicker.endpoints.model

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import java.util.concurrent.Callable

/**
 * How the scan takes read locks.
 *
 * [YIELD_TO_WRITES] is what the IDE uses: the read action is cancelled and retried whenever a write
 * action wants the lock, so the EDT never waits on the scan. A plain read action — even a short one
 * — makes every write queue behind it, and with searches that take seconds on a large repository
 * that shows up as a stutter each time.
 *
 * Those reads also demand smart mode. Resolving a supertype or looking a class up by name goes
 * through the stub indexes, and every such call throws `IndexNotReadyException` while indexing
 * runs. Checking `isDumb` before the scan proves nothing: indexing can start at any point during
 * one — a VFS refresh right after an IDE restart is enough — and the retry of a cancelled chunk
 * then lands in dumb mode. `inSmartMode` makes each chunk wait for indexes instead of failing, and
 * the wait is interruptible, so a cancelled scan still stops at once.
 *
 * [BLOCKING] exists for tests, which run on the EDT holding the write-intent lock; a
 * write-prioritised read from a background thread would be cancelled forever there.
 */
enum class ScanRead {
    YIELD_TO_WRITES,
    BLOCKING,
    ;

    fun <T> compute(project: Project, block: () -> T): T = when (this) {
        YIELD_TO_WRITES -> ReadAction.nonBlocking(Callable { block() })
            .inSmartMode(project)
            .expireWhen { project.isDisposed }
            .executeSynchronously()
        BLOCKING -> ReadAction.compute<T, RuntimeException> { block() }
    }
}
