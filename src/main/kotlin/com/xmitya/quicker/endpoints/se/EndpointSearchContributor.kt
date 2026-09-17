package com.xmitya.quicker.endpoints.se

import com.intellij.ide.actions.searcheverywhere.FoundItemDescriptor
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereCommandInfo
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributorFactory
import com.intellij.ide.actions.searcheverywhere.WeightedSearchEverywhereContributor
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.util.Processor
import com.xmitya.quicker.endpoints.match.EndpointMatcher
import com.xmitya.quicker.endpoints.match.EndpointQuery
import com.xmitya.quicker.endpoints.model.EndpointModelService
import com.xmitya.quicker.endpoints.model.RankedEndpoint
import com.xmitya.quicker.endpoints.ui.EndpointCellRenderer
import com.xmitya.quicker.endpoints.ui.EndpointNavigator
import javax.swing.ListCellRenderer

/**
 * The same engine behind Double Shift. Nothing here scores anything itself -- it hands our score to
 * Search Everywhere as the element weight, so the two entry points cannot drift apart.
 */
class EndpointSearchContributor(private val project: Project) :
    WeightedSearchEverywhereContributor<RankedEndpoint> {

    private val service: EndpointModelService get() = project.service()

    override fun getSearchProviderId(): String = EndpointSearchContributor::class.java.name
    override fun getGroupName(): String = "Endpoints"
    override fun getSortWeight(): Int = 400
    override fun showInFindResults(): Boolean = false
    override fun isShownInSeparateTab(): Boolean = true
    override fun isEmptyPatternSupported(): Boolean = false

    override fun getSupportedCommands(): List<SearchEverywhereCommandInfo> = listOf(
        SearchEverywhereCommandInfo("get", "GET endpoints only", this),
        SearchEverywhereCommandInfo("post", "POST endpoints only", this),
    )

    override fun fetchWeightedElements(
        pattern: String,
        indicator: ProgressIndicator,
        consumer: Processor<in FoundItemDescriptor<RankedEndpoint>>,
    ) {
        val query = EndpointQuery.parse(pattern)
        if (query.isEmpty) return

        // Served possibly-stale, exactly as the popup does; a rebuild is scheduled underneath.
        val endpoints = service.endpoints()
        val matcher = EndpointMatcher(query)

        val hits = endpoints.asSequence()
            .mapNotNull { e ->
                indicator.checkCanceled()
                matcher.score(e.info)?.let { RankedEndpoint(e, it) }
            }
            .sortedWith(compareBy(EndpointMatcher.RANKING) { it.hit })
            .take(LIMIT)

        for (hit in hits) {
            indicator.checkCanceled()
            if (!consumer.process(FoundItemDescriptor(hit, hit.hit.score))) return
        }
    }

    override fun processSelectedItem(selected: RankedEndpoint, modifiers: Int, searchText: String): Boolean {
        EndpointNavigator.navigate(project, selected, split = false)
        return true
    }

    override fun getElementsRenderer(): ListCellRenderer<in RankedEndpoint> = EndpointCellRenderer()

    override fun getDataForItem(element: RankedEndpoint, dataId: String): Any? =
        if (CommonDataKeys.PSI_ELEMENT.`is`(dataId)) {
            ReadAction.compute<Any?, RuntimeException> { element.endpoint.location.resolve(project) }
        } else {
            null
        }

    override fun dispose() = Unit

    private companion object {
        const val LIMIT = 100
    }
}

class EndpointSearchContributorFactory : SearchEverywhereContributorFactory<RankedEndpoint> {
    override fun createContributor(initEvent: AnActionEvent): SearchEverywhereContributor<RankedEndpoint> =
        EndpointSearchContributor(initEvent.getRequiredData(CommonDataKeys.PROJECT))
}
