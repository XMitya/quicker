package com.xmitya.quicker.endpoints.ui

import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.JBUI
import com.xmitya.quicker.endpoints.match.HttpVerb
import com.xmitya.quicker.endpoints.model.RankedEndpoint
import java.awt.Color
import javax.swing.JList

/**
 * One row: `[GET] /api/v1/users/{id}/orders    UserOrdersController.getOrders  :orders-api`
 *
 * Matched ranges come from the platform matcher, so the bolding lines up with why the row scored.
 */
class EndpointCellRenderer : ColoredListCellRenderer<RankedEndpoint>() {

    override fun customizeCellRenderer(
        list: JList<out RankedEndpoint>,
        value: RankedEndpoint?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        val row = value ?: return
        val e = row.endpoint.info

        ipad = JBUI.insets(2, 6)
        append(e.verb.name.padEnd(7), SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, e.verb.color()))

        appendWithMatches(e.path, row.hit.pathFragments, e.unresolved)

        append("  ")
        append(e.flatName, SimpleTextAttributes.GRAYED_ATTRIBUTES)
        e.moduleName?.let { append("  :$it", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
    }

    private fun appendWithMatches(text: String, ranges: List<IntRange>?, unresolved: Boolean) {
        // A path still holding a ${property} placeholder is shown as-is, in italics, so it is
        // obvious why it will never match a concrete URL from a log.
        val plain = if (unresolved) SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES
        else SimpleTextAttributes.REGULAR_ATTRIBUTES

        if (ranges.isNullOrEmpty()) {
            append(text, plain)
            return
        }
        var cursor = 0
        for (r in ranges.sortedBy { it.first }) {
            val from = r.first.coerceIn(0, text.length)
            val to = (r.last + 1).coerceIn(from, text.length)
            if (from > cursor) append(text.substring(cursor, from), plain)
            if (to > from) append(text.substring(from, to), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            cursor = to
        }
        if (cursor < text.length) append(text.substring(cursor), plain)
    }
}

/** Read-vs-write colouring, so a destructive handler is visually distinct at a glance. */
private fun HttpVerb.color(): Color = when (this) {
    HttpVerb.GET, HttpVerb.HEAD, HttpVerb.OPTIONS -> JBColor(0x2E7D32, 0x6A9955)
    HttpVerb.POST -> JBColor(0x1565C0, 0x569CD6)
    HttpVerb.PUT, HttpVerb.PATCH -> JBColor(0xE65100, 0xCE9178)
    HttpVerb.DELETE -> JBColor(0xC62828, 0xD16969)
    HttpVerb.ANY -> JBColor.GRAY
}
