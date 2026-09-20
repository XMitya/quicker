package com.xmitya.quicker.endpoints.model

import com.xmitya.quicker.endpoints.match.HttpVerb
import com.xmitya.quicker.endpoints.match.MatchContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * The scoring itself is the matcher's business and is tested there. What is asserted here is the
 * contract of the wrapper the UI calls: which endpoints survive, in what order, how many, and that
 * each one still knows where to navigate.
 */
class RankedEndpointTest {

    private val orders = testEndpoint("/api/v1/users/{id}/orders", "UserOrdersController", "getOrders")
    private val user = testEndpoint("/api/v1/users/{id}", "UserController", "getUser")
    private val payments =
        testEndpoint("/api/v1/payments", "PaymentController", "pay", verb = HttpVerb.POST)
    private val all = listOf(orders, user, payments)

    @Test
    fun `a blank query ranks nothing`() {
        assertThat(rankEndpoints(all, "   ")).isEmpty()
    }

    @Test
    fun `a concrete url ranks its template first`() {
        val ranked = rankEndpoints(all, "users/42/orders")
        assertThat(ranked.first().endpoint).isEqualTo(orders)
    }

    @Test
    fun `endpoints that cannot match are dropped`() {
        assertThat(rankEndpoints(all, "payments").map { it.endpoint }).containsExactly(payments)
    }

    @Test
    fun `the limit caps the result`() {
        assertThat(rankEndpoints(all, "api", limit = 1)).hasSize(1)
    }

    @Test
    fun `the navigation target rides along with the score`() {
        val top = rankEndpoints(all, "users/42/orders").first()
        assertThat(top.hit.score).isPositive()
        assertThat(top.endpoint.location.classFqn).isEqualTo("demo.UserOrdersController")
    }

    /** The current module is a ranking input, so it has to reach the matcher. */
    @Test
    fun `context is passed through to the matcher`() {
        val here = testEndpoint("/api/v1/things", "ThingController", "list", module = "here")
        val elsewhere = testEndpoint("/api/v1/things", "OtherThingController", "list", module = "far")
        val ranked = rankEndpoints(
            listOf(elsewhere, here),
            "things",
            MatchContext(currentModule = "here"),
        )
        assertThat(ranked.first().endpoint).isEqualTo(here)
    }
}
