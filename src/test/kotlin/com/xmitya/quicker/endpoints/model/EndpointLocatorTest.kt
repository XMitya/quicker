package com.xmitya.quicker.endpoints.model

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import org.assertj.core.api.Assertions.assertThat
import org.intellij.lang.annotations.Language

/**
 * A model restored from disk carries no smart pointers, so navigation re-resolves by class name --
 * through the stub indexes, which are closed while the IDE indexes. Navigation must degrade there
 * rather than throw `IndexNotReadyException` at the user.
 */
class EndpointLocatorTest : LightJavaCodeInsightFixtureTestCase() {

    /** See [EndpointScannerTest]: the default descriptor resolves annotations from Maven. */
    override fun getProjectDescriptor(): LightProjectDescriptor = DefaultLightProjectDescriptor()

    @Language("JAVA")
    private val source = """
        package demo;
        public class Demo {
            public String get() { return "x"; }
        }
    """.trimIndent()

    fun `test resolves by name`() {
        val resolved = resolve(locator())
        assertThat(resolved?.text).isEqualTo("get")
    }

    fun `test falls back to the offset while indexing`() {
        val locator = locator()
        val resolved = DumbModeTestUtils.computeInDumbModeSynchronously<PsiElement?>(project) {
            resolve(locator)
        }
        assertThat(resolved?.containingFile?.name).isEqualTo("Demo.java")
    }

    private fun resolve(locator: EndpointLocator): PsiElement? =
        ReadAction.compute<PsiElement?, RuntimeException> { locator.resolve(project) }

    /** As a cached model stores it: names and an offset, no pointer. */
    private fun locator(): EndpointLocator {
        val file: PsiFile = myFixture.addFileToProject("demo/Demo.java", source)
        return EndpointLocator(
            fileUrl = file.virtualFile.url,
            offset = source.indexOf("get"),
            classFqn = "demo.Demo",
            methodName = "get",
        )
    }
}
