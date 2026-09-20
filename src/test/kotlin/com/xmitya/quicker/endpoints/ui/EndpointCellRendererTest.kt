package com.xmitya.quicker.endpoints.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.SimpleTextAttributes
import com.xmitya.quicker.endpoints.match.Hit
import com.xmitya.quicker.endpoints.match.HttpVerb
import com.xmitya.quicker.endpoints.model.Endpoint
import com.xmitya.quicker.endpoints.model.RankedEndpoint
import com.xmitya.quicker.endpoints.model.testEndpoint
import org.assertj.core.api.Assertions.assertThat
import javax.swing.JList

/**
 * The row is the only place the user reads the model, so what it prints -- and which part of it is
 * emboldened -- is behaviour, not decoration.
 */
class EndpointCellRendererTest : BasePlatformTestCase() {

    private val endpoint = testEndpoint("/api/v1/users/{id}", "UserController", "getUser")

    fun `test a row shows verb, path, declaration and module`() {
        val text = render(endpoint).toString()
        assertThat(text).contains("GET")
        assertThat(text).contains("/api/v1/users/{id}")
        assertThat(text).contains("UserController.getUser")
        assertThat(text).contains(":svc.main")
    }

    fun `test matched ranges are emboldened without altering the path`() {
        val renderer = render(endpoint, fragments = listOf(8..12)) // "users"
        assertThat(renderer.toString()).contains("/api/v1/users/{id}")
        assertThat(boldPathFragments(renderer)).containsExactly("users")
    }

    fun `test out of range matches are clamped rather than thrown`() {
        val renderer = render(endpoint, fragments = listOf(-5..3, 100..200))
        assertThat(renderer.toString()).contains("/api/v1/users/{id}")
        assertThat(boldPathFragments(renderer)).containsExactly("/api")
    }

    /** A path still holding a property placeholder can never match a real URL, and says so. */
    fun `test an unresolved path is shown in italics`() {
        val path = "/\${base}/users"
        val renderer = render(testEndpoint(path, "UserController", "getUser", unresolved = true))
        assertThat(styleOf(renderer, path) and SimpleTextAttributes.STYLE_ITALIC).isNotZero()
    }

    /** Read and write verbs are coloured apart, which is the point of colouring them at all. */
    fun `test destructive verbs are coloured differently from reads`() {
        val colours = HttpVerb.entries.associateWith { verb ->
            val row = testEndpoint("/x", "XController", "x", verb = verb)
            colourOf(render(row), verb.name.padEnd(VERB_WIDTH))
        }
        assertThat(colours.values).doesNotContainNull()
        assertThat(colours[HttpVerb.GET]).isNotEqualTo(colours[HttpVerb.DELETE])
        assertThat(colours[HttpVerb.POST]).isNotEqualTo(colours[HttpVerb.PUT])
        assertThat(colours[HttpVerb.GET]).isEqualTo(colours[HttpVerb.HEAD])
    }

    fun `test a module-less endpoint drops the suffix`() {
        val text = render(testEndpoint("/x", "XController", "x", module = null)).toString()
        assertThat(text).doesNotContain(":")
    }

    fun `test a null row renders nothing`() {
        assertThat(render(null).toString()).isEmpty()
    }

    private fun render(endpoint: Endpoint?, fragments: List<IntRange>? = null): EndpointCellRenderer {
        val row = endpoint?.let { RankedEndpoint(it, Hit(it.info, score = 100, pathFragments = fragments)) }
        val renderer = EndpointCellRenderer()
        renderer.getListCellRendererComponent(JList(), row, 0, false, false)
        return renderer
    }

    /** Bold fragments of the path, i.e. everything but the verb column, which is always bold. */
    private fun boldPathFragments(renderer: EndpointCellRenderer): List<String> {
        val bold = ArrayList<String>()
        val fragments = renderer.iterator()
        var first = true
        while (fragments.hasNext()) {
            fragments.next()
            // Compared by style bits: the component hands back derived attribute instances, so
            // identity with the REGULAR_BOLD_ATTRIBUTES constant does not hold.
            val isBold = fragments.textAttributes.style and SimpleTextAttributes.STYLE_BOLD != 0
            if (isBold && !first) bold += fragments.fragment
            first = false
        }
        return bold
    }

    private fun styleOf(renderer: EndpointCellRenderer, fragment: String): Int =
        attributesOf(renderer, fragment)?.style ?: 0

    private fun colourOf(renderer: EndpointCellRenderer, fragment: String) =
        attributesOf(renderer, fragment)?.fgColor

    private fun attributesOf(renderer: EndpointCellRenderer, fragment: String): SimpleTextAttributes? {
        val fragments = renderer.iterator()
        while (fragments.hasNext()) {
            fragments.next()
            if (fragments.fragment == fragment) return fragments.textAttributes
        }
        return null
    }

    private companion object {
        /** The width the renderer pads the verb column to. */
        const val VERB_WIDTH = 7
    }
}
