package com.xmitya.quicker.endpoints.model

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.startup.ProjectActivity
import com.xmitya.quicker.endpoints.settings.EndpointSettings
import kotlinx.coroutines.delay

/**
 * Builds the endpoint model once the project is open, so the first press of the shortcut is
 * instant rather than waiting on a scan.
 *
 * The scan itself runs as a background task, so it never blocks the UI, and the model remains
 * lazily refreshed afterwards — this only warms it. Switching it off in settings gives back the
 * previous behaviour, where the first search pays for the scan.
 */
class EndpointStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val service = project.service<EndpointModelService>()
        // Restore first and outside dumb mode: it reads a file, touches no indexes, and makes
        // search usable before indexing has even finished.
        service.primeFromCache()

        if (!EndpointSettings.getInstance().state.scanOnStartup) return

        // Indexes are necessary but not sufficient. Smart mode says indexing finished; it does not
        // say the project model is configured, and on a Gradle project the module graph can land
        // tens of seconds later. Scanning in that window finds candidate files but rejects every
        // one of them, because they are not in a source root yet -- a scan that completes, reports
        // nothing, and looks entirely healthy.
        awaitSourceRoots(project) ?: return

        DumbService.getInstance(project).runWhenSmart {
            if (project.isDisposed) return@runWhenSmart
            service.refresh()
        }
    }

    private suspend fun awaitSourceRoots(project: Project): Unit? {
        repeat(MAX_ATTEMPTS) {
            if (project.isDisposed) return null
            val ready = ReadAction.compute<Boolean, RuntimeException> {
                ProjectRootManager.getInstance(project).contentSourceRoots.isNotEmpty()
            }
            if (ready) return Unit
            delay(POLL_MS)
        }
        // A project genuinely without source roots: scan anyway rather than never.
        return Unit
    }

    private companion object {
        const val POLL_MS = 1_000L
        const val MAX_ATTEMPTS = 120
    }
}
