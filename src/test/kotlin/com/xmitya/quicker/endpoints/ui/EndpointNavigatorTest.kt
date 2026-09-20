package com.xmitya.quicker.endpoints.ui

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.xmitya.quicker.endpoints.match.Hit
import com.xmitya.quicker.endpoints.model.Endpoint
import com.xmitya.quicker.endpoints.model.RankedEndpoint
import com.xmitya.quicker.endpoints.model.testEndpoint
import org.assertj.core.api.Assertions.assertThat
import org.intellij.lang.annotations.Language

/** Navigation is the whole point of the feature; a hit that opens nothing is a bug, not a miss. */
class EndpointNavigatorTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = DefaultLightProjectDescriptor()

    @Language("JAVA")
    private val source = """
        package demo;
        public class UserController {
            public String get() { return ""; }
        }
    """.trimIndent()

    fun `test navigating opens the declaring file`() {
        EndpointNavigator.navigate(project, row(declared()), split = false)
        assertThat(openedFiles()).containsExactly("UserController.java")
    }

    fun `test navigating into a split opens it as well`() {
        EndpointNavigator.navigate(project, row(declared()), split = true)
        assertThat(openedFiles()).containsExactly("UserController.java")
    }

    /** A model restored from disk can outlive the file it points at. */
    fun `test an endpoint whose file is gone opens nothing`() {
        val vanished = testEndpoint(
            "/users",
            "GoneController",
            "get",
            fileUrl = "file:///nowhere/GoneController.java",
        )
        EndpointNavigator.navigate(project, row(vanished), split = false)
        assertThat(openedFiles()).isEmpty()
    }

    private fun declared(): Endpoint {
        val file = myFixture.addFileToProject("demo/UserController.java", source)
        return testEndpoint(
            "/users",
            "UserController",
            "get",
            fileUrl = file.virtualFile.url,
            offset = source.indexOf("get"),
        )
    }

    private fun row(endpoint: Endpoint) = RankedEndpoint(endpoint, Hit(endpoint.info, score = 100))

    private fun openedFiles() = FileEditorManager.getInstance(project).selectedFiles.map { it.name }
}
