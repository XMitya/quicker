package com.xmitya.quicker.endpoints.model

import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.CommonClassNames
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.GlobalSearchScope
import com.xmitya.quicker.endpoints.match.EndpointInfo
import com.xmitya.quicker.endpoints.match.EndpointKind
import com.xmitya.quicker.endpoints.match.HttpVerb
import com.xmitya.quicker.endpoints.match.joinPath
import com.xmitya.quicker.endpoints.match.parseSegments

/** Builds the endpoint model from PSI, taking its own read actions -- see [scan]. */
class EndpointScanner(
    private val project: Project,
    /** Tests run on the EDT under the write-intent lock; see [ScanRead]. */
    private val reads: ScanRead = ScanRead.YIELD_TO_WRITES,
) {

    private val fileIndex = ProjectFileIndex.getInstance(project)
    private val scope: GlobalSearchScope = GlobalSearchScope.projectScope(project)
    private val pointers = SmartPointerManager.getInstance(project)

    /**
     * Resolves every endpoint in the project.
     *
     * Runs as a series of short read actions rather than one long one. Holding the read lock for
     * the whole scan blocks every write action behind it, and on a large repository that froze the
     * IDE for tens of seconds at startup: the EDT sat in `upgradeWritePermit` waiting for a lock
     * this scan would not release until it finished. Chunking lets writes through between batches,
     * so the worst any of them waits is one batch.
     *
     * Each read action yields to pending write actions rather than making them wait, so the EDT is
     * never blocked by the scan — see [ScanRead]. A cancelled chunk is simply retried.
     *
     * Call from a background thread *without* holding a read action.
     */
    fun scan(progress: ProgressIndicator? = null): List<Endpoint> {
        // Each of these takes its own short read actions internally.
        val closure = AnnotationClosure.build(project, GlobalSearchScope.allScope(project), reads)
        if (closure.size == 0) return emptyList()
        progress?.checkCanceled()
        val types = discoverTypes(closure)
        val resolver = MappingResolver(project, closure)
        val out = ArrayList<Endpoint>(4096)
        val emitted = HashSet<String>()

        for (batch in types.chunked(BATCH_SIZE)) {
            progress?.checkCanceled()
            val resolved = read {
                val batchOut = ArrayList<Endpoint>()
                for (pointer in batch) {
                    val type = pointer.element ?: continue // invalidated by an edit mid-scan
                    if (!type.isValid) continue
                    collectFrom(type, resolver, HashSet(), batchOut)
                }
                batchOut
            }
            for (endpoint in resolved) {
                val info = endpoint.info
                val key = info.controllerSimpleName + "|" + info.verb + "|" + info.path + "|" + info.methodName
                if (emitted.add(key)) out += endpoint
            }
        }
        return out
    }

    private fun collectFrom(
        type: PsiClass,
        resolver: MappingResolver,
        emitted: MutableSet<String>,
        out: MutableList<Endpoint>,
    ) {
        if (resolver.isFeignClient(type)) return // outbound calls, not endpoints
        val kind = if (resolver.isController(type)) EndpointKind.CONTROLLER else EndpointKind.ORPHAN
        val base = resolver.pathOf(type).orEmpty()

        for (method in type.allMethods) {
            ProgressManager.checkCanceled()
            if (method.isConstructor) continue
            if (method.hasModifierProperty(PsiModifier.STATIC)) continue
            if (method.containingClass?.qualifiedName == CommonClassNames.JAVA_LANG_OBJECT) continue

            val verb = resolver.verbOf(method) ?: continue
            val suffix = resolver.pathOf(method).orEmpty()
            val path = joinPath(base, suffix)

            // A controller and the interface it implements declare the same endpoint twice.
            val dedupe = "${type.qualifiedName}|$verb|$path|${method.name}"
            if (!emitted.add(dedupe)) continue

            out += build(type, method, verb, path, kind)
        }
    }

    private fun <T> read(block: () -> T): T = reads.compute(project, block)

    private fun pointerTo(type: PsiClass): SmartPsiElementPointer<PsiClass> =
        pointers.createSmartPsiElementPointer(type)

    /**
     * Types annotated with anything in the closure, restricted to hand-written sources.
     *
     * Two filters, and both earn their place. [ProjectFileIndex.isInSource] keeps out libraries and
     * generated output; requiring a `/src/` path segment keeps out everything else that lives
     * inside a content root -- notably the 662 `bin/` directories in the target monorepo holding
     * ~10,800 gitignored-but-on-disk copies of its sources, which would otherwise silently double
     * the endpoint set.
     */
    private fun discoverTypes(closure: AnnotationClosure): List<SmartPsiElementPointer<PsiClass>> {
        val seen = LinkedHashMap<String, SmartPsiElementPointer<PsiClass>>()
        // One path for both modes, over the word index. AnnotatedElementsSearch would be more
        // precise, but a single query for @Controller returns thousands of classes and takes
        // seconds -- too long for one read action either way: blocking it stalls the EDT, and
        // making it cancellable means it never survives to completion in a busy IDE. Chunks of
        // files are bounded by construction. Each read returns its finds and merging happens
        // outside, because a cancelled read is re-run and must not have half-applied its effects.
        for (chunk in filesMentioningMappings(closure).chunked(BATCH_SIZE)) {
            ProgressManager.checkCanceled()
            val found = read {
                chunk.flatMap { file -> classesIn(file).mapNotNull { accept(it, closure) } }
            }
            found.forEach { (fqn, pointer) -> seen.putIfAbsent(fqn, pointer) }
        }
        return seen.values.toList()
    }

    /** Null when the type is not a candidate. Must be called inside a read action. */
    private fun accept(
        type: PsiClass,
        closure: AnnotationClosure,
    ): Pair<String, SmartPsiElementPointer<PsiClass>>? {
        val fqn = type.qualifiedName ?: return null
        val vf = type.containingFile?.virtualFile ?: return null
        if (!fileIndex.isInSource(vf)) return null
        if (!isHandWrittenSource(vf)) return null
        if (type.mappingAnnotations(closure).isEmpty() &&
            type.methods.none { it.mappingAnnotations(closure).isNotEmpty() }
        ) return null
        return fqn to pointerTo(type)
    }

    /**
     * Files mentioning any mapping annotation by simple name. The word index is already built for
     * every project and costs nothing extra; it just yields more candidates, which [accept] then
     * filters against the closure.
     */
    private fun filesMentioningMappings(closure: AnnotationClosure): List<PsiFile> {
        val helper = PsiSearchHelper.getInstance(project)
        val files = LinkedHashSet<PsiFile>()
        for (name in closure.simpleNames()) {
            ProgressManager.checkCanceled()
            read { helper.processAllFilesWithWord(name, scope, { files += it; true }, true) }
        }
        return files.toList()
    }

    private fun isHandWrittenSource(file: VirtualFile): Boolean = isHandWrittenSourcePath(file.path)

    private fun build(
        type: PsiClass,
        method: PsiMethod,
        verb: HttpVerb,
        path: String,
        kind: EndpointKind,
    ): Endpoint {
        // Navigate to the declaration the user can actually see: prefer the implementation in the
        // controller over the interface declaration it inherits the mapping from.
        val target = type.findMethodsByName(method.name, false).firstOrNull() ?: method
        val anchor = target.nameIdentifier ?: target
        val vf = target.containingFile?.virtualFile
        val info = EndpointInfo(
            verb = verb,
            path = path,
            segments = parseSegments(path),
            controllerSimpleName = type.name.orEmpty(),
            methodName = method.name,
            kind = kind,
            unresolved = path.contains("\${"),
            inTestSource = vf != null && fileIndex.isInTestSourceContent(vf),
            moduleName = ModuleUtilCore.findModuleForPsiElement(type)?.name,
        )
        val locator = EndpointLocator(
            fileUrl = vf?.url.orEmpty(),
            offset = anchor.textOffset,
            classFqn = target.containingClass?.qualifiedName.orEmpty(),
            methodName = method.name,
            pointer = pointers.createSmartPsiElementPointer(anchor),
        )
        return Endpoint(info, locator)
    }


    private companion object {
        /**
         * A cancelled batch is redone from scratch, so smaller batches mean less rework when writes
         * are frequent — which they are during startup.
         */
        const val BATCH_SIZE = 25
    }
}

/**
 * True only for hand-written sources: under a `src/` directory and not inside a compiler or build
 * output directory.
 *
 * The output check is not redundant with the `src/` one. The target monorepo keeps 662 `bin/`
 * directories holding ~10,800 gitignored-but-on-disk copies of its sources, and a path like
 * `service/bin/main/src/...` would satisfy a naive `src/` substring test while being a duplicate.
 */
internal fun isHandWrittenSourcePath(path: String): Boolean {
    val segments = path.split('/')
    if (segments.none { it == "src" }) return false
    return segments.none { it in OUTPUT_DIRS }
}

private val OUTPUT_DIRS = setOf("bin", "build", "out", "target", ".gradle", "generated")
