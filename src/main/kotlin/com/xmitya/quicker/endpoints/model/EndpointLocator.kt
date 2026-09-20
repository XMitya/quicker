package com.xmitya.quicker.endpoints.model

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.search.GlobalSearchScope

/**
 * Where an endpoint's handler lives, and how to get back to it.
 *
 * Carries three routes because they degrade differently. A [pointer] is exact but only exists for
 * a model built in this session. Everything restored from disk has to re-resolve, and it must do so
 * by *name* rather than by offset: the file may well have been edited while the IDE was closed, and
 * a stale offset does not fail — it silently lands somewhere else, which is worse than an error.
 * The offset is kept only as a last resort for a declaration that can no longer be resolved.
 */
class EndpointLocator(
    val fileUrl: String,
    val offset: Int,
    val classFqn: String,
    val methodName: String,
    private val pointer: SmartPsiElementPointer<out PsiElement>? = null,
) {
    /** Must be called inside a read action. Null when the declaration is gone. */
    fun resolve(project: Project): PsiElement? =
        pointer?.element
            ?: resolveByName(project)
            ?: resolveByOffset(project)

    private fun resolveByName(project: Project): PsiElement? {
        if (classFqn.isEmpty()) return null
        // A class lookup by name goes through the stub indexes, which throw while indexing runs.
        // Navigation from a cached model then falls back to the offset -- approximate, but the
        // alternative during indexing is an error dialog and no navigation at all.
        if (DumbService.isDumb(project)) return null
        val cls = JavaPsiFacade.getInstance(project)
            .findClass(classFqn, GlobalSearchScope.allScope(project)) ?: return null
        val method = cls.findMethodsByName(methodName, true).firstOrNull() ?: return cls
        return method.nameIdentifier ?: method
    }

    private fun resolveByOffset(project: Project): PsiElement? {
        val file = VirtualFileManager.getInstance().findFileByUrl(fileUrl) ?: return null
        val psi = PsiManager.getInstance(project).findFile(file) ?: return null
        if (offset < 0 || offset >= psi.textLength) return psi
        return psi.findElementAt(offset) ?: psi
    }
}
