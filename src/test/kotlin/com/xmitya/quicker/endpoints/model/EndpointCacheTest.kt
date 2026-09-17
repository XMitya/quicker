package com.xmitya.quicker.endpoints.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import com.xmitya.quicker.endpoints.match.EndpointInfo
import com.xmitya.quicker.endpoints.match.EndpointKind
import com.xmitya.quicker.endpoints.match.HttpVerb
import com.xmitya.quicker.endpoints.match.parseSegments
import java.nio.file.Files

class EndpointCacheTest {

    private fun endpoint(
        verb: HttpVerb,
        path: String,
        controller: String,
        method: String,
        kind: EndpointKind = EndpointKind.CONTROLLER,
        unresolved: Boolean = false,
        test: Boolean = false,
        module: String? = "svc.main",
    ) = Endpoint(
        EndpointInfo(
            verb = verb,
            path = path,
            segments = parseSegments(path),
            controllerSimpleName = controller,
            methodName = method,
            kind = kind,
            unresolved = unresolved,
            inTestSource = test,
            moduleName = module,
        ),
        EndpointLocator("file:///repo/$controller.java", 42, "demo.$controller", method),
    )

    @Test
    fun `round trips every field`() {
        val original = listOf(
            endpoint(HttpVerb.GET, "/api/v1/users/{id}/orders", "UserOrdersController", "getOrders"),
            endpoint(HttpVerb.POST, "/api/v1/users", "UserController", "create", kind = EndpointKind.ORPHAN),
            endpoint(HttpVerb.ANY, "/legacy/any", "LegacyController", "any", unresolved = true, test = true),
            endpoint(HttpVerb.DELETE, "/x", "XController", "del", module = null),
        )
        val file = Files.createTempDirectory("quicker").resolve("model.bin")
        EndpointCache.saveTo(file, original)

        val restored = EndpointCache.loadFrom(file)
        assertThat(restored).isNotNull()
        assertThat(restored!!.map { it.info }).isEqualTo(original.map { it.info })
        assertThat(restored.map { it.location.classFqn }).isEqualTo(original.map { it.location.classFqn })
        assertThat(restored.map { it.location.fileUrl }).isEqualTo(original.map { it.location.fileUrl })
        assertThat(restored.map { it.location.offset }).isEqualTo(original.map { it.location.offset })
    }

    @Test
    fun `missing file yields null rather than failing`() {
        val file = Files.createTempDirectory("quicker").resolve("absent.bin")
        assertThat(EndpointCache.loadFrom(file)).isNull()
    }

    /** A cache truncated by a crash must not break startup. */
    @Test
    fun `truncated file is discarded`() {
        val file = Files.createTempDirectory("quicker").resolve("model.bin")
        EndpointCache.saveTo(file, listOf(endpoint(HttpVerb.GET, "/a", "AController", "a")))
        val bytes = Files.readAllBytes(file)
        Files.write(file, bytes.copyOf(bytes.size / 2))
        assertThat(EndpointCache.loadFrom(file)).isNull()
    }

    @Test
    fun `garbage file is discarded`() {
        val file = Files.createTempDirectory("quicker").resolve("model.bin")
        Files.write(file, "not a cache".toByteArray())
        assertThat(EndpointCache.loadFrom(file)).isNull()
    }
}
