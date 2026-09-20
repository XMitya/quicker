package com.xmitya.quicker.endpoints.match

/**
 * The matchable facts about one endpoint. Deliberately free of PSI: the matcher runs off the EDT
 * on every keystroke, and dereferencing a [com.intellij.psi.SmartPsiElementPointer] outside a read
 * action throws. The navigable element lives on `model.Endpoint`, which wraps this.
 */
data class EndpointInfo(
    val verb: HttpVerb,
    val path: String,
    val segments: List<Segment>,
    val controllerSimpleName: String,
    val methodName: String,
    val kind: EndpointKind = EndpointKind.CONTROLLER,
    /** Path still contains an unresolved `${...}` placeholder. */
    val unresolved: Boolean = false,
    val inTestSource: Boolean = false,
    val moduleName: String? = null,
) {
    val flatName: String get() = "$controllerSimpleName.$methodName"
}

enum class EndpointKind { CONTROLLER, ORPHAN }

/**
 * `ANY` is a real Spring state, not an unknown: a bare `@RequestMapping` with no `method` attribute
 * answers every verb.
 */
enum class HttpVerb { GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS, ANY }

sealed interface Segment {
    val text: String

    @JvmInline
    value class Lit(override val text: String) : Segment

    /** A path variable. [text] is the variable name with any inline regex stripped. */
    @JvmInline
    value class Var(override val text: String) : Segment
}

/**
 * Splits a path on `/`, but only at brace depth 0.
 *
 * A naive split breaks on real templates: `{productCode:^[.a-zA-Z\d_-]{1,155}$}` contains both a
 * nested `{1,155}` and no slash, and `@GetMapping("/a/{x:[0-9]{2}}/b")` would lose its structure.
 */
fun splitSegments(path: String): List<String> {
    val out = ArrayList<String>(8)
    val sb = StringBuilder()
    var depth = 0
    for (c in path) {
        when {
            c == '{' -> {
                depth++
                sb.append(c)
            }
            c == '}' -> {
                if (depth > 0) depth--
                sb.append(c)
            }
            c == '/' && depth == 0 -> {
                if (sb.isNotEmpty()) {
                    out += sb.toString()
                    sb.setLength(0)
                }
            }
            else -> sb.append(c)
        }
    }
    if (sb.isNotEmpty()) out += sb.toString()
    return out
}

/** `{productCode:regex}` -> Var("productCode"); `{id}` -> Var("id"); `users` -> Lit("users"). */
fun toSegment(raw: String): Segment =
    if (raw.length > 1 && raw[0] == '{' && raw.last() == '}') {
        Segment.Var(raw.substring(1, raw.length - 1).substringBefore(':').trim())
    } else {
        Segment.Lit(raw)
    }

fun parseSegments(path: String): List<Segment> = splitSegments(path).map(::toSegment)

/**
 * Joins a class-level base path with a method-level suffix.
 *
 * Not `"$base$suffix".replace("//", "/")` — a global replace collapses only one level and also
 * mangles a path that legitimately contains `//`.
 */
fun joinPath(base: String, suffix: String): String {
    val b = base.trim().trimEnd('/')
    val s = suffix.trim().removePrefix("/")
    return when {
        s.isEmpty() -> if (b.isEmpty()) "/" else b
        b.isEmpty() -> "/$s"
        else -> "$b/$s"
    }
}
