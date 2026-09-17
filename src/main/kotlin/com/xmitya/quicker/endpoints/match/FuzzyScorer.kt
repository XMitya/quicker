package com.xmitya.quicker.endpoints.match

import com.intellij.psi.codeStyle.MinusculeMatcher
import com.intellij.psi.codeStyle.NameUtil

/**
 * Track B of the ranking: Search-Everywhere-grade fuzzy matching.
 *
 * Behind an interface so the pure scoring logic stays unit-testable, and so the camel-hump/typo
 * behaviour is the platform's rather than something hand-rolled.
 */
interface FuzzyScorer {
    /** 0 when there is no match at all, otherwise 1..1000. */
    fun score(haystack: String): Int

    /** Ranges to embolden in the renderer, or null when there is no match. */
    fun fragments(haystack: String): List<IntRange>?

    companion object {
        fun forPattern(pattern: String): FuzzyScorer =
            if (pattern.isBlank()) NoMatch else PlatformFuzzyScorer(pattern)
    }

    object NoMatch : FuzzyScorer {
        override fun score(haystack: String) = 0
        override fun fragments(haystack: String): List<IntRange>? = null
    }
}

private class PlatformFuzzyScorer(pattern: String) : FuzzyScorer {
    private val matcher: MinusculeMatcher = NameUtil.buildMatcher(pattern)
        .withCaseSensitivity(NameUtil.MatchingCaseSensitivity.NONE)
        .preferringStartMatches()
        .typoTolerant()
        .build()

    override fun score(haystack: String): Int {
        if (!matcher.matches(haystack)) return 0
        val degree = matcher.matchingDegree(haystack, false).coerceAtLeast(0)
        // Squash an open-ended degree into 0..1000 so it can be blended with Track A.
        return (1000L * degree / (degree + 400L)).toInt().coerceAtLeast(1)
    }

    override fun fragments(haystack: String): List<IntRange>? =
        matcher.matchingFragments(haystack)?.map { it.startOffset until it.endOffset }
}
