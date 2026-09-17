package com.xmitya.quicker.endpoints.ui

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ReadAction
import com.xmitya.quicker.endpoints.model.RankedEndpoint

object EndpointNavigator {

    /** [split] opens in a vertical split rather than reusing the current editor. */
    fun navigate(project: Project, selected: RankedEndpoint, split: Boolean) {
        val descriptor = ReadAction.compute<OpenFileDescriptor?, RuntimeException> {
            val element = selected.endpoint.location.resolve(project) ?: return@compute null
            val file = element.containingFile?.virtualFile ?: return@compute null
            OpenFileDescriptor(project, file, element.textOffset)
        } ?: return

        if (split) {
            com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                .openFileEditor(descriptor, true)
        } else {
            descriptor.navigate(true)
        }
    }
}
