package com.xmitya.quicker.endpoints.model

import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.xmitya.quicker.endpoints.match.EndpointKind
import org.assertj.core.api.Assertions.assertThat
import org.intellij.lang.annotations.Language

/**
 * Copying a path is the reverse of search, so every case here is a shape the scanner already
 * handles — asked the other way round: from a handler method to the path it is served at.
 */
class EndpointPathResolverTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = DefaultLightProjectDescriptor()

    override fun setUp() {
        super.setUp()
        SpringStubs.install(myFixture)
    }

    private fun add(path: String, @Language("JAVA") text: String) {
        myFixture.addFileToProject(path, text)
    }

    /** Opens [path] in the editor with the caret where `<caret>` marks it. */
    private fun open(path: String, text: String) {
        myFixture.addFileToProject(path, text)
        myFixture.configureFromTempProjectFile(path)
    }

    private fun openJava(path: String, @Language("JAVA") text: String) = open(path, text)

    private fun openKotlin(path: String, @Language("kotlin") text: String) = open(path, text)

    /** Null when the caret is not on a handler, i.e. the action would not be offered. */
    private fun pathsAtCaret(): List<String>? {
        val resolver = EndpointPathResolver(project)
        val element = myFixture.file.findElementAt(myFixture.caretOffset) ?: return null
        val handler = resolver.handlerAt(element) ?: return null
        return resolver.pathsOf(handler).map { "${it.verb} ${it.path}" }
    }

    fun `test controller method with literal paths`() {
        openJava(
            "demo/DltController.java",
            """
            package demo;
            import org.springframework.web.bind.annotation.*;
            @RestController
            @RequestMapping("/internal/dlt")
            public class DltController {
                @GetMapping
                public String list() { return null; }
                @PostMapping("/{consumer}")
                public void st<caret>art(@PathVariable String consumer) {}
            }
            """.trimIndent(),
        )
        assertThat(pathsAtCaret()).containsExactly("POST /internal/dlt/{consumer}")
    }

    /** Anywhere in the method counts, not only its name: the annotation and the body too. */
    fun `test caret on the mapping annotation`() {
        openJava(
            "demo/AccountsController.java",
            """
            package demo;
            import org.springframework.web.bind.annotation.*;
            @RestController
            @RequestMapping("/v1/accounts")
            public class AccountsController {
                @Get<caret>Mapping("/{id}") public String one() { return null; }
            }
            """.trimIndent(),
        )
        assertThat(pathsAtCaret()).containsExactly("GET /v1/accounts/{id}")
    }

    fun `test caret in the method body`() {
        openJava(
            "demo/AccountsController.java",
            """
            package demo;
            import org.springframework.web.bind.annotation.*;
            @RestController
            @RequestMapping("/v1/accounts")
            public class AccountsController {
                @GetMapping("/{id}") public String one() { return nu<caret>ll; }
            }
            """.trimIndent(),
        )
        assertThat(pathsAtCaret()).containsExactly("GET /v1/accounts/{id}")
    }

    fun `test plain method is not a handler`() {
        openJava(
            "demo/AccountsController.java",
            """
            package demo;
            import org.springframework.web.bind.annotation.*;
            @RestController
            @RequestMapping("/v1/accounts")
            public class AccountsController {
                private String hel<caret>per() { return null; }
            }
            """.trimIndent(),
        )
        assertThat(pathsAtCaret()).isNull()
    }

    /**
     * The interface alone knows only the suffix; the base sits on the controller implementing it.
     * Copying from the interface has to reach across to that controller.
     */
    fun `test interface method takes the base path from its controller`() {
        add(
            "demo/UserProfileController.java",
            """
            package demo;
            import api.UserProfileApi;
            import org.springframework.web.bind.annotation.*;
            @RestController
            @RequestMapping(UserProfileApi.BASE_PATH)
            public class UserProfileController implements UserProfileApi {
                @Override public String getProfile(String id) { return null; }
            }
            """.trimIndent(),
        )
        openJava(
            "api/UserProfileApi.java",
            """
            package api;
            import org.springframework.web.bind.annotation.*;
            public interface UserProfileApi {
                String BASE_PATH = "/dmp/v1/profile";
                @GetMapping("/{id}")
                String get<caret>Profile(@PathVariable String id);
            }
            """.trimIndent(),
        )
        assertThat(pathsAtCaret()).containsExactly("GET /dmp/v1/profile/{id}")
    }

    /** The override carries no annotation of its own; the mapping is inherited from the interface. */
    fun `test unannotated override in the controller`() {
        add(
            "api/UserProfileApi.java",
            """
            package api;
            import org.springframework.web.bind.annotation.*;
            public interface UserProfileApi {
                String BASE_PATH = "/dmp/v1/profile";
                @GetMapping("/{id}") String getProfile(@PathVariable String id);
            }
            """.trimIndent(),
        )
        openJava(
            "demo/UserProfileController.java",
            """
            package demo;
            import api.UserProfileApi;
            import org.springframework.web.bind.annotation.*;
            @RestController
            @RequestMapping(UserProfileApi.BASE_PATH)
            public class UserProfileController implements UserProfileApi {
                @Override public String get<caret>Profile(String id) { return null; }
            }
            """.trimIndent(),
        )
        assertThat(pathsAtCaret()).containsExactly("GET /dmp/v1/profile/{id}")
    }

    fun `test HttpExchange on the interface supplies the base path`() {
        add(
            "demo/TransactionController.java",
            """
            package demo;
            import api.TransactionApi;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            public class TransactionController implements TransactionApi {
                @Override public String get(String id) { return null; }
            }
            """.trimIndent(),
        )
        openJava(
            "api/TransactionApi.java",
            """
            package api;
            import org.springframework.web.bind.annotation.*;
            import org.springframework.web.service.annotation.HttpExchange;
            @HttpExchange(TransactionApi.BASE_PATH)
            public interface TransactionApi {
                String BASE_PATH = "/v1/user-gateway/transactions";
                @GetMapping("/{id}") String g<caret>et(@PathVariable String id);
            }
            """.trimIndent(),
        )
        assertThat(pathsAtCaret()).containsExactly("GET /v1/user-gateway/transactions/{id}")
    }

    /** One interface, two controllers under different bases: both paths are real. */
    fun `test interface implemented by two controllers yields both paths`() {
        add(
            "demo/PublicOrders.java",
            """
            package demo;
            import api.OrdersApi;
            import org.springframework.web.bind.annotation.*;
            @RestController @RequestMapping("/public/orders")
            public class PublicOrders implements OrdersApi {
                @Override public String get(String id) { return null; }
            }
            """.trimIndent(),
        )
        add(
            "demo/InternalOrders.java",
            """
            package demo;
            import api.OrdersApi;
            import org.springframework.web.bind.annotation.*;
            @RestController @RequestMapping("/internal/orders")
            public class InternalOrders implements OrdersApi {
                @Override public String get(String id) { return null; }
            }
            """.trimIndent(),
        )
        openJava(
            "api/OrdersApi.java",
            """
            package api;
            import org.springframework.web.bind.annotation.*;
            public interface OrdersApi {
                @GetMapping("/{id}") String g<caret>et(@PathVariable String id);
            }
            """.trimIndent(),
        )
        assertThat(pathsAtCaret()).containsExactlyInAnyOrder(
            "GET /public/orders/{id}",
            "GET /internal/orders/{id}",
        )
    }

    /** Nothing implements it yet, so the interface's own mapping is all there is. */
    fun `test interface without a controller reports its own path`() {
        openJava(
            "api/DraftApi.java",
            """
            package api;
            import org.springframework.web.bind.annotation.*;
            @RequestMapping("/v1/drafts")
            public interface DraftApi {
                @PostMapping String cre<caret>ate();
            }
            """.trimIndent(),
        )
        assertThat(pathsAtCaret()).containsExactly("POST /v1/drafts")
    }

    /** A shared API interface is often implemented by both sides; only the server side serves it. */
    fun `test feign client implementing the interface is ignored`() {
        add(
            "demo/UsersClient.java",
            """
            package demo;
            import api.UsersApi;
            import org.springframework.cloud.openfeign.FeignClient;
            import org.springframework.web.bind.annotation.*;
            @FeignClient(name = "users") @RequestMapping("/remote/users")
            public interface UsersClient extends UsersApi { }
            """.trimIndent(),
        )
        add(
            "demo/UsersController.java",
            """
            package demo;
            import api.UsersApi;
            import org.springframework.web.bind.annotation.*;
            @RestController @RequestMapping("/v1/users")
            public class UsersController implements UsersApi {
                @Override public String get(String id) { return null; }
            }
            """.trimIndent(),
        )
        openJava(
            "api/UsersApi.java",
            """
            package api;
            import org.springframework.web.bind.annotation.*;
            public interface UsersApi {
                @GetMapping("/{id}") String g<caret>et(@PathVariable String id);
            }
            """.trimIndent(),
        )
        assertThat(pathsAtCaret()).containsExactly("GET /v1/users/{id}")
    }

    fun `test feign client method is not offered`() {
        openJava(
            "demo/KseClient.java",
            """
            package demo;
            import org.springframework.cloud.openfeign.FeignClient;
            import org.springframework.web.bind.annotation.*;
            @FeignClient(name = "kse", url = "http://kse")
            public interface KseClient {
                @GetMapping("/kse/v1/files/{id}") String g<caret>et(@PathVariable String id);
            }
            """.trimIndent(),
        )
        assertThat(pathsAtCaret()).isNull()
    }

    /**
     * The custom annotation is found by walking up from it, not down from the Spring seeds — the
     * shape the upward closure exists for.
     */
    fun `test custom meta-annotation with AliasFor`() {
        add(
            "gw/AutoGatewayController.java",
            """
            package gw;
            import java.lang.annotation.*;
            import org.springframework.core.annotation.AliasFor;
            import org.springframework.web.bind.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
            @RestController
            @RequestMapping
            public @interface AutoGatewayController {
                @AliasFor(annotation = RequestMapping.class, attribute = "path") String value() default "";
            }
            """.trimIndent(),
        )
        add(
            "demo/AppController.java",
            """
            package demo;
            import api.AppApi;
            import gw.AutoGatewayController;
            @AutoGatewayController(AppApi.BASE_PATH)
            public interface AppController extends AppApi { }
            """.trimIndent(),
        )
        openJava(
            "api/AppApi.java",
            """
            package api;
            import org.springframework.web.bind.annotation.*;
            public interface AppApi {
                String BASE_PATH = "/v1/gateway/apps";
                @GetMapping("/{appId}") String g<caret>et(@PathVariable String appId);
            }
            """.trimIndent(),
        )
        assertThat(pathsAtCaret()).containsExactly("GET /v1/gateway/apps/{appId}")
    }

    fun `test RequestMapping without a method answers any verb`() {
        openJava(
            "demo/LegacyController.java",
            """
            package demo;
            import org.springframework.web.bind.annotation.*;
            @RestController
            @RequestMapping("/legacy")
            public class LegacyController {
                @RequestMapping("/any")
                public String a<caret>ny() { return null; }
            }
            """.trimIndent(),
        )
        assertThat(pathsAtCaret()).containsExactly("ANY /legacy/any")
    }

    fun `test kotlin controller method`() {
        openKotlin(
            "demo/RoleController.kt",
            """
            package demo
            import org.springframework.web.bind.annotation.*

            @RestController
            @RequestMapping("/v1/roles")
            class RoleController {
                @PutMapping("/{id}/state")
                fun upd<caret>ate(@PathVariable id: String) {}
            }
            """.trimIndent(),
        )
        assertThat(pathsAtCaret()).containsExactly("PUT /v1/roles/{id}/state")
    }

    /** Kotlin `override fun` carries no annotation, and the base is a companion constant. */
    fun `test kotlin interface method reaches the controller`() {
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
                override fun getAccount(id: String): String? = null
            }
            """.trimIndent(),
        )
        openKotlin(
            "api/GoogleAccountsApi.kt",
            """
            package api
            import org.springframework.web.bind.annotation.*

            interface GoogleAccountsApi {
                @GetMapping("/{id}")
                fun getAcc<caret>ount(@PathVariable id: String): String?

                companion object {
                    const val BASE_PATH = "/internal/v1/accounts/gplay"
                }
            }
            """.trimIndent(),
        )
        assertThat(pathsAtCaret()).containsExactly("GET /internal/v1/accounts/gplay/{id}")
    }

    /**
     * The property that makes this the reverse of search: from the handler of every endpoint the
     * scanner finds, copying yields exactly the path search would match it by.
     *
     * Controllers only. An API interface is also reported by the scanner as an ORPHAN under its own
     * partial path, and copying from it deliberately gives the controller's full one instead.
     */
    fun `test every scanned endpoint copies back to its own path`() {
        add(
            "api/UserProfileApi.java",
            """
            package api;
            import org.springframework.web.bind.annotation.*;
            public interface UserProfileApi {
                String BASE_PATH = "/dmp/v1/profile";
                @GetMapping String getProfile();
                @PutMapping("/{id}") String update(@PathVariable String id);
            }
            """.trimIndent(),
        )
        add(
            "demo/UserProfileController.java",
            """
            package demo;
            import api.UserProfileApi;
            import org.springframework.web.bind.annotation.*;
            @RestController
            @RequestMapping(UserProfileApi.BASE_PATH)
            public class UserProfileController implements UserProfileApi {
                @Override public String getProfile() { return null; }
                @Override public String update(String id) { return null; }
            }
            """.trimIndent(),
        )
        add(
            "demo/UploadController.java",
            """
            package demo;
            import org.springframework.web.bind.annotation.*;
            import static demo.Paths.UPLOAD_PATH;
            @RestController
            @RequestMapping("/v1")
            public class UploadController {
                static final String APP_PATH = "/app";
                @PostMapping(APP_PATH + UPLOAD_PATH + "/start") public void start() {}
                @RequestMapping(value = "/ping", method = RequestMethod.POST) public String ping() { return null; }
                @GetMapping("") public String list() { return null; }
            }
            """.trimIndent(),
        )
        add(
            "demo/Paths.java",
            """
            package demo;
            public interface Paths { String UPLOAD_PATH = "/upload"; }
            """.trimIndent(),
        )

        val scanned = EndpointScanner(project, ScanRead.BLOCKING).scan()
            .filter { it.info.kind == EndpointKind.CONTROLLER }
        assertThat(scanned).hasSize(5)
        val resolver = EndpointPathResolver(project)
        for (endpoint in scanned) {
            val element = endpoint.location.resolve(project)!!
            val handler = resolver.handlerAt(element)
            assertThat(handler).describedAs(endpoint.info.flatName).isNotNull()
            assertThat(resolver.pathsOf(handler!!).map { "${it.verb} ${it.path}" })
                .describedAs(endpoint.info.flatName)
                .containsExactly("${endpoint.info.verb} ${endpoint.info.path}")
        }
    }
}
