package com.xmitya.quicker.endpoints.model

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.util.MethodSignatureUtil
import com.xmitya.quicker.endpoints.match.HttpVerb
import com.xmitya.quicker.endpoints.match.joinPath
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.getUastParentOfType

/** One full path a handler method serves, and the controller that serves it. */
data class ResolvedPath(
    val verb: HttpVerb,
    val path: String,
    val controllerSimpleName: String,
)

/**
 * Assembles the full path of a single handler method straight from PSI — the reverse of search.
 *
 * Deliberately not a lookup in [EndpointModelService]: that model is served stale by design, and a
 * stale path on the clipboard is silently wrong. Resolution goes through the same [MappingResolver]
 * and [joinPath] as [EndpointScanner], so what is copied is what search matches. Only the annotation
 * closure differs: it is walked up from the declarations involved ([AnnotationClosure.around])
 * instead of down across the project, which is what makes this cheap enough to run on demand.
 *
 * Must be called inside a read action in smart mode.
 */
class EndpointPathResolver(private val project: Project) {

    private val fileIndex = ProjectFileIndex.getInstance(project)

    /**
     * The handler method enclosing [element] — anywhere in it, annotations and body included — or
     * null when there is none. Cheap enough for an action's `update()`: no search, only resolves.
     *
     * Through UAST so that a Kotlin `fun` arrives as its light [PsiMethod] without referencing any
     * Kotlin class.
     */
    fun handlerAt(element: PsiElement): PsiMethod? {
        val method = element.getUastParentOfType(UMethod::class.java, false)?.javaPsi ?: return null
        val owner = method.containingClass ?: return null
        val closure = AnnotationClosure.around(method.withHierarchy() + owner.withHierarchy())
        val resolver = MappingResolver(project, closure)
        // Outbound calls, not endpoints; the scanner leaves them out of search as well.
        if (resolver.isFeignClient(owner)) return null
        return method.takeIf { resolver.verbOf(it) != null }
    }

    /**
     * Every full path [method] is served at.
     *
     * A method on an API interface has no path of its own: the base usually sits on the controller
     * implementing it, possibly in another module, and several controllers may implement the same
     * interface under different bases. So the paths are those of each controller serving the
     * method. Only when nothing does — an interface with no implementation yet — is the declaring
     * type's own path reported, which is the scanner's ORPHAN case.
     */
    fun pathsOf(method: PsiMethod): List<ResolvedPath> {
        val owner = method.containingClass ?: return emptyList()
        val candidates = listOf(owner to method) + inheritorsOf(owner).mapNotNull { type ->
            MethodSignatureUtil.findMethodBySuperMethod(type, method, true)?.let { type to it }
        }
        val closure = AnnotationClosure.around(
            candidates.asSequence().flatMap { (type, impl) -> type.withHierarchy() + impl.withHierarchy() },
        )
        val resolver = MappingResolver(project, closure)

        val live = candidates.filterNot { (type, _) -> resolver.isFeignClient(type) }
        val served = live.filter { (type, _) -> resolver.isController(type) }
            .ifEmpty { live.filter { (type, _) -> type == owner } }

        return served.mapNotNull { (type, impl) ->
            val verb = resolver.verbOf(impl) ?: return@mapNotNull null
            val path = joinPath(resolver.pathOf(type).orEmpty(), resolver.pathOf(impl).orEmpty())
            ResolvedPath(verb, path, type.name.orEmpty())
        }.distinctBy { it.verb to it.path }
    }

    /**
     * Subclasses and implementations of [type] in hand-written sources, by the same filter the
     * scanner applies — without it, the gitignored `bin/` copies would report every path twice.
     */
    private fun inheritorsOf(type: PsiClass): List<PsiClass> =
        ClassInheritorsSearch.search(type, GlobalSearchScope.projectScope(project), true).findAll()
            .filter { inheritor ->
                val vf = inheritor.containingFile?.virtualFile
                vf != null && fileIndex.isInSource(vf) && isHandWrittenSourcePath(vf.path)
            }
}
