package com.xmitya.quicker.endpoints.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * The scan is restricted to hand-written sources. Pure, because a light fixture cannot express it:
 * it rewrites every added file under its own `/src/` root.
 */
class SourcePathFilterTest {

    @Test
    fun `accepts standard gradle and maven source layouts`() {
        assertThat(isHandWrittenSourcePath("/repo/svc/src/main/java/a/B.java")).isTrue()
        assertThat(isHandWrittenSourcePath("/repo/svc/src/main/kotlin/a/B.kt")).isTrue()
        assertThat(isHandWrittenSourcePath("/repo/svc/src/test/java/a/BTest.java")).isTrue()
    }

    /** 662 such directories in the target monorepo, holding ~10,800 duplicate sources. */
    @Test
    fun `rejects compiler output copies`() {
        assertThat(isHandWrittenSourcePath("/repo/svc/bin/main/a/B.java")).isFalse()
        assertThat(isHandWrittenSourcePath("/repo/svc/build/classes/a/B.java")).isFalse()
        assertThat(isHandWrittenSourcePath("/repo/svc/out/production/a/B.java")).isFalse()
    }

    /** A `src` nested inside an output directory is still a duplicate, not a source. */
    @Test
    fun `rejects src nested under an output directory`() {
        assertThat(isHandWrittenSourcePath("/repo/svc/bin/main/src/a/B.java")).isFalse()
        assertThat(isHandWrittenSourcePath("/repo/svc/build/generated/src/a/B.java")).isFalse()
    }

    @Test
    fun `rejects paths with no src segment`() {
        assertThat(isHandWrittenSourcePath("/repo/svc/java/a/B.java")).isFalse()
        assertThat(isHandWrittenSourcePath("/repo/srcfoo/main/a/B.java")).isFalse()
    }
}
