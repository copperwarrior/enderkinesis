package org.shipwrights.enderkinesis.remnant

import java.math.BigInteger
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap
import net.minecraft.nbt.ByteArrayTag
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.world.level.saveddata.SavedData

/**
 * One particle of a Sselith Remnant, drawn as one cube of the cluster.
 *
 * Mutable and recycled in place: the simulation updates thousands of these
 * per tick and must not allocate per point per tick (see the hot-path rule
 * in CLAUDE.md).
 */
class IrreverantRemnantBlob(
    /** Stable identity across syncs. Without it a client can only see an
     *  unordered list of positions and must snap to it wholesale; with it
     *  each blob can be tracked, predicted, and smoothly corrected. Survives
     *  merges (the absorbing blob keeps its id). */
    @JvmField val id: Int,
    override var x: Double,
    override var y: Double,
    override var z: Double,
    override var vx: Double,
    override var vy: Double,
    override var vz: Double,
    override var radius: Double,
    /** Index into [IrreverantRemnantData]'s parallel table. Only players on this parallel see, feed, or are hurt by
     *  this particle, and particles on different parallels never interact. */
    @JvmField val parallel: Int,
) : RemnantBody {
    /** True while resting against geometry — drives the clinging drag and
     *  lets the mass crawl (including upward) instead of merely falling. */
    override var onSurface: Boolean = false
    override var seeping: Boolean = false
    override var airborneTicks: Int = 0
    override var restTicks: Int = 0
    override var reboundTicks: Int = 0

    /**
     * The particle this one hangs from.
     *
     * Persistent, and that is the whole point. Link topology was recomputed
     * from scratch every frame, so a drip's connection flicked between
     * neighbours as positions shifted by fractions of a block — visible as
     * tendrils snapping and swapping. A parent is chosen once and kept until
     * it is genuinely out of reach.
     *
     * It is also what the drip hangs from physically, not merely what gets
     * drawn: tension acts along this link, so a separating particle settles
     * directly beneath its parent the way gravity would actually take it.
     */
    @JvmField
    var parent: IrreverantRemnantBlob? = null

    /** Ticks this particle's link has been stretched past breaking, and a
     *  spell after a break during which it will not reattach. Transient. */
    @JvmField
    var stretchTicks: Int = 0

    @JvmField
    var parentCooldown: Int = 0

    /** Ticks since this particle began to seep out, up to [IrreverantRemnantNetwork.SPAWN_TICKS]; the client
     *  forms it on the face it seeped from over that time. Negative while it waits its turn. Starts finished,
     *  so a particle loaded from a save does not form again. Transient. */
    @JvmField
    var spawnAge: Int = IrreverantRemnantNetwork.SPAWN_TICKS

    /** Set when this particle is removed for having no player near, so a particle that hangs from it lets go.
     *  Transient. */
    @JvmField
    var evicted: Boolean = false

    /** Neighbours counted last pass — parents are chosen upward in density,
     *  so a drip links back to the body rather than to its fellow drips. */
    @JvmField
    var packedCount: Int = 0

    /** Volume this particle gains (or loses) from its neighbours this tick, applied after the force pass so
     *  the pass does not depend on visit order. Transient. */
    @JvmField
    var shareDelta: Double = 0.0

    /** Neighbours within the fixed share reach, counted last pass. Transient. */
    @JvmField
    var shareCount: Int = 0

    /** Route this particle's mass is following. A reference to one shared
     *  object, not a copy — transient, and never persisted. */
    @JvmField
    var path: RemnantPath? = null

    /** Unit normal of the surface this blob clings to (zero when airborne).
     *  Synced, because client prediction adheres along it. */
    override var normalX: Double = 0.0
    override var normalY: Double = 0.0
    override var normalZ: Double = 0.0

    /** Where this blob is currently trying to go, assigned by its cluster's
     *  goal (hunt or wander). Not persisted: goals re-roll on the first tick
     *  after load, and a stale saved target would be worse than none. */
    @JvmField
    var goalX: Double = Double.NaN

    @JvmField
    var goalY: Double = Double.NaN

    @JvmField
    var goalZ: Double = Double.NaN

    /** True when the goal is a player rather than a wander point — hunting
     *  masses push harder and climb. */
    @JvmField
    var hunting: Boolean = false

    /** True while this particle is a runner: one of its mass's rear particles,
     *  freed from cohesion and viscosity and racing for a point past the front. */
    @JvmField
    var running: Boolean = false

    /** Ticks spent as a runner, so one blocked by geometry gives up. */
    @JvmField
    var runTicks: Int = 0

    /** Multiplier on this particle's steering: boosted for a runner, eased for
     *  the rest of a mass that has runners, else 1. */
    @JvmField
    var steerScale: Double = 1.0

    val hasGoal: Boolean
        get() = !goalX.isNaN()

    /** Volume, the quantity conserved when two blobs merge. Radius is the
     *  rendered/queried property, so merges work in volume and convert back. */
    val volume: Double
        get() = radius * radius * radius
}

/**
 * Persistent state for every Sselith Remnant blob in the dimension.
 *
 * Flat list rather than chunk buckets (unlike [org.shipwrights.enderkinesis.dimension.SselithPlacementData]):
 * blobs are mobile, comparatively few, and every one of them is simulated
 * each tick regardless of position, so the per-chunk indexing that pays off
 * for a million static edits would just add rebuild churn here. The
 * simulation builds its own spatial hash per tick for merge queries.
 */
class IrreverantRemnantData : SavedData() {

    val blobs: MutableList<IrreverantRemnantBlob> = ArrayList()

    /** Interned parallels, so particles compare parallels as ints in the hot loops. Index 0 is parallel zero. */
    private val parallels = ArrayList<BigInteger>().apply { add(BigInteger.ZERO) }
    private val parallelIndex = Object2IntOpenHashMap<BigInteger>().apply {
        defaultReturnValue(-1)
        put(BigInteger.ZERO, 0)
    }

    /** The table index of [parallel], adding it when new. */
    fun internParallel(parallel: BigInteger): Int {
        val known = parallelIndex.getInt(parallel)
        if (known >= 0) return known
        parallels.add(parallel)
        parallelIndex.put(parallel, parallels.size - 1)
        return parallels.size - 1
    }

    /** The table index of [parallel], or -1 when no particle was ever on it. */
    fun parallelIndexOf(parallel: BigInteger): Int = parallelIndex.getInt(parallel)

    /** Monotonic id source. Persisted so ids stay unique across reloads and
     *  a client can't confuse an old blob with a recycled id. */
    private var nextId: Int = 1

    fun newBlob(
        x: Double, y: Double, z: Double,
        vx: Double, vy: Double, vz: Double,
        radius: Double,
        parallel: Int,
    ): IrreverantRemnantBlob = IrreverantRemnantBlob(nextId++, x, y, z, vx, vy, vz, radius, parallel)

    override fun save(tag: CompoundTag): CompoundTag {
        tag.putInt(KEY_NEXT_ID, nextId)
        val table = ListTag()
        for (parallel in parallels) table.add(ByteArrayTag(parallel.toByteArray()))
        tag.put(KEY_PARALLELS, table)
        val list = ListTag()
        for (blob in blobs) {
            val entry = CompoundTag()
            entry.putInt(KEY_ID, blob.id)
            entry.putDouble(KEY_X, blob.x)
            entry.putDouble(KEY_Y, blob.y)
            entry.putDouble(KEY_Z, blob.z)
            entry.putDouble(KEY_VX, blob.vx)
            entry.putDouble(KEY_VY, blob.vy)
            entry.putDouble(KEY_VZ, blob.vz)
            entry.putDouble(KEY_RADIUS, blob.radius)
            entry.putInt(KEY_PARALLEL, blob.parallel)
            list.add(entry)
        }
        tag.put(KEY_BLOBS, list)
        return tag
    }

    companion object {
        const val NAME = "enderkinesis_irreverant_remnant"

        private const val KEY_BLOBS = "blobs"
        private const val KEY_NEXT_ID = "nextId"
        private const val KEY_ID = "id"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        private const val KEY_Z = "z"
        private const val KEY_VX = "vx"
        private const val KEY_VY = "vy"
        private const val KEY_VZ = "vz"
        private const val KEY_RADIUS = "r"
        private const val KEY_PARALLEL = "p"
        private const val KEY_PARALLELS = "parallels"

        fun load(tag: CompoundTag): IrreverantRemnantData {
            val data = IrreverantRemnantData()
            val table = tag.getList(KEY_PARALLELS, Tag.TAG_BYTE_ARRAY.toInt())
            // Re-interned rather than trusted by position, so a saved table always maps onto a valid one.
            val remap = IntArray(table.size) { data.internParallel(BigInteger((table[it] as ByteArrayTag).asByteArray)) }
            val list = tag.getList(KEY_BLOBS, Tag.TAG_COMPOUND.toInt())
            var maxId = 0
            for (i in 0 until list.size) {
                val entry = list.getCompound(i)
                val id = entry.getInt(KEY_ID)
                if (id > maxId) maxId = id
                data.blobs.add(
                    IrreverantRemnantBlob(
                        id,
                        entry.getDouble(KEY_X), entry.getDouble(KEY_Y), entry.getDouble(KEY_Z),
                        entry.getDouble(KEY_VX), entry.getDouble(KEY_VY), entry.getDouble(KEY_VZ),
                        entry.getDouble(KEY_RADIUS),
                        // A save from before parallels holds no index, which reads as parallel zero.
                        remap.getOrElse(entry.getInt(KEY_PARALLEL)) { 0 },
                    ),
                )
            }
            // Guard against a truncated/edited save handing out a live id.
            data.nextId = maxOf(tag.getInt(KEY_NEXT_ID), maxId + 1)
            return data
        }
    }
}
