package com.xmitya.quicker.endpoints.ui

import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.StatusBar
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.util.concurrency.AppExecutorUtil
import com.xmitya.quicker.endpoints.match.HttpVerb
import com.xmitya.quicker.endpoints.model.EndpointPathResolver
import com.xmitya.quicker.endpoints.model.ResolvedPath
import java.awt.datatransfer.StringSelection

/** Copies only the path: `/api/v1/users/{id}`. */
class CopyEndpointPathAction : CopyEndpointPathActionBase(withVerb = false)

/** Copies the verb and the path: `GET /api/v1/users/{id}`. */
class CopyEndpointPathWithMethodAction : CopyEndpointPathActionBase(withVerb = true)

/**
 * Copies the full path of the handler method under the caret — the reverse of Go to Endpoint.
 *
 * Offered only on handler methods, in a controller or an API interface. Not `DumbAware`: resolving
 * annotations and searching for implementations both need the indexes.
 */
abstract class CopyEndpointPathActionBase(private val withVerb: Boolean) : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val handler = if (project == null) null else elementAt(e)?.let(EndpointPathResolver(project)::handlerAt)
        e.presentation.isEnabledAndVisible = handler != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val element = elementAt(e) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        val pointer = SmartPointerManager.createPointer(element)

        // Off the EDT: finding the controllers behind an API interface is an index search.
        ReadAction.nonBlocking<List<ResolvedPath>> {
            val resolver = EndpointPathResolver(project)
            pointer.element?.let(resolver::handlerAt)?.let(resolver::pathsOf).orEmpty()
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { paths ->
                deliver(project, editor, paths)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /**
     * An API interface implemented by several controllers is served under several paths, and only
     * the user knows which one they are after.
     */
    private fun deliver(project: Project, editor: Editor?, paths: List<ResolvedPath>) {
        if (paths.size <= 1) {
            paths.firstOrNull()?.let { copy(project, editor, it) }
                ?: inform(project, editor, "No endpoint path could be resolved here")
            return
        }
        val byLabel = paths.associateBy { "${it.verb} ${it.path} — ${it.controllerSimpleName}" }
        val popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(byLabel.keys.toList())
            .setTitle("Choose Endpoint to Copy")
            .setItemChosenCallback { label -> copy(project, editor, byLabel.getValue(label)) }
            .createPopup()
        if (editor != null && !editor.isDisposed) {
            popup.showInBestPositionFor(editor)
        } else {
            popup.showCenteredInCurrentWindow(project)
        }
    }

    private fun copy(project: Project, editor: Editor?, path: ResolvedPath) {
        val text = clipboardText(path, withVerb)
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        inform(project, editor, "Copied $text")
    }

    private fun inform(project: Project, editor: Editor?, message: String) {
        if (editor != null && !editor.isDisposed) {
            HintManager.getInstance().showInformationHint(editor, message)
        } else {
            StatusBar.Info.set(message, project)
        }
    }

    /**
     * The caret position in an editor: a right-click moves the caret there first. Elsewhere — the
     * Structure view, say — the selected element.
     */
    private fun elementAt(e: AnActionEvent): PsiElement? {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.PSI_FILE)
        if (editor != null && file != null) return file.findElementAt(editor.caretModel.offset)
        return e.getData(CommonDataKeys.PSI_ELEMENT)
    }
}

/**
 * `GET /a/b`, or only the path. `ANY` is dropped even when the verb is asked for: it is what a bare
 * `@RequestMapping` answers to, not a verb anyone can send — and search does not accept it either.
 */
internal fun clipboardText(path: ResolvedPath, withVerb: Boolean): String =
    if (withVerb && path.verb != HttpVerb.ANY) "${path.verb} ${path.path}" else path.path
