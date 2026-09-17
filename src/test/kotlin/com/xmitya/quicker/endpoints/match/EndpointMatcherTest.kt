package com.xmitya.quicker.endpoints.match

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * These assert *ordering properties*, not score values. The weights are tuning knobs and will move;
 * the invariants below are what the feature actually promises, so they are what must not regress.
 */
class EndpointMatcherTest {

    private fun ep(
        verb: HttpVerb,
        path: String,
        controller: String,
        method: String,
        kind: EndpointKind = EndpointKind.CONTROLLER,
        test: Boolean = false,
    ) = EndpointInfo(
        verb = verb,
        path = path,
        segments = parseSegments(path),
        controllerSimpleName = controller,
        methodName = method,
        kind = kind,
        inTestSource = test,
    )

    private val e1 = ep(HttpVerb.GET, "/api/v1/users/{id}/orders", "UserOrdersController", "getOrders")
    private val e2 = ep(HttpVerb.GET, "/api/v1/users/{id}", "UserController", "getUser")
    private val e3 = ep(HttpVerb.POST, "/api/v1/users", "UserController", "createUser")
    private val e4 = ep(HttpVerb.GET, "/api/v1/users", "UserController", "listUsers")
    private val e5 = ep(HttpVerb.DELETE, "/api/v1/users/{id}", "UserController", "deleteUser")
    private val e6 = ep(HttpVerb.GET, "/api/v1/users/{id}/orders/{orderId}", "UserOrdersController", "getOrder")
    private val e7 = ep(HttpVerb.GET, "/api/v2/users/{id}/orders", "UserOrdersV2Controller", "getOrders")
    private val e8 = ep(HttpVerb.GET, "/internal/v1/orders", "OrderInternalController", "listOrders")
    private val e9 = ep(HttpVerb.POST, "/api/v1/orders/{orderId}/refund", "OrderController", "refund")
    private val e10 = ep(HttpVerb.GET, "/api/v1/user-sessions/{id}", "UserSessionController", "get")
    private val e11 = ep(HttpVerb.GET, "/dmp/v1/profile/users/{id}", "ProfileController", "getUser")
    private val e12 = ep(HttpVerb.GET, "/api/v1/orders", "OrderController", "list")

    private val all = listOf(e1, e2, e3, e4, e5, e6, e7, e8, e9, e10, e11, e12)

    private fun paths(input: String) = rank(all, input).map { it.endpoint.path }
    private fun names(input: String) = rank(all, input).map { it.endpoint.flatName }

    /**
     * The motivating case: a concrete URL pasted out of a production log. `42` appears in no
     * template, so a fuzzy matcher alone scores every candidate at zero — only structural alignment
     * with `{id}` as a wildcard can resolve this.
     */
    @Test
    fun `concrete logged url resolves to its template`() {
        val result = rank(all, "users/42/orders")
        assertThat(result).isNotEmpty()
        assertThat(result.first().endpoint).isEqualTo(e1)
        // v1 and v2 both match structurally; the deeper /orders/{orderId} ranks below both.
        assertThat(result.map { it.endpoint }).containsSubsequence(e1, e7)
        // Endpoints that cannot absorb three segments must not appear at all.
        assertThat(result.map { it.endpoint }).doesNotContain(e3, e4, e12)
    }

    @Test
    fun `full url with host and query string resolves the same way`() {
        assertThat(rank(all, "http://localhost:8080/api/v1/users/42/orders?trace=1").first().endpoint)
            .isEqualTo(e1)
    }

    @Test
    fun `exact template is ranked first`() {
        val result = rank(all, "/api/v1/users/{id}")
        assertThat(result.first().endpoint.path).isEqualTo("/api/v1/users/{id}")
        // Both the GET and DELETE handler share that exact path; GET sorts first by verb order.
        assertThat(result.map { it.endpoint }).containsSubsequence(e2, e5)
    }

    @Test
    fun `camel hump query matches controller and method names`() {
        val result = names("usr ord")
        assertThat(result).isNotEmpty()
        assertThat(result.first()).startsWith("UserOrdersController")
        // Nothing without both "usr" and "ord" should survive.
        assertThat(result).noneMatch { it.startsWith("UserSessionController") }
    }

    /** A verb prefix filters; it must not merely down-rank. */
    @Test
    fun `verb prefix excludes other verbs`() {
        val result = rank(all, "GET users")
        assertThat(result.map { it.endpoint.verb }).containsOnly(HttpVerb.GET)
        assertThat(result.map { it.endpoint }).doesNotContain(e3, e5)
    }

    @Test
    fun `bare noun prefers the collection endpoint over nested ones`() {
        val result = rank(all, "orders")
        assertThat(result).isNotEmpty()
        assertThat(result.first().endpoint.path).isEqualTo("/api/v1/orders")
        assertThat(result.map { it.endpoint }).containsSubsequence(e12, e1)
    }

    @Test
    fun `version in query outranks a different version`() {
        val result = rank(all, "v1/users")
        val v1 = result.indexOfFirst { it.endpoint == e4 }
        val v2 = result.indexOfFirst { it.endpoint == e7 }
        assertThat(v1).isGreaterThanOrEqualTo(0)
        assertThat(if (v2 < 0) Int.MAX_VALUE else v2).isGreaterThan(v1)
    }

    @Test
    fun `exact segment outranks a longer segment that merely contains it`() {
        val result = rank(all, "v1/users")
        val exact = result.indexOfFirst { it.endpoint == e4 }        // /api/v1/users
        val kebab = result.indexOfFirst { it.endpoint == e10 }       // /api/v1/user-sessions/{id}
        assertThat(exact).isGreaterThanOrEqualTo(0)
        assertThat(if (kebab < 0) Int.MAX_VALUE else kebab).isGreaterThan(exact)
    }

    /** A numeric segment absorbed by `{id}` is free; an identifier absorbed by `{id}` is penalized. */
    @Test
    fun `identifier absorbed by a path variable ranks below a numeric one`() {
        val numeric = rank(all, "users/42").first { it.endpoint == e2 }.score
        val identifier = rank(all, "users/profile").firstOrNull { it.endpoint == e2 }?.score ?: 0
        assertThat(numeric).isGreaterThan(identifier)
    }

    @Test
    fun `partial suffix of a url matches`() {
        assertThat(paths("users/{id}/orders")).contains("/api/v1/users/{id}/orders")
    }

    @Test
    fun `test sources rank below production`() {
        val prod = ep(HttpVerb.GET, "/api/v1/things", "ThingController", "list")
        val test = ep(HttpVerb.GET, "/api/v1/things", "ThingControllerTest", "list", test = true)
        val result = rank(listOf(test, prod), "things")
        assertThat(result.first().endpoint).isEqualTo(prod)
    }

    @Test
    fun `controller endpoints rank above orphan interface methods`() {
        val controller = ep(HttpVerb.GET, "/api/v1/widgets", "WidgetController", "list")
        val orphan = ep(HttpVerb.GET, "/api/v1/widgets", "WidgetApi", "list", kind = EndpointKind.ORPHAN)
        val result = rank(listOf(orphan, controller), "widgets")
        assertThat(result.first().endpoint).isEqualTo(controller)
    }

    @Test
    fun `blank query returns nothing`() {
        assertThat(rank(all, "   ")).isEmpty()
    }

    @Test
    fun `query longer than any template returns nothing`() {
        assertThat(rank(all, "a/b/c/d/e/f/g/h/i/j/k")).isEmpty()
    }

    /**
     * Observed on the real monorepo: a query matching nothing came back with a full page of
     * unrelated endpoints. A template `{id}` absorbs any segment, so every endpoint ending in a
     * path variable scored ~900 on a one-segment query and buried the genuine matches.
     */
    @Test
    fun `a query matching no literal segment returns nothing`() {
        assertThat(rank(all, "no_rustore")).isEmpty()
        assertThat(rank(all, "zzzz")).isEmpty()
        assertThat(rank(all, "1234567")).isEmpty()
    }

    @Test
    fun `path variables alone never carry a match`() {
        val onlyVars = listOf(
            ep(HttpVerb.GET, "/apps/{appId}", "AppController", "getAppById"),
            ep(HttpVerb.GET, "/skeleton/{id}", "SkeletonController", "getSkeletonValue"),
        )
        assertThat(rank(onlyVars, "gateway-proxy")).isEmpty()
        // ...but a real literal hit still works on the same data.
        assertThat(rank(onlyVars, "apps").map { it.endpoint.path }).containsExactly("/apps/{appId}")
    }

    /** A partial path fragment must surface its own endpoints, not a generic fallback list. */
    @Test
    fun `partial path fragment returns only endpoints containing it`() {
        val data = listOf(
            ep(HttpVerb.GET, "/gateway-proxy/v1/promo", "PromotionsGatewayControllerV1", "getPromos"),
            ep(HttpVerb.GET, "/gateway-proxy/v1/app-labels", "AppLabelGatewayControllerV1", "getAppLabels"),
            ep(HttpVerb.GET, "/apps/{appId}", "AppController", "getAppById"),
            ep(HttpVerb.GET, "/skeleton/{id}", "SkeletonController", "getSkeletonValue"),
        )
        val paths = rank(data, "/gateway-proxy").map { it.endpoint.path }
        assertThat(paths).containsExactlyInAnyOrder(
            "/gateway-proxy/v1/promo",
            "/gateway-proxy/v1/app-labels",
        )
    }

    @Test
    fun `ranking is deterministic across runs`() {
        assertThat(paths("users")).isEqualTo(paths("users"))
    }
}
