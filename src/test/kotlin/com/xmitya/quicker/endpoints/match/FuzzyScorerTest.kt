package com.xmitya.quicker.endpoints.match

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class FuzzyScorerTest {

    /** A query with nothing to fuzzy-match on must match nothing, not everything. */
    @Test
    fun `a blank pattern matches nothing`() {
        val scorer = FuzzyScorer.forPattern("   ")
        assertThat(scorer.score("/api/v1/users")).isZero()
        assertThat(scorer.fragments("/api/v1/users")).isNull()
    }

    /** Camel-hump matching is the platform's, which is the reason this goes through NameUtil. */
    @Test
    fun `a camel hump pattern scores its match and says where it matched`() {
        val scorer = FuzzyScorer.forPattern("userCon")
        assertThat(scorer.score("UserController.getUser")).isPositive()
        assertThat(scorer.fragments("UserController.getUser")).isNotEmpty()
    }

    @Test
    fun `a pattern that does not occur scores zero`() {
        val scorer = FuzzyScorer.forPattern("payments")
        assertThat(scorer.score("UserController.getUser")).isZero()
        assertThat(scorer.fragments("UserController.getUser")).isNull()
    }
}
