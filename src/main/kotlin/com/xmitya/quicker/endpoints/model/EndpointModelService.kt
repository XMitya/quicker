package com.xmitya.quicker.endpoints.model

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.psi.util.PsiModificationTracker
import com.xmitya.quicker.endpoints.settings.EndpointSettings
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Holds the resolved endpoint model.
 *
 * Built on first use behind a progress indicator rather than eagerly at startup: resolving ~3,000
 * files through PSI costs tens of seconds on a monorepo, and paying that on every IDE launch for a
 * feature nobody may invoke is a bad trade.
 *
 * Staleness is a *signal*, not a cache dependency. A `CachedValueProvider` keyed on the PSI
 * modification tracker would recompute the whole model on every keystroke in any Java or Kotlin
 * file, which is a multi-second freeze. Instead the model is served stale while a rebuild runs --
 * last-minute-stale results instantly beat correct results after a wait.
 */
@Service(Service.Level.PROJECT)
class EndpointModelService(private val project: Project) {

    private class Snapshot(
        val stamp: Long,
        val endpoints: List<Endpoint>,
        /** Restored from disk and not yet confirmed by a scan in this session. */
        val fromCache: Boolean = false,
    )

    private val snapshot = AtomicReference<Snapshot?>(null)
    private val building = AtomicBoolean(false)
    private val pending = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    /** A scan is already waiting for indexing to finish; further requests join it. */
    private val waitingForSmart = AtomicBoolean(false)
    /** Consecutive scans abandoned because indexes went away; reset by any scan that completes. */
    private val indexFailures = AtomicInteger(0)

    val isReady: Boolean get() = snapshot.get() != null

    /** True while the popup is showing last session's results and a scan is still running. */
    val isStale: Boolean get() = snapshot.get()?.fromCache == true

    /** Size of the current model without scheduling anything — unlike [endpoints]. */
    val size: Int get() = snapshot.get()?.endpoints?.size ?: 0

    /**
     * Restores the previous session's model so search is usable at once.
     *
     * Deliberately stored under a stamp that can never match the live one, so the next call to
     * [endpoints] schedules a real scan. Nothing can detect edits made while the IDE was closed,
     * so this is a placeholder, never an answer.
     */
    fun primeFromCache(): Boolean {
        if (!EndpointSettings.getInstance().state.persistCache) return false
        if (snapshot.get() != null) return false
        val cached = EndpointCache.load(project) ?: return false
        snapshot.set(Snapshot(STALE_STAMP, cached, fromCache = true))
        return true
    }

    /** Current endpoints, possibly stale. Never blocks; schedules a rebuild when needed. */
    fun endpoints(): List<Endpoint> {
        val current = snapshot.get()
        // Never built, or showing last session's cache: a real scan is required either way, and no
        // setting should be able to leave the model permanently unbuilt.
        val mustBuild = current == null || current.fromCache
        val outdatedByEdits = current != null && !current.fromCache && current.stamp != stamp()
        if (mustBuild || (outdatedByEdits && EndpointSettings.getInstance().state.rebuildOnChange)) {
            refresh()
        }
        return current?.endpoints.orEmpty()
    }

    /**
     * [onDone] fires when the model is next ready, whether this call started the scan or merely
     * joined one already in flight. Dropping the callback in the second case would leave a caller
     * waiting forever — and since the model is now warmed at startup, joining is the common case,
     * not the exception.
     */
    fun refresh(onDone: (() -> Unit)? = null) {
        if (DumbService.isDumb(project)) {
            // The scan reads the indexes, so it cannot run now. Queue it for the moment they are
            // back rather than dropping it: [onDone] still fires at once, so nothing waits on that
            // later scan, and the model is rebuilt without needing another search to ask for it.
            scanWhenSmart()
            onDone?.invoke()
            return
        }
        onDone?.let { pending += it }
        if (!building.compareAndSet(false, true)) return

        object : Task.Backgroundable(project, "Scanning Spring endpoints", true) {
            /** Indexing swallowed this scan; see the catch below. */
            private var retry = false

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    val stamp = stamp()
                    // Not wrapped in a read action: the scanner takes short ones itself, so write
                    // actions are not blocked behind the whole scan.
                    val found = EndpointScanner(project).scan(indicator)
                    snapshot.set(Snapshot(stamp, found))
                    indexFailures.set(0)
                    if (EndpointSettings.getInstance().state.persistCache) {
                        EndpointCache.save(project, found)
                    }
                } catch (e: IndexNotReadyException) {
                    // Every read the scan takes asks for smart mode, so this is the narrow race
                    // where indexing starts between that check and the lookup itself. Half a model
                    // is worse than none, so the partial result is dropped and the previous
                    // snapshot keeps serving searches; the scan is redone once indexes are back.
                    // Bounded, because a scan that fails this way every time must not spin.
                    LOG.info("Endpoint scan interrupted by indexing; retrying when indexes are ready", e)
                    retry = indexFailures.incrementAndGet() <= MAX_INDEX_RETRIES
                } finally {
                    building.set(false)
                }
            }

            override fun onFinished() {
                val callbacks = ArrayList(pending)
                pending.removeAll(callbacks)
                callbacks.forEach { it() }
                // After the callbacks, and after `building` is clear, or the retry would be
                // refused as a scan already in flight.
                if (retry) scanWhenSmart()
            }
        }.queue()
    }

    /** Runs one scan once indexing finishes. Calls while one is already queued are no-ops. */
    private fun scanWhenSmart() {
        if (!waitingForSmart.compareAndSet(false, true)) return
        DumbService.getInstance(project).runWhenSmart {
            waitingForSmart.set(false)
            if (!project.isDisposed) refresh()
        }
    }

    private fun stamp(): Long = PsiModificationTracker.getInstance(project).modificationCount

    companion object {
        private val LOG = logger<EndpointModelService>()

        /** No live modification count is negative, so a primed snapshot always looks stale. */
        private const val STALE_STAMP = -1L

        /** Indexing restarts legitimately; a scan that keeps losing to it does not. */
        private const val MAX_INDEX_RETRIES = 3

        fun getInstance(project: Project): EndpointModelService = project.service()
    }
}
