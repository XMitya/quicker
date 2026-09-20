package com.xmitya.quicker.endpoints.model

import com.xmitya.quicker.endpoints.match.EndpointKind
import com.xmitya.quicker.endpoints.match.HttpVerb
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.nio.file.Files

class EndpointCacheTest {

    @Test
    fun `round trips every field`() {
        val original = listOf(
            testEndpoint("/api/v1/users/{id}/orders", "UserOrdersController", "getOrders"),
            testEndpoint("/api/v1/users", "UserController", "create", verb = HttpVerb.POST, kind = EndpointKind.ORPHAN),
            testEndpoint("/legacy/any", "LegacyController", "any", verb = HttpVerb.ANY, unresolved = true, test = true),
            testEndpoint("/x", "XController", "del", verb = HttpVerb.DELETE, module = null),
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
        EndpointCache.saveTo(file, listOf(testEndpoint("/a", "AController", "a")))
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
