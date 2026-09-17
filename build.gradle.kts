import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    kotlin("jvm") version "2.1.21"
    id("org.jetbrains.intellij.platform")
}

group = "com.xmitya.quicker"
version = providers.gradleProperty("pluginVersion").get()

/** Claimed compatibility floor, shared by the plugin descriptor and the repository manifest. */
val pluginSinceBuild = "252"

kotlin {
    jvmToolchain(21) // 252/253/261 platform jars are Java 21 bytecode
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion"))

        // PSI-Java, UAST, MetaAnnotationUtil, AnnotatedElementsSearch
        bundledPlugin("com.intellij.java")
        // Compile-time only. At runtime Kotlin support arrives for free: the Kotlin plugin
        // registers FirKotlinUastLanguagePlugin and KotlinAnnotatedElementsSearcher, and we
        // reference no org.jetbrains.kotlin.* class, so there is nothing to declare optional.
        bundledPlugin("org.jetbrains.kotlin")

        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
    }
    testImplementation("junit:junit:4.13.2")
    // LightJavaCodeInsightFixtureTestCase's default project descriptor attaches this as a library
    // root; without it on the test classpath every fixture fails in setUp with "No roots for".
    testImplementation("org.jetbrains:annotations:24.0.0")
    testImplementation("org.assertj:assertj-core:3.27.3")
}

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = false

    pluginVerification {
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
            VerifyPluginTask.FailureLevel.NON_EXTENDABLE_API_USAGES,
        )
        ides {
            // Prefer the locally installed IDE when there is one -- that is the build this has to
            // run in, and it saves downloading a second copy. CI has no such install, so fall back
            // to fetching the range the plugin claims to support.
            val installed = file(System.getProperty("user.home") + "/Applications/IntelliJ IDEA.app")
            if (installed.isDirectory) {
                local(installed)
            } else {
                select {
                    types = listOf(IntelliJPlatformType.IntellijIdeaCommunity)
                    channels = listOf(ProductRelease.Channel.RELEASE)
                    sinceBuild = "252"
                    untilBuild = "252.*"
                }
            }
        }
    }

    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = pluginSinceBuild
            untilBuild = provider { null } // explicit: no upper bound
        }
    }
}

// Sandbox against the locally installed IU-262.10968.63, for testing against `mono`.
val runLocalIde by intellijPlatformTesting.runIde.registering {
    localPath = file(System.getProperty("user.home") + "/Applications/IntelliJ IDEA.app")
    sandboxDirectory = layout.buildDirectory.dir("sandbox-local")
    task {
        jvmArgs("-Xmx8g")
    }
}

/**
 * Opens the same IntelliJ Community build the headless harness uses, with the plugin loaded and a
 * project path pre-opened.
 *
 * Importing has to happen in *this* IDE rather than the locally installed Ultimate: a project
 * imported by 2026.2 writes a newer `.idea` format that this 2025.2.3 sandbox reads poorly
 * (observed: 0 source roots). Same build in, same build out.
 *
 *   ./gradlew runIdeOnProject -PtargetProject=/path/to/repo
 */
val runIdeOnProject by intellijPlatformTesting.runIde.registering {
    sandboxDirectory = layout.buildDirectory.dir("sandbox-dump") // share the harness sandbox
    task {
        val target = providers.gradleProperty("targetProject").orNull.orEmpty()
        if (target.isNotEmpty()) args = listOf(target)
        jvmArgs("-Xmx8g")
    }
}

/**
 * Headless correctness/perf harness: resolves every endpoint in a project and prints the count,
 * the scan time and the full list. Runs against the locally installed 2026.2 rather than the
 * downloaded 2025.2.3 compile target, so it opens a project whose `.idea` was written by that same
 * IDE -- avoiding a cross-version Gradle re-import -- and doubles as a forward-compatibility check
 * for a plugin built with since-build 252.
 *
 *   ./gradlew dumpEndpoints -PtargetProject=/path/to/repo -PoutFile=/tmp/endpoints.tsv
 */
val dumpEndpoints by intellijPlatformTesting.runIde.registering {
    // Runs on the downloaded Community build, i.e. the same platform this is compiled against.
    // Pointing at the locally installed Ultimate instead fails: the sandbox is configured from the
    // Community dependency, so every bundled Ultimate plugin is left demanding a
    // `com.intellij.modules.ultimate` that is not enabled.
    sandboxDirectory = layout.buildDirectory.dir("sandbox-dump")
    task {
        val target = providers.gradleProperty("targetProject").orNull.orEmpty()
        val out = providers.gradleProperty("outFile").orNull.orEmpty()
        args = listOfNotNull("quicker-dump", target.takeIf { it.isNotEmpty() }, out.takeIf { it.isNotEmpty() })
        jvmArgs(
            "-Xmx8g",
            "-Djava.awt.headless=true",
            "-Didea.log.console=true",
            "-Dquicker.jdk=" + (providers.gradleProperty("jdkHome").orNull
                ?: (System.getProperty("user.home") + "/.sdkman/candidates/java/21.0.2-tem")),
        )
    }
}

/**
 * Writes the `updatePlugins.xml` a custom plugin repository needs, so a team can receive updates
 * through the normal plugin update flow instead of passing a zip around.
 *
 * Name, vendor and description are copied out of plugin.xml rather than repeated here: the IDE
 * renders the repository listing from this manifest alone — it only downloads the zip on install —
 * so anything missing here shows up as a plugin with no title and no description.
 *
 *   ./gradlew generateUpdatePluginsXml -PpluginBaseUrl=https://host/path
 */
val generateUpdatePluginsXml by tasks.registering {
    val baseUrl = providers.gradleProperty("pluginBaseUrl")
    val pluginVersion = providers.gradleProperty("pluginVersion")
    val descriptor = layout.projectDirectory.file("src/main/resources/META-INF/plugin.xml")
    val output = layout.buildDirectory.file("distributions/updatePlugins.xml")
    val since = pluginSinceBuild
    inputs.file(descriptor)
    outputs.file(output)
    doLast {
        val url = baseUrl.orNull?.trimEnd('/')
            ?: error("Pass -PpluginBaseUrl=<url of the directory holding the zip>")
        val version = pluginVersion.get()

        val root = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(descriptor.asFile)
            .documentElement
        // Direct children only: `id` and `name` also occur deeper in the descriptor.
        fun field(tag: String): String {
            val children = root.childNodes
            for (i in 0 until children.length) {
                val node = children.item(i)
                if (node.nodeName == tag) return node.textContent.trim()
            }
            return ""
        }

        // Built line by line rather than from a raw string: the description is multi-line and
        // unindented, which makes trimIndent() see a common indent of zero and strip nothing.
        fun escape(text: String) = text
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        val file = output.get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            buildString {
                appendLine("<plugins>")
                appendLine(
                    "  <plugin id=\"${escape(field("id"))}\"" +
                        " url=\"${escape("$url/quicker-$version.zip")}\"" +
                        " version=\"${escape(version)}\">",
                )
                appendLine("    <name>${escape(field("name"))}</name>")
                appendLine("    <vendor>${escape(field("vendor"))}</vendor>")
                appendLine("    <idea-version since-build=\"$since\"/>")
                appendLine("    <description><![CDATA[")
                appendLine(field("description"))
                appendLine("    ]]></description>")
                appendLine("  </plugin>")
                appendLine("</plugins>")
            },
        )
        logger.lifecycle("Wrote " + file)
    }
}

tasks.test {
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
}
