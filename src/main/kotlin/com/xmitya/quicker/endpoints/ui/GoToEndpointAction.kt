package com.xmitya.quicker.endpoints.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbService

class GoToEndpointAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        // Discovery runs off the annotation indexes, which are unavailable during indexing.
        if (DumbService.isDumb(project)) {
            DumbService.getInstance(project).showDumbModeNotification(
                "Endpoint search is available once indexing finishes",
            )
            return
        }
        EndpointPopup(project).show()
    }
}
