package com.xmitya.quicker.endpoints.match

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class SegmentationTest {

    @Test
    fun `splits on slash`() {
        assertThat(splitSegments("/api/v1/users")).containsExactly("api", "v1", "users")
    }

    /**
     * Real site: smartpay-integration ProductController.java:118. The inline regex contains its own
     * braces, so a naive `\{[^}]*\}` or a plain split would shred it.
     */
    @Test
    fun `regex path variable with nested braces survives`() {
        val path = "/applications/{appId}/subscription/{productCode:^[.a-zA-Z\\d_-]{1,155}$}"
        assertThat(splitSegments(path))
            .containsExactly("applications", "{appId}", "subscription", "{productCode:^[.a-zA-Z\\d_-]{1,155}\$}")

        val segments = parseSegments(path)
        assertThat(segments.map { it.text })
            .containsExactly("applications", "appId", "subscription", "productCode")
        assertThat(segments[3]).isInstanceOf(Segment.Var::class.java)
    }

    @Test
    fun `inline regex is stripped from the variable name`() {
        assertThat(toSegment("{id:[0-9]+}")).isEqualTo(Segment.Var("id"))
        assertThat(toSegment("{id}")).isEqualTo(Segment.Var("id"))
        assertThat(toSegment("users")).isEqualTo(Segment.Lit("users"))
    }

    /** `@GetMapping("")` and a bare `@GetMapping` both mean "the base path exactly". */
    @Test
    fun `empty suffix yields the base path`() {
        assertThat(joinPath("/internal/v1/accounts/gplay", "")).isEqualTo("/internal/v1/accounts/gplay")
        assertThat(joinPath("/internal/v1/accounts/gplay", "/")).isEqualTo("/internal/v1/accounts/gplay")
    }

    @Test
    fun `join does not double or swallow slashes`() {
        assertThat(joinPath("/api/v1/users", "/{id}")).isEqualTo("/api/v1/users/{id}")
        assertThat(joinPath("/api/v1/users/", "{id}")).isEqualTo("/api/v1/users/{id}")
        assertThat(joinPath("", "/{id}")).isEqualTo("/{id}")
        assertThat(joinPath("", "")).isEqualTo("/")
    }
}
