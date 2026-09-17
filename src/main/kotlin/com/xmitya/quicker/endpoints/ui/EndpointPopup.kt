package com.xmitya.quicker.endpoints.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.CaretModel
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiManager
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.xmitya.quicker.endpoints.match.MatchContext
import com.xmitya.quicker.endpoints.model.EndpointModelService
import com.xmitya.quicker.endpoints.model.RankedEndpoint
import com.xmitya.quicker.endpoints.model.rankEndpoints
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent

/**
 * Search field on top, ranked endpoint list beneath.
 *
 * Searching runs off the EDT under a cancelling read action, so a fast typist never queues work:
 * each keystroke supersedes the one before it.
 */
class EndpointPopup(private val project: Project) {

    private val service = EndpointModelService.getInstance(project)
    private val searchField = SearchTextField(false)
    private val listModel = DefaultListModel<RankedEndpoint>()
    private val list = JBList(listModel)
    private val status = JBLabel()
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD)
    private var popup: JBPopup? = null
    private var generation = 0

    fun show() {
        list.cellRenderer = EndpointCellRenderer()
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.emptyText.text = "Type a URL, a template, or part of either"

        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty()
            add(searchField, BorderLayout.NORTH)
            add(JBScrollPane(list), BorderLayout.CENTER)
            add(status.apply { border = JBUI.Borders.empty(2, 8) }, BorderLayout.SOUTH)
            preferredSize = Dimension(JBUI.scale(760), JBUI.scale(420))
        }

        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = scheduleSearch()
        })
        installKeyHandling()

        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, searchField.textEditor)
            .setTitle("Find Endpoint")
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .setDimensionServiceKey(project, DIMENSION_KEY, true)
            .createPopup()
            .also { it.showCenteredInCurrentWindow(project) }

        warmUp()
    }

    private fun warmUp() {
        // Only scan when there is nothing usable yet, when what is on screen came from the cache,
        // or when the model is empty. Asking unconditionally meant every time the popup opened it
        // kicked off another full rescan; never asking would strand a scan that completed early
        // and found nothing, which is indistinguishable from one that failed.
        if (service.isReady && !service.isStale && service.size > 0) {
            updateStatus()
            return
        }
        if (!service.isReady) {
            status.text = "Scanning endpoints…"
        }
        updateStatus()
        // Fires whether this starts a scan or joins one already running -- including the case where
        // last session's results are on screen and a fresh scan is replacing them.
        service.refresh {
            SwingUtilities.invokeLater {
                if (popup?.isDisposed == false) {
                    updateStatus()
                    scheduleSearch()
                }
            }
        }
    }

    private fun updateStatus() {
        if (!service.isReady) return
        val total = service.endpoints().size
        val shown = if (listModel.isEmpty) "$total endpoints indexed" else "${listModel.size()} of $total"
        // Say so plainly when these are last session's results: they can be out of date.
        status.text = if (service.isStale) "$shown — from cache, rescanning…" else shown
    }

    private fun scheduleSearch() {
        alarm.cancelAllRequests()
        alarm.addRequest({ runSearch() }, DEBOUNCE_MS)
    }

    private fun runSearch() {
        val text = searchField.text
        val current = ++generation
        if (text.isBlank()) {
            listModel.clear()
            updateStatus()
            return
        }
        val context = MatchContext(currentModule = currentModuleName())
        val endpoints = service.endpoints()

        ReadAction.nonBlocking<List<RankedEndpoint>> { rankEndpoints(endpoints, text, context, LIMIT) }
            .coalesceBy(this)
            .finishOnUiThread(ModalityState.any()) { hits ->
                if (current != generation || popup?.isDisposed != false) return@finishOnUiThread
                listModel.clear()
                hits.forEach(listModel::addElement)
                if (!listModel.isEmpty) list.selectedIndex = 0
                updateStatus()
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun currentModuleName(): String? {
        val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull() ?: return null
        val psi = PsiManager.getInstance(project).findFile(file) ?: return null
        return ModuleUtilCore.findModuleForPsiElement(psi)?.name
    }

    private fun installKeyHandling() {
        val navigate = { split: Boolean ->
            listModel.takeIf { !it.isEmpty }
                ?.let { list.selectedValue }
                ?.let { selected ->
                    popup?.cancel()
                    EndpointNavigator.navigate(project, selected, split)
                }
            Unit
        }

        searchField.textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_DOWN -> { move(1); e.consume() }
                    KeyEvent.VK_UP -> { move(-1); e.consume() }
                    KeyEvent.VK_PAGE_DOWN -> { move(10); e.consume() }
                    KeyEvent.VK_PAGE_UP -> { move(-10); e.consume() }
                    KeyEvent.VK_ENTER -> { navigate(e.isShiftDown); e.consume() }
                    KeyEvent.VK_ESCAPE -> { popup?.cancel(); e.consume() }
                }
            }
        })

        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) navigate(false)
            }
        })
    }

    private fun move(delta: Int) {
        if (listModel.isEmpty) return
        val next = (list.selectedIndex + delta).coerceIn(0, listModel.size() - 1)
        list.selectedIndex = next
        list.ensureIndexIsVisible(next)
    }

    companion object {
        private const val DIMENSION_KEY = "Quicker.FindEndpoint"
        private const val DEBOUNCE_MS = 120
        private const val LIMIT = 50
    }
}
