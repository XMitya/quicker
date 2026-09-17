package com.xmitya.quicker.endpoints.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.xmitya.quicker.endpoints.model.EndpointModelService
import javax.swing.JLabel

class EndpointConfigurable : BoundConfigurable("Quicker Endpoints") {

    private val rebuildStatus = JLabel()

    override fun createPanel(): DialogPanel {
        val state = EndpointSettings.getInstance().state
        return panel {
            group("Indexing") {
                row {
                    checkBox("Scan endpoints when a project opens")
                        .bindSelected(state::scanOnStartup)
                        .comment(
                            "Scanning runs in the background once indexing finishes, so the first " +
                                "search is instant.<br/>" +
                                "Turn this off to scan on the first search instead — worth doing if " +
                                "you keep many projects open and rarely search most of them.",
                        )
                }
                row {
                    checkBox("Keep the index on disk between sessions")
                        .bindSelected(state::persistCache)
                        .comment(
                            "Search is usable immediately on startup, from the previous session's " +
                                "results, while a fresh scan replaces them in the background.<br/>" +
                                "Cached results can be out of date if the code changed while the " +
                                "IDE was closed, so navigation re-resolves by name rather than by " +
                                "position.",
                        )
                }
                row {
                    checkBox("Rebuild the index when files change")
                        .bindSelected(state::rebuildOnChange)
                        .comment(
                            "Off by default: there is no incremental update yet, so any edit to a " +
                                "Java or Kotlin file invalidates the whole model and the next " +
                                "search would pay for a full rescan.<br/>" +
                                "While off, results go out of date as you edit — use Rebuild Now, " +
                                "or reopen the project.",
                        )
                }
            }
            group("Maintenance") {
                row {
                    button("Rebuild Now") { rebuildOpenProjects() }
                    cell(rebuildStatus)
                }.rowComment(
                    "Rescans every open project. Useful after turning off automatic rebuilds, or " +
                        "when something looks out of date.",
                )
            }
        }
    }

    private fun rebuildOpenProjects() {
        val projects = ProjectManager.getInstance().openProjects.filterNot { it.isDisposed }
        if (projects.isEmpty()) {
            rebuildStatus.text = "No open projects"
            return
        }
        rebuildStatus.text = "Rescanning…"
        val remaining = java.util.concurrent.atomic.AtomicInteger(projects.size)
        projects.forEach { project ->
            project.service<EndpointModelService>().refresh {
                if (remaining.decrementAndGet() == 0) {
                    // `size`, not `endpoints()` — the latter would schedule another scan from
                    // inside the completion callback of the one that just finished.
                    val total = projects.sumOf { it.service<EndpointModelService>().size }
                    rebuildStatus.text = "$total endpoints"
                }
            }
        }
    }
}
