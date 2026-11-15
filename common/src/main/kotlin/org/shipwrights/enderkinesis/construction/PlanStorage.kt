package org.shipwrights.enderkinesis.construction

import com.mojang.logging.LogUtils
import dev.architectury.event.events.common.LifecycleEvent
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Checked plan section files under `<world>/data/enderkinesis/plans/<owner>/<sha256>.plan`, with a per-owner index. */
object PlanStorage {
    private val LOG = LogUtils.getLogger()
    private const val INDEX = "index.dat"
    private const val PLANS = "Plans"

    // Owner folders are UUIDs, so this name cannot meet one.
    private const val UPLOADS = ".uploads"
    private const val MAX_STALE_UPLOADS = 1024
    private const val MAX_INDEXES = 256

    sealed interface Store {
        val ref: PlanRef?
        data class Stored(override val ref: PlanRef) : Store
        data class Duplicate(override val ref: PlanRef) : Store
        data object Full : Store { override val ref: PlanRef? = null }
        data object Failed : Store { override val ref: PlanRef? = null }
    }

    // Server thread only.
    private val limits: PlanLimits get() = ServerBuildConfig.current.plans
    // A fetch can name any owner, so only indexes read from a file are kept, and only the most recent few.
    private val indexes = object : LinkedHashMap<UUID, List<PlanRef>>(MAX_INDEXES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UUID, List<PlanRef>>) = size > MAX_INDEXES
    }
    // Replaced at server start, after the config loads, to take its capacity.
    private var sectionCache = PlanSectionCache(BuildConfig.DEFAULT.plans.serverSectionCache)
    private val open = object : LinkedHashMap<PlanRef, FilePlanSections>(BuildConfig.DEFAULT.plans.openPlans, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PlanRef, FilePlanSections>): Boolean {
            if (size <= limits.openPlans) return false
            eldest.value.evict()
            return true
        }
    }

    fun init() {
        LifecycleEvent.SERVER_STARTING.register { sectionCache = PlanSectionCache(limits.serverSectionCache) }
        LifecycleEvent.SERVER_STARTED.register { server -> clearUploads(server) }
        LifecycleEvent.SERVER_STOPPED.register {
            indexes.clear()
            open.values.forEach(FilePlanSections::evict)
            open.clear()
            sectionCache.clear()
        }
    }

    fun root(server: MinecraftServer): Path =
        server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("enderkinesis").resolve("plans")

    /** Upload temp files and conversion scratch; cleared at server start. */
    fun uploadDir(server: MinecraftServer): Path = root(server).resolve(UPLOADS)

    fun list(server: MinecraftServer, owner: UUID): List<PlanRef> {
        indexes[owner]?.let { return it }
        return readIndex(root(server), owner).also { if (it.isNotEmpty()) indexes[owner] = it }
    }

    fun find(server: MinecraftServer, owner: UUID, hash: String): PlanRef? = list(server, owner).firstOrNull { it.hash == hash }

    /** The plan by section, or null when the owner has no such plan. Server thread; see [PlanSections]. */
    fun sections(server: MinecraftServer, ref: PlanRef): PlanSections? = openFile(server, ref)

    /** The file behind a plan, for the client section fetch. */
    fun file(server: MinecraftServer, ref: PlanRef): PlanSectionFile? = openFile(server, ref)?.file

    private fun openFile(server: MinecraftServer, ref: PlanRef): FilePlanSections? {
        // The stored ref is the key, so a ref with another display name still hits.
        val stored = find(server, ref.owner, ref.hash) ?: return null
        open[stored]?.let { return it }
        val path = planFile(root(server), stored.owner, stored.hash)
        val sections = try {
            FilePlanSections(PlanSectionFile.open(path, keepOpen = true), sectionCache) { LOG.error("Cannot read a section of plan {}", path, it) }
        } catch (e: IOException) {
            LOG.error("Cannot open plan {}", path, e)
            return null
        }
        open[stored] = sections
        return sections
    }

    /** Moves a converted section file into place. A plan with the same hash is kept as it is; a full folder refuses. */
    fun store(server: MinecraftServer, owner: UUID, hash: String, name: String, check: PlanCheck, converted: Path): Store {
        val size = checkNotNull(check.size) { "cannot store a refused plan" }
        val current = list(server, owner)
        current.firstOrNull { it.hash == hash }?.let { return Store.Duplicate(it) }
        if (current.size >= PlanLimits.QUOTA) return Store.Full
        val ref = PlanRef(owner, hash, name, size)
        val root = root(server)
        return try {
            val file = planFile(root, owner, hash)
            Files.createDirectories(file.parent)
            Files.move(converted, file, StandardCopyOption.REPLACE_EXISTING)
            writeIndex(root, owner, current + ref)
            Store.Stored(ref)
        } catch (e: IOException) {
            LOG.error("Cannot save plan {}/{}", owner, hash, e)
            Store.Failed
        }
    }

    fun delete(server: MinecraftServer, owner: UUID, hash: String): Boolean {
        val current = list(server, owner)
        val ref = current.firstOrNull { it.hash == hash } ?: return false
        val root = root(server)
        return try {
            writeIndex(root, owner, current - ref)
            open.remove(ref)?.evict()
            Files.deleteIfExists(planFile(root, owner, hash))
            true
        } catch (e: IOException) {
            LOG.error("Cannot delete plan {}/{}", owner, hash, e)
            false
        }
    }

    fun canStore(server: MinecraftServer, owner: UUID): Boolean = list(server, owner).size < PlanLimits.QUOTA

    private fun clearUploads(server: MinecraftServer) {
        val dir = uploadDir(server)
        if (!Files.isDirectory(dir)) return
        try {
            Files.walk(dir).use { paths ->
                paths.sorted(Comparator.reverseOrder()).limit(MAX_STALE_UPLOADS.toLong()).forEach { if (it != dir) Files.deleteIfExists(it) }
            }
        } catch (e: IOException) {
            LOG.warn("Cannot clear old plan uploads in {}", dir, e)
        } catch (e: java.io.UncheckedIOException) {
            LOG.warn("Cannot clear old plan uploads in {}", dir, e)
        }
    }

    private fun readIndex(root: Path, owner: UUID): List<PlanRef> {
        val file = root.resolve(owner.toString()).resolve(INDEX)
        if (!Files.isRegularFile(file)) return emptyList()
        return try {
            val plans = NbtIo.read(file.toFile())?.getList(PLANS, Tag.TAG_COMPOUND.toInt()) ?: return emptyList()
            (0 until minOf(plans.size, PlanLimits.QUOTA)).mapNotNull { i ->
                plans.getCompound(i).toPlanRef()?.takeIf { it.owner == owner }
            }
        } catch (e: IOException) {
            LOG.error("Cannot read plan index {}", file, e)
            emptyList()
        }
    }

    private fun writeIndex(root: Path, owner: UUID, plans: List<PlanRef>) {
        val dir = root.resolve(owner.toString())
        Files.createDirectories(dir)
        val list = ListTag()
        for (plan in plans) list.add(plan.toTag())
        NbtIo.write(CompoundTag().also { it.put(PLANS, list) }, dir.resolve(INDEX).toFile())
        indexes[owner] = plans
    }
}
