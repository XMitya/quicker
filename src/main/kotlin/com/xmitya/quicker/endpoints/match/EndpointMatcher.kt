package com.xmitya.quicker.endpoints.match

/** One scored endpoint. [pathFragments] drives the bolding in the renderer. */
data class Hit(
    val endpoint: EndpointInfo,
    val score: Int,
    val pathFragments: List<IntRange>? = null,
)

/** Context that nudges ranking toward what the user is currently looking at. */
data class MatchContext(
    val currentModule: String? = null,
    /** Endpoints picked recently, most-recent-first. */
    val recent: List<String> = emptyList(),
)

/**
 * Ranks endpoints against a query.
 *
 * Two independent tracks, because they answer different questions:
 *
 *  - **Track A (structural)** aligns query segments against template segments, treating a template
 *    `{var}` as a wildcard. This is the only thing that can match a concrete URL out of a log
 *    (`/users/42/orders`) against its template (`/users/{id}/orders`) — a fuzzy matcher scores that
 *    at zero, because `42` appears nowhere in the template.
 *  - **Track B (fuzzy)** is the platform's `MinusculeMatcher` over the flat path and over
 *    `Controller.method`, giving camel-hump and typo tolerance for partial typing.
 *
 * Construct once per query, then [score] over the endpoint list.
 */
class EndpointMatcher(
    private val query: EndpointQuery,
    private val context: MatchContext = MatchContext(),
) {
    private val pathFuzzy: FuzzyScorer = FuzzyScorer.forPattern(query.segments.joinToString(" "))
    private val segmentFuzzy: List<FuzzyScorer> = query.segments.map { FuzzyScorer.forPattern(it) }

    fun score(e: EndpointInfo): Hit? {
        if (query.isEmpty) return null

        // A verb prefix is a filter, not a hint: "GET users" must not surface the POST handler.
        // ANY endpoints (bare @RequestMapping) answer every verb, so they always survive.
        val verb = query.verb
        if (verb != null && e.verb != HttpVerb.ANY && e.verb != verb) return null

        val trackA = if (query.mode == EndpointQuery.Mode.FUZZY) 0 else structuralScore(e)
        val trackB = fuzzyScore(e, structural = trackA > 0)
        if (trackA == 0 && trackB == 0) return null

        var score = if (trackA > 0) {
            (0.75 * trackA + 0.25 * trackB).toInt()
        } else {
            (0.60 * trackB).toInt()
        }

        score += bonuses(e, verb)
        if (score < MIN_SCORE) return null
        return Hit(e, score, pathFuzzy.fragments(e.path))
    }

    // ---- Track A -----------------------------------------------------------------------------

    /**
     * Best contiguous alignment of the query's segments anywhere in the template, scaled by how
     * well-anchored that alignment is.
     */
    private fun structuralScore(e: EndpointInfo): Int {
        val ts = e.segments
        val qs = query.segments
        val m = qs.size
        val n = ts.size
        if (m == 0 || m > n) return 0

        var bestRun = 0.0
        var bestOffset = -1
        for (offset in 0..(n - m)) {
            var sum = 0.0
            var literals = 0
            for (i in 0 until m) {
                val t = ts[offset + i]
                val s = segMatch(t, qs[i], i)
                if (s == 0.0) { sum = 0.0; break }
                if (t is Segment.Lit) literals++
                sum += s
            }
            // An alignment carried entirely by path variables is not evidence of anything: a
            // template `{id}` absorbs any segment, so `/apps/{appId}` would score ~900 against a
            // query like `no_rustore` and crowd out the real matches. Require at least one literal
            // segment to have actually matched.
            if (sum > 0.0 && literals > 0) {
                val run = sum / m
                if (run > bestRun) { bestRun = run; bestOffset = offset }
            }
        }
        if (bestOffset < 0) return 0

        val anchor = when {
            bestOffset == 0 && m == n -> 1.00   // whole template
            bestOffset == 0 -> 0.92             // prefix
            bestOffset + m == n -> 0.88         // suffix — people remember the tail of a URL
            else -> 0.80                        // floating
        }
        var a = 1000.0 * bestRun * anchor

        var allExact = true
        var identifierAbsorbed = 0
        for (i in 0 until m) {
            val t = ts[bestOffset + i]
            val q = qs[i]
            if (t is Segment.Var && !isBraceToken(q)) {
                // A numeric segment from a log absorbed by {id} is free. An identifier absorbed by
                // {id} is suspicious: "v1" landing in {id} of an unrelated template is noise.
                if (IDENTIFIER.matches(q)) identifierAbsorbed++
            }
            if (segMatch(t, q, i) < 1.0) allExact = false
        }
        if (allExact) a += 60
        a -= 25.0 * identifierAbsorbed
        // Every template segment the query did not cover is a little less certainty.
        a -= minOf(50, (n - m) * 10)
        return a.toInt().coerceAtLeast(0)
    }

    private fun segMatch(t: Segment, q: String, qIndex: Int): Double {
        if (t is Segment.Var) return 1.0
        // A typed `{id}` is a template placeholder; it cannot stand in for a literal segment.
        if (isBraceToken(q)) return 0.0
        val text = t.text
        if (text.equals(q, ignoreCase = true)) return 1.0
        if (q.length >= 3) {
            if (text.startsWith(q, ignoreCase = true)) return 0.75
            if (text.contains(q, ignoreCase = true)) return 0.60
        }
        return if (segmentFuzzy[qIndex].score(text) > 0) 0.45 else 0.0
    }

    // ---- Track B -----------------------------------------------------------------------------

    /**
     * When the query already aligned against the path, the path is the signal that matters and the
     * declaration name must not override it. Blending the name in unconditionally lets an
     * artefact decide the ranking: `MinusculeMatcher` rewards shorter haystacks, so for the query
     * `orders` the name `OrderInternalController.listOrders` outscores `OrderController.list` and
     * drags `/internal/v1/orders` above `/api/v1/orders` even though both paths match identically.
     * The name only gets a vote when there is no structural match to trust -- which is exactly the
     * `usr ord` case it exists for.
     */
    private fun fuzzyScore(e: EndpointInfo, structural: Boolean): Int {
        val byPath = pathFuzzy.score(e.path)
        if (structural) return byPath
        val byName = (0.85 * pathFuzzy.score(e.flatName)).toInt()
        return maxOf(byPath, byName)
    }

    // ---- Tie-breakers ------------------------------------------------------------------------

    private fun bonuses(e: EndpointInfo, verb: HttpVerb?): Int {
        var b = 0
        if (verb != null && e.verb == verb) b += 40
        if (e.kind == EndpointKind.ORPHAN) b -= 60
        if (context.currentModule != null && e.moduleName == context.currentModule) b += 20
        val mru = context.recent.indexOf(e.flatName)
        if (mru >= 0) b += (15 - mru).coerceAtLeast(1)
        if (e.inTestSource) b -= 30 else b += 10
        if (e.unresolved) b -= 20
        if (query.segments.any { q -> e.controllerSimpleName.startsWith(q, ignoreCase = true) }) b += 8
        return b
    }

    companion object {
        const val MIN_SCORE = 300
        private val IDENTIFIER = Regex("^[A-Za-z][A-Za-z0-9_-]*$")
        private fun isBraceToken(s: String) = s.length > 1 && s[0] == '{' && s.last() == '}'

        /** Stable ordering for equal scores, so results never jitter between keystrokes. */
        val RANKING: Comparator<Hit> = compareByDescending<Hit> { it.score }
            .thenBy { it.endpoint.verb.ordinal }
            .thenBy { it.endpoint.path.length }
            .thenBy { it.endpoint.path }
            .thenBy { it.endpoint.flatName }
    }
}

/** Ranks [endpoints] against [input], best first. */
fun rank(
    endpoints: List<EndpointInfo>,
    input: String,
    context: MatchContext = MatchContext(),
    limit: Int = 50,
): List<Hit> {
    val query = EndpointQuery.parse(input)
    if (query.isEmpty) return emptyList()
    val matcher = EndpointMatcher(query, context)
    return endpoints.asSequence()
        .mapNotNull(matcher::score)
        .sortedWith(EndpointMatcher.RANKING)
        .take(limit)
        .toList()
}
