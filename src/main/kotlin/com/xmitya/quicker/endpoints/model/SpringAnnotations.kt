package com.xmitya.quicker.endpoints.model

import com.xmitya.quicker.endpoints.match.HttpVerb

object SpringAnnotations {
    const val CONTROLLER = "org.springframework.stereotype.Controller"
    const val REQUEST_MAPPING = "org.springframework.web.bind.annotation.RequestMapping"
    const val HTTP_EXCHANGE = "org.springframework.web.service.annotation.HttpExchange"
    const val FEIGN_CLIENT = "org.springframework.cloud.openfeign.FeignClient"
    const val ALIAS_FOR = "org.springframework.core.annotation.AliasFor"

    /**
     * Roots of the meta-annotation closure. Anything transitively meta-annotated with one of these
     * counts as a mapping annotation — which is how a project's own custom annotation is picked up
     * without naming it. `@RestController` is itself meta-annotated `@Controller`, so it arrives
     * through the closure rather than being listed.
     */
    val SEEDS = listOf(CONTROLLER, REQUEST_MAPPING, HTTP_EXCHANGE)

    /**
     * Attribute names holding the path, per meta-annotation, in precedence order.
     * `@RequestMapping` wins over `@HttpExchange` where a declaration carries both.
     */
    val PATH_SOURCES = listOf(
        REQUEST_MAPPING to listOf("path", "value"),
        HTTP_EXCHANGE to listOf("value", "url"),
    )

    /** Shorthand mapping annotations, and the verb each implies. */
    val SHORTHAND_VERBS = mapOf(
        "org.springframework.web.bind.annotation.GetMapping" to HttpVerb.GET,
        "org.springframework.web.bind.annotation.PostMapping" to HttpVerb.POST,
        "org.springframework.web.bind.annotation.PutMapping" to HttpVerb.PUT,
        "org.springframework.web.bind.annotation.DeleteMapping" to HttpVerb.DELETE,
        "org.springframework.web.bind.annotation.PatchMapping" to HttpVerb.PATCH,
        "org.springframework.web.service.annotation.GetExchange" to HttpVerb.GET,
        "org.springframework.web.service.annotation.PostExchange" to HttpVerb.POST,
        "org.springframework.web.service.annotation.PutExchange" to HttpVerb.PUT,
        "org.springframework.web.service.annotation.DeleteExchange" to HttpVerb.DELETE,
        "org.springframework.web.service.annotation.PatchExchange" to HttpVerb.PATCH,
    )

    private val SHORTHAND_BY_SIMPLE_NAME =
        SHORTHAND_VERBS.entries.associate { (fqn, verb) -> fqn.substringAfterLast('.') to verb }

    /** Accepts a qualified name or, when dependencies are unresolved, the name as written. */
    fun verbOf(name: String?): HttpVerb? {
        if (name == null) return null
        return SHORTHAND_VERBS[name] ?: SHORTHAND_BY_SIMPLE_NAME[name]
    }
}
