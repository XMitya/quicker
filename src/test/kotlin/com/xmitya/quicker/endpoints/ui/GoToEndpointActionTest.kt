package com.xmitya.quicker.endpoints.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.assertj.core.api.Assertions.assertThat

class GoToEndpointActionTest : BasePlatformTestCase() {

    private val action = GoToEndpointAction()

    fun `test the action is offered inside a project`() {
        val event = event(withProject = true)
        action.update(event)
        assertThat(event.presentation.isEnabledAndVisible).isTrue()
    }

    fun `test the action is hidden without a project`() {
        val event = event(withProject = false)
        action.update(event)
        assertThat(event.presentation.isEnabledAndVisible).isFalse()
    }

    /** Resolving the project must not happen on the EDT; the popup is opened from there instead. */
    fun `test the action updates off the event thread`() {
        assertThat(action.actionUpdateThread).isEqualTo(ActionUpdateThread.BGT)
    }

    private fun event(withProject: Boolean) = TestActionEvent.createTestEvent(
        DataContext { if (withProject && CommonDataKeys.PROJECT.`is`(it)) project else null },
    )
}
