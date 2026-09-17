package com.xmitya.quicker.endpoints.model

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiVariable
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.ULiteralExpression
import org.jetbrains.uast.UPolyadicExpression
import org.jetbrains.uast.UResolvable
import org.jetbrains.uast.evaluateString
import org.jetbrains.uast.toUElementOfType
import com.xmitya.quicker.endpoints.match.HttpVerb

/**
 * Turns mapping annotations into a path string and an HTTP verb.
 *
 * The hard part is that ~91% of class-level paths in the target monorepo are constant references
 * rather than literals, spread across four shapes (qualified Java interface field, Kotlin companion
 * `const val` reached through a static import, Kotlin file-level `const val`, Java static import)
 * plus `+` concatenation. `UExpression.evaluateString()` folds all of them, in both languages,
 * which is the reason this resolves through UAST rather than raw PSI or the syntax tree.
 */
class MappingResolver(private val project: Project, private val closure: AnnotationClosure) {

    private val aliasCache = HashMap<String, Map<String, List<String>>>()
    private val constants = JavaPsiFacade.getInstance(project).constantEvaluationHelper

    /**
     * The path contributed by [owner], searching its own annotations first and only then its
     * hierarchy.
     *
     * Order matters and is deliberately Spring's, not the in-repo reference implementation's: a
     * merged annotation lookup returns the *nearest* declaration, so a `@RestController` class wins
     * over an interface it implements. The reference parser checks the interface first, which
     * happens to agree today but is backwards.
     */
    fun pathOf(owner: PsiModifierListOwner): String? {
        for ((metaFqn, attrs) in SpringAnnotations.PATH_SOURCES) {
            for (candidate in owner.withHierarchy()) {
                for (annotation in candidate.mappingAnnotations(closure)) {
                    if (metaFqn !in closure.rootsOf(annotation)) continue
                    readPath(annotation, attrs)?.let { return it }
                }
            }
        }
        return null
    }

    /** Null when [method] declares no mapping at all — i.e. it is not a handler. */
    fun verbOf(method: PsiMethod): HttpVerb? {
        for (candidate in method.withHierarchy()) {
            for (annotation in candidate.mappingAnnotations(closure)) {
                // In name mode `qualifiedName` is the name as written, so match on both.
                SpringAnnotations.verbOf(annotation.qualifiedName)?.let { return it }
                SpringAnnotations.verbOf(annotation.nameReferenceElement?.referenceName)?.let { return it }
                val roots = closure.rootsOf(annotation)
                if (SpringAnnotations.REQUEST_MAPPING in roots || SpringAnnotations.HTTP_EXCHANGE in roots) {
                    return readVerb(annotation) ?: HttpVerb.ANY
                }
            }
        }
        return null
    }

    /** True when the type is a controller rather than a plain mapping-bearing interface. */
    fun isController(type: PsiClass): Boolean =
        type.withHierarchy().any { candidate ->
            candidate.mappingAnnotations(closure).any { a ->
                SpringAnnotations.CONTROLLER in closure.rootsOf(a)
            }
        }

    fun isFeignClient(type: PsiClass): Boolean =
        type.withHierarchy().any { candidate ->
            candidate.modifierList?.annotations?.any {
                it.qualifiedName == SpringAnnotations.FEIGN_CLIENT ||
                    it.nameReferenceElement?.referenceName == FEIGN_SIMPLE_NAME
            } == true
        }

    // ---- attribute reading --------------------------------------------------------------------

    private fun readPath(annotation: PsiAnnotation, attrs: List<String>): String? {
        val u = annotation.toUElementOfType<UAnnotation>() ?: return null
        // 1. the attribute named as the meta-annotation names it
        for (a in attrs) evalPath(u.findDeclaredAttributeValue(a))?.let { return it }
        // 2. an @AliasFor-declared alias on a custom meta-annotated annotation
        for (alias in aliasesFor(annotation.nameReferenceElement?.resolve() as? PsiClass, attrs)) {
            evalPath(u.findDeclaredAttributeValue(alias))?.let { return it }
        }
        // 3. a path baked into the meta-annotation itself: @RequestMapping("/base") @interface X
        val declaring = annotation.nameReferenceElement?.resolve() as? PsiClass ?: return null
        for (meta in declaring.modifierList?.annotations.orEmpty()) {
            if (!closure.isMapping(meta)) continue
            if (meta.qualifiedName == annotation.qualifiedName) continue
            readPath(meta, attrs)?.let { return it }
        }
        return null
    }

    /**
     * Folds an annotation argument down to a path string.
     *
     * UAST's own `evaluate()`/`evaluateString()` return null for the two shapes that dominate this
     * codebase -- a reference to a constant (`Api.BASE_PATH`) and concatenation (`A + "/b"`) --
     * verified against the fixture. So evaluation is done explicitly: the Java constant evaluator
     * folds both natively, and the UAST walk below covers Kotlin, where `const val` and companion
     * constants surface as light fields whose constant value PSI can still compute.
     */
    private fun evalPath(expr: UExpression?): String? = evalPath(expr, 0)?.takeIf { it.isNotEmpty() }

    private fun evalPath(expr: UExpression?, depth: Int): String? {
        if (expr == null || depth > MAX_DEPTH) return null

        (expr.sourcePsi as? PsiExpression)?.let { psi ->
            (constants.computeConstantExpression(psi) as? String)?.let { return it }
        }

        return when (expr) {
            is ULiteralExpression -> expr.value as? String
            // Covers UBinaryExpression, i.e. `A + B + "/c"`.
            is UPolyadicExpression -> buildString {
                for (operand in expr.operands) append(evalPath(operand, depth + 1) ?: return null)
            }
            // Java `{"/a"}` / Kotlin `["/a"]`. No path in the target repo has two elements, but
            // Marketplace users will, so take the first rather than bailing out.
            is UCallExpression -> expr.valueArguments.firstNotNullOfOrNull { evalPath(it, depth + 1) }
            is UResolvable -> resolveConstant(expr, depth)
            else -> expr.evaluateString() ?: expr.evaluate() as? String
        }
    }

    /**
     * A reference to a constant, e.g. `Api.BASE_PATH` or a statically imported `SIGNATURE_PATH`.
     *
     * `computeConstantValue()` is tried first, but it needs the field's declared type to resolve --
     * in a module whose JDK or dependencies are not fully configured it returns null even for a
     * `final String` with a literal initializer. Since ~91% of base paths in the target codebase
     * are constant references, silently losing them there would gut the feature, so the initializer
     * is evaluated directly as a fallback.
     */
    private fun resolveConstant(expr: UResolvable, depth: Int): String? {
        val variable = expr.resolve() as? PsiVariable ?: return null
        (variable.computeConstantValue() as? String)?.let { return it }
        val initializer = variable.initializer?.toUElementOfType<UExpression>() ?: return null
        return evalPath(initializer, depth + 1)
    }

    private fun readVerb(annotation: PsiAnnotation): HttpVerb? {
        val u = annotation.toUElementOfType<UAnnotation>() ?: return null
        val raw = u.findDeclaredAttributeValue("method") ?: return null
        val candidates = if (raw is UCallExpression) raw.valueArguments else listOf(raw)
        for (c in candidates) {
            // RequestMethod.GET evaluates to the enum constant; HttpExchange.method is a String.
            val text = c.evaluate()?.toString() ?: c.sourcePsi?.text ?: continue
            val name = text.substringAfterLast('.').trim('"', ' ').uppercase()
            HttpVerb.entries.firstOrNull { it.name == name }?.let { return it }
        }
        return null
    }

    /**
     * `@AliasFor(annotation = RequestMapping.class, attribute = "path")` -> which local attributes
     * feed which target attribute. There is no platform utility for this; the Spring plugin's is
     * Ultimate-only.
     */
    private fun aliasesFor(annotationClass: PsiClass?, attrs: List<String>): List<String> {
        val fqn = annotationClass?.qualifiedName ?: return emptyList()
        val map = aliasCache.getOrPut(fqn) {
            val out = HashMap<String, MutableList<String>>()
            for (method in annotationClass.methods) {
                val alias = method.modifierList.annotations
                    .firstOrNull { it.qualifiedName == SpringAnnotations.ALIAS_FOR } ?: continue
                val target = (alias.findAttributeValue("attribute") as? com.intellij.psi.PsiLiteralExpression)
                    ?.value as? String
                    ?: (alias.findAttributeValue("value") as? com.intellij.psi.PsiLiteralExpression)?.value as? String
                    ?: continue
                out.getOrPut(target) { ArrayList() } += method.name
            }
            out
        }
        return attrs.flatMap { map[it].orEmpty() }
    }

    private companion object {
        const val MAX_DEPTH = 16
        val FEIGN_SIMPLE_NAME = SpringAnnotations.FEIGN_CLIENT.substringAfterLast('.')
    }
}

/** The declaration itself, then its hierarchy: superclasses and interfaces, nearest first. */
private fun PsiModifierListOwner.withHierarchy(): Sequence<PsiModifierListOwner> = when (this) {
    is PsiClass -> sequence {
        val seen = HashSet<String>()
        val queue = ArrayDeque<PsiClass>()
        queue += this@withHierarchy
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            val fqn = c.qualifiedName
            if (fqn != null && !seen.add(fqn)) continue
            yield(c)
            queue += c.supers.filter { it.qualifiedName != "java.lang.Object" }
        }
    }
    is PsiMethod -> sequenceOf(this) + findSuperMethods().asSequence()
    else -> sequenceOf(this)
}
