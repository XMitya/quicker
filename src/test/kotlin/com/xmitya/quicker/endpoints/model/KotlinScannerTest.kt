package com.xmitya.quicker.endpoints.model

import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import org.assertj.core.api.Assertions.assertThat

/**
 * Kotlin controllers are 58% of the target monorepo, and the Kotlin-specific constant shapes
 * (companion `const val` reached through a static import, file-level `const val`) are the ones
 * least likely to survive the Java-shaped resolver.
 */
class KotlinScannerTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = DefaultLightProjectDescriptor()

    override fun setUp() {
        super.setUp()
        SpringStubs.install(myFixture)
    }

    // No outer read action: the scanner takes short ones itself, so that writes are not blocked
    // behind a whole scan. Wrapping it here would nest them and no longer match production.
    // BLOCKING reads: the fixture runs on the EDT holding the write-intent lock, where a
    // write-prioritised read from this thread would be cancelled and retried forever.
    private fun scan(): List<String> =
        EndpointScanner(project, ScanRead.BLOCKING).scan().map { "${it.info.verb} ${it.info.path}" }

    fun `test kotlin controller with literal paths`() {
        myFixture.addFileToProject(
            "demo/DltController.kt",
            """
            package demo
            import org.springframework.web.bind.annotation.*

            @RestController
            @RequestMapping("/internal/dlt")
            class DltController {
                @GetMapping
                fun list(): String? = null

                @PostMapping("/{consumer}")
                fun start(@PathVariable consumer: String) {}
            }
            """.trimIndent(),
        )
        assertThat(scan()).containsExactlyInAnyOrder(
            "GET /internal/dlt",
            "POST /internal/dlt/{consumer}",
        )
    }

    /** File-level `const val` — one of the four constant shapes in the survey. */
    fun `test file level const val`() {
        myFixture.addFileToProject(
            "api/InternalApi.kt",
            """
            package api
            const val BASE_PATH = "/internal/v1"
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "demo/InternalController.kt",
            """
            package demo
            import api.BASE_PATH
            import org.springframework.web.bind.annotation.*

            @RestController
            @RequestMapping(BASE_PATH)
            class InternalController {
                @GetMapping("/ping")
                fun ping(): String? = null
            }
            """.trimIndent(),
        )
        assertThat(scan()).contains("GET /internal/v1/ping")
    }

    /**
     * Companion `const val` pulled in by a static import, so the annotation argument is a bare
     * unqualified identifier. Modelled on store-aggregator/GoogleAccountsController.kt.
     */
    fun `test companion const val via static import`() {
        myFixture.addFileToProject(
            "api/GoogleAccountsApi.kt",
            """
            package api
            import org.springframework.web.bind.annotation.*

            interface GoogleAccountsApi {
                @GetMapping("")
                fun getAccounts(): String?

                @GetMapping("/{id}")
                fun getAccount(@PathVariable id: String): String?

                companion object {
                    const val BASE_PATH = "/internal/v1/accounts/gplay"
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "demo/GoogleAccountsController.kt",
            """
            package demo
            import api.GoogleAccountsApi
            import api.GoogleAccountsApi.Companion.BASE_PATH
            import org.springframework.web.bind.annotation.*

            @RequestMapping(BASE_PATH)
            @RestController
            class GoogleAccountsController : GoogleAccountsApi {
                override fun getAccounts(): String? = null
                override fun getAccount(id: String): String? = null
            }
            """.trimIndent(),
        )
        assertThat(scan()).contains(
            "GET /internal/v1/accounts/gplay",
            "GET /internal/v1/accounts/gplay/{id}",
        )
    }

    /** Kotlin `override fun` carries no annotation; the mapping is on the interface method. */
    fun `test kotlin interface and impl across files`() {
        myFixture.addFileToProject(
            "api/TransactionApi.kt",
            """
            package api
            import org.springframework.web.bind.annotation.*
            import org.springframework.web.service.annotation.HttpExchange

            @HttpExchange(TransactionApi.BASE_PATH)
            interface TransactionApi {
                @GetMapping("/{id}")
                fun get(@PathVariable id: String): String?

                companion object {
                    const val BASE_PATH = "/v1/user-gateway/transactions"
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "demo/TransactionController.kt",
            """
            package demo
            import api.TransactionApi
            import org.springframework.web.bind.annotation.RestController

            @RestController
            class TransactionController : TransactionApi {
                override fun get(id: String): String? = null
            }
            """.trimIndent(),
        )
        assertThat(scan()).contains("GET /v1/user-gateway/transactions/{id}")
    }

    /** Kotlin array-literal attribute syntax, forced by a sibling `consumes`/`produces`. */
    fun `test kotlin array literal path`() {
        myFixture.addFileToProject(
            "demo/ArrayController.kt",
            """
            package demo
            import org.springframework.web.bind.annotation.*

            @RestController
            @RequestMapping("/v1")
            class ArrayController {
                @GetMapping(value = ["/statistic/{type}"])
                fun stat(@PathVariable type: String): String? = null
            }
            """.trimIndent(),
        )
        assertThat(scan()).contains("GET /v1/statistic/{type}")
    }

    /** Concatenation of Kotlin constants at method level. */
    fun `test kotlin concatenation`() {
        myFixture.addFileToProject(
            "api/Paths.kt",
            """
            package api
            const val UPDATE_PATH = "/update"
            const val STATE_PATH = "/state"
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "demo/RoleController.kt",
            """
            package demo
            import api.STATE_PATH
            import api.UPDATE_PATH
            import org.springframework.web.bind.annotation.*

            @RestController
            @RequestMapping("/v1/roles")
            class RoleController {
                @PutMapping(UPDATE_PATH + STATE_PATH)
                fun update() {}
            }
            """.trimIndent(),
        )
        assertThat(scan()).contains("PUT /v1/roles/update/state")
    }
}
