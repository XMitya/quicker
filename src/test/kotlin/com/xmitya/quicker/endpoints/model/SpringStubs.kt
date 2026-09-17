package com.xmitya.quicker.endpoints.model

import com.intellij.testFramework.fixtures.JavaCodeInsightTestFixture

/**
 * Minimal stand-ins for the Spring annotations the resolver keys on.
 *
 * The fixture has no Spring on its classpath, and pulling the real jars in would make these tests
 * slow and version-coupled. Only the shape matters to the resolver: the meta-annotation graph and
 * the attribute names.
 */
object SpringStubs {

    fun install(fixture: JavaCodeInsightTestFixture) {
        fixture.addFileToProject(
            "org/springframework/core/annotation/AliasFor.java",
            """
            package org.springframework.core.annotation;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
            public @interface AliasFor {
                String value() default "";
                String attribute() default "";
                Class<? extends Annotation> annotation() default Annotation.class;
            }
            """.trimIndent(),
        )
        fixture.addFileToProject(
            "org/springframework/stereotype/Controller.java",
            """
            package org.springframework.stereotype;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
            public @interface Controller { String value() default ""; }
            """.trimIndent(),
        )
        fixture.addFileToProject(
            "org/springframework/web/bind/annotation/RestController.java",
            """
            package org.springframework.web.bind.annotation;
            import java.lang.annotation.*;
            import org.springframework.stereotype.Controller;
            @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
            @Controller
            public @interface RestController { String value() default ""; }
            """.trimIndent(),
        )
        fixture.addFileToProject(
            "org/springframework/web/bind/annotation/RequestMethod.java",
            """
            package org.springframework.web.bind.annotation;
            public enum RequestMethod { GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS, TRACE }
            """.trimIndent(),
        )
        fixture.addFileToProject(
            "org/springframework/web/bind/annotation/RequestMapping.java",
            """
            package org.springframework.web.bind.annotation;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.TYPE, ElementType.METHOD, ElementType.ANNOTATION_TYPE})
            public @interface RequestMapping {
                String[] value() default {};
                String[] path() default {};
                RequestMethod[] method() default {};
            }
            """.trimIndent(),
        )
        for ((simple, verb) in listOf(
            "GetMapping" to "GET", "PostMapping" to "POST", "PutMapping" to "PUT",
            "DeleteMapping" to "DELETE", "PatchMapping" to "PATCH",
        )) {
            fixture.addFileToProject(
                "org/springframework/web/bind/annotation/$simple.java",
                """
                package org.springframework.web.bind.annotation;
                import java.lang.annotation.*;
                import org.springframework.core.annotation.AliasFor;
                @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
                @RequestMapping(method = RequestMethod.$verb)
                public @interface $simple {
                    @AliasFor(annotation = RequestMapping.class, attribute = "path") String[] value() default {};
                    @AliasFor(annotation = RequestMapping.class, attribute = "path") String[] path() default {};
                }
                """.trimIndent(),
            )
        }
        fixture.addFileToProject(
            "org/springframework/web/service/annotation/HttpExchange.java",
            """
            package org.springframework.web.service.annotation;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.TYPE, ElementType.METHOD, ElementType.ANNOTATION_TYPE})
            public @interface HttpExchange {
                String value() default "";
                String url() default "";
                String method() default "";
            }
            """.trimIndent(),
        )
        fixture.addFileToProject(
            "org/springframework/cloud/openfeign/FeignClient.java",
            """
            package org.springframework.cloud.openfeign;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
            public @interface FeignClient { String name() default ""; String url() default ""; }
            """.trimIndent(),
        )
    }
}
