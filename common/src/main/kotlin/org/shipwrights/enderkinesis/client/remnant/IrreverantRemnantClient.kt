package org.shipwrights.enderkinesis.client.remnant

import com.mojang.blaze3d.shaders.BlendMode
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexFormat
import dev.architectury.networking.NetworkManager
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import org.shipwrights.enderkinesis.dimension.SselithParallelView
import org.shipwrights.enderkinesis.remnant.IrreverantRemnantMotion
import org.shipwrights.enderkinesis.remnant.IrreverantRemnantNetwork
import org.shipwrights.enderkinesis.remnant.RemnantBody
import org.shipwrights.enderkinesis.remnant.RemnantSurfaceProbe
import org.shipwrights.enderkinesis.registry.EKParticles
import kotlin.math.abs

/**
 * Client-side Irreverant Remnant: keeps its own predicted copy of the blob
 * simulation, corrects it against server syncs, and draws each particle as a
 * translucent cube inside a fainter skin, lit as vanilla lights entities.
 *
 * **Why predict.** Syncs arrive a few times a second. Rendering them
 * directly means the mass teleports on each packet and is frozen between —
 * visible stutter. Instead the client runs the *same* integrator the server
 * does ([IrreverantRemnantMotion], shared precisely so the two can't drift)
 * every client tick, so blobs keep creeping, falling, and resting against
 * geometry between packets.
 *
 * **Reconciliation.** Blobs carry stable ids, so a sync is matched per blob
 * rather than wholesale. Rather than snapping to the authoritative position
 * — which would reintroduce the stutter this exists to remove — the error is
 * stored and bled off over the following ticks, so corrections read as the
 * mass easing into place. Only a gross error (teleport, chunk reload) snaps.
 *
 * **Sub-tick smoothing.** Positions are kept for the previous and current
 * tick and interpolated by the frame's partial tick, so motion is smooth at
 * any framerate rather than stepping at 20 Hz.
 */
object IrreverantRemnantClient {

    /** A client-side blob: predicted state plus what's needed to reconcile
     *  and interpolate it. */
    private class ClientBlob(
        val id: Int,
        override var x: Double,
        override var y: Double,
        override var z: Double,
        override var radius: Double,
    ) : RemnantBody {
        override var vx: Double = 0.0
        override var vy: Double = 0.0
        override var vz: Double = 0.0
        override var onSurface: Boolean = false
        override var seeping: Boolean = false
        override var airborneTicks: Int = 0
        override var restTicks: Int = 0
        override var reboundTicks: Int = 0
        override var normalX: Double = 0.0
        override var normalY: Double = 0.0
        override var normalZ: Double = 0.0

        /** Packed block and sky light at the cube, as vanilla entities carry it; sampled once per tick. */
        var packedLight: Int = LightTexture.FULL_BRIGHT

        /** Id of the particle this one physically hangs from, or -1. A drip's skin stretches to it. */
        var parentId: Int = -1

        /** Ticks since it seeped out, counted on from the server's value at first sight. Below
         *  [IrreverantRemnantNetwork.SPAWN_TICKS] it is still growing in from the plane below. */
        var spawnAge: Int = IrreverantRemnantNetwork.SPAWN_TICKS

        /** World-space box of the thin plane on the face it seeped from, and that face's axis and direction. */
        var planeAxis: Int = 0
        var planeSign: Double = 1.0
        var planeMinX: Double = 0.0
        var planeMinY: Double = 0.0
        var planeMinZ: Double = 0.0
        var planeMaxX: Double = 0.0
        var planeMaxY: Double = 0.0
        var planeMaxZ: Double = 0.0

        /** Size pulse: its phase, which advances with distance moved, and its depth, which follows speed. The
         *  previous tick's values are kept for frame interpolation. */
        var pulsePhase: Double = 0.0
        var prevPulsePhase: Double = 0.0
        var pulseDepth: Double = 0.0
        var prevPulseDepth: Double = 0.0

        /** Squash and stretch: drawn size per axis as a multiple of the cube's, eased each tick, with the
         *  previous tick's values for frame interpolation. */
        var stretchX: Double = 1.0
        var stretchY: Double = 1.0
        var stretchZ: Double = 1.0
        var prevStretchX: Double = 1.0
        var prevStretchY: Double = 1.0
        var prevStretchZ: Double = 1.0

        /** Position at the previous client tick, for frame interpolation. */
        var prevX: Double = x
        var prevY: Double = y
        var prevZ: Double = z

        /** Outstanding correction toward the last authoritative position,
         *  applied as a fraction per tick so it never pops. */
        var errX: Double = 0.0
        var errY: Double = 0.0
        var errZ: Double = 0.0

        /** Syncs since this blob was last present — retires blobs that left
         *  sync range or the local player's parallel. */
        var unseenSyncs: Int = 0
    }

    private val blobs = Int2ObjectOpenHashMap<ClientBlob>()
    private val seen = IntOpenHashSet()

    /** Our own vertex buffer, so the draw is never deferred by someone
     *  else's batching. */
    private val surfaceBuilder = BufferBuilder(1 shl 16)

    /** Same real-collision-shape probe the server integrates against, so
     *  prediction matches. */
    private val surface = RemnantSurfaceProbe()

    private val lightPos = BlockPos.MutableBlockPos()

    /** Result of [formSpan]. */
    private var spanLow = 0.0
    private var spanHigh = 0.0

    /** Per-frame copy of the pose, its rotated axes, and scratch for transforming each vertex. */
    private val pose = Matrix4f()
    private val axisX = Vector3f()
    private val axisY = Vector3f()
    private val axisZ = Vector3f()
    private val scratchPosition = Vector3f()

    /** A link skin's rotation (world up onto the link), its axes in the frame's pose, and normal scratch. */
    private val linkRotation = Quaternionf()
    private val linkAxisX = Vector3f()
    private val linkAxisY = Vector3f()
    private val linkAxisZ = Vector3f()
    private val scratchNormal = Vector3f()

    /** Unit cube (half-side 1) as quads, `x, y, z, nx, ny, nz, face axis` per vertex. */
    private val unitCube: FloatArray = buildUnitCube()

    /** This frame's drawn cubes and their camera-relative centres and core half-sides, sorted back to front by
     *  [drawOrder]. Reused, so a frame allocates nothing. */
    private val drawn = ArrayList<ClientBlob>()
    private var drawX = DoubleArray(0)
    private var drawY = DoubleArray(0)
    private var drawZ = DoubleArray(0)
    private var drawHalf = DoubleArray(0)
    private var drawDistance = DoubleArray(0)
    private var drawOrder = IntArray(0)
    private val farthestFirst = it.unimi.dsi.fastutil.ints.IntComparator { a, b ->
        drawDistance[b].compareTo(drawDistance[a])
    }

    /** Client ticks seen, for staggering dust between cubes. */
    private var clientTicks = 0L

    /** Splatters since the last tick, against [MAX_SPLATS_PER_TICK]. */
    private var splatsThisTick = 0

    /** Ticks since the last sync, and the local player's parallel at the last tick. The server stops syncing
     *  when its pool empties, so cubes it removed are only dropped here by these two checks. */
    private var ticksSinceSync = 0
    private var lastParallel: java.math.BigInteger? = null

    fun init() {
        NetworkManager.registerReceiver(
            NetworkManager.Side.S2C,
            IrreverantRemnantNetwork.SYNC,
        ) { buf, context ->
            // Decoded here because the buffer is only valid on this thread, but
            // applied on the client thread. Sync arrives on netty, and writing
            // the blob map from there while the tick walks it leaves the
            // iterator reading a table that has been rehashed underneath it —
            // which surfaces as a null inside fastutil rather than as anything
            // that names the real problem.
            // Positions are float offsets from this origin; see IrreverantRemnantNetwork.
            val originX = buf.readInt()
            val originY = buf.readInt()
            val originZ = buf.readInt()
            val count = buf.readShort().toInt() and 0xFFFF
            val ids = IntArray(count)
            val parents = IntArray(count)
            val ages = IntArray(count)
            val seeps = BooleanArray(count)
            val values = DoubleArray(count * FIELDS_PER_SYNC)
            for (i in 0 until count) {
                val id = buf.readVarInt()
                val sx = originX + buf.readFloat().toDouble()
                val sy = originY + buf.readFloat().toDouble()
                val sz = originZ + buf.readFloat().toDouble()
                val svx = buf.readByte().toDouble() * IrreverantRemnantNetwork.VELOCITY_STEP
                val svy = buf.readByte().toDouble() * IrreverantRemnantNetwork.VELOCITY_STEP
                val svz = buf.readByte().toDouble() * IrreverantRemnantNetwork.VELOCITY_STEP
                val packed = buf.readByte().toInt() and 0xFF
                val seeping = (packed and IrreverantRemnantNetwork.SEEP_FLAG) != 0
                val radius = ((packed and (IrreverantRemnantNetwork.SEEP_FLAG - 1)) *
                    IrreverantRemnantNetwork.RADIUS_STEP).toDouble()
                val nx = buf.readByte().toDouble() * IrreverantRemnantNetwork.NORMAL_STEP
                val ny = buf.readByte().toDouble() * IrreverantRemnantNetwork.NORMAL_STEP
                val nz = buf.readByte().toDouble() * IrreverantRemnantNetwork.NORMAL_STEP
                parents[i] = buf.readVarInt() - 1
                ages[i] = buf.readByte().toInt()
                ids[i] = id
                seeps[i] = seeping
                val base = i * FIELDS_PER_SYNC
                values[base] = sx; values[base + 1] = sy; values[base + 2] = sz
                values[base + 3] = svx; values[base + 4] = svy; values[base + 5] = svz
                values[base + 6] = radius
                values[base + 7] = nx; values[base + 8] = ny; values[base + 9] = nz
            }
            context.queue {
                ticksSinceSync = 0
                seen.clear()
                for (i in 0 until count) {
                    val base = i * FIELDS_PER_SYNC
                    seen.add(ids[i])
                    reconcile(
                        ids[i],
                        values[base], values[base + 1], values[base + 2],
                        values[base + 3], values[base + 4], values[base + 5],
                        values[base + 6],
                        values[base + 7], values[base + 8], values[base + 9],
                        seeps[i],
                        parents[i],
                        ages[i],
                    )
                }
                retireUnseen()
            }
        }
    }

    /** Folds one authoritative blob state into the predicted copy. */
    private fun reconcile(
        id: Int,
        sx: Double, sy: Double, sz: Double,
        svx: Double, svy: Double, svz: Double,
        radius: Double,
        nx: Double, ny: Double, nz: Double,
        seeping: Boolean,
        parent: Int,
        spawnAge: Int,
    ) {
        val existing = blobs.get(id)
        if (existing == null) {
            blobs.put(id, ClientBlob(id, sx, sy, sz, radius).also {
                it.normalX = nx; it.normalY = ny; it.normalZ = nz
                it.seeping = seeping
                it.parentId = parent
                it.spawnAge = spawnAge
                if (spawnAge < IrreverantRemnantNetwork.SPAWN_TICKS) anchorSpawn(it)
            })
            return
        }
        existing.unseenSyncs = 0
        existing.parentId = parent
        existing.seeping = seeping
        existing.radius = radius
        detectBounce(existing, svx - existing.vx, svy - existing.vy, svz - existing.vz)
        existing.vx = svx
        existing.vy = svy
        existing.vz = svz
        val dx = sx - existing.x
        val dy = sy - existing.y
        val dz = sz - existing.z
        if (abs(dx) > SNAP_DISTANCE || abs(dy) > SNAP_DISTANCE || abs(dz) > SNAP_DISTANCE) {
            // Too far gone to ease — teleport or chunk reload.
            existing.x = sx; existing.y = sy; existing.z = sz
            existing.prevX = sx; existing.prevY = sy; existing.prevZ = sz
            existing.errX = 0.0; existing.errY = 0.0; existing.errZ = 0.0
            return
        }
        // Bleed the difference off over the next few ticks instead of
        // snapping — this is what keeps corrections invisible.
        existing.errX = dx
        existing.errY = dy
        existing.errZ = dz
    }

    /**
     * Finds the face a fresh drip seeped from and stores it as the thin plane
     * the drip grows in from: the collision bounds of the block behind the
     * drip, flattened onto the side the drip is on. A block with no collision
     * (the edit was a removal) gives its whole face.
     */
    private fun anchorSpawn(blob: ClientBlob) {
        val level = Minecraft.getInstance().level
        val ax = abs(blob.normalX)
        val ay = abs(blob.normalY)
        val az = abs(blob.normalZ)
        val axis = if (ax >= ay && ax >= az) 0 else if (ay >= az) 1 else 2
        val along = when (axis) { 0 -> blob.normalX; 1 -> blob.normalY; else -> blob.normalZ }
        if (level == null || abs(along) < MIN_SPAWN_NORMAL) {
            blob.spawnAge = IrreverantRemnantNetwork.SPAWN_TICKS
            return
        }
        val sign = if (along > 0.0) 1.0 else -1.0
        blob.planeAxis = axis
        blob.planeSign = sign
        // The block is behind the drip, against its normal.
        lightPos.set(
            blob.x - (if (axis == 0) sign else 0.0) * ANCHOR_REACH,
            blob.y - (if (axis == 1) sign else 0.0) * ANCHOR_REACH,
            blob.z - (if (axis == 2) sign else 0.0) * ANCHOR_REACH,
        )
        val shape = level.getBlockState(lightPos).getCollisionShape(level, lightPos)
        val box = if (shape.isEmpty) WHOLE_BLOCK else shape.bounds()
        blob.planeMinX = lightPos.x + box.minX; blob.planeMaxX = lightPos.x + box.maxX
        blob.planeMinY = lightPos.y + box.minY; blob.planeMaxY = lightPos.y + box.maxY
        blob.planeMinZ = lightPos.z + box.minZ; blob.planeMaxZ = lightPos.z + box.maxZ
        // Flattened onto the face toward the drip, just proud of it.
        when (axis) {
            0 -> {
                val face = if (sign > 0.0) blob.planeMaxX else blob.planeMinX
                blob.planeMinX = minOf(face, face + sign * PLANE_THICKNESS)
                blob.planeMaxX = maxOf(face, face + sign * PLANE_THICKNESS)
            }
            1 -> {
                val face = if (sign > 0.0) blob.planeMaxY else blob.planeMinY
                blob.planeMinY = minOf(face, face + sign * PLANE_THICKNESS)
                blob.planeMaxY = maxOf(face, face + sign * PLANE_THICKNESS)
            }
            else -> {
                val face = if (sign > 0.0) blob.planeMaxZ else blob.planeMinZ
                blob.planeMinZ = minOf(face, face + sign * PLANE_THICKNESS)
                blob.planeMaxZ = maxOf(face, face + sign * PLANE_THICKNESS)
            }
        }
    }

    /** Drops blobs missing from recent syncs (out of range, or on another
     *  parallel). A short grace avoids flicker for blobs straddling the sync
     *  radius. */
    private fun retireUnseen() {
        val ids = blobs.keys.toIntArray()
        for (id in ids) {
            if (seen.contains(id)) continue
            val blob = blobs.get(id) ?: continue
            if (++blob.unseenSyncs > UNSEEN_GRACE_SYNCS) blobs.remove(id)
        }
    }

    /** Advances the predicted simulation one client tick. */
    fun tick() {
        // Tracked even with no cubes, so cubes that arrive after a parallel change are not mistaken for old ones.
        val parallel = SselithParallelView.local
        val parallelChanged = lastParallel != null && parallel != lastParallel
        lastParallel = parallel
        if (blobs.isEmpty()) return
        val minecraft = Minecraft.getInstance()
        // The client tick keeps running while the game is paused — the world
        // does not. Predicting through a pause would drift the mass away from
        // a server that isn't moving it.
        if (minecraft.isPaused) return
        val level = minecraft.level
        // Cubes belong to one parallel and one live connection. A changed parallel, a lost level, or a sync
        // gone quiet means the ones held here are no longer the player's to see.
        if (level == null || parallelChanged || ++ticksSinceSync > SYNC_TIMEOUT_TICKS) {
            clear()
            return
        }
        surface.bind(level)
        refreshEntitySurfaces(level)
        clientTicks++
        var dust = 0
        splatsThisTick = 0
        val values = blobs.values.iterator()
        while (values.hasNext()) {
            val blob = values.next()
            blob.prevX = blob.x
            blob.prevY = blob.y
            blob.prevZ = blob.z
            if (blob.spawnAge < IrreverantRemnantNetwork.SPAWN_TICKS) {
                // Still forming: the server holds it still, so prediction does too.
                blob.vx = 0.0
                blob.vy = 0.0
                blob.vz = 0.0
            } else {
                IrreverantRemnantMotion.integrate(blob, surface)
            }
            // Bleed off part of any outstanding correction.
            blob.x += blob.errX * CORRECTION_RATE
            blob.y += blob.errY * CORRECTION_RATE
            blob.z += blob.errZ * CORRECTION_RATE
            blob.errX *= (1.0 - CORRECTION_RATE)
            blob.errY *= (1.0 - CORRECTION_RATE)
            blob.errZ *= (1.0 - CORRECTION_RATE)
            lightPos.set(blob.x, blob.y, blob.z)
            blob.packedLight = LevelRenderer.getLightColor(level, lightPos)
            val moved = Math.sqrt(
                (blob.x - blob.prevX) * (blob.x - blob.prevX) +
                    (blob.y - blob.prevY) * (blob.y - blob.prevY) +
                    (blob.z - blob.prevZ) * (blob.z - blob.prevZ),
            )
            advancePulse(blob, moved)
            advanceStretch(blob, moved)
            if (blob.spawnAge < IrreverantRemnantNetwork.SPAWN_TICKS) blob.spawnAge++
            // Dust like a Cataloger's: one mote per moving cube every few ticks, staggered by id. Client-side, so
            // only players on this cube's parallel see it.
            if (moved > DUST_MIN_STEP && dust < MAX_DUST_PER_TICK && (clientTicks + blob.id) % DUST_INTERVAL == 0L) {
                if (blob.onSurface) spawnClingingDust(level, blob) else spawnFallingDust(level, blob)
                dust++
            }
        }
    }

    /**
     * Reads the server's bounce out of two syncs. A bounce is the only thing
     * that changes a velocity this much in one step, and the change points
     * along the surface it struck. The client's own prediction is not used: it
     * runs a little behind, and the server's rebound reaches it before its
     * copy reaches the surface.
     */
    private fun detectBounce(blob: ClientBlob, dvx: Double, dvy: Double, dvz: Double) {
        val jump = Math.sqrt(dvx * dvx + dvy * dvy + dvz * dvz)
        if (jump < MIN_BOUNCE_JUMP || splatsThisTick >= MAX_SPLATS_PER_TICK) return
        val level = Minecraft.getInstance().level ?: return
        splatsThisTick++
        splatter(
            level, blob,
            dvx / jump, dvy / jump, dvz / jump,
            jump / (1.0 + IrreverantRemnantMotion.RESTITUTION),
        )
    }

    /**
     * A fast hit at [impact] speed on a surface with unit normal ([nx], [ny],
     * [nz]): the cube is flattened hard against it, and springs back through
     * the stretch easing; and a burst of clinging dust is thrown across the
     * surface round the hit, wider for a harder one.
     */
    @Suppress("LongParameterList") // A surface normal and a speed; a holder object would allocate per hit.
    private fun splatter(
        level: net.minecraft.client.multiplayer.ClientLevel,
        blob: ClientBlob,
        nx: Double, ny: Double, nz: Double,
        impact: Double,
    ) {
        val force = (impact / SPLAT_FULL_IMPACT).coerceAtMost(1.0)
        // Thin along the normal, wide across it.
        val squash = force * SPLAT_SQUASH
        blob.stretchX = 1.0 + squash * 0.5 - squash * 1.5 * nx * nx
        blob.stretchY = 1.0 + squash * 0.5 - squash * 1.5 * ny * ny
        blob.stretchZ = 1.0 + squash * 0.5 - squash * 1.5 * nz * nz
        val h = blob.radius * IrreverantRemnantMotion.CUBE_HALF_EXTENT
        val spread = h * (1.0 + force * SPLAT_SPREAD)
        val gap = blob.radius * IrreverantRemnantMotion.CONTACT_RADIUS_FACTOR - CLING_LIFT
        val random = level.random
        repeat(SPLAT_MOTES) {
            // A random offset with its part along the normal removed, so it lies in the surface.
            var ox = (random.nextDouble() * 2.0 - 1.0) * spread
            var oy = (random.nextDouble() * 2.0 - 1.0) * spread
            var oz = (random.nextDouble() * 2.0 - 1.0) * spread
            val along = ox * nx + oy * ny + oz * nz
            ox -= nx * along; oy -= ny * along; oz -= nz * along
            level.addParticle(
                EKParticles.sselithClingingDust(),
                blob.x + ox - nx * gap, blob.y + oy - ny * gap, blob.z + oz - nz * gap,
                0.0, 0.0, 0.0,
            )
        }
    }

    private fun spawnFallingDust(level: net.minecraft.client.multiplayer.ClientLevel, blob: ClientBlob) {
        val h = blob.radius * IrreverantRemnantMotion.CUBE_HALF_EXTENT
        val random = level.random
        level.addParticle(
            EKParticles.sselithDust(),
            blob.x + (random.nextDouble() * 2.0 - 1.0) * h,
            blob.y + (random.nextDouble() * 2.0 - 1.0) * h,
            blob.z + (random.nextDouble() * 2.0 - 1.0) * h,
            0.0, 0.0, 0.0,
        )
    }

    /** Dust left on the surface a crawling cube touches: at its contact point, scattered across the face it
     *  rests on, and held just off it so the surface does not hide it. */
    private fun spawnClingingDust(level: net.minecraft.client.multiplayer.ClientLevel, blob: ClientBlob) {
        val length = Math.sqrt(blob.normalX * blob.normalX + blob.normalY * blob.normalY + blob.normalZ * blob.normalZ)
        if (length < 1.0e-3) return
        val nx = blob.normalX / length
        val ny = blob.normalY / length
        val nz = blob.normalZ / length
        val h = blob.radius * IrreverantRemnantMotion.CUBE_HALF_EXTENT
        val random = level.random
        // A random offset within the cube, with its part along the normal removed so it lies in the surface.
        var ox = (random.nextDouble() * 2.0 - 1.0) * h
        var oy = (random.nextDouble() * 2.0 - 1.0) * h
        var oz = (random.nextDouble() * 2.0 - 1.0) * h
        val along = ox * nx + oy * ny + oz * nz
        ox -= nx * along; oy -= ny * along; oz -= nz * along
        val gap = blob.radius * IrreverantRemnantMotion.CONTACT_RADIUS_FACTOR - CLING_LIFT
        level.addParticle(
            EKParticles.sselithClingingDust(),
            blob.x + ox - nx * gap,
            blob.y + oy - ny * gap,
            blob.z + oz - nz * gap,
            0.0, 0.0, 0.0,
        )
    }

    /**
     * Eases the cube's squash and stretch toward what its motion and gravity
     * ask for. It lengthens along the way it moves, more with speed. Gravity
     * flattens it on a floor and draws it out under a ceiling or in the air.
     * Each effect thins the other two axes by half as much, which keeps the
     * volume close to constant.
     */
    private fun advanceStretch(blob: ClientBlob, moved: Double) {
        blob.prevStretchX = blob.stretchX
        blob.prevStretchY = blob.stretchY
        blob.prevStretchZ = blob.stretchZ
        var targetX = 1.0
        var targetY = 1.0
        var targetZ = 1.0
        // A forming drip has its own shape.
        if (blob.spawnAge >= IrreverantRemnantNetwork.SPAWN_TICKS) {
            if (moved > MIN_STRETCH_STEP) {
                val amount = (moved / STRETCH_FULL_STEP).coerceAtMost(1.0) * MOVE_STRETCH
                val ux = (blob.x - blob.prevX) / moved
                val uy = (blob.y - blob.prevY) / moved
                val uz = (blob.z - blob.prevZ) / moved
                // +amount along the motion, -amount/2 across it.
                targetX += amount * (3.0 * ux * ux - 1.0) * 0.5
                targetY += amount * (3.0 * uy * uy - 1.0) * 0.5
                targetZ += amount * (3.0 * uz * uz - 1.0) * 0.5
            }
            // Its contact normal's height says how gravity sits on it: up for a floor, down for a ceiling.
            val pull = if (blob.onSurface) -blob.normalY * GRAVITY_SQUASH else GRAVITY_SQUASH * AIRBORNE_SAG
            targetY *= 1.0 + pull
            targetX *= 1.0 - pull * 0.5
            targetZ *= 1.0 - pull * 0.5
        }
        blob.stretchX += (targetX - blob.stretchX) * STRETCH_EASE
        blob.stretchY += (targetY - blob.stretchY) * STRETCH_EASE
        blob.stretchZ += (targetZ - blob.stretchZ) * STRETCH_EASE
    }

    /** Advances the size pulse by distance moved, so it beats with travel and stops at rest. */
    private fun advancePulse(blob: ClientBlob, moved: Double) {
        blob.prevPulsePhase = blob.pulsePhase
        blob.prevPulseDepth = blob.pulseDepth
        blob.pulsePhase += moved * PULSE_RADIANS_PER_BLOCK
        val target = (moved / PULSE_FULL_STEP).coerceAtMost(1.0) * PULSE_DEPTH
        blob.pulseDepth += (target - blob.pulseDepth) * PULSE_EASE
    }

    /** Called from the LevelRenderer hook once per frame. [poseStack] is in
     *  the neutral post-pop state, so world geometry draws at
     *  `worldPos − cameraPos`. */
    @JvmStatic
    fun render(
        poseStack: PoseStack,
        camX: Double,
        camY: Double,
        camZ: Double,
        partialTick: Float,
    ) {
        if (blobs.isEmpty()) return
        if (Minecraft.getInstance().options.renderDebug) {
            renderParticles(poseStack, camX, camY, camZ, partialTick)
        }
        if (!RemnantRenderTypes.isReady) return
        val shader = RemnantRenderTypes.getShader() ?: return
        pose.set(poseStack.last().pose())
        pose.transformDirection(1f, 0f, 0f, axisX)
        pose.transformDirection(0f, 1f, 0f, axisY)
        pose.transformDirection(0f, 0f, 1f, axisZ)
        if (gatherDrawn(partialTick.toDouble(), camX, camY, camZ) == 0) return
        // Translucent and unsorted by depth writes, so drawn back to front.
        it.unimi.dsi.fastutil.ints.IntArrays.quickSort(drawOrder, 0, drawn.size, farthestFirst)

        // Built and drawn on the spot, not handed to the shared buffer source:
        // Iris defers that source's endBatch, so the uniforms set up here would
        // not be the state in force at the draw.
        val t = partialTick.toDouble()
        surfaceBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY)
        for (k in 0 until drawn.size) {
            val i = drawOrder[k]
            val blob = drawn[i]
            val seed = noiseSeed(blob.id)
            val core = drawHalf[i]
            if (blob.spawnAge < IrreverantRemnantNetwork.SPAWN_TICKS) {
                val phase = ((blob.spawnAge + t) / IrreverantRemnantNetwork.SPAWN_TICKS).coerceIn(0.0, 1.0)
                // Fades in early, so the film does not pop into view.
                val fade = smooth((phase / SPAWN_FADE_END).coerceAtMost(1.0))
                emitForming(blob, i, core, phase, seed, (CORE_ALPHA * fade).toInt(), camX, camY, camZ)
                emitForming(blob, i, core * SKIN_SCALE, phase, seed, (SKIN_ALPHA * fade).toInt(), camX, camY, camZ)
                continue
            }
            emitStretched(blob, i, core, seed, CORE_ALPHA, t)
            emitSkin(blob, i, core * SKIN_SCALE, seed, t, camX, camY, camZ)
        }

        // Render state first: it binds our shader, so the uniforms below land
        // on the instance that is about to draw.
        RemnantRenderTypes.SURFACE.setupRenderState()
        applyUniforms(shader)
        // The lightmap into sampler 2, where the vertex shader reads it, as for entities.
        val lightTexture = Minecraft.getInstance().gameRenderer.lightTexture()
        lightTexture.turnOnLightLayer()
        BufferUploader.drawWithShader(surfaceBuilder.end())
        lightTexture.turnOffLightLayer()
        RemnantRenderTypes.SURFACE.clearRenderState()
        // Hand vanilla's blend cache back opaque, or the next core shader can draw with blending off.
        BlendMode().apply()
        RenderSystem.defaultBlendFunc()
    }

    /**
     * A drip forming on its face, like a drop on a wet surface, at [phase] 0..1
     * of the way to its real box of half-side [half]. Three overlapping moves:
     * the film gathers in from the whole face to the drip's width; it swells
     * out from the face, slowly then faster; and near the end it sags, longer
     * and narrower, before it settles into its cube.
     */
    @Suppress("LongParameterList") // Per-cube scalars; a holder object would allocate per cube per frame.
    private fun emitForming(
        blob: ClientBlob,
        i: Int,
        half: Double,
        phase: Double,
        seed: Int,
        alpha: Int,
        camX: Double,
        camY: Double,
        camZ: Double,
    ) {
        val gather = smooth((phase / SPAWN_GATHER_END).coerceAtMost(1.0))
        val swell = phase * phase
        // Rises and falls back to zero at the end, so the drip lands exactly on its cube.
        val sag = Math.sin(Math.PI * ((phase - SPAWN_SAG_START) / (1.0 - SPAWN_SAG_START)).coerceIn(0.0, 1.0))
        formSpan(blob.planeMinX - camX, blob.planeMaxX - camX, drawX[i], half, facing(blob, 0), gather, swell, sag)
        val minX = spanLow
        val maxX = spanHigh
        formSpan(blob.planeMinY - camY, blob.planeMaxY - camY, drawY[i], half, facing(blob, 1), gather, swell, sag)
        val minY = spanLow
        val maxY = spanHigh
        formSpan(blob.planeMinZ - camZ, blob.planeMaxZ - camZ, drawZ[i], half, facing(blob, 2), gather, swell, sag)
        emitBox(
            (minX + maxX) * 0.5, (minY + maxY) * 0.5, (spanLow + spanHigh) * 0.5,
            (maxX - minX) * 0.5, (maxY - minY) * 0.5, (spanHigh - spanLow) * 0.5,
            blob.packedLight, seed, alpha, false,
        )
    }

    /** +1 or -1 when [axis] is the one the drip's face looks along, else 0. */
    private fun facing(blob: ClientBlob, axis: Int): Double = if (blob.planeAxis == axis) blob.planeSign else 0.0

    /**
     * One axis of a forming drip, into [spanLow]/[spanHigh]. Across the face
     * ([facing] 0) the span gathers from the plane to the cube and narrows with
     * the sag. Along the face's normal it swells out, and the sag stretches its
     * free side further from the surface.
     */
    @Suppress("LongParameterList") // One axis of the box; a holder object would allocate per axis per frame.
    private fun formSpan(
        planeLow: Double, planeHigh: Double,
        centre: Double, half: Double,
        facing: Double,
        gather: Double, swell: Double, sag: Double,
    ) {
        if (facing == 0.0) {
            val low = lerp(planeLow, centre - half, gather)
            val high = lerp(planeHigh, centre + half, gather)
            val middle = (low + high) * 0.5
            val narrowed = (high - low) * 0.5 * (1.0 - sag * SPAWN_SAG_NECK)
            spanLow = middle - narrowed
            spanHigh = middle + narrowed
            return
        }
        spanLow = lerp(planeLow, centre - half, swell)
        spanHigh = lerp(planeHigh, centre + half, swell)
        val stretch = sag * SPAWN_SAG_STRETCH * 2.0 * half * swell
        if (facing > 0.0) spanHigh += stretch else spanLow -= stretch
    }

    private fun smooth(x: Double): Double = x * x * (3.0 - 2.0 * x)

    private fun lerp(from: Double, to: Double, amount: Double): Double = from + (to - from) * amount

    /** Collects the cubes in range with their interpolated, camera-relative centres and pulsed core sizes.
     *  Returns how many. */
    private fun gatherDrawn(t: Double, camX: Double, camY: Double, camZ: Double): Int {
        drawn.clear()
        val capacity = blobs.size
        if (drawX.size < capacity) {
            drawX = DoubleArray(capacity * 2)
            drawY = DoubleArray(capacity * 2)
            drawZ = DoubleArray(capacity * 2)
            drawHalf = DoubleArray(capacity * 2)
            drawDistance = DoubleArray(capacity * 2)
            drawOrder = IntArray(capacity * 2)
        }
        val values = blobs.values.iterator()
        while (values.hasNext() && drawn.size < capacity) {
            val blob = values.next()
            val x = blob.prevX + (blob.x - blob.prevX) * t - camX
            val y = blob.prevY + (blob.y - blob.prevY) * t - camY
            val z = blob.prevZ + (blob.z - blob.prevZ) * t - camZ
            val distance = x * x + y * y + z * z
            if (distance > CUBE_RANGE_SQ) continue
            val i = drawn.size
            drawn.add(blob)
            drawX[i] = x
            drawY[i] = y
            drawZ[i] = z
            drawDistance[i] = distance
            drawOrder[i] = i
            val phase = blob.prevPulsePhase + (blob.pulsePhase - blob.prevPulsePhase) * t
            val depth = blob.prevPulseDepth + (blob.pulseDepth - blob.prevPulseDepth) * t
            // Offset per cube, so a mass does not swell in unison.
            val pulse = 1.0 + depth * Math.sin(phase + hash01(blob.id) * 2.0 * Math.PI)
            drawHalf[i] = blob.radius * IrreverantRemnantMotion.CUBE_HALF_EXTENT * pulse
        }
        return drawn.size
    }

    /**
     * The skin: a larger, fainter box round the core. An airborne drip's skin
     * instead runs from the drip to the particle it hangs from, turned to lie
     * along the link, so it connects the two at any angle.
     */
    @Suppress("LongParameterList") // Per-cube scalars; a holder object would allocate per cube per frame.
    private fun emitSkin(
        blob: ClientBlob,
        i: Int,
        skin: Double,
        seed: Int,
        t: Double,
        camX: Double,
        camY: Double,
        camZ: Double,
    ) {
        // The server sends a parent only while the link physically acts.
        val parent = if (blob.parentId < 0) null else blobs.get(blob.parentId)
        if (parent != null) {
            val dx = parent.prevX + (parent.x - parent.prevX) * t - camX - drawX[i]
            val dy = parent.prevY + (parent.y - parent.prevY) * t - camY - drawY[i]
            val dz = parent.prevZ + (parent.z - parent.prevZ) * t - camZ - drawZ[i]
            val length = Math.sqrt(dx * dx + dy * dy + dz * dz)
            if (length > MIN_LINK_LENGTH) {
                // Shortest turn from world up, so a drip hanging straight down keeps an upright skin.
                linkRotation.rotationTo(0f, 1f, 0f, (dx / length).toFloat(), (dy / length).toFloat(), (dz / length).toFloat())
                poseLinkAxes()
                emitBox(
                    drawX[i] + dx * 0.5, drawY[i] + dy * 0.5, drawZ[i] + dz * 0.5,
                    skin, length * 0.5 + skin, skin,
                    blob.packedLight, seed, SKIN_ALPHA, true,
                )
                return
            }
        }
        emitStretched(blob, i, skin, seed, SKIN_ALPHA, t)
    }

    /** An axis-aligned box of nominal half-side [half], squashed and stretched. On a surface it is shifted
     *  along the contact normal by what its size changed, so it stays on the surface and does not shrink
     *  toward its centre. */
    @Suppress("LongParameterList") // Per-cube scalars; a holder object would allocate per cube per frame.
    private fun emitStretched(blob: ClientBlob, i: Int, half: Double, seed: Int, alpha: Int, t: Double) {
        val sx = blob.prevStretchX + (blob.stretchX - blob.prevStretchX) * t
        val sy = blob.prevStretchY + (blob.stretchY - blob.prevStretchY) * t
        val sz = blob.prevStretchZ + (blob.stretchZ - blob.prevStretchZ) * t
        var cx = drawX[i]
        var cy = drawY[i]
        var cz = drawZ[i]
        if (blob.onSurface) {
            cx += blob.normalX * half * (sx - 1.0)
            cy += blob.normalY * half * (sy - 1.0)
            cz += blob.normalZ * half * (sz - 1.0)
        }
        emitBox(cx, cy, cz, half * sx, half * sy, half * sz, blob.packedLight, seed, alpha, false)
    }

    private fun poseLinkAxes() {
        linkRotation.transform(1f, 0f, 0f, scratchNormal)
        pose.transformDirection(scratchNormal.x, scratchNormal.y, scratchNormal.z, linkAxisX)
        linkRotation.transform(0f, 1f, 0f, scratchNormal)
        pose.transformDirection(scratchNormal.x, scratchNormal.y, scratchNormal.z, linkAxisY)
        linkRotation.transform(0f, 0f, 1f, scratchNormal)
        pose.transformDirection(scratchNormal.x, scratchNormal.y, scratchNormal.z, linkAxisZ)
    }

    /**
     * One box around a camera-relative centre, with half-sizes along its own
     * axes. [linked] turns it by [linkRotation], whose axes [poseLinkAxes] has
     * set; otherwise it is axis-aligned.
     */
    @Suppress("LongParameterList") // Centre, size and per-box scalars; a holder object would allocate per box per frame.
    private fun emitBox(
        centreX: Double, centreY: Double, centreZ: Double,
        halfX: Double, halfY: Double, halfZ: Double,
        packedLight: Int, seed: Int, alpha: Int, linked: Boolean,
    ) {
        val hx = halfX.toFloat()
        val hy = halfY.toFloat()
        val hz = halfZ.toFloat()
        val bx = if (linked) linkAxisX else axisX
        val by = if (linked) linkAxisY else axisY
        val bz = if (linked) linkAxisZ else axisZ
        // The pose is affine, so pose(centre + d) = pose(centre) + rotate(d), and rotate(d) sums the rotated axes.
        pose.transformPosition(centreX.toFloat(), centreY.toFloat(), centreZ.toFloat(), scratchPosition)
        val cx = scratchPosition.x
        val cy = scratchPosition.y
        val cz = scratchPosition.z
        var v = 0
        while (v < unitCube.size) {
            val ox = unitCube[v] * hx
            val oy = unitCube[v + 1] * hy
            val oz = unitCube[v + 2] * hz
            val face = unitCube[v + 6].toInt()
            // Normals stay in world space, turned with the box when it is linked.
            scratchNormal.set(unitCube[v + 3], unitCube[v + 4], unitCube[v + 5])
            if (linked) linkRotation.transform(scratchNormal)
            surfaceBuilder.vertex(
                (cx + bx.x * ox + by.x * oy + bz.x * oz).toDouble(),
                (cy + bx.y * ox + by.y * oy + bz.y * oz).toDouble(),
                (cz + bx.z * ox + by.z * oy + bz.z * oz).toDouble(),
            )
                // Noise seed and opacity ride in the colour channels; the shader reads the seed as data.
                .color(seed, 0, 0, alpha)
                // Position on the face from its corner, in blocks, for the noise.
                .uv(
                    faceOffset((face + 1) % 3, ox, oy, oz, hx, hy, hz),
                    faceOffset((face + 2) % 3, ox, oy, oz, hx, hy, hz),
                )
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(packedLight)
                .normal(scratchNormal.x, scratchNormal.y, scratchNormal.z)
                .endVertex()
            v += FLOATS_PER_VERTEX
        }
    }

    /** Distance along [axis] from the box's low corner, for a corner offset (ox, oy, oz) from its centre. */
    @Suppress("LongParameterList") // Picks one of three axes; arrays would allocate per vertex.
    private fun faceOffset(
        axis: Int,
        ox: Float, oy: Float, oz: Float,
        hx: Float, hy: Float, hz: Float,
    ): Float = when (axis) {
        0 -> ox + hx
        1 -> oy + hy
        else -> oz + hz
    }

    /** 0..255 from a particle id, so each cube has its own noise pattern. */
    private fun noiseSeed(id: Int): Int = (id * NOISE_HASH) ushr NOISE_SHIFT

    /** Deterministic 0..1 from an id, by the golden-ratio multiplier. */
    private fun hash01(id: Int): Double {
        val x = id * 0.6180339887498949
        return x - Math.floor(x)
    }

    /** Unit cube (half-side 1) as six quads with flat face normals and each vertex's face axis. */
    private fun buildUnitCube(): FloatArray {
        val out = FloatArray(FACES * QUAD_VERTICES * FLOATS_PER_VERTEX)
        var at = 0
        for (axis in 0 until 3) {
            for (sign in -1..1 step 2) {
                for (step in 0 until QUAD_VERTICES) {
                    // Counter-clockwise seen from outside, for GL back-face culling: the in-face axes
                    // run cyclically after the face axis, so a negative face takes them in reverse.
                    val corner = if (sign > 0) step else (QUAD_VERTICES - step) % QUAD_VERTICES
                    out[at + axis] = sign.toFloat()
                    out[at + (axis + 1) % 3] = if (corner == 1 || corner == 2) 1f else -1f
                    out[at + (axis + 2) % 3] = if (corner >= 2) 1f else -1f
                    out[at + 3 + axis] = sign.toFloat()
                    out[at + 6] = axis.toFloat()
                    at += FLOATS_PER_VERTEX
                }
            }
        }
        return out
    }

    /**
     * Per-particle debug view, on the F3 screen.
     *
     * The surface deliberately hides the particles it is built from, which
     * leaves no way to see what the simulation is actually doing — whether a
     * mass is packed or compressed, which particles are asleep, which are
     * seeping through a block, or where a contact normal is pointing. Each of
     * those has been diagnosed by inference from the outside at some point;
     * this shows it directly.
     *
     * Drawn before any culling, so particles the mesh has dropped for being
     * off-screen still appear — the debug view should show what is there, not
     * what survived the optimisations.
     *
     * Box colour is the particle's state, the yellow spur is its contact
     * normal, and the orange one its velocity, exaggerated to be legible at
     * the speeds this creature moves at.
     */
    private fun renderParticles(
        poseStack: PoseStack,
        camX: Double,
        camY: Double,
        camZ: Double,
        partialTick: Float,
    ) {
        val source = Minecraft.getInstance().renderBuffers().bufferSource()
        val lines = source.getBuffer(RenderType.lines())
        val matrix = poseStack.last().pose()
        val normalMatrix = poseStack.last().normal()
        val t = partialTick.toDouble()
        val values = blobs.values.iterator()
        while (values.hasNext()) {
            val blob = values.next()
            val x = blob.prevX + (blob.x - blob.prevX) * t - camX
            val y = blob.prevY + (blob.y - blob.prevY) * t - camY
            val z = blob.prevZ + (blob.z - blob.prevZ) * t - camZ
            val r = blob.radius
            val normalLength = Math.sqrt(
                blob.normalX * blob.normalX + blob.normalY * blob.normalY + blob.normalZ * blob.normalZ,
            )
            val red: Float
            val green: Float
            val blue: Float
            when {
                blob.seeping -> { red = 1f; green = 0.2f; blue = 1f }
                normalLength < 0.2 -> { red = 0.2f; green = 0.85f; blue = 1f }
                blob.restTicks > 0 -> { red = 0.25f; green = 0.5f; blue = 0.25f }
                else -> { red = 0.3f; green = 1f; blue = 0.35f }
            }
            LevelRenderer.renderLineBox(
                poseStack, lines,
                x - r, y - r, z - r, x + r, y + r, z + r,
                red, green, blue, 1f,
            )
            if (normalLength > 1.0e-4) {
                spur(
                    lines, matrix, normalMatrix, x, y, z,
                    blob.normalX / normalLength * NORMAL_SPUR,
                    blob.normalY / normalLength * NORMAL_SPUR,
                    blob.normalZ / normalLength * NORMAL_SPUR,
                    1f, 0.9f, 0.2f,
                )
            }
            spur(
                lines, matrix, normalMatrix, x, y, z,
                blob.vx * VELOCITY_SPUR, blob.vy * VELOCITY_SPUR, blob.vz * VELOCITY_SPUR,
                1f, 0.5f, 0.1f,
            )
        }
        source.endBatch(RenderType.lines())
    }

    /** One debug line from a point along a delta. */
    private fun spur(
        consumer: com.mojang.blaze3d.vertex.VertexConsumer,
        matrix: Matrix4f,
        normalMatrix: org.joml.Matrix3f,
        x: Double, y: Double, z: Double,
        dx: Double, dy: Double, dz: Double,
        red: Float, green: Float, blue: Float,
    ) {
        val length = Math.sqrt(dx * dx + dy * dy + dz * dz)
        if (length < 1.0e-6) return
        val ux = (dx / length).toFloat()
        val uy = (dy / length).toFloat()
        val uz = (dz / length).toFloat()
        consumer.vertex(matrix, x.toFloat(), y.toFloat(), z.toFloat())
            .color(red, green, blue, 1f)
            .normal(normalMatrix, ux, uy, uz)
            .endVertex()
        consumer.vertex(matrix, (x + dx).toFloat(), (y + dy).toFloat(), (z + dz).toFloat())
            .color(red, green, blue, 1f)
            .normal(normalMatrix, ux, uy, uz)
            .endVertex()
    }


    /** Uniforms are pushed after the render state binds the shader, so they
     *  cannot be overwritten by whatever drew last. */
    private fun applyUniforms(shader: net.minecraft.client.renderer.ShaderInstance) {
        shader.safeGetUniform("GlowColor").set(GLOW_R, GLOW_G, GLOW_B, 1f)
        shader.safeGetUniform("EntityLight0").set(ENTITY_LIGHT_0.x, ENTITY_LIGHT_0.y, ENTITY_LIGHT_0.z)
        shader.safeGetUniform("EntityLight1").set(ENTITY_LIGHT_1.x, ENTITY_LIGHT_1.y, ENTITY_LIGHT_1.z)
        shader.safeGetUniform("Thickness").set(CUBE_THICKNESS)
        shader.safeGetUniform("NoiseStrength").set(NOISE_STRENGTH)
        shader.safeGetUniform("NoiseScale").set(NOISE_SCALE)
        shader.safeGetUniform("NoiseLevels").set(NOISE_LEVELS)
        shader.safeGetUniform("CoreColor").set(CORE_R, CORE_G, CORE_B, 1f)
        shader.safeGetUniform("CorePower").set(CORE_POWER)
        shader.safeGetUniform("CoreStrength").set(CORE_STRENGTH)
    }

    /** Gathers the entity hulls prediction collides against, over the
     *  predicted mass. Kept in step with the server, which does the same
     *  once per tick, so the two integrate against the same surfaces. */
    private fun refreshEntitySurfaces(level: net.minecraft.world.level.Level) {
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var minZ = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE; var maxZ = -Double.MAX_VALUE
        val values = blobs.values.iterator()
        while (values.hasNext()) {
            val blob = values.next()
            if (blob.x < minX) minX = blob.x
            if (blob.y < minY) minY = blob.y
            if (blob.z < minZ) minZ = blob.z
            if (blob.x > maxX) maxX = blob.x
            if (blob.y > maxY) maxY = blob.y
            if (blob.z > maxZ) maxZ = blob.z
        }
        surface.refreshEntities(
            level,
            net.minecraft.world.phys.AABB(minX, minY, minZ, maxX, maxY, maxZ)
                .inflate(ENTITY_SURFACE_PADDING),
        )
    }

    private fun clear() {
        blobs.clear()
        ticksSinceSync = 0
    }

    /** Fraction of the outstanding correction applied per tick. Low enough
     *  that corrections are invisible, high enough to converge well within
     *  a second. */
    private const val CORRECTION_RATE = 0.25

    /** Beyond this the correction snaps rather than eases. */
    private const val SNAP_DISTANCE = 4.0

    /** Syncs a blob may be absent from before it is retired: about half a second at one sync a tick. */
    private const val UNSEEN_GRACE_SYNCS = 10

    /** Ticks without any sync before every cube is dropped. The server syncs every tick while it has any. */
    private const val SYNC_TIMEOUT_TICKS = 10

    /** Position, velocity, radius and normal, per blob, in one sync. */
    private const val FIELDS_PER_SYNC = 10

    /** Radius around the camera within which cubes are drawn. */
    private const val CUBE_RANGE_SQ = 40.0 * 40.0

    /** How far past the mass an entity is still gathered as surface. */
    private const val ENTITY_SURFACE_PADDING = 2.0


    /** How much body light crosses inside a cube, 0..1, for the shader's
     *  scatter and core terms. One value for every cube. */
    private const val CUBE_THICKNESS = 0.5f

    /** How far the per-pixel noise moves a face's brightness up or down, as a fraction. */
    private const val NOISE_STRENGTH = 0.18f

    /** Golden-ratio multiplier, and the top byte it keeps, for [noiseSeed]. */
    private const val NOISE_HASH = -0x61c88647
    private const val NOISE_SHIFT = 24

    private const val FLOATS_PER_VERTEX = 7

    /** Opacity of the core and of its skin, 0..255, and how much larger the skin is than the core. */
    private const val CORE_ALPHA = 170
    private const val SKIN_ALPHA = 60
    private const val SKIN_SCALE = 1.22

    /** Spawn plane: how far behind a fresh drip its block is looked for, how thick the plane starts, and the
     *  least normal a drip needs for the face it is on to be known. */
    private const val ANCHOR_REACH = 0.8
    private const val PLANE_THICKNESS = 1.0 / 64.0
    private const val MIN_SPAWN_NORMAL = 0.5

    /** Forming drip, as fractions of the spawn: when it is fully faded in, when the film has gathered to the
     *  drip's width, and when the sag begins; then how far the sag stretches it (in cube sides) and narrows it. */
    private const val SPAWN_FADE_END = 0.4
    private const val SPAWN_GATHER_END = 0.6
    private const val SPAWN_SAG_START = 0.6
    private const val SPAWN_SAG_STRETCH = 0.3
    private const val SPAWN_SAG_NECK = 0.2
    private val WHOLE_BLOCK = net.minecraft.world.phys.AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)

    /** Below this drip-to-parent distance a link skin has no direction worth turning to, so it stays a cube. */
    private const val MIN_LINK_LENGTH = 1.0e-3

    /** Size pulse while moving: phase per block travelled, the step per tick at which it reaches full depth, that
     *  depth as a fraction of size, and how fast the depth follows speed. */
    private const val PULSE_RADIANS_PER_BLOCK = 5.0
    private const val PULSE_FULL_STEP = 0.03
    private const val PULSE_DEPTH = 0.15
    private const val PULSE_EASE = 0.15

    /** Squash and stretch: the step per tick below which motion does not stretch, the step at which it
     *  stretches fully, that full stretch, the squash from resting on a floor (reversed under a ceiling), the
     *  share of it an airborne cube sags by, and how fast the shape follows. */
    private const val MIN_STRETCH_STEP = 1.0e-4
    private const val STRETCH_FULL_STEP = 0.08
    private const val MOVE_STRETCH = 0.3
    private const val GRAVITY_SQUASH = 0.15
    private const val AIRBORNE_SAG = 0.5
    private const val STRETCH_EASE = 0.2

    /** Splatter: the impact speed that gives the full effect, how much the cube flattens at that speed, how
     *  many motes it throws, how far past the cube they spread, and a cap on splatters per tick (Rule 2). */
    private const val SPLAT_FULL_IMPACT = 0.3
    private const val SPLAT_SQUASH = 0.45
    private const val SPLAT_MOTES = 6
    private const val SPLAT_SPREAD = 1.5
    private const val MAX_SPLATS_PER_TICK = 8

    /** Smallest velocity change between two syncs that can only be a bounce: the weakest rebound, less one
     *  step of velocity quantisation on each axis. */
    private const val MIN_BOUNCE_JUMP =
        IrreverantRemnantMotion.BOUNCE_MIN_IMPACT * (1.0 + IrreverantRemnantMotion.RESTITUTION) - 0.02

    /** Dust: the step per tick that counts as moving, ticks between motes per cube, and a cap per tick across
     *  all cubes (Rule 2). Crawling cubes leave clinging dust, airborne ones falling dust. */
    private const val DUST_MIN_STEP = 0.004
    private const val DUST_INTERVAL = 10L
    private const val MAX_DUST_PER_TICK = 6

    /** How far off the surface clinging dust sits. */
    private const val CLING_LIFT = 0.03

    /** World pixels per noise lattice cell, and the brightness levels the noise is cut into. */
    private const val NOISE_SCALE = 4f
    private const val NOISE_LEVELS = 5f
    private const val QUAD_VERTICES = 4
    private const val FACES = 6

    /** Debug spur lengths. Velocity is exaggerated heavily — a settled
     *  particle moves thousandths of a block per tick. */
    private const val NORMAL_SPUR = 0.35
    private const val VELOCITY_SPUR = 12.0

    /** Sselith yellow. */
    private const val GLOW_R = 1.0f
    private const val GLOW_G = 0.85f
    private const val GLOW_B = 0.20f

    /** Vanilla's two entity light directions in world space (`Lighting.DIFFUSE_LIGHT_0/1`). The vanilla
     *  `Light0_Direction` uniform is in view space, and these cube normals are in world space. */
    private val ENTITY_LIGHT_0: Vector3f = Vector3f(0.2f, 1.0f, -0.7f).normalize()
    private val ENTITY_LIGHT_1: Vector3f = Vector3f(-0.2f, 1.0f, 0.7f).normalize()

    /** Light held inside the body, in Sselith's glyph yellow, so the
     *  interior is not flat black. */
    private const val CORE_R = 1.0f
    private const val CORE_G = 0.82f
    private const val CORE_B = 0.16f
    private const val CORE_POWER = 1.6f
    private const val CORE_STRENGTH = 0.26f

}
