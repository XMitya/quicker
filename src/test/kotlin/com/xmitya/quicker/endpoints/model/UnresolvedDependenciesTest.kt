package com.xmitya.quicker.endpoints.model

import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import org.assertj.core.api.Assertions.assertThat

/**
 * Spring is deliberately NOT on the classpath here: no stubs are installed, so every
 * `org.springframework.*` reference is unresolvable.
 *
 * This is the state of a freshly cloned monorepo, a Gradle import that has not finished, or one
 * whose dependency download failed -- and it is common enough on a repo this size to matter. A
 * resolver that keys on `JavaPsiFacade.findClass("org.springframework...")` silently reports zero
 * endpoints there, which is indistinguishable from "this project has none".
 */
class UnresolvedDependenciesTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = DefaultLightProjectDescriptor()

    // No outer read action: the scanner takes short ones itself, so that writes are not blocked
    // behind a whole scan. Wrapping it here would nest them and no longer match production.
    // BLOCKING reads: the fixture runs on the EDT holding the write-intent lock, where a
    // write-prioritised read from this thread would be cancelled and retried forever.
    private fun scan(): List<String> =
        EndpointScanner(project, ScanRead.BLOCKING).scan().map { "${it.info.verb} ${it.info.path}" }

    fun `test resolves endpoints with spring absent from the classpath`() {
        myFixture.addFileToProject(
            "demo/src/main/java/demo/OrderController.java",
            """
            package demo;
            import org.springframework.web.bind.annotation.*;
            @RestController
            @RequestMapping("/api/v1/orders")
            public class OrderController {
                @GetMapping("/{id}")
                public String get(@PathVariable String id) { return null; }
                @PostMapping
                public String create() { return null; }
            }
            """.trimIndent(),
        )
        assertThat(scan()).containsExactlyInAnyOrder(
            "GET /api/v1/orders/{id}",
            "POST /api/v1/orders",
        )
    }

    /** The custom meta-annotation still has to be followed, and it is in source, so it resolves. */
    fun `test custom meta-annotation with spring absent`() {
        myFixture.addFileToProject(
            "gw/src/main/java/gw/AutoGatewayController.java",
            """
            package gw;
            import java.lang.annotation.*;
            import org.springframework.web.bind.annotation.RestController;
            import org.springframework.web.bind.annotation.RequestMapping;
            @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
            @RestController
            @RequestMapping
            public @interface AutoGatewayController { String value() default ""; }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "demo/src/main/java/demo/AppController.java",
            """
            package demo;
            import gw.AutoGatewayController;
            import org.springframework.web.bind.annotation.GetMapping;
            @AutoGatewayController("/v1/gateway/apps")
            public interface AppController {
                @GetMapping("/{appId}") String get(String appId);
            }
            """.trimIndent(),
        )
        assertThat(scan()).contains("GET /v1/gateway/apps/{appId}")
    }

    /** Constants still fold: the declaring interface is in source too. */
    fun `test constant base path with spring absent`() {
        myFixture.addFileToProject(
            "api/src/main/java/api/UserApi.java",
            """
            package api;
            public interface UserApi { String BASE_PATH = "/dmp/v1/profile"; }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "demo/src/main/java/demo/UserController.java",
            """
            package demo;
            import api.UserApi;
            import org.springframework.web.bind.annotation.*;
            @RestController
            @RequestMapping(UserApi.BASE_PATH)
            public class UserController {
                @GetMapping("/me") public String me() { return null; }
            }
            """.trimIndent(),
        )
        assertThat(scan()).contains("GET /dmp/v1/profile/me")
    }

    /** Copying resolves by simple name too, like the scan: nothing on the handler resolves here. */
    fun `test copied path with spring absent`() {
        myFixture.addFileToProject(
            "demo/src/main/java/demo/OrderController.java",
            """
            package demo;
            import org.springframework.web.bind.annotation.*;
            @RestController
            @RequestMapping("/api/v1/orders")
            public class OrderController {
                @GetMapping("/{id}")
                public String get<caret>Order(@PathVariable String id) { return null; }
            }
            """.trimIndent(),
        )
        myFixture.configureFromTempProjectFile("demo/src/main/java/demo/OrderController.java")
        val resolver = EndpointPathResolver(project)
        val handler = resolver.handlerAt(myFixture.file.findElementAt(myFixture.caretOffset)!!)
        assertThat(handler).isNotNull()
        assertThat(resolver.pathsOf(handler!!).map { "${it.verb} ${it.path}" })
            .containsExactly("GET /api/v1/orders/{id}")
    }
}
