package com.xmitya.quicker.endpoints.model

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.getProjectCachePath
import com.xmitya.quicker.endpoints.match.EndpointInfo
import com.xmitya.quicker.endpoints.match.EndpointKind
import com.xmitya.quicker.endpoints.match.HttpVerb
import com.xmitya.quicker.endpoints.match.parseSegments
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists

/**
 * Persists the resolved model so search is usable the moment a project opens, instead of after a
 * scan that takes seconds on a large repository.
 *
 * What is stored is deliberately *not* treated as truth. Nothing can detect edits made while the
 * IDE was closed, so a restored model is served only as an instant placeholder while a real scan
 * runs, and is replaced as soon as that finishes. Navigation re-resolves by name for the same
 * reason — see [EndpointLocator].
 */
object EndpointCache {

    private const val MAGIC = 0x51434B52 // "QCKR"
    private const val VERSION = 1

    fun load(project: Project): List<Endpoint>? = loadFrom(cacheFile(project))

    fun save(project: Project, endpoints: List<Endpoint>) = saveTo(cacheFile(project), endpoints)

    /** Split from the project-aware entry points so the format can be round-tripped in a test. */
    fun loadFrom(file: Path): List<Endpoint>? {
        if (!Files.exists(file)) return null
        return try {
            DataInputStream(Files.newInputStream(file).buffered()).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) return null
                val count = input.readInt()
                if (count < 0 || count > MAX_ENTRIES) return null
                ArrayList<Endpoint>(count).apply {
                    repeat(count) { add(readEndpoint(input)) }
                }
            }
        } catch (e: Exception) {
            // A truncated or half-written cache must never break startup; just rebuild.
            thisLogger().info("Discarding unreadable endpoint cache", e)
            runCatching { file.deleteIfExists() }
            null
        }
    }

    fun saveTo(file: Path, endpoints: List<Endpoint>) {
        try {
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling("${file.fileName}.tmp")
            DataOutputStream(Files.newOutputStream(tmp).buffered()).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeInt(endpoints.size)
                endpoints.forEach { writeEndpoint(out, it) }
            }
            // Written aside and moved, so an interrupted save cannot leave a corrupt cache behind.
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            thisLogger().info("Could not write endpoint cache", e)
        }
    }

    private fun writeEndpoint(out: DataOutputStream, endpoint: Endpoint) {
        val info = endpoint.info
        val at = endpoint.location
        out.writeUTF(info.verb.name)
        out.writeUTF(info.path)
        out.writeUTF(info.controllerSimpleName)
        out.writeUTF(info.methodName)
        out.writeUTF(info.moduleName.orEmpty())
        out.writeUTF(at.classFqn)
        out.writeUTF(at.fileUrl)
        out.writeInt(at.offset)
        out.writeByte(
            (if (info.kind == EndpointKind.ORPHAN) 1 else 0) or
                (if (info.unresolved) 2 else 0) or
                (if (info.inTestSource) 4 else 0),
        )
    }

    private fun readEndpoint(input: DataInputStream): Endpoint {
        val verb = HttpVerb.valueOf(input.readUTF())
        val path = input.readUTF()
        val controller = input.readUTF()
        val method = input.readUTF()
        val module = input.readUTF()
        val classFqn = input.readUTF()
        val fileUrl = input.readUTF()
        val offset = input.readInt()
        val flags = input.readByte().toInt()
        val info = EndpointInfo(
            verb = verb,
            path = path,
            segments = parseSegments(path),
            controllerSimpleName = controller,
            methodName = method,
            kind = if (flags and 1 != 0) EndpointKind.ORPHAN else EndpointKind.CONTROLLER,
            unresolved = flags and 2 != 0,
            inTestSource = flags and 4 != 0,
            moduleName = module.ifEmpty { null },
        )
        return Endpoint(info, EndpointLocator(fileUrl, offset, classFqn, method))
    }

    private fun cacheFile(project: Project): Path =
        project.getProjectCachePath("quicker-endpoints").resolve("model.bin")

    private const val MAX_ENTRIES = 500_000
}
