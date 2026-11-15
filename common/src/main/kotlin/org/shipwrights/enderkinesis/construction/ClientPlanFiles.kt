package org.shipwrights.enderkinesis.construction

import com.mojang.logging.LogUtils
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag
import net.minecraft.world.level.storage.LevelResource
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.extension
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** A local plan read by section, as an untrusted upload would store it, and what the check found. Call [close] when done. */
class LocalPlanSections internal constructor(val sections: PlanSections, val issues: List<PlanIssue>, private val onClose: () -> Unit) {
    private val closed = AtomicBoolean()

    /** Lets the plan cache prune the converted file again. */
    fun close() {
        if (closed.compareAndSet(false, true)) onClose()
    }
}

/** A structure file the client can upload. [path] is only ever a file this listing found. */
data class LocalPlanFile(val name: String, val path: Path, val source: Source, val bytes: Long) {
    enum class Source { FOLDER, WORLD }

    /**
     * Worker thread. Converts the file once into a cached section file (streamed, never one tag tree) and reads
     * that by section. Null when the file cannot be read, when the server would refuse it, or on cancel.
     * The result holds no open file; it is safe to read from any thread.
     */
    @Suppress("TooGenericExceptionCaught") // A broken file must only fail this sketch; the data fixer throws RuntimeExceptions.
    fun openSections(cancelled: () -> Boolean): LocalPlanSections? = try {
        ClientPlanFiles.open(this, cancelled)
    } catch (_: CancellationException) {
        null
    } catch (e: IOException) {
        LOG.debug("Cannot read plan file {}", path, e)
        null
    } catch (e: RuntimeException) {
        LOG.debug("Cannot read plan file {}", path, e)
        null
    }

    private companion object {
        val LOG = LogUtils.getLogger()
    }
}

/** Client only: lists the .nbt files in `<game>/enderkinesis/plans` and, in singleplayer, the world's structure-block exports. */
object ClientPlanFiles {
    private val LOG = LogUtils.getLogger()
    private const val MAX_FILES = 256
    private const val MAX_CACHED = 32
    private const val HASH_BUFFER = 64 * 1024
    private const val SECTIONS = 1024
    private const val ISSUES = "Issues"
    private const val ISSUE_KEY = "Key"
    private const val ISSUE_ARGS = "Args"

    // generated/<namespace>/structures/<path...>.nbt
    private const val WORLD_DEPTH = 6

    private val cache = PlanSectionCache(SECTIONS)

    // Worker and client threads. Converted files in use, with a count of readers; prune keeps them.
    private val reading = ConcurrentHashMap<Path, Int>()

    // A file converted or opened this recently can be between its conversion and its first reader.
    private const val RECENT_MILLIS = 5 * 60 * 1000L

    fun folder(): Path {
        val folder = Minecraft.getInstance().gameDirectory.toPath().resolve("enderkinesis").resolve("plans")
        try {
            Files.createDirectories(folder)
        } catch (e: IOException) {
            LOG.warn("Cannot make the plans folder {}", folder, e)
        }
        return folder
    }

    private fun cacheFolder(): Path = Minecraft.getInstance().gameDirectory.toPath().resolve("enderkinesis").resolve("plan-cache")

    fun list(): List<LocalPlanFile> {
        val out = ArrayList<LocalPlanFile>()
        collect(folder(), 1, LocalPlanFile.Source.FOLDER, out)
        val server = Minecraft.getInstance().singleplayerServer
        if (server != null) {
            val generated = server.getWorldPath(LevelResource.GENERATED_DIR)
            if (Files.isDirectory(generated)) collect(generated, WORLD_DEPTH, LocalPlanFile.Source.WORLD, out)
        }
        return out.sortedBy { it.name.lowercase() }
    }

    internal fun open(file: LocalPlanFile, cancelled: () -> Boolean): LocalPlanSections? {
        val dir = cacheFolder()
        Files.createDirectories(dir)
        val hash = hash(file.path, cancelled)
        val target = dir.resolve("$hash.plan")
        val sidecar = dir.resolve("$hash.issues")
        reading.merge(target, 1, Int::plus)
        var opened = false
        try {
            return openConverted(file, dir, target, sidecar, cancelled)?.also { opened = true }
        } finally {
            if (!opened) release(target)
        }
    }

    private fun release(target: Path) {
        reading.computeIfPresent(target) { _, readers -> if (readers > 1) readers - 1 else null }
    }

    private fun openConverted(file: LocalPlanFile, dir: Path, target: Path, sidecar: Path, cancelled: () -> Boolean): LocalPlanSections? {
        val issues = if (Files.isRegularFile(target) && Files.isRegularFile(sidecar)) {
            Files.setLastModifiedTime(target, FileTime.fromMillis(System.currentTimeMillis()))
            readIssues(sidecar)
        } else {
            val check = convertPlanFile(file.path, target, dir.resolve("scratch"), Minecraft.getInstance().fixerUpper, trusted = false, cancelled)
            if (!check.accepted) return null
            writeIssues(sidecar, check.issues)
            prune(dir)
            check.issues
        }
        val sections = FilePlanSections(PlanSectionFile.open(target), cache) { LOG.warn("Cannot read a section of plan {}", target, it) }
        return LocalPlanSections(sections, issues) { release(target) }
    }

    private class Hashed(val size: Long, val modified: FileTime, val hash: CompletableFuture<String?>)

    // Client thread writes, workers run the hashing. Keyed by path; stale when the file's size or time changed.
    private val hashes = ConcurrentHashMap<Path, Hashed>()

    /** Client thread. The file's SHA-256, the plan hash the server stores. Null while a worker still hashes or when unreadable. */
    fun hashIfKnown(file: LocalPlanFile): String? {
        val (size, modified) = try {
            file.path.fileSize() to Files.getLastModifiedTime(file.path)
        } catch (_: IOException) {
            return null
        }
        val known = hashes.compute(file.path) { _, old ->
            if (old != null && old.size == size && old.modified == modified) old
            else Hashed(size, modified, CompletableFuture.supplyAsync({
                try { hash(file.path) { false } } catch (e: IOException) { LOG.debug("Cannot hash {}", file.path, e); null }
            }, Util.backgroundExecutor()))
        }!!
        return if (known.hash.isDone) known.hash.getNow(null) else null
    }

    internal fun hash(path: Path, cancelled: () -> Boolean): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(HASH_BUFFER)
            while (true) {
                if (cancelled()) throw CancellationException("plan read cancelled")
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return PlanUploadStream.hex(digest.digest())
    }

    private fun writeIssues(file: Path, issues: List<PlanIssue>) {
        val list = ListTag()
        for (issue in issues) {
            list.add(CompoundTag().also { tag ->
                tag.putString(ISSUE_KEY, issue.key)
                tag.put(ISSUE_ARGS, ListTag().also { args -> issue.args.forEach { args.add(StringTag.valueOf(it)) } })
            })
        }
        NbtIo.write(CompoundTag().also { it.put(ISSUES, list) }, file.toFile())
    }

    private fun readIssues(file: Path): List<PlanIssue> {
        val list = NbtIo.read(file.toFile())?.getList(ISSUES, Tag.TAG_COMPOUND.toInt()) ?: return emptyList()
        return List(list.size) { i ->
            val tag = list.getCompound(i)
            val args = tag.getList(ISSUE_ARGS, Tag.TAG_STRING.toInt())
            PlanIssue(tag.getString(ISSUE_KEY), List(args.size) { args.getString(it) })
        }
    }

    /** Keeps the [MAX_CACHED] most recently used converted plans. */
    private fun prune(dir: Path) {
        val plans = Files.list(dir).use { paths ->
            paths.filter { it.extension == "plan" }.limit(MAX_FILES.toLong()).toList()
        }
        if (plans.size <= MAX_CACHED) return
        val recent = System.currentTimeMillis() - RECENT_MILLIS
        for (old in plans.sortedByDescending { Files.getLastModifiedTime(it) }.drop(MAX_CACHED)) {
            if (reading.containsKey(old) || Files.getLastModifiedTime(old).toMillis() > recent) continue
            Files.deleteIfExists(old)
            Files.deleteIfExists(old.resolveSibling(old.name.removeSuffix(".plan") + ".issues"))
        }
    }

    private fun collect(root: Path, depth: Int, source: LocalPlanFile.Source, out: MutableList<LocalPlanFile>) {
        try {
            Files.walk(root, depth).use { paths ->
                val iterator = paths.iterator()
                while (iterator.hasNext() && out.size < MAX_FILES) {
                    val path = iterator.next()
                    if (path.extension != "nbt" || !path.isRegularFile(LinkOption.NOFOLLOW_LINKS)) continue
                    if (source == LocalPlanFile.Source.WORLD && !inStructures(root.relativize(path))) continue
                    val bytes = path.fileSize()
                    if (bytes > 0) out += LocalPlanFile(cleanPlanName(path.name), path, source, bytes)
                }
            }
        } catch (e: IOException) {
            LOG.warn("Cannot list plans in {}", root, e)
        } catch (e: UncheckedIOException) {
            // Files.walk throws this from the iterator when it cannot read a folder.
            LOG.warn("Cannot list plans in {}", root, e)
        }
    }

    private fun inStructures(relative: Path): Boolean = relative.nameCount > 2 && relative.getName(1).toString() == "structures"
}
