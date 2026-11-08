package org.shipwrights.enderkinesis.block

import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.TickEvent
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import org.joml.Quaterniond
import org.joml.Quaterniondc
import org.joml.Vector3d
import org.joml.primitives.AABBd
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.core.internal.joints.VSD6Joint
import org.valkyrienskies.core.internal.joints.VSJointAndId
import org.valkyrienskies.core.internal.joints.VSJointPose
import org.valkyrienskies.core.internal.joints.VSPrismaticJoint
import org.valkyrienskies.core.internal.world.VsiPhysLevel
import org.valkyrienskies.mod.api.vsApi
import org.valkyrienskies.mod.common.ValkyrienSkiesMod
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Predicate
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.util.PhysTickOnly
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.core.api.util.UnavailableInKrunch

/**
 * Pins a body to each guardian spike that runs into one of its blocks: a world spike into a ship, a ship's
 * spike into the world, or a ship's spike into another ship. The target slides deeper along the spike against
 * drag, resists a pull off, and tears free under a load scaled to the bodies' mass.
 * A torn spike does not pin that target again until the target pulls off it.
 */
object GuardianSpikeImpaler {

    /** Body id of the world in keys and impales; joints take null for it. */
    private const val WORLD = -1L

    private class Impale(
        var lastSeen: Long,
        val spikeBody: Long,
        val targetBody: Long,
        /** Weld point in the spike body's frame. */
        val spikeLocal: Vector3d,
        /** Unit spike axis, base to tip, in the spike body's frame. */
        val direction: Vector3d,
        /** Slide offset of the spike base: the deepest the target can go. */
        val floor: Double,
        val stiffness: Float,
        val damping: Float,
        val compliance: Double,
    ) {
        /** [PENDING], [CANCELLED], [TORN], or the live joint id. Only the physics thread leaves PENDING with a joint. */
        val state = AtomicInteger(PENDING)
        /** Written by the physics thread before [state] gets the id. */
        var joint: VSPrismaticJoint? = null
        /** Weld point in the target body's frame. Written by the physics thread before [state] gets the id. */
        val targetLocal = Vector3d()
        /** Slide offset the limit spring holds now. It only moves toward [floor]. */
        var centre: Double = 0.0
    }

    private class Hit(val spikeLocal: Vector3d, val direction: Vec3, val depth: Double)

    private data class Key(val spikeBody: Long, val spike: Long, val targetBody: Long)

    /** Server thread only. Joints are not serialized, so a reload starts empty and re-welds what still touches. */
    private val impales = HashMap<ResourceKey<Level>, HashMap<Key, Impale>>()

    /** Welds that wait for the physics thread, by VS dimension id. */
    private val pending = ConcurrentHashMap<String, ConcurrentLinkedQueue<Impale>>()

    private val scratchPoint = Vector3d()
    private val scratchWorld = Vector3d()
    private val scratchTarget = Vector3d()
    private val scratchAxis = Vector3d()
    private val scratchBox = AABBd()
    private val shipCursor = BlockPos.MutableBlockPos()
    private val spikeCursor = BlockPos.MutableBlockPos()
    private val IS_SPIKE = Predicate<BlockState> { it.block is GuardianSpikeBase }
    private val IDENTITY: Quaterniondc = Quaterniond()

    // Subscribes on the game thread; the physTickEvent callback runs on the physics thread.
    @OptIn(PhysTickOnly::class, VsBeta::class)
    fun init() {
        TickEvent.SERVER_LEVEL_POST.register(::tick)
        LifecycleEvent.SERVER_STOPPED.register {
            impales.clear()
            pending.clear()
        }
        vsApi.physTickEvent.on { event, _ ->
            val physLevel = event.world as? VsiPhysLevel ?: return@on
            val queue = pending[physLevel.dimension] ?: return@on
            while (true) attach(physLevel, queue.poll() ?: break)
        }
    }

    @OptIn(GameTickOnly::class)
    private fun tick(level: ServerLevel) {
        if (level.gameTime % SCAN_INTERVAL != 0L) return
        val dimId = level.dimensionId
        val now = level.gameTime
        val map = impales.getOrPut(level.dimension()) { HashMap() }
        for (ship in level.shipObjectWorld.loadedShips) {
            if (ship.chunkClaimDimension != dimId) continue
            scanWorldSpikes(level, ship, map, now)
            scanShipSpikes(level, ship, map, now, dimId)
        }
        sweep(level, map, now)
    }

    /** World spikes inside [ship]'s world AABB, against [ship]. */
    @OptIn(GameTickOnly::class)
    private fun scanWorldSpikes(level: ServerLevel, ship: LoadedServerShip, map: HashMap<Key, Impale>, now: Long) {
        var welds = 0
        val box = ship.worldAABB
        forEachSpike(level, box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()) { x, y, z, state ->
            val hit = impaledPoint(level, null, x, y, z, state, ship)
            if (hit != null) consider(level, map, Key(WORLD, BlockPos.asLong(x, y, z), ship.id), hit, null, ship, now, ++welds)
        }
    }

    /** Spikes built on [ship], against the world and every other ship they reach. */
    @OptIn(GameTickOnly::class)
    private fun scanShipSpikes(level: ServerLevel, ship: LoadedServerShip, map: HashMap<Key, Impale>, now: Long, dimId: String) {
        val box = ship.shipAABB ?: return
        var welds = 0
        val ships = level.shipObjectWorld.loadedShips
        forEachSpike(
            level, box.minX().toDouble(), box.minY().toDouble(), box.minZ().toDouble(),
            box.maxX().toDouble(), box.maxY().toDouble(), box.maxZ().toDouble(),
        ) { x, y, z, state ->
            val pos = BlockPos.asLong(x, y, z)
            val worldHit = impaledPoint(level, ship, x, y, z, state, null)
            if (worldHit != null) consider(level, map, Key(ship.id, pos, WORLD), worldHit, ship, null, now, ++welds)
            ship.transform.shipToWorld.transformPosition(scratchWorld.set(x + 0.5, y + 0.5, z + 0.5))
            scratchBox.setMin(scratchWorld.x - 1, scratchWorld.y - 1, scratchWorld.z - 1)
                .setMax(scratchWorld.x + 1, scratchWorld.y + 1, scratchWorld.z + 1)
            for (other in ships.getIntersecting(scratchBox, dimId)) {
                if (other.id == ship.id) continue
                val hit = impaledPoint(level, ship, x, y, z, state, other) ?: continue
                consider(level, map, Key(ship.id, pos, other.id), hit, ship, other, now, ++welds)
            }
        }
    }

    /** Calls [action] for each spike cell in the block box, in whichever frame the box is given. */
    @Suppress("NestedBlockDepth") // Chunk → section → cell walk; splitting it only moves the bounds around.
    private inline fun forEachSpike(
        level: ServerLevel, x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double,
        action: (Int, Int, Int, BlockState) -> Unit,
    ) {
        val minX = Math.floor(x0).toInt(); val maxX = Math.floor(x1).toInt()
        val minY = Math.max(Math.floor(y0).toInt(), level.minBuildHeight)
        val maxY = Math.min(Math.floor(y1).toInt(), level.maxBuildHeight - 1)
        val minZ = Math.floor(z0).toInt(); val maxZ = Math.floor(z1).toInt()
        if (minY > maxY) return
        var budget = MAX_SECTIONS_PER_SCAN
        for (cx in (minX shr 4)..(maxX shr 4)) for (cz in (minZ shr 4)..(maxZ shr 4)) {
            val chunk = level.chunkSource.getChunkNow(cx, cz) ?: continue
            for (sy in (minY shr 4)..(maxY shr 4)) {
                if (--budget < 0) return
                val section = chunk.getSection(chunk.getSectionIndexFromSectionY(sy))
                if (section.hasOnlyAir() || !section.maybeHas(IS_SPIKE)) continue
                for (x in Math.max(minX, cx shl 4)..Math.min(maxX, (cx shl 4) + 15))
                    for (y in Math.max(minY, sy shl 4)..Math.min(maxY, (sy shl 4) + 15))
                        for (z in Math.max(minZ, cz shl 4)..Math.min(maxZ, (cz shl 4) + 15)) {
                            val state = section.getBlockState(x and 15, y and 15, z and 15)
                            if (state.block is GuardianSpikeBase) action(x, y, z, state)
                        }
            }
        }
    }

    @OptIn(GameTickOnly::class)
    private fun consider(
        level: ServerLevel, map: HashMap<Key, Impale>, key: Key, hit: Hit,
        spikeShip: LoadedServerShip?, targetShip: LoadedServerShip?, now: Long, count: Int,
    ) {
        val known = map[key]
        if (known != null) {
            known.lastSeen = now
            track(level, known, spikeShip, targetShip)
            return
        }
        if (count > MAX_WELDS_PER_SCAN) return
        val mass = reducedMass(spikeShip, targetShip) ?: return
        val impale = newImpale(key, hit, mass, now)
        map[key] = impale
        pending.computeIfAbsent(level.dimensionId) { ConcurrentLinkedQueue() }.add(impale)
    }

    /**
     * The first point on a spike that lies inside a solid block of [target] (null: the world), or null.
     * [spikeShip] is the body the spike is built on (null: the world); the spike cell is in its frame.
     */
    private fun impaledPoint(
        level: ServerLevel, spikeShip: Ship?, x: Int, y: Int, z: Int, state: BlockState, target: Ship?,
    ): Hit? {
        val block = state.block as GuardianSpikeBase
        for (spike in block.placed(state, spikeCursor.set(x, y, z))) for (fraction in SAMPLES) {
            val local = spike.attach.along(fraction).add(spike.shift)
            scratchPoint.set(x + local.x, y + local.y, z + local.z)
            spikeShip?.transform?.shipToWorld?.transformPosition(scratchPoint)
            target?.transform?.worldToShip?.transformPosition(scratchPoint)
            shipCursor.set(Math.floor(scratchPoint.x), Math.floor(scratchPoint.y), Math.floor(scratchPoint.z))
            if (level.chunkSource.getChunkNow(shipCursor.x shr 4, shipCursor.z shr 4) == null) continue
            val targetState = level.getBlockState(shipCursor)
            if (targetState.isAir) continue
            val lx = scratchPoint.x - shipCursor.x
            val ly = scratchPoint.y - shipCursor.y
            val lz = scratchPoint.z - shipCursor.z
            if (targetState.getCollisionShape(level, shipCursor).toAabbs().any { it.contains(lx, ly, lz) }) {
                return Hit(Vector3d(x + local.x, y + local.y, z + local.z), spike.attach.direction, SpikeAttachment.LENGTH * fraction)
            }
        }
        return null
    }

    /** Mass for the relative motion of the two bodies; the world and static ships do not move. Null if neither moves. */
    @OptIn(GameTickOnly::class)
    private fun reducedMass(a: LoadedServerShip?, b: LoadedServerShip?): Double? {
        val ma = a?.takeUnless { it.isStatic }?.let { Math.max(it.inertiaData.mass, MIN_MASS) }
        val mb = b?.takeUnless { it.isStatic }?.let { Math.max(it.inertiaData.mass, MIN_MASS) }
        return when {
            ma == null -> mb
            mb == null -> ma
            else -> ma * mb / (ma + mb)
        }
    }

    private fun newImpale(key: Key, hit: Hit, mass: Double, now: Long): Impale {
        val weight = mass * GRAVITY
        return Impale(
            lastSeen = now,
            spikeBody = key.spikeBody,
            targetBody = key.targetBody,
            spikeLocal = hit.spikeLocal,
            direction = Vector3d(hit.direction.x, hit.direction.y, hit.direction.z),
            floor = -hit.depth,
            stiffness = (weight / PULL_SAG).toFloat(),
            damping = (mass * DRAG_RATE).toFloat(),
            compliance = SAG / weight,
        )
    }

    /**
     * Physics thread. Poses the joint from the live transforms: a pose taken on the server thread is a tick or
     * more old, and the joint snaps the bodies back to it. Crossed spikes then fight and throw the ship.
     */
    @OptIn(PhysTickOnly::class, VsBeta::class, UnavailableInKrunch::class)
    private fun attach(physLevel: VsiPhysLevel, impale: Impale) {
        if (impale.state.get() != PENDING) return
        val spikeShip = if (impale.spikeBody == WORLD) null else physLevel.getShipById(impale.spikeBody)
        val targetShip = if (impale.targetBody == WORLD) null else physLevel.getShipById(impale.targetBody)
        if ((impale.spikeBody != WORLD && spikeShip == null) || (impale.targetBody != WORLD && targetShip == null)) {
            impale.state.compareAndSet(PENDING, TORN)
            return
        }
        impale.targetLocal.set(impale.spikeLocal)
        spikeShip?.transform?.shipToWorld?.transformPosition(impale.targetLocal)
        targetShip?.transform?.worldToShip?.transformPosition(impale.targetLocal)
        val frame = Quaterniond().rotationTo(LOCAL_X, impale.direction)
        // target⁻¹ · spike · frame, so both joint frames line up at the live pose.
        val targetFrame = Quaterniond(targetShip?.transform?.shipToWorldRotation ?: IDENTITY).conjugate()
            .mul(spikeShip?.transform?.shipToWorldRotation ?: IDENTITY).mul(frame)
        val joint = VSPrismaticJoint(
            shipId0 = spikeShip?.id,
            pose0 = VSJointPose(Vector3d(impale.spikeLocal), frame),
            shipId1 = targetShip?.id,
            pose1 = VSJointPose(Vector3d(impale.targetLocal), targetFrame),
            // Krunch tears silently and VS never hears of it; a later remove of that id kills the physics thread.
            maxForceTorque = null,
            compliance = impale.compliance,
            linearLimitPair = impale.limit(),
        )
        impale.joint = joint
        val id = physLevel.addJoint(joint)
        if (!impale.state.compareAndSet(PENDING, id)) physLevel.removeJoint(id)
    }

    /**
     * Moves the limit spring after a target that went deeper, so a push meets drag and a pull meets the spring.
     * Tears the weld when the stretch shows a load over [HOLD_G] weights: stiffness and compliance scale with mass.
     */
    @OptIn(VsBeta::class, UnavailableInKrunch::class)
    private fun track(level: ServerLevel, impale: Impale, spikeShip: Ship?, targetShip: Ship?) {
        val id = impale.state.get()
        if (id < 0) return
        scratchWorld.set(impale.spikeLocal)
        spikeShip?.transform?.shipToWorld?.transformPosition(scratchWorld)
        scratchTarget.set(impale.targetLocal)
        targetShip?.transform?.shipToWorld?.transformPosition(scratchTarget)
        scratchAxis.set(impale.direction)
        spikeShip?.transform?.shipToWorldRotation?.transform(scratchAxis)
        val delta = scratchTarget.sub(scratchWorld)
        val offset = delta.dot(scratchAxis)
        val sideways = delta.fma(-offset, scratchAxis).length()
        val gtpa = ValkyrienSkiesMod.getOrCreateGTPA(level.dimensionId)
        if (offset - impale.centre > HOLD_G * PULL_SAG || sideways > HOLD_G * SAG) {
            impale.state.set(TORN)
            gtpa.removeJoint(id)
            return
        }
        val target = Math.max(offset, impale.floor)
        if (target > impale.centre - SLIDE_STEP) return
        impale.centre = target
        val joint = checkNotNull(impale.joint).copy(linearLimitPair = impale.limit())
        impale.joint = joint
        gtpa.updateJoint(VSJointAndId(id, joint))
    }

    /** Zero-width soft limit: a spring-damper at [Impale.centre]. Limits are world distances. */
    @OptIn(VsBeta::class, UnavailableInKrunch::class)
    private fun Impale.limit() = VSD6Joint.LinearLimitPair(
        lowerLimit = centre.toFloat(),
        upperLimit = centre.toFloat(),
        stiffness = stiffness,
        damping = damping,
    )

    /** Releases every weld whose target no longer touches its spike, or whose spike or body is gone. */
    @OptIn(GameTickOnly::class)
    private fun sweep(level: ServerLevel, map: HashMap<Key, Impale>, now: Long) {
        if (map.isEmpty()) return
        val gtpa = ValkyrienSkiesMod.getOrCreateGTPA(level.dimensionId)
        val ships = level.shipObjectWorld.loadedShips
        val it = map.values.iterator()
        while (it.hasNext()) {
            val impale = it.next()
            if (impale.lastSeen == now) continue
            val id = impale.state.getAndSet(CANCELLED)
            // VS drops a gone ship's joints itself; a second remove of the id crashes Krunch.
            val loaded = (impale.spikeBody == WORLD || ships.getById(impale.spikeBody) != null) &&
                (impale.targetBody == WORLD || ships.getById(impale.targetBody) != null)
            if (id >= 0 && loaded) gtpa.removeJoint(id)
            it.remove()
        }
    }

    private const val PENDING = -1
    /** Torn welds stay in the map, so the spike does not weld that target again until it pulls off. */
    private const val TORN = -2
    /** Released before the physics thread attached it: the physics thread removes the joint it makes. */
    private const val CANCELLED = -3
    private const val SCAN_INTERVAL = 2L
    /** Bounds the palette checks for a very large ship's AABB. */
    private const val MAX_SECTIONS_PER_SCAN = 4096
    /** Bounds solver load when a ship ploughs through a whole cluster. */
    private const val MAX_WELDS_PER_SCAN = 16
    /** Spike points tested against target blocks, as fractions of the spike length from its base. */
    private val SAMPLES = doubleArrayOf(0.5, 1.0)
    /** Prismatic convention: local +X is the slide axis. */
    private val LOCAL_X = Vector3d(1.0, 0.0, 0.0)
    private const val GRAVITY = 9.81
    /** Keeps the spring and the tear load above zero for a near-empty ship. */
    private const val MIN_MASS = 50.0
    /** One spike holds this many times the bodies' weight before it tears. */
    private const val HOLD_G = 3.0
    /** Pull-off stretch, in blocks, per weight of load. */
    private const val PULL_SAG = 0.1
    /** Drag on a push deeper: deceleration per unit of slide speed, in 1/s. */
    private const val DRAG_RATE = 4.0
    /** Sideways and twist give, in blocks, under one weight of load. */
    private const val SAG = 0.02
    /** Smallest move deeper, in blocks, that updates the live joint. */
    private const val SLIDE_STEP = 0.02
}
