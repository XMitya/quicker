package com.xmitya.quicker.endpoints.model

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext

/**
 * The set of annotation types that mean "this declares an HTTP mapping", expanded transitively.
 *
 * Spring resolves annotations through meta-annotations: an annotation annotated with
 * `@RequestMapping` *is* a request mapping. Projects lean on this to define their own
 * (`@AutoGatewayController` in the target monorepo is 37% of the HTTP surface and never mentions
 * `@RestController`). Expanding the closure handles all of them without naming any.
 *
 * Two modes. **Resolved** is the good one, used whenever Spring is on the classpath. **Name** is the
 * fallback for a project whose dependencies have not been resolved -- an unsynced Gradle import, a
 * fresh clone, a failed download. There the Spring classes do not exist, every lookup returns null,
 * and a closure built only from resolved classes would come back empty and report zero endpoints
 * while looking perfectly healthy. Matching on simple names is less precise but keeps the feature
 * working, which matters because on a monorepo "not fully synced yet" is a common state.
 */
class AnnotationClosure private constructor(
    /** FQN *or* simple name -> the seed FQNs it is transitively meta-annotated with. */
    private val roots: Map<String, Set<String>>,
    /** Annotation classes to hand to [AnnotatedElementsSearch]; empty in name mode. */
    val annotations: List<PsiClass>,
    val resolved: Boolean,
) {
    val size: Int get() = roots.size

    fun isMapping(annotation: PsiAnnotation): Boolean = key(annotation) != null

    fun rootsOf(annotation: PsiAnnotation): Set<String> = key(annotation)?.let { roots[it] }.orEmpty()

    /** Prefers the resolved qualified name, falling back to the name as written. */
    private fun key(annotation: PsiAnnotation): String? {
        annotation.qualifiedName?.takeIf { roots.containsKey(it) }?.let { return it }
        return annotation.nameReferenceElement?.referenceName?.takeIf { roots.containsKey(it) }
    }

    /** Simple names to feed the word index when no annotation class can be resolved. */
    fun simpleNames(): Set<String> = roots.keys.mapTo(HashSet()) { it.substringAfterLast('.') }

    companion object {
        fun build(
            project: Project,
            scope: GlobalSearchScope,
            read: ScanRead = ScanRead.YIELD_TO_WRITES,
        ): AnnotationClosure = buildResolved(project, scope, read) ?: buildByName(project, scope, read)

        /**
         * Null when Spring itself cannot be resolved, i.e. the project has no dependencies yet.
         *
         * Takes one read action per annotation rather than one for the whole closure: each of these
         * searches scans the project, and holding the lock across all of them is what froze the IDE
         * at startup — every write action queued behind it until the scan finished.
         */
        private fun buildResolved(
            project: Project,
            scope: GlobalSearchScope,
            read: ScanRead,
        ): AnnotationClosure? {
            val classes = LinkedHashMap<String, PsiClass>()
            val roots = HashMap<String, MutableSet<String>>()
            val queue = ArrayDeque<Pair<String, PsiClass>>()

            // Every read action here is a pure lookup whose result is merged afterwards. A
            // write-prioritised read is cancelled and re-run, so a body that mutated shared state
            // would apply its effects twice against half-built collections.
            val seeds = read.compute(project) {
                val facade = JavaPsiFacade.getInstance(project)
                SpringAnnotations.SEEDS.mapNotNull { fqn ->
                    facade.findClass(fqn, scope)?.takeIf { it.isAnnotationType }?.let { fqn to it }
                }
            }
            if (seeds.isEmpty()) return null
            seeds.forEach { (fqn, cls) ->
                classes[fqn] = cls
                roots.getOrPut(fqn) { HashSet() } += fqn
                queue += fqn to cls
            }

            // Added to the closure but deliberately NOT walked: nothing meta-annotates @GetMapping
            // and friends, and searching the whole project for classes annotated with each of them
            // was ten full sweeps of pure waste.
            read.compute(project) {
                val facade = JavaPsiFacade.getInstance(project)
                SpringAnnotations.SHORTHAND_VERBS.keys.mapNotNull { fqn ->
                    facade.findClass(fqn, scope)?.let { fqn to it }
                }
            }.forEach { (fqn, cls) ->
                classes.putIfAbsent(fqn, cls)
                roots.getOrPut(fqn) { HashSet() } += SpringAnnotations.REQUEST_MAPPING
            }

            val helper = PsiSearchHelper.getInstance(project)
            while (queue.isNotEmpty()) {
                ProgressManager.checkCanceled()
                val (parentFqn, parent) = queue.removeFirst()
                val inherited = roots[parentFqn].orEmpty()
                // Via the word index rather than AnnotatedElementsSearch: the latter returns every
                // class annotated with @Controller -- thousands on a monorepo, seconds per query --
                // when all that is wanted here is annotation declarations. A read action that long
                // either blocks the EDT or, if made cancellable, never survives to completion in a
                // busy IDE.
                val children = annotationTypesReferencing(project, helper, parent, scope, read)
                for ((childFqn, child) in children) {
                    val grew = roots.getOrPut(childFqn) { HashSet() }.addAll(inherited)
                    val isNew = classes.put(childFqn, child) == null
                    if (isNew || grew) queue += childFqn to child
                }
            }
            return AnnotationClosure(roots, classes.values.toList(), resolved = true)
        }

        /**
         * Seeds with the simple names Spring's own annotations are written as, then walks the
         * project's own annotation declarations -- those are in source, so they resolve even when
         * the jars do not.
         */
        private fun buildByName(
            project: Project,
            scope: GlobalSearchScope,
            read: ScanRead,
        ): AnnotationClosure {
            val roots = HashMap<String, MutableSet<String>>()
            fun seed(name: String, root: String) {
                roots.getOrPut(name.substringAfterLast('.')) { HashSet() } += root
            }
            SpringAnnotations.SEEDS.forEach { seed(it, it) }
            seed("RestController", SpringAnnotations.CONTROLLER)
            SpringAnnotations.SHORTHAND_VERBS.keys.forEach { seed(it, SpringAnnotations.REQUEST_MAPPING) }
            SpringAnnotations.SHORTHAND_VERBS.keys
                .filter { it.contains(".service.annotation.") }
                .forEach { seed(it, SpringAnnotations.HTTP_EXCHANGE) }

            // Fixpoint over project-declared annotations: @AutoGatewayController is annotated
            // @RestController, so it inherits that root and becomes a mapping annotation itself.
            val helper = PsiSearchHelper.getInstance(project)
            repeat(MAX_ROUNDS) {
                var grew = false
                for (name in roots.keys.toList()) {
                    ProgressManager.checkCanceled()
                    val snapshot = roots.mapValues { it.value.toSet() }
                    val discovered = read.compute(project) {
                        annotationTypesMentioning(helper, name, scope).mapNotNull { declaration ->
                            val simple = declaration.name ?: return@mapNotNull null
                            val inherited = declaration.modifierList?.annotations.orEmpty()
                                .mapNotNull { it.nameReferenceElement?.referenceName }
                                .flatMap { snapshot[it].orEmpty() }
                                .toSet()
                            if (inherited.isEmpty()) null else simple to inherited
                        }
                    }
                    for ((simple, inherited) in discovered) {
                        if (roots.getOrPut(simple) { HashSet() }.addAll(inherited)) grew = true
                    }
                }
                if (!grew) return@repeat
            }
            return AnnotationClosure(roots, emptyList(), resolved = false)
        }

        /** Annotation declarations in files that mention [name] at all. */
        private fun annotationTypesMentioning(
            helper: PsiSearchHelper,
            name: String,
            scope: GlobalSearchScope,
        ): List<PsiClass> {
            val files = ArrayList<PsiFile>()
            helper.processAllFilesWithWord(name, scope, { files += it; true }, true)
            return files.flatMap { file -> classesIn(file).filter { it.isAnnotationType } }
        }

        /**
         * Annotation types annotated with [parent], found through the word index and processed in
         * bounded chunks so no single read action runs long.
         */
        private fun annotationTypesReferencing(
            project: Project,
            helper: PsiSearchHelper,
            parent: PsiClass,
            scope: GlobalSearchScope,
            read: ScanRead,
        ): List<Pair<String, PsiClass>> {
            val simpleName = read.compute(project) { parent.name } ?: return emptyList()
            val files = read.compute(project) {
                ArrayList<PsiFile>().also { out ->
                    helper.processAllFilesWithWord(simpleName, scope, { out += it; true }, true)
                }
            }
            val out = ArrayList<Pair<String, PsiClass>>()
            for (chunk in files.chunked(FILE_CHUNK)) {
                ProgressManager.checkCanceled()
                out += read.compute(project) {
                    chunk.flatMap { file ->
                        classesIn(file)
                            .filter { it.isAnnotationType }
                            .filter { cls ->
                                cls.modifierList?.annotations.orEmpty().any { a ->
                                    a.nameReferenceElement?.referenceName == simpleName
                                }
                            }
                            .mapNotNull { cls -> cls.qualifiedName?.let { it to cls } }
                    }
                }
            }
            return out
        }

        private const val FILE_CHUNK = 50

        private const val MAX_ROUNDS = 5
    }
}

/** Annotations declared directly on [owner] that belong to the closure. */
fun PsiModifierListOwner.mappingAnnotations(closure: AnnotationClosure): List<PsiAnnotation> =
    modifierList?.annotations?.filter { closure.isMapping(it) }.orEmpty()
