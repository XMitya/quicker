package com.xmitya.quicker.endpoints.model

import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import org.assertj.core.api.Assertions.assertThat
import org.intellij.lang.annotations.Language

/**
 * The service decides *when* a scan happens, which is the part indexing keeps interfering with.
 *
 * Each test builds its own instance rather than taking the project service: a light fixture reuses
 * its project across test methods, so a shared instance would carry one test's model into the next.
 * Background tasks run on the calling thread in tests, so every `refresh` below is synchronous.
 */
class EndpointModelServiceTest : LightJavaCodeInsightFixtureTestCase() {

    /** See [EndpointScannerTest]: the default descriptor resolves annotations from Maven. */
    override fun getProjectDescriptor(): LightProjectDescriptor = DefaultLightProjectDescriptor()

    @Language("JAVA")
    private val controller = """
        package demo;
        import org.springframework.web.bind.annotation.*;
        @RestController
        @RequestMapping("/api/v1/users")
        public class UserController {
            @GetMapping("/{id}")
            public String get(String id) { return id; }
            @PostMapping
            public String create() { return ""; }
        }
    """.trimIndent()

    override fun setUp() {
        super.setUp()
        SpringStubs.install(myFixture)
        myFixture.addFileToProject("demo/UserController.java", controller)
    }

    fun `test nothing is scanned until the model is asked for`() {
        val service = EndpointModelService(project)
        assertThat(service.isReady).isFalse()
        assertThat(service.size).isZero()
    }

    fun `test refreshing builds the model and reports it`() {
        val service = EndpointModelService(project)
        var done = false
        service.refresh { done = true }
        assertThat(done).isTrue()
        assertThat(service.isReady).isTrue()
        assertThat(service.isStale).isFalse()
        assertThat(service.size).isEqualTo(2)
    }

    /** The first ask is answered from an empty model while the scan it schedules runs. */
    fun `test asking for endpoints schedules the scan`() {
        val service = EndpointModelService(project)
        assertThat(service.endpoints()).isEmpty()
        assertThat(service.endpoints().map { it.info.path })
            .containsExactlyInAnyOrder("/api/v1/users/{id}", "/api/v1/users")
    }

    fun `test a cached model is served as stale until a scan replaces it`() {
        EndpointCache.save(project, listOf(testEndpoint("/from/cache", "CachedController", "get")))
        val service = EndpointModelService(project)

        assertThat(service.primeFromCache()).isTrue()
        assertThat(service.isStale).isTrue()
        assertThat(service.size).isEqualTo(1)
        // Already primed: a second call must not overwrite a model that is being scanned.
        assertThat(service.primeFromCache()).isFalse()

        service.refresh()
        assertThat(service.isStale).isFalse()
        assertThat(service.size).isEqualTo(2)
    }

    fun `test indexing postpones the scan instead of cancelling it`() {
        val service = EndpointModelService(project)
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            service.refresh()
            // Indexes are closed, so the scan cannot run -- and must not be attempted.
            assertThat(service.isReady).isFalse()
        }
        // Leaving dumb mode releases the scan that was queued while indexing.
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertThat(service.isReady).isTrue()
        assertThat(service.size).isEqualTo(2)
    }

    /** The callback is what the popup waits on; dropping it would leave it spinning forever. */
    fun `test the callback fires even when indexing defers the scan`() {
        val service = EndpointModelService(project)
        var done = false
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            service.refresh { done = true }
        }
        assertThat(done).isTrue()
    }
}
