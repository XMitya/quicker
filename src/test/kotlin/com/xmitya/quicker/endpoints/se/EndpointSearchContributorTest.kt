package com.xmitya.quicker.endpoints.se

import com.intellij.ide.actions.searcheverywhere.FoundItemDescriptor
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.psi.PsiElement
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.util.Processor
import com.xmitya.quicker.endpoints.model.EndpointModelService
import com.xmitya.quicker.endpoints.model.RankedEndpoint
import com.xmitya.quicker.endpoints.model.SpringStubs
import org.assertj.core.api.Assertions.assertThat
import org.intellij.lang.annotations.Language

/**
 * Search Everywhere sees the model through this contributor, so the invariant worth pinning is that
 * it serves the same ranking the popup does and hands the platform a usable navigation target.
 */
class EndpointSearchContributorTest : LightJavaCodeInsightFixtureTestCase() {

    /** See [com.xmitya.quicker.endpoints.model.EndpointScannerTest]: no Maven lookups in tests. */
    override fun getProjectDescriptor(): LightProjectDescriptor = DefaultLightProjectDescriptor()

    private lateinit var contributor: EndpointSearchContributor

    @Language("JAVA")
    private val controller = """
        package demo;
        import org.springframework.web.bind.annotation.*;
        @RestController
        @RequestMapping("/api/v1/users")
        public class UserController {
            @GetMapping("/{id}")
            public String get(String id) { return id; }
            @PostMapping
            public String create() { return ""; }
        }
    """.trimIndent()

    override fun setUp() {
        super.setUp()
        SpringStubs.install(myFixture)
        myFixture.addFileToProject("demo/UserController.java", controller)
        // Backgroundable tasks run on the calling thread in tests, so the model is ready after this.
        EndpointModelService.getInstance(project).refresh()
        contributor = EndpointSearchContributor(project)
    }

    fun `test the model is served to search everywhere`() {
        assertThat(fetch("users").map { it.item.endpoint.info.path })
            .contains("/api/v1/users/{id}", "/api/v1/users")
    }

    fun `test an endpoint that cannot match is not offered`() {
        assertThat(fetch("payments")).isEmpty()
    }

    fun `test an empty pattern is not searched`() {
        assertThat(fetch("")).isEmpty()
    }

    /** Search Everywhere sorts by the weight, so it has to be our score rather than a constant. */
    fun `test the weight is the endpoint score`() {
        val found = fetch("users")
        assertThat(found).isNotEmpty()
        assertThat(found.map { it.weight }).isEqualTo(found.map { it.item.hit.score })
    }

    fun `test a consumer that stops is not fed further results`() {
        val seen = ArrayList<RankedEndpoint>()
        contributor.fetchWeightedElements("users", EmptyProgressIndicator()) {
            seen += it.item
            false
        }
        assertThat(seen).hasSize(1)
    }

    fun `test the declaration is offered to the platform as a psi element`() {
        val top = fetch("users").first().item
        val element = contributor.getDataForItem(top, CommonDataKeys.PSI_ELEMENT.name)
        assertThat(element).isInstanceOf(PsiElement::class.java)
        assertThat((element as PsiElement).containingFile.name).isEqualTo("UserController.java")
        assertThat(contributor.getDataForItem(top, CommonDataKeys.VIRTUAL_FILE.name)).isNull()
    }

    fun `test selecting an endpoint opens its declaration`() {
        val top = fetch("users").first().item
        assertThat(contributor.processSelectedItem(top, 0, "users")).isTrue()
        assertThat(FileEditorManager.getInstance(project).selectedFiles.map { it.name })
            .containsExactly("UserController.java")
    }

    fun `test it declares itself as a tab of its own`() {
        assertThat(contributor.groupName).isEqualTo("Endpoints")
        assertThat(contributor.isShownInSeparateTab).isTrue()
        assertThat(contributor.showInFindResults()).isFalse()
        assertThat(contributor.isEmptyPatternSupported).isFalse()
        assertThat(contributor.supportedCommands.map { it.commandWithPrefix }).containsExactly("/get", "/post")
    }

    fun `test the factory builds a contributor for the current project`() {
        val event = TestActionEvent.createTestEvent(
            DataContext { if (CommonDataKeys.PROJECT.`is`(it)) project else null },
        )
        val built = EndpointSearchContributorFactory().createContributor(event)
        assertThat(built.searchProviderId).isEqualTo(contributor.searchProviderId)
    }

    private fun fetch(pattern: String): List<FoundItemDescriptor<RankedEndpoint>> {
        val found = ArrayList<FoundItemDescriptor<RankedEndpoint>>()
        contributor.fetchWeightedElements(
            pattern,
            EmptyProgressIndicator(),
            Processor {
                found += it
                true
            },
        )
        return found
    }
}
