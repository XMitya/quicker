package com.xmitya.quicker.endpoints.match

/**
 * A parsed search query.
 *
 * Accepts the three things people actually paste: a full template (`/api/v1/users/{id}`), a
 * concrete URL out of a log (`http://host:8080/api/v1/users/42/orders?trace=1`), or a fragment of
 * either. An optional leading verb token filters rather than scores.
 */
data class EndpointQuery(
    val raw: String,
    val verb: HttpVerb?,
    val segments: List<String>,
    val mode: Mode,
) {
    enum class Mode {
        /** Query looked like a path — run structural alignment and fuzzy. */
        PATH,

        /** Whitespace-separated words — fuzzy only; structural alignment would be meaningless. */
        FUZZY,

        /** A single bare token — could be either, so try both. */
        BOTH,
    }

    val isEmpty: Boolean get() = segments.isEmpty()

    companion object {
        private val VERBS = HttpVerb.entries.filter { it != HttpVerb.ANY }.associateBy { it.name }
        private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://[^/]*")

        fun parse(input: String): EndpointQuery {
            var s = input.trim()

            // Leading verb token: "GET /users", "post users".
            var verb: HttpVerb? = null
            val firstBreak = s.indexOfFirst { it.isWhitespace() }
            if (firstBreak > 0) {
                val head = s.substring(0, firstBreak).uppercase()
                VERBS[head]?.let {
                    verb = it
                    s = s.substring(firstBreak).trim()
                }
            }

            s = SCHEME.replace(s, "") // scheme + authority
            s = s.substringBefore('?').substringBefore('#')
            s = s.trim().trim('/')

            val hadSlash = s.contains('/')
            val hadSpace = s.any { it.isWhitespace() }

            val segments = if (hadSpace && !hadSlash) {
                s.split(Regex("\\s+")).filter { it.isNotEmpty() }
            } else {
                splitSegments(s).filter { it.isNotEmpty() }
            }

            val mode = when {
                hadSpace -> Mode.FUZZY
                hadSlash -> Mode.PATH
                else -> Mode.BOTH
            }
            return EndpointQuery(input, verb, segments, mode)
        }
    }
}
