package com.xmitya.quicker.endpoints.model

import com.xmitya.quicker.endpoints.match.EndpointMatcher
import com.xmitya.quicker.endpoints.match.EndpointQuery
import com.xmitya.quicker.endpoints.match.Hit
import com.xmitya.quicker.endpoints.match.MatchContext

/** A scored endpoint together with the element to navigate to. */
data class RankedEndpoint(val endpoint: Endpoint, val hit: Hit)

/**
 * Ranks navigable endpoints. Scoring itself stays in the PSI-free matcher; this only carries the
 * navigation target alongside, so nothing in the hot path dereferences a PSI pointer.
 */
fun rankEndpoints(
    endpoints: List<Endpoint>,
    input: String,
    context: MatchContext = MatchContext(),
    limit: Int = 50,
): List<RankedEndpoint> {
    val query = EndpointQuery.parse(input)
    if (query.isEmpty) return emptyList()
    val matcher = EndpointMatcher(query, context)
    return endpoints.asSequence()
        .mapNotNull { e -> matcher.score(e.info)?.let { RankedEndpoint(e, it) } }
        .sortedWith(compareBy(EndpointMatcher.RANKING) { it.hit })
        .take(limit)
        .toList()
}
