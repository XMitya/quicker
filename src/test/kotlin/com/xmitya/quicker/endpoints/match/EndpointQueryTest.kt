package com.xmitya.quicker.endpoints.match

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class EndpointQueryTest {

    @Test
    fun `strips scheme, authority, query string and fragment`() {
        val q = EndpointQuery.parse("http://localhost:8080/api/v1/users/42?trace=1#x")
        assertThat(q.segments).containsExactly("api", "v1", "users", "42")
        assertThat(q.verb).isNull()
    }

    @Test
    fun `leading verb token is extracted`() {
        val q = EndpointQuery.parse("GET /api/v1/users")
        assertThat(q.verb).isEqualTo(HttpVerb.GET)
        assertThat(q.segments).containsExactly("api", "v1", "users")
    }

    @Test
    fun `verb token is case insensitive`() {
        assertThat(EndpointQuery.parse("post users").verb).isEqualTo(HttpVerb.POST)
    }

    /** "users" is not a verb; it must not be eaten as one. */
    @Test
    fun `non-verb first word is kept`() {
        val q = EndpointQuery.parse("users orders")
        assertThat(q.verb).isNull()
        assertThat(q.segments).containsExactly("users", "orders")
    }

    @Test
    fun `whitespace query is fuzzy, slash query is path`() {
        assertThat(EndpointQuery.parse("usr ord").mode).isEqualTo(EndpointQuery.Mode.FUZZY)
        assertThat(EndpointQuery.parse("v1/users").mode).isEqualTo(EndpointQuery.Mode.PATH)
        assertThat(EndpointQuery.parse("orders").mode).isEqualTo(EndpointQuery.Mode.BOTH)
    }

    @Test
    fun `template braces survive parsing`() {
        assertThat(EndpointQuery.parse("/api/v1/users/{id}").segments)
            .containsExactly("api", "v1", "users", "{id}")
    }

    @Test
    fun `blank query is empty`() {
        assertThat(EndpointQuery.parse("   ").isEmpty).isTrue()
    }
}
