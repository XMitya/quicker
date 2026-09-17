package com.xmitya.quicker.endpoints.model

import com.xmitya.quicker.endpoints.match.EndpointInfo

/**
 * A resolved endpoint plus how to navigate to it.
 *
 * The matchable half is kept separate from the navigable half on purpose: the matcher runs off the
 * EDT on every keystroke, and resolving a location touches PSI, which needs a read action.
 */
data class Endpoint(
    val info: EndpointInfo,
    val location: EndpointLocator,
)
