package com.xmitya.quicker.endpoints.model

import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import org.assertj.core.api.Assertions.assertThat
import org.intellij.lang.annotations.Language

/**
 * Resolver tests over the shapes that actually occur in the target monorepo. Each case here is
 * modelled on a real site; see the plan's "Must-pass test set".
 */
class EndpointScannerTest : LightJavaCodeInsightFixtureTestCase() {

    /**
     * The default descriptor attaches `org.jetbrains:annotations` by resolving it from Maven while
     * the fixture initialises, which fails offline. Nothing here needs it -- SpringStubs supplies
     * every annotation these tests touch.
     */
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

    private fun add(path: String, @Language("JAVA") text: String) {
        myFixture.addFileToProject(path, text)
    }

    fun `test literal paths on a self-contained controller`() {
        add(
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
                public void start(@PathVariable String consumer) {}
            }
            """.trimIndent(),
        )
        assertThat(scan()).containsExactlyInAnyOrder(
            "GET /internal/dlt",
            "POST /internal/dlt/{consumer}",
        )
    }

    /** ~91% of class-level paths in the target repo are a constant reference, not a literal. */
    fun `test constant reference from an interface in another file`() {
        add(
            "api/UserProfileApi.java",
            """
            package api;
            import org.springframework.web.bind.annotation.*;
            public interface UserProfileApi {
                String BASE_PATH = "/dmp/v1/profile";
                @GetMapping
                String getProfile();
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
            }
            """.trimIndent(),
        )
        // The mapping lives on the interface method; the override carries no annotation.
        assertThat(scan()).contains("GET /dmp/v1/profile")
    }

    fun `test static import of a constant`() {
        add(
            "api/SignatureApi.java",
            """
            package api;
            public interface SignatureApi { String SIGNATURE_PATH = "/v1/signature"; }
            """.trimIndent(),
        )
        add(
            "demo/SignatureController.java",
            """
            package demo;
            import org.springframework.web.bind.annotation.*;
            import static api.SignatureApi.SIGNATURE_PATH;
            @RestController
            @RequestMapping(SIGNATURE_PATH)
            public class SignatureController {
                @GetMapping("/check") public String check() { return null; }
            }
            """.trimIndent(),
        )
        assertThat(scan()).contains("GET /v1/signature/check")
    }

    /** 84 real sites concatenate constants at method level. */
    fun `test string concatenation of constants`() {
        add(
            "demo/UploadController.java",
            """
            package demo;
            import org.springframework.web.bind.annotation.*;
            @RestController
            @RequestMapping("/v1")
            public class UploadController {
                static final String APP_PATH = "/app";
                static final String UPLOAD_PATH = "/upload";
                @PostMapping(APP_PATH + UPLOAD_PATH + "/start")
                public void start() {}
                @PutMapping(UPLOAD_PATH + "/{id}/status/{status}")
                public void status() {}
            }
            """.trimIndent(),
        )
        assertThat(scan()).containsExactlyInAnyOrder(
            "POST /v1/app/upload/start",
            "PUT /v1/upload/{id}/status/{status}",
        )
    }

    /** `@GetMapping("")` and a bare `@GetMapping` both mean the base path exactly. */
    fun `test empty and absent method paths collapse to the base path`() {
        add(
            "demo/AccountsController.java",
            """
            package demo;
            import org.springframework.web.bind.annotation.*;
            @RestController
            @RequestMapping("/internal/v1/accounts")
            public class AccountsController {
                @GetMapping("") public String list() { return null; }
                @PostMapping public String create() { return null; }
                @GetMapping("/{id}") public String one() { return null; }
            }
            """.trimIndent(),
        )
        assertThat(scan()).containsExactlyInAnyOrder(
            "GET /internal/v1/accounts",
            "POST /internal/v1/accounts",
            "GET /internal/v1/accounts/{id}",
        )
    }

    /**
     * A custom annotation meta-annotated with the Spring ones, aliasing the path attribute. In the
     * target repo this shape (`@AutoGatewayController`) is 37% of the HTTP surface and never
     * mentions `@RestController`.
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
                @AliasFor(annotation = RequestMapping.class, attribute = "path") String controllerPath() default "";
            }
            """.trimIndent(),
        )
        add(
            "api/AppApi.java",
            """
            package api;
            import org.springframework.web.bind.annotation.*;
            public interface AppApi {
                String BASE_PATH = "/v1/gateway/apps";
                @GetMapping("/{appId}") String get(@PathVariable String appId);
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
        assertThat(scan()).contains("GET /v1/gateway/apps/{appId}")
    }

    /** For 227 real controllers the base path exists only as @HttpExchange on the interface. */
    fun `test HttpExchange on the interface supplies the base path`() {
        add(
            "api/TransactionApi.java",
            """
            package api;
            import org.springframework.web.bind.annotation.*;
            import org.springframework.web.service.annotation.HttpExchange;
            @HttpExchange(TransactionApi.BASE_PATH)
            public interface TransactionApi {
                String BASE_PATH = "/v1/user-gateway/transactions";
                @GetMapping("/{id}") String get(@PathVariable String id);
            }
            """.trimIndent(),
        )
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
        assertThat(scan()).contains("GET /v1/user-gateway/transactions/{id}")
    }

    /** Regex inside a path variable contains its own braces. */
    fun `test regex path variable is preserved`() {
        add(
            "demo/ProductController.java",
            """
            package demo;
            import org.springframework.web.bind.annotation.*;
            @RestController
            @RequestMapping("/v1")
            public class ProductController {
                @GetMapping("/applications/{appId}/subscription/{productCode:^[.a-zA-Z\\d_-]{1,155}${'$'}}")
                public String get() { return null; }
            }
            """.trimIndent(),
        )
        val found = scan().single()
        assertThat(found).startsWith("GET /v1/applications/{appId}/subscription/{productCode:")
    }

    /** Feign clients are outbound calls, not endpoints this service serves. */
    fun `test feign clients are excluded`() {
        add(
            "demo/KseClient.java",
            """
            package demo;
            import org.springframework.cloud.openfeign.FeignClient;
            import org.springframework.web.bind.annotation.*;
            @FeignClient(name = "kse", url = "http://kse")
            public interface KseClient {
                @GetMapping("/kse/v1/files/{id}") String get(@PathVariable String id);
            }
            """.trimIndent(),
        )
        assertThat(scan()).isEmpty()
    }

    fun `test RequestMapping with explicit method attribute`() {
        add(
            "demo/LegacyController.java",
            """
            package demo;
            import org.springframework.web.bind.annotation.*;
            @RestController
            @RequestMapping("/legacy")
            public class LegacyController {
                @RequestMapping(value = "/ping", method = RequestMethod.POST)
                public String ping() { return null; }
                @RequestMapping("/any")
                public String any() { return null; }
            }
            """.trimIndent(),
        )
        assertThat(scan()).containsExactlyInAnyOrder(
            "POST /legacy/ping",
            "ANY /legacy/any",
        )
    }
}
