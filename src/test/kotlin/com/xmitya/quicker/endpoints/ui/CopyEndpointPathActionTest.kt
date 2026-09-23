package com.xmitya.quicker.endpoints.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.impl.NonBlockingReadActionImpl
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.xmitya.quicker.endpoints.match.HttpVerb
import com.xmitya.quicker.endpoints.model.ResolvedPath
import com.xmitya.quicker.endpoints.model.SpringStubs
import org.assertj.core.api.Assertions.assertThat
import org.intellij.lang.annotations.Language
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

class CopyEndpointPathActionTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = DefaultLightProjectDescriptor()

    override fun setUp() {
        super.setUp()
        SpringStubs.install(myFixture)
        // Something else on the clipboard, so a copy that never happened cannot pass by accident.
        CopyPasteManager.getInstance().setContents(StringSelection("untouched"))
    }

    private fun open(@Language("JAVA") text: String) {
        myFixture.addFileToProject("demo/OrderController.java", text)
        myFixture.configureFromTempProjectFile("demo/OrderController.java")
    }

    private fun openHandler() = open(
        """
        package demo;
        import org.springframework.web.bind.annotation.*;
        @RestController
        @RequestMapping("/api/v1/orders")
        public class OrderController {
            @GetMapping("/{id}")
            public String get<caret>Order(@PathVariable String id) { return null; }
            private String helper() { return null; }
        }
        """.trimIndent(),
    )

    private fun event(action: AnAction, withProject: Boolean = true): AnActionEvent {
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, if (withProject) project else null)
            .add(CommonDataKeys.EDITOR, myFixture.editor)
            .add(CommonDataKeys.PSI_FILE, myFixture.file)
            .build()
        return TestActionEvent.createTestEvent(action, context)
    }

    private fun isOffered(action: AnAction, withProject: Boolean = true): Boolean {
        val event = event(action, withProject)
        action.update(event)
        return event.presentation.isEnabledAndVisible
    }

    /** Runs the action to completion: the paths are resolved off the EDT and delivered back. */
    private fun perform(action: AnAction): String? {
        action.actionPerformed(event(action))
        NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        return CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor)
    }

    fun `test offered on a handler method`() {
        openHandler()
        assertThat(isOffered(CopyEndpointPathAction())).isTrue()
        assertThat(isOffered(CopyEndpointPathWithMethodAction())).isTrue()
    }

    fun `test hidden on a method that is not a handler`() {
        openHandler()
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("helper"))
        assertThat(isOffered(CopyEndpointPathAction())).isFalse()
    }

    fun `test hidden without a project`() {
        openHandler()
        assertThat(isOffered(CopyEndpointPathAction(), withProject = false)).isFalse()
    }

    /** Resolving annotations must not happen on the EDT. */
    fun `test the action updates off the event thread`() {
        assertThat(CopyEndpointPathAction().actionUpdateThread).isEqualTo(ActionUpdateThread.BGT)
    }

    fun `test copies the path`() {
        openHandler()
        assertThat(perform(CopyEndpointPathAction())).isEqualTo("/api/v1/orders/{id}")
    }

    fun `test copies the path with its method`() {
        openHandler()
        assertThat(perform(CopyEndpointPathWithMethodAction())).isEqualTo("GET /api/v1/orders/{id}")
    }

    /** `ANY` is not a verb anyone can send, and search would not accept it back either. */
    fun `test clipboard text drops ANY`() {
        val any = ResolvedPath(HttpVerb.ANY, "/legacy/any", "LegacyController")
        assertThat(clipboardText(any, withVerb = true)).isEqualTo("/legacy/any")
        val post = ResolvedPath(HttpVerb.POST, "/legacy/ping", "LegacyController")
        assertThat(clipboardText(post, withVerb = true)).isEqualTo("POST /legacy/ping")
        assertThat(clipboardText(post, withVerb = false)).isEqualTo("/legacy/ping")
    }
}
