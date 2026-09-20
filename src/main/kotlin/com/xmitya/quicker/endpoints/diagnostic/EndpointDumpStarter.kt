package com.xmitya.quicker.endpoints.diagnostic

import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.application.ApplicationStarter
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.xmitya.quicker.endpoints.model.AnnotationClosure
import com.xmitya.quicker.endpoints.model.SpringAnnotations
import com.xmitya.quicker.endpoints.model.EndpointScanner
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Headless entry point: open a project, resolve every endpoint, print them, exit.
 *
 * Exists so correctness and cold-start cost are *measurable* rather than eyeballed in a GUI. Its
 * output is also the input to the coverage check against the in-repo `EndpointOwnerParser`, which
 * is the only ground truth available for this codebase.
 *
 *   runIde --args="quicker-dump <projectPath> [outFile]"
 */
class EndpointDumpStarter : ApplicationStarter {

    override val commandName: String get() = "quicker-dump"
    override val requiredModality: Int get() = ApplicationStarter.NOT_IN_EDT
    override val isHeadless: Boolean get() = true

    override fun main(args: List<String>) {
        val projectPath = args.getOrNull(1)
        if (projectPath == null) {
            System.err.println("usage: quicker-dump <projectPath> [outFile]")
            exitProcess(2)
        }
        val outFile = args.getOrNull(2)

        try {
            // A fresh sandbox has an empty JDK table, so every module ends up without an SDK:
            // java.lang.String does not resolve, Spring does not resolve, the annotation closure
            // comes back empty and the scan reports zero endpoints while looking perfectly healthy.
            // Register the JDK before opening, so the Gradle import can bind to it by name.
            val sdk = registerJdk()
            log("jdk: ${sdk?.name} -> ${sdk?.homePath}")

            log("opening $projectPath")
            val openedAt = System.currentTimeMillis()
            val project = ProjectUtil.openOrImport(Path.of(projectPath))
            if (project == null) {
                System.err.println("FAILED to open project at $projectPath")
                exitProcess(3)
            }
            log("opened in ${ms(openedAt)}")

            // The module graph loads asynchronously after openOrImport returns, and indexing has
            // not necessarily started yet -- so an immediate waitForSmartMode returns true against
            // an empty project and everything downstream silently finds nothing.
            val moduleStart = System.currentTimeMillis()
            var modules = 0
            while (System.currentTimeMillis() - moduleStart < MODULE_TIMEOUT_MS) {
                modules = ReadAction.compute<Int, RuntimeException> {
                    ModuleManager.getInstance(project).modules.size
                }
                if (modules > MIN_MODULES) break
                Thread.sleep(POLL_MS)
            }
            log("modules loaded: $modules (after ${ms(moduleStart)})")

            // Opening a Gradle project only *links* it; the import that resolves dependencies runs
            // asynchronously, if at all, in a headless IDE. Without it there are no libraries and
            // no module-to-module dependencies, so a controller cannot resolve the API interface it
            // implements and only self-contained ones yield endpoints. Force it and block.
            if (System.getProperty("quicker.sync", "true").toBoolean()) {
                val syncStart = System.currentTimeMillis()
                ExternalSystemUtil.refreshProject(
                    projectPath,
                    ImportSpecBuilder(project, ProjectSystemId("GRADLE"))
                        .use(ProgressExecutionMode.MODAL_SYNC),
                )
                val after = ReadAction.compute<Int, RuntimeException> {
                    ModuleManager.getInstance(project).modules.size
                }
                log("gradle sync finished in ${ms(syncStart)}; modules now $after")
            }

            // Registering the JDK in the table is not enough: the project and its modules still
            // point at no SDK, so nothing from java.lang resolves. Bind it explicitly.
            if (sdk != null) attachSdk(project, sdk)

            val indexStart = System.currentTimeMillis()
            // Indexing can start, finish, and restart as roots settle; require smart mode to hold.
            // Polls `isDumb` rather than calling the timed `waitForSmartMode(long)`, which is
            // marked @ApiStatus.Internal and is flagged by the Plugin Verifier.
            var stable = 0
            while (System.currentTimeMillis() - indexStart < INDEX_TIMEOUT_MS) {
                Thread.sleep(SETTLE_MS)
                stable = if (DumbService.isDumb(project)) 0 else stable + 1
                if (stable >= STABLE_ROUNDS) break
            }
            if (stable < STABLE_ROUNDS) {
                System.err.println("FAILED: indexing never settled")
                exitProcess(4)
            }
            log("indexed in ${ms(indexStart)}")

            diagnose(project, modules)

            val scanStart = System.currentTimeMillis()
            // Deliberately not inside a read action: the scanner takes its own, and each of them
            // waits for smart mode. Nested inside an outer read action that wait could never end,
            // because leaving dumb mode needs the write lock this thread would be holding shut.
            // The mapping below touches no PSI -- an EndpointInfo is plain data.
            val endpoints = EndpointScanner(project).scan().map {
                val i = it.info
                "${i.verb}\t${i.path}\t${i.controllerSimpleName}.${i.methodName}\t${i.kind}\t${i.moduleName}"
            }
            val scanMs = System.currentTimeMillis() - scanStart

            log("scanned in ${scanMs} ms -> ${endpoints.size} endpoints")
            val sorted = endpoints.sorted()
            if (outFile != null) {
                Path.of(outFile).toFile().writeText(sorted.joinToString("\n"))
                log("written to $outFile")
            }
            println("QUICKER_RESULT count=${endpoints.size} scanMs=$scanMs")
            sorted.take(PREVIEW).forEach { println("  $it") }
            exitProcess(0)
        } catch (t: Throwable) {
            t.printStackTrace()
            exitProcess(1)
        }
    }

    /**
     * Registers a JDK in the sandbox's table, named to match what the target project expects
     * (`mono`'s .idea/gradle.xml pins `gradleJvm = temurin-21`).
     */
    private fun registerJdk(): Sdk? {
        val home = System.getProperty("quicker.jdk")
            ?: System.getenv("JAVA_HOME")
            ?: return null
        val name = System.getProperty("quicker.jdk.name") ?: "temurin-21"
        return WriteAction.computeAndWait<Sdk?, RuntimeException> {
            val table = ProjectJdkTable.getInstance()
            table.findJdk(name)?.let { return@computeAndWait it }
            val jdk = JavaSdk.getInstance().createJdk(name, home, false)
            table.addJdk(jdk)
            jdk
        }
    }

    private fun attachSdk(project: com.intellij.openapi.project.Project, sdk: Sdk) {
        WriteAction.runAndWait<RuntimeException> {
            ProjectRootManager.getInstance(project).projectSdk = sdk
        }
        var fixed = 0
        for (module in ModuleManager.getInstance(project).modules) {
            val hasSdk = ReadAction.compute<Boolean, RuntimeException> {
                ModuleRootManager.getInstance(module).sdk != null
            }
            if (!hasSdk) {
                ModuleRootModificationUtil.setSdkInherited(module)
                fixed++
            }
        }
        log("sdk attached to project; modules switched to inherited: $fixed")
    }

    /** Without this, an empty result is indistinguishable from a correct one. */
    private fun diagnose(project: com.intellij.openapi.project.Project, modules: Int) {
        ReadAction.run<RuntimeException> {
            val roots = ProjectRootManager.getInstance(project).contentSourceRoots
            log("source roots: ${roots.size}")
            val facade = JavaPsiFacade.getInstance(project)
            val all = GlobalSearchScope.allScope(project)
            for (fqn in SpringAnnotations.SEEDS) {
                log("resolve $fqn -> ${facade.findClass(fqn, all) != null}")
            }
            log("resolve java.lang.String -> ${facade.findClass("java.lang.String", all) != null}")
            val projectLibs = LibraryTablesRegistrar.getInstance().getLibraryTable(project).libraries
            log("project libraries: ${projectLibs.size}")
            val springLib = projectLibs.firstOrNull { (it.name ?: "").contains("spring-web", ignoreCase = true) }
            log("spring-web library: ${springLib?.name} roots=${springLib?.getFiles(OrderRootType.CLASSES)?.size}")
            val firstModule = ModuleManager.getInstance(project).modules.firstOrNull { m ->
                ModuleRootManager.getInstance(m).contentRoots.isNotEmpty()
            }
            log("sample module: ${firstModule?.name} sdk=${firstModule?.let { ModuleRootManager.getInstance(it).sdk?.name }}")

            val closure = AnnotationClosure.build(project, all)
            log("annotation closure: ${closure.size} (resolved=${closure.resolved}) -> " +
                closure.annotations.mapNotNull { it.qualifiedName }.sorted().take(12))
            if (modules == 0) log("WARNING: no modules; the project was opened without its module graph")
        }
    }

    private fun log(message: String) = println("[quicker] $message")

    private fun ms(since: Long) = "${System.currentTimeMillis() - since} ms"

    private companion object {
        val INDEX_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(60)
        val MODULE_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(40)
        const val POLL_MS = 500L
        const val SETTLE_MS = 3000L
        const val STABLE_ROUNDS = 3
        // A Gradle import reports the root project long before the real module graph lands.
        const val MIN_MODULES = 5
        const val PREVIEW = 40
    }
}
