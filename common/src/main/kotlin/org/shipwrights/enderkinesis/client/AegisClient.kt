package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import dev.architectury.event.events.client.ClientTickEvent
import dev.architectury.networking.NetworkManager
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.command.AegisDebugCommands
import org.shipwrights.enderkinesis.item.AegisBox
import org.shipwrights.enderkinesis.registry.EKItems
import org.shipwrights.enderkinesis.registry.EKParticles
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Client-side renderer for the Staff-of-Aegis shield box. Draws the
 *  wireframe + glowing white particles for every visible player who is
 *  currently using the staff — local player included.
 *
 *  The box geometry is computed via [AegisBox.forPlayer] using the same
 *  inputs as the server tick, so client + server agree on the shield's
 *  position without a sync packet. Particles are spawned client-only.
 *
 *  Wiring follows the same cross-platform pattern as
 *  [OrbBeamLineRenderer] / [CatalogerTomeWorldRenderer] — the loader's
 *  client init registers [renderAll] under `WorldRenderEvents.AFTER_TRANSLUCENT`
 *  (Fabric) or the matching `RenderLevelStageEvent` stage (Forge). */
object AegisClient {

    /** Glowing-particles spawn count per tick per active player. */
    private const val PARTICLES_PER_TICK: Int = 2

    /** Box-local-frame drift speed per tick for each sparkle. ~0.04 b/t =
     *  0.8 b/s — visible motion over the ~2 s lifetime, but slow enough
     *  that the sparkle stays inside the box for most of its life. */
    private const val SPARKLE_DRIFT_SPEED: Double = 0.04

    /** Distance from each face edge inward (as a fraction of the half-edge
     *  toward the face centre) where the glow strip's α reaches 0. Smaller =
     *  thinner, sharper edge highlight. */
    private const val GLOW_INSET: Double = 0.32

    /** Peak α on the outer (face-edge) verts of the glow strips. With
     *  additive blending two strips meet at every face corner, so the
     *  effective on-screen brightness at corners is ~2× this value. */
    private const val GLOW_OUTER_ALPHA: Int = 60

    /** Ticks the box takes to expand from a point at the centre to full
     *  size when the player engages the staff. 10 ticks = 0.5 s @ 20 TPS. */
    private const val EXPAND_DURATION_TICKS: Int = 10

    /** Ticks the shatter animation runs after the staff is released. The
     *  shards fade out linearly over this window — long enough to see
     *  them fall and bounce a few times. */
    private const val SHATTER_DURATION_TICKS: Int = 40

    /** Outward velocity (blocks/tick) of each shard at the moment the
     *  shatter begins. Direction is the unit vector from box centre to
     *  shard midpoint. */
    private const val SHATTER_OUTWARD_SPEED: Double = 0.12

    /** Gravity accel (blocks/tick²) on every shard once the shatter has
     *  begun. ~0.03 reads as "broken glass falling" — heavier than a
     *  particle, lighter than an Entity item drop. */
    private const val SHATTER_GRAVITY: Double = 0.03

    /** Fraction of the vertical velocity retained when a shard bounces
     *  off solid ground. Smaller = more dampened bounce. */
    private const val SHATTER_BOUNCE_DAMP: Double = 0.45

    /** Fraction of the horizontal velocity retained on bounce — friction
     *  with the surface. */
    private const val SHATTER_GROUND_FRICTION: Double = 0.6

    /** A shard whose vertical bounce velocity is below this magnitude
     *  is considered settled — it stops moving and just fades in place. */
    private const val SHATTER_SETTLE_VY: Double = 0.04

    /** Per-shard tumble rate (radians/tick) — random sign per shard so
     *  fragments spin both ways. Zeroed once a shard settles. */
    private const val SHATTER_ROT_RATE: Double = 0.3

    /** Six faces of the OBB, each as four corner indices into
     *  [AegisBox.Frame.corner]. Order around each face's perimeter — winding
     *  doesn't matter because [AegisRenderType.FACE_GLOW] is NO_CULL. */
    private val FACES: Array<IntArray> = arrayOf(
        intArrayOf(0, 2, 6, 4),                            // sx = -1
        intArrayOf(1, 5, 7, 3),                            // sx = +1
        intArrayOf(0, 4, 5, 1),                            // sy = -1
        intArrayOf(2, 3, 7, 6),                            // sy = +1
        intArrayOf(0, 1, 3, 2),                            // sz = -1
        intArrayOf(4, 6, 7, 5),                            // sz = +1
    )

    /** Six faces of a shatter cube, each as 4 corners packed as triples of
     *  ±1 sign values (sx, sy, sz) — multiplied by half-edge × the cube's
     *  body-frame X/Y/Z axes to get the world corner positions. NO_CULL on
     *  the render type means winding doesn't matter. */
    private val CUBE_FACES: Array<IntArray> = arrayOf(
        intArrayOf(-1, -1, -1,  -1, -1, +1,  -1, +1, +1,  -1, +1, -1),  // -X
        intArrayOf(+1, -1, -1,  +1, +1, -1,  +1, +1, +1,  +1, -1, +1),  // +X
        intArrayOf(-1, -1, -1,  +1, -1, -1,  +1, -1, +1,  -1, -1, +1),  // -Y
        intArrayOf(-1, +1, -1,  -1, +1, +1,  +1, +1, +1,  +1, +1, -1),  // +Y
        intArrayOf(-1, -1, -1,  -1, +1, -1,  +1, +1, -1,  +1, -1, -1),  // -Z
        intArrayOf(-1, -1, +1,  +1, -1, +1,  +1, +1, +1,  -1, +1, +1),  // +Z
    )

    /** Number of cubes spawned per shatter. They scatter inside the box's
     *  volume at random positions / orientations / sizes. */
    private const val SHATTER_CUBE_COUNT: Int = 18

    /** Inclusive half-edge range for a single cube — random uniform per
     *  shard. Sized so the largest pieces stay clearly sub-block and the
     *  smallest still read as solid debris rather than dust. */
    private const val SHATTER_CUBE_HALF_EDGE_MIN: Double = 0.08
    private const val SHATTER_CUBE_HALF_EDGE_MAX: Double = 0.22

    /** Local-space half-extents of the box, mirrored from [AegisBox]'s
     *  WIDTH / HEIGHT / VISUAL_DEPTH so seed positions stay inside. */
    private const val SHATTER_LOCAL_HALF_X: Double = AegisBox.WIDTH * 0.5
    private const val SHATTER_LOCAL_HALF_Y: Double = AegisBox.HEIGHT * 0.5
    private const val SHATTER_LOCAL_HALF_Z: Double = AegisBox.VISUAL_DEPTH * 0.5

    /** Pairs of corner indices defining the 12 edges of one cube. Same
     *  3-bit (sx, sy, sz) encoding as [AegisBox.edges]. */
    private val CUBE_EDGES: IntArray = intArrayOf(
        0, 1, 2, 3, 4, 5, 6, 7,                            // X-aligned
        0, 2, 1, 3, 4, 6, 5, 7,                            // Y-aligned
        0, 4, 1, 5, 2, 6, 3, 7,                            // Z-aligned
    )

    /** One cube-shaped piece of the shattered box. The cube carries its own
     *  orthonormal body frame ([axisX], [axisY], [axisZ]) so the renderer
     *  can reconstruct the 8 corners and 6 face quads at any tilted
     *  orientation. Gravity + ground bounce act on [pos]/[vel] in world
     *  space; [omega] around [rotAxis] tumbles the body frame each tick.
     *
     *  [lastPos]/[lastAxis*] snapshot the previous tick so the renderer can
     *  lerp to the current state for sub-tick smoothness. */
    private class Shard(
        val halfEdge: Double,
        var pos: Vec3,
        var vel: Vec3,
        var axisX: Vec3,
        var axisY: Vec3,
        var axisZ: Vec3,
        val rotAxis: Vec3,
        var omega: Double,
        var lastPos: Vec3 = pos,
        var lastAxisX: Vec3 = axisX,
        var lastAxisY: Vec3 = axisY,
        var lastAxisZ: Vec3 = axisZ,
        var settled: Boolean = false,
    )

    /** Active shatter for one wielder. [startGameTime] sets the linear
     *  alpha fade across [SHATTER_DURATION_TICKS]. */
    private data class ShatterState(val shards: List<Shard>, val startGameTime: Long)

    /** Per-player shatter state. Populated on the use-end transition,
     *  removed when [SHATTER_DURATION_TICKS] elapse. */
    private val shatters: MutableMap<UUID, ShatterState> = ConcurrentHashMap()

    /** Set of players who were wielding the staff last tick — used to spot
     *  the wield → no-wield transition that triggers a shatter. */
    private val wieldingLastTick: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /** Interior glow-dot inside the shield. Position stored in **box-local
     *  frame** so the renderer can transform it through the box's *current*
     *  Frame each render frame (no tick-level snapshot, no sub-tick lag).
     *  Each sparkle is also assigned a single cardinal drift direction
     *  (left / right / up / down in box-local frame) when it spawns; the
     *  drift velocity is held constant for its whole life and folded into
     *  the position render-side via [age] + [tickDelta]. */
    private data class Sparkle(
        var lx: Double, var ly: Double, var lz: Double,
        /** Per-tick drift along box-local X axis (right). One of -[SPARKLE_DRIFT_SPEED], 0, +[SPARKLE_DRIFT_SPEED]. */
        val vlx: Double,
        /** Per-tick drift along box-local Y axis (up). */
        val vly: Double,
        var age: Int,
        val lifetime: Int,
    )

    /** Per-player live sparkles. Local player only — remote players' boxes
     *  can't be tracked accurately enough to make remote sparkles look right. */
    private val sparkles: MutableMap<UUID, MutableList<Sparkle>> = ConcurrentHashMap()

    /** Half-extent of each sparkle's camera-facing quad, in blocks. */
    private const val SPARKLE_HALF_SIZE: Double = 0.08

    /** Peak α on a sparkle's outer ring. Additive blend means overlapping
     *  sparkles brighten naturally; matching the brim glow's [GLOW_OUTER_ALPHA]
     *  keeps the look cohesive. */
    private const val SPARKLE_ALPHA: Int = 110

    /** Persistent debug boxes spawned by `/aegisbox`. Each entry is a
     *  frozen [AegisBox.Frame] snapshotted at the moment the command ran.
     *  Cleared by `/aegisbox clear`. Drawn at full expansion in
     *  [renderAll]. Synchronized because writes come in from the network
     *  thread via [NetworkManager.registerReceiver] callbacks
     *  (re-dispatched onto the client thread via `Minecraft.execute`, but
     *  belt-and-braces). */
    private val debugBoxes: MutableList<AegisBox.Frame> =
        java.util.Collections.synchronizedList(mutableListOf())

    /** Snapshot the current client-side debug boxes — used by
     *  `SunderingClient` to feed the same shield list to its polyline
     *  tracer that `AegisClient` is rendering. Synchronizes on the same
     *  list as the render loop and returns a defensive copy. */
    @JvmStatic
    fun snapshotDebugBoxes(): List<AegisBox.Frame> {
        synchronized(debugBoxes) {
            if (debugBoxes.isEmpty()) return emptyList()
            return ArrayList(debugBoxes)
        }
    }

    @JvmStatic
    fun init() {
        // Architectury per-frame-after-level tick is fine for particle
        // emission. The world-render hook is per-platform and registered
        // in the loader's client init.
        ClientTickEvent.CLIENT_LEVEL_POST.register(ClientTickEvent.ClientLevel { level ->
            detectWieldTransitions()
            tickShatters(level)
            tickSparkles()
        })
        // Debug-command S2C receivers — add / clear persistent
        // visualisation boxes for `/aegisbox`.
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, AegisDebugCommands.ADD_PACKET) { buf, _ ->
            val cx = buf.readDouble(); val cy = buf.readDouble(); val cz = buf.readDouble()
            val xx = buf.readDouble(); val xy = buf.readDouble(); val xz = buf.readDouble()
            val yx = buf.readDouble(); val yy = buf.readDouble(); val yz = buf.readDouble()
            val zx = buf.readDouble(); val zy = buf.readDouble(); val zz = buf.readDouble()
            Minecraft.getInstance().execute {
                debugBoxes.add(AegisBox.Frame(
                    center = Vec3(cx, cy, cz),
                    axisX = Vec3(xx, xy, xz),
                    axisY = Vec3(yx, yy, yz),
                    axisZ = Vec3(zx, zy, zz),
                ))
            }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, AegisDebugCommands.CLEAR_PACKET) { _, _ ->
            Minecraft.getInstance().execute { debugBoxes.clear() }
        }
    }

    /** Called from the loader's per-frame world-render hook
     *  (`WorldRenderEvents.AFTER_TRANSLUCENT` / `RenderLevelStageEvent`).
     *  Iterates every visible player using the staff and draws their shield
     *  wireframe in world space. */
    @JvmStatic
    fun renderAll(pose: PoseStack, consumers: MultiBufferSource, camX: Double, camY: Double, camZ: Double, tickDelta: Float) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        for (player in level.players()) {
            if (isWielding(player)) {
                renderBox(pose, consumers, camX, camY, camZ, player, tickDelta)
            }
        }
        // Debug `/aegisbox` snapshots — fully-open static boxes.
        if (debugBoxes.isNotEmpty()) {
            synchronized(debugBoxes) {
                for (frame in debugBoxes) {
                    renderFrame(pose, consumers, camX, camY, camZ, frame, expand = 1.0)
                }
            }
        }
        // Shatter pieces persist after wielding ends — render them
        // independently of the active set.
        renderShatters(pose, consumers, camX, camY, camZ, tickDelta)
        // Interior sparkles inside the active local-player box.
        renderSparkles(pose, consumers, camX, camY, camZ, tickDelta)
    }

    /** Once per game tick: scan all visible players and snapshot the
     *  current wield set. Any UUID that was in last tick's set but isn't
     *  in this one's = wield → no-wield transition → seed a shatter. */
    private fun detectWieldTransitions() {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val now = mutableSetOf<UUID>()
        for (player in level.players()) {
            if (isWielding(player)) now.add(player.uuid)
        }
        for (uuid in wieldingLastTick) {
            if (uuid !in now) {
                val player = level.getPlayerByUUID(uuid) ?: continue
                seedShatter(player)
            }
        }
        wieldingLastTick.clear()
        wieldingLastTick.addAll(now)
    }

    /** Capture the current box and spawn [SHATTER_CUBE_COUNT] cubes at
     *  random positions inside its volume, each with a uniformly-sampled
     *  half-edge in [[SHATTER_CUBE_HALF_EDGE_MIN], [SHATTER_CUBE_HALF_EDGE_MAX]].
     *  Initial body frame = the box's own axes; the per-cube tumble axis
     *  is sampled uniformly on the unit sphere so cubes spin in every
     *  direction. */
    private fun seedShatter(player: Player) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val frame = AegisBox.forPlayer(player)
        val rand = level.random

        val shards = ArrayList<Shard>(SHATTER_CUBE_COUNT)
        repeat(SHATTER_CUBE_COUNT) {
            // Random position inside the OBB. Sample uniformly in box-local
            // coordinates then push through frame.axis* to world.
            val lx = (rand.nextDouble() * 2 - 1) * SHATTER_LOCAL_HALF_X
            val ly = (rand.nextDouble() * 2 - 1) * SHATTER_LOCAL_HALF_Y
            val lz = (rand.nextDouble() * 2 - 1) * SHATTER_LOCAL_HALF_Z
            val centre = Vec3(
                frame.center.x + frame.axisX.x * lx + frame.axisY.x * ly + frame.axisZ.x * lz,
                frame.center.y + frame.axisX.y * lx + frame.axisY.y * ly + frame.axisZ.y * lz,
                frame.center.z + frame.axisX.z * lx + frame.axisY.z * ly + frame.axisZ.z * lz,
            )
            // Initial velocity: radial from box centre at SHATTER_OUTWARD_SPEED
            // + a small upward kick so the cloud briefly puffs up before falling.
            val outwardRaw = centre.subtract(frame.center)
            val outward = if (outwardRaw.lengthSqr() < 1e-6) frame.axisZ else outwardRaw.normalize()
            val vel = outward.scale(SHATTER_OUTWARD_SPEED).add(Vec3(0.0, 0.05, 0.0))

            // Random unit tumble axis on the sphere — re-roll near-zero
            // samples so we never normalise a zero vector.
            var rax: Double; var ray: Double; var raz: Double
            do {
                rax = rand.nextDouble() * 2 - 1
                ray = rand.nextDouble() * 2 - 1
                raz = rand.nextDouble() * 2 - 1
            } while (rax * rax + ray * ray + raz * raz < 1e-6)
            val rl = kotlin.math.sqrt(rax * rax + ray * ray + raz * raz)
            val rotAxis = Vec3(rax / rl, ray / rl, raz / rl)
            val omega = (rand.nextDouble() * 2 - 1) * SHATTER_ROT_RATE

            // Random half-edge in the configured range.
            val halfEdge = SHATTER_CUBE_HALF_EDGE_MIN +
                rand.nextDouble() * (SHATTER_CUBE_HALF_EDGE_MAX - SHATTER_CUBE_HALF_EDGE_MIN)

            shards.add(
                Shard(
                    halfEdge = halfEdge,
                    pos = centre,
                    vel = vel,
                    axisX = frame.axisX,
                    axisY = frame.axisY,
                    axisZ = frame.axisZ,
                    rotAxis = rotAxis,
                    omega = omega,
                )
            )
        }
        shatters[player.uuid] = ShatterState(shards, level.gameTime)
    }

    /** Per-tick physics step for every active shatter. Each shard:
     *  - saves its previous-tick state (for sub-tick render lerp),
     *  - applies gravity,
     *  - moves to the new position, bouncing off the first solid block
     *    along the Y trajectory,
     *  - rotates its edge / inner axes around [Shard.rotAxis] by [Shard.omega].
     *
     *  A shard with a small post-bounce Y speed settles — gravity / rotation
     *  stop, the strip lies on the ground until the fade-out completes. */
    private fun tickShatters(level: ClientLevel) {
        if (shatters.isEmpty()) return
        val now = level.gameTime
        val iter = shatters.entries.iterator()
        while (iter.hasNext()) {
            val (_, state) = iter.next()
            // Drop the whole state once its fade is complete.
            if (now - state.startGameTime >= SHATTER_DURATION_TICKS) {
                iter.remove()
                continue
            }
            for (shard in state.shards) {
                shard.lastPos = shard.pos
                shard.lastAxisX = shard.axisX
                shard.lastAxisY = shard.axisY
                shard.lastAxisZ = shard.axisZ
                if (shard.settled) continue
                // Integrate gravity onto velocity.
                shard.vel = Vec3(shard.vel.x, shard.vel.y - SHATTER_GRAVITY, shard.vel.z)
                // Step position; handle vertical collision against the next
                // solid block in the cube's path.
                val nextPos = shard.pos.add(shard.vel)
                val bounced = tryBounce(level, shard.pos, nextPos, shard)
                if (!bounced) shard.pos = nextPos
                // Rotate ALL THREE body-frame basis vectors by the same
                // Rodrigues rotation — preserves orthonormality (rigid
                // body transform) so the cube tumbles as a whole.
                shard.axisX = rotateAroundAxis(shard.axisX, shard.rotAxis, shard.omega)
                shard.axisY = rotateAroundAxis(shard.axisY, shard.rotAxis, shard.omega)
                shard.axisZ = rotateAroundAxis(shard.axisZ, shard.rotAxis, shard.omega)
            }
        }
    }

    /** If moving from `cur` to `next` crosses a solid block top during the
     *  Y descent, snap the shard's position to that block top and reflect
     *  its Y velocity (dampened by [SHATTER_BOUNCE_DAMP]). Returns true if
     *  a bounce was applied. Horizontal motion is preserved with
     *  [SHATTER_GROUND_FRICTION] friction. */
    private fun tryBounce(level: ClientLevel, cur: Vec3, next: Vec3, shard: Shard): Boolean {
        if (shard.vel.y >= 0.0) return false
        // Scan the Y column from cur.y downward to next.y for a solid block.
        val xi = kotlin.math.floor(next.x).toInt()
        val zi = kotlin.math.floor(next.z).toInt()
        val curY = kotlin.math.floor(cur.y).toInt()
        val nextY = kotlin.math.floor(next.y).toInt()
        val tmpPos = BlockPos.MutableBlockPos()
        for (y in curY downTo nextY) {
            tmpPos.set(xi, y, zi)
            val state = level.getBlockState(tmpPos)
            if (state.isAir) continue
            val topY = (y + 1).toDouble()
            // Only count the block as a floor if `next` actually crosses or
            // sits on its top face. Side-collisions are ignored for simplicity.
            if (next.y <= topY) {
                shard.pos = Vec3(next.x, topY + 0.001, next.z)
                val newVy = (-shard.vel.y) * SHATTER_BOUNCE_DAMP
                shard.vel = Vec3(
                    shard.vel.x * SHATTER_GROUND_FRICTION,
                    newVy,
                    shard.vel.z * SHATTER_GROUND_FRICTION,
                )
                if (newVy < SHATTER_SETTLE_VY) {
                    shard.vel = Vec3.ZERO
                    shard.omega = 0.0
                    shard.settled = true
                }
                return true
            }
        }
        return false
    }

    private fun isWielding(player: Player): Boolean {
        if (!player.isUsingItem) return false
        return player.useItem.item == EKItems.STAFF_OF_AEGIS.get()
    }

    /** Tick: age existing sparkles, retire dead, spawn fresh ones for the
     *  local wielder. No box query here — positions stay in box-local
     *  frame and only get transformed at render time. */
    private fun tickSparkles() {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val player = mc.player ?: return
        val list = sparkles.getOrPut(player.uuid) { mutableListOf() }
        list.removeAll { ++it.age >= it.lifetime }
        if (!isWielding(player)) {
            list.clear()
            return
        }
        val expand = expansionFactor(player, 0f)
        if (expand < 1e-3) return
        val rand = level.random
        repeat(PARTICLES_PER_TICK) {
            val lx = (rand.nextDouble() - 0.5) * AegisBox.WIDTH * expand
            val ly = (rand.nextDouble() - 0.5) * AegisBox.HEIGHT * expand
            val lz = (rand.nextDouble() - 0.5) * AegisBox.VISUAL_DEPTH * expand
            // Pick one of 4 cardinal box-local drift directions uniformly.
            val (vlx, vly) = when (rand.nextInt(4)) {
                0 -> -SPARKLE_DRIFT_SPEED to 0.0          // left
                1 -> SPARKLE_DRIFT_SPEED to 0.0           // right
                2 -> 0.0 to SPARKLE_DRIFT_SPEED           // up
                else -> 0.0 to -SPARKLE_DRIFT_SPEED       // down
            }
            list.add(Sparkle(lx, ly, lz, vlx, vly, 0, 25 + rand.nextInt(15)))
        }
    }

    /** Render every live sparkle as a small camera-facing additive quad,
     *  positioned at its box-local offset transformed through the *current*
     *  Frame (interpolated by [tickDelta]). Because this runs in the same
     *  world-render pass as [renderBox], the sparkles share the box's
     *  sub-tick interpolation — no lag. */
    private fun renderSparkles(
        pose: PoseStack, consumers: MultiBufferSource,
        camX: Double, camY: Double, camZ: Double,
        tickDelta: Float,
    ) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        if (!isWielding(player)) return
        val list = sparkles[player.uuid] ?: return
        if (list.isEmpty()) return

        val frame = AegisBox.forPlayer(player, tickDelta)
        // Current expansion factor scales the per-axis half-extents so a
        // drifted sparkle is clamped to whatever the box visibly is right
        // now — during the expand-out animation the shield's half-size,
        // after fully open the full bounds.
        val expandNow = expansionFactor(player, tickDelta)
        val halfW = AegisBox.WIDTH * 0.5 * expandNow
        val halfH = AegisBox.HEIGHT * 0.5 * expandNow
        val halfD = AegisBox.VISUAL_DEPTH * 0.5 * expandNow
        // Camera-facing axes for billboard quads. Camera.getLeftVector and
        // getUpVector return Vector3f (joml) in world frame.
        val cam = mc.gameRenderer.mainCamera
        val left = cam.leftVector
        val up = cam.upVector
        val rx = -left.x().toDouble()  // right = -left
        val ry = -left.y().toDouble()
        val rz = -left.z().toDouble()
        val ux = up.x().toDouble()
        val uy = up.y().toDouble()
        val uz = up.z().toDouble()

        pose.pushPose()
        pose.translate(-camX, -camY, -camZ)
        val matrix = pose.last().pose()
        val consumer = consumers.getBuffer(AegisRenderType.FACE_GLOW)

        for (s in list) {
            // Drift the box-local position by velocity × elapsed (with
            // sub-tick partial). Keeps tick simulation cheap (no per-tick
            // position writes) while preserving render smoothness. Clamp
            // each axis to the current box half-extents so a sparkle that
            // drifts toward a wall sticks there until it fades out instead
            // of escaping the shield.
            val ageF = s.age + tickDelta.toDouble()
            val driftedLx = (s.lx + s.vlx * ageF).coerceIn(-halfW, halfW)
            val driftedLy = (s.ly + s.vly * ageF).coerceIn(-halfH, halfH)
            val driftedLz = s.lz.coerceIn(-halfD, halfD)
            // World position from drifted box-local frame.
            val px = frame.center.x + frame.axisX.x * driftedLx + frame.axisY.x * driftedLy + frame.axisZ.x * driftedLz
            val py = frame.center.y + frame.axisX.y * driftedLx + frame.axisY.y * driftedLy + frame.axisZ.y * driftedLz
            val pz = frame.center.z + frame.axisX.z * driftedLx + frame.axisY.z * driftedLy + frame.axisZ.z * driftedLz

            // Smooth fade: ramp up over the first 4 ticks (so newly-spawned
            // sparkles don't pop in), hold, then fade out over the last 8.
            val lifeF = s.lifetime.toDouble()
            val fadeIn = (ageF / 4.0).coerceIn(0.0, 1.0)
            val fadeOut = ((lifeF - ageF) / 8.0).coerceIn(0.0, 1.0)
            val alpha = (SPARKLE_ALPHA * fadeIn * fadeOut).toInt().coerceIn(0, 255)
            if (alpha == 0) continue

            val s2 = SPARKLE_HALF_SIZE
            // 4 quad corners around the world position, in the camera's
            // billboard plane (right × up).
            val v0x = px + (-rx + -ux) * s2; val v0y = py + (-ry + -uy) * s2; val v0z = pz + (-rz + -uz) * s2
            val v1x = px + (+rx + -ux) * s2; val v1y = py + (+ry + -uy) * s2; val v1z = pz + (+rz + -uz) * s2
            val v2x = px + (+rx + +ux) * s2; val v2y = py + (+ry + +uy) * s2; val v2z = pz + (+rz + +uz) * s2
            val v3x = px + (-rx + +ux) * s2; val v3y = py + (-ry + +uy) * s2; val v3z = pz + (-rz + +uz) * s2

            consumer.vertex(matrix, v0x.toFloat(), v0y.toFloat(), v0z.toFloat()).color(255, 255, 255, alpha).endVertex()
            consumer.vertex(matrix, v1x.toFloat(), v1y.toFloat(), v1z.toFloat()).color(255, 255, 255, alpha).endVertex()
            consumer.vertex(matrix, v2x.toFloat(), v2y.toFloat(), v2z.toFloat()).color(255, 255, 255, alpha).endVertex()
            consumer.vertex(matrix, v3x.toFloat(), v3y.toFloat(), v3z.toFloat()).color(255, 255, 255, alpha).endVertex()
        }
        pose.popPose()
    }

    /** Fraction of full size to render for `player`'s box this frame. 0 at
     *  the moment the staff was engaged, 1 after [EXPAND_DURATION_TICKS]
     *  game ticks have elapsed. Ease-out cubic: snaps out fast, settles
     *  softly. The tickDelta argument interpolates within the current game
     *  tick so the expansion is smooth at 60+ FPS. */
    private fun expansionFactor(player: Player, tickDelta: Float): Double {
        val stack = player.useItem
        val total = stack.item.getUseDuration(stack)
        val remaining = player.useItemRemainingTicks
        val elapsed = (total - remaining).coerceAtLeast(0).toFloat() + tickDelta
        val progress = (elapsed / EXPAND_DURATION_TICKS).coerceIn(0f, 1f)
        val inv = 1f - progress
        return (1f - inv * inv * inv).toDouble()
    }

    /** Draws the 12-edge wireframe of `player`'s shield as solid lines via
     *  vanilla's `RenderType.lines()`. Lines render depth-tested so the
     *  shield is occluded by world geometry — consistent with the
     *  particles, which are world-objects subject to the same depth.
     *
     *  Frame uses the render-context partial tick so the box interpolates
     *  with the player's smooth render motion (otherwise it would snap to
     *  tick-end position every 1/20s and read as stutter at 60+ FPS). */
    private fun renderBox(
        pose: PoseStack, consumers: MultiBufferSource,
        camX: Double, camY: Double, camZ: Double,
        player: Player,
        tickDelta: Float,
    ) {
        val expand = expansionFactor(player, tickDelta)
        if (expand < 1e-3) return                          // fully collapsed — nothing to draw
        renderFrame(pose, consumers, camX, camY, camZ, AegisBox.forPlayer(player, tickDelta), expand)
    }

    /** Frame-renderer shared by the wielded box and the debug `/aegisbox`
     *  command. Takes a precomputed [AegisBox.Frame] + an expansion factor
     *  so the live wielded path can feed its 0→1 ease-out and the debug
     *  path can pass 1.0 for fully-open static boxes. */
    private fun renderFrame(
        pose: PoseStack, consumers: MultiBufferSource,
        camX: Double, camY: Double, camZ: Double,
        frame: AegisBox.Frame, expand: Double,
    ) {
        // Precompute the 8 expanded corner positions once and share between
        // the glow strips and the wireframe so they stay in lockstep
        // through the expansion animation.
        val center = frame.center
        val corners = Array(8) { i ->
            val raw = frame.corner(i)
            Vec3(
                center.x + (raw.x - center.x) * expand,
                center.y + (raw.y - center.y) * expand,
                center.z + (raw.z - center.z) * expand,
            )
        }

        pose.pushPose()
        pose.translate(-camX, -camY, -camZ)
        val matrix = pose.last().pose()

        // 1. Face glow strips first — additive, no depth write — so the
        //    wireframe lines drawn on top read as the bright "core" of the
        //    glow and the strips fade inward from there.
        renderFaceGlow(consumers.getBuffer(AegisRenderType.FACE_GLOW), matrix, corners)

        // 2. Wireframe edges.
        val lineConsumer = consumers.getBuffer(RenderType.lines())
        val normal = pose.last().normal()
        for (i in 0 until AegisBox.edges.size step 2) {
            val a0 = corners[AegisBox.edges[i]]
            val a1 = corners[AegisBox.edges[i + 1]]
            val dx = (a1.x - a0.x).toFloat()
            val dy = (a1.y - a0.y).toFloat()
            val dz = (a1.z - a0.z).toFloat()
            val invLen = 1.0f / kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-4f)
            val nx = dx * invLen; val ny = dy * invLen; val nz = dz * invLen
            lineConsumer.vertex(matrix, a0.x.toFloat(), a0.y.toFloat(), a0.z.toFloat())
                .color(255, 255, 255, 255)
                .normal(normal, nx, ny, nz)
                .endVertex()
            lineConsumer.vertex(matrix, a1.x.toFloat(), a1.y.toFloat(), a1.z.toFloat())
                .color(255, 255, 255, 255)
                .normal(normal, nx, ny, nz)
                .endVertex()
        }
        pose.popPose()
    }

    /** Emits four trapezoidal "frame" strips per face — one along each edge
     *  — with the outer edge at α=[GLOW_OUTER_ALPHA] and the inner edge at
     *  α=0. Overlapping α at the corners (additive blend) brightens the
     *  corners further, so the four edges read as a continuous luminous
     *  outline that fades inward into the face's middle. */
    private fun renderFaceGlow(consumer: VertexConsumer, matrix: Matrix4f, corners: Array<Vec3>) {
        for (face in FACES) {
            val a = corners[face[0]]
            val b = corners[face[1]]
            val c = corners[face[2]]
            val d = corners[face[3]]
            val cx = (a.x + b.x + c.x + d.x) * 0.25
            val cy = (a.y + b.y + c.y + d.y) * 0.25
            val cz = (a.z + b.z + c.z + d.z) * 0.25
            val ai = lerp(a, cx, cy, cz, GLOW_INSET)
            val bi = lerp(b, cx, cy, cz, GLOW_INSET)
            val ci = lerp(c, cx, cy, cz, GLOW_INSET)
            val di = lerp(d, cx, cy, cz, GLOW_INSET)
            emitStrip(consumer, matrix, a, b, bi, ai)
            emitStrip(consumer, matrix, b, c, ci, bi)
            emitStrip(consumer, matrix, c, d, di, ci)
            emitStrip(consumer, matrix, d, a, ai, di)
        }
    }

    /** One trapezoidal strip: outer corners (α=[GLOW_OUTER_ALPHA]) → inner
     *  corners (α=0) → linear gradient across the strip. QUADS-mode vertex
     *  order. */
    private fun emitStrip(
        consumer: VertexConsumer, matrix: Matrix4f,
        outerA: Vec3, outerB: Vec3, innerB: Vec3, innerA: Vec3,
    ) {
        glowVertex(consumer, matrix, outerA, GLOW_OUTER_ALPHA)
        glowVertex(consumer, matrix, outerB, GLOW_OUTER_ALPHA)
        glowVertex(consumer, matrix, innerB, 0)
        glowVertex(consumer, matrix, innerA, 0)
    }

    private fun glowVertex(consumer: VertexConsumer, matrix: Matrix4f, p: Vec3, alpha: Int) {
        consumer.vertex(matrix, p.x.toFloat(), p.y.toFloat(), p.z.toFloat())
            .color(255, 255, 255, alpha)
            .endVertex()
    }

    private fun lerp(p: Vec3, cx: Double, cy: Double, cz: Double, t: Double): Vec3 = Vec3(
        p.x + (cx - p.x) * t,
        p.y + (cy - p.y) * t,
        p.z + (cz - p.z) * t,
    )

    /** Renders all active shatter animations. Each cube-shaped shard
     *  emits 6 face quads (additive glow, no depth write) + a 12-edge
     *  wireframe through [RenderType.lines] so every cube reads as a
     *  miniature copy of the original shield. State is interpolated from
     *  the previous tick for sub-tick smoothness.
     *
     *  Two-pass: all glow faces first, then all wireframes. We can't hold
     *  cached buffer references across [MultiBufferSource.getBuffer] calls
     *  for different render types — the second call auto-ends the first
     *  type's batch, leaving a stale `VertexConsumer` reference that
     *  crashes on `endVertex()` ("Not filled all elements of the vertex"). */
    private fun renderShatters(
        pose: PoseStack, consumers: MultiBufferSource,
        camX: Double, camY: Double, camZ: Double,
        tickDelta: Float,
    ) {
        if (shatters.isEmpty()) return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return

        pose.pushPose()
        pose.translate(-camX, -camY, -camZ)
        val matrix = pose.last().pose()
        val normal = pose.last().normal()
        val t = tickDelta.toDouble()
        val corners = Array(8) { Vec3.ZERO }

        // Pass 1: glow faces for every cube in every active state.
        val glowConsumer = consumers.getBuffer(AegisRenderType.FACE_GLOW)
        for ((_, state) in shatters) {
            val faceAlpha = shatterFaceAlpha(state, level, tickDelta)
            if (faceAlpha == 0) continue
            for (shard in state.shards) {
                computeShardCorners(shard, t, corners)
                emitCubeFaces(glowConsumer, matrix, corners, faceAlpha)
            }
        }

        // Pass 2: wireframes. Calling getBuffer(lines) here ends the glow
        // batch above — but we never touch glowConsumer again so that's safe.
        val lineConsumer = consumers.getBuffer(RenderType.lines())
        for ((_, state) in shatters) {
            val edgeAlpha = shatterEdgeAlpha(state, level, tickDelta)
            if (edgeAlpha == 0) continue
            for (shard in state.shards) {
                computeShardCorners(shard, t, corners)
                emitCubeWireframe(lineConsumer, matrix, normal, corners, edgeAlpha)
            }
        }

        pose.popPose()
    }

    /** Linear α fade scaled by [GLOW_OUTER_ALPHA] for the glow faces. */
    private fun shatterFaceAlpha(state: ShatterState, level: ClientLevel, tickDelta: Float): Int {
        val elapsed = (level.gameTime - state.startGameTime).toFloat() + tickDelta
        if (elapsed >= SHATTER_DURATION_TICKS) return 0
        val progress = (elapsed / SHATTER_DURATION_TICKS).coerceIn(0f, 1f)
        return (GLOW_OUTER_ALPHA * (1f - progress)).toInt().coerceIn(0, 255)
    }

    /** Linear α fade across the full 0..255 range for the wireframe lines. */
    private fun shatterEdgeAlpha(state: ShatterState, level: ClientLevel, tickDelta: Float): Int {
        val elapsed = (level.gameTime - state.startGameTime).toFloat() + tickDelta
        if (elapsed >= SHATTER_DURATION_TICKS) return 0
        val progress = (elapsed / SHATTER_DURATION_TICKS).coerceIn(0f, 1f)
        return ((1f - progress) * 255f).toInt().coerceIn(0, 255)
    }

    /** Fill [out] with one shard's 8 world-space corners at sub-tick
     *  interpolation `t`. Mutates `out` in place — caller reuses a single
     *  allocation across all shards in a frame. */
    private fun computeShardCorners(shard: Shard, t: Double, out: Array<Vec3>) {
        val center = lerpVec(shard.lastPos, shard.pos, t)
        val ax = lerpAndRenormalize(shard.lastAxisX, shard.axisX, t)
        val ay = lerpAndRenormalize(shard.lastAxisY, shard.axisY, t)
        val az = lerpAndRenormalize(shard.lastAxisZ, shard.axisZ, t)
        for (idx in 0 until 8) {
            val sx = if (idx and 1 != 0) shard.halfEdge else -shard.halfEdge
            val sy = if (idx and 2 != 0) shard.halfEdge else -shard.halfEdge
            val sz = if (idx and 4 != 0) shard.halfEdge else -shard.halfEdge
            out[idx] = Vec3(
                center.x + ax.x * sx + ay.x * sy + az.x * sz,
                center.y + ax.y * sx + ay.y * sy + az.y * sz,
                center.z + ax.z * sx + ay.z * sy + az.z * sz,
            )
        }
    }

    /** Submit the 6 face quads of one cube from its precomputed corners
     *  array. Each face's 4 corners are emitted at uniform [alpha] —
     *  corner-brightness emerges from the additive blend of three
     *  orthogonal faces meeting at every corner. */
    private fun emitCubeFaces(
        consumer: VertexConsumer, matrix: Matrix4f,
        corners: Array<Vec3>, alpha: Int,
    ) {
        for (face in CUBE_FACES) {
            var i = 0
            while (i < 12) {
                // Decode (sx, sy, sz) triplet into the 3-bit corner index
                // used to address [corners]: bit 0 = +X, bit 1 = +Y, bit 2 = +Z.
                var idx = 0
                if (face[i] > 0) idx = idx or 1
                if (face[i + 1] > 0) idx = idx or 2
                if (face[i + 2] > 0) idx = idx or 4
                val p = corners[idx]
                consumer.vertex(matrix, p.x.toFloat(), p.y.toFloat(), p.z.toFloat())
                    .color(255, 255, 255, alpha)
                    .endVertex()
                i += 3
            }
        }
    }

    /** Submit the 12 wireframe edges of one cube using its precomputed
     *  corners. Same pattern as the live shield's wireframe pass. */
    private fun emitCubeWireframe(
        consumer: VertexConsumer, matrix: Matrix4f, normal: org.joml.Matrix3f,
        corners: Array<Vec3>, alpha: Int,
    ) {
        var i = 0
        while (i < CUBE_EDGES.size) {
            val a0 = corners[CUBE_EDGES[i]]
            val a1 = corners[CUBE_EDGES[i + 1]]
            val dx = (a1.x - a0.x).toFloat()
            val dy = (a1.y - a0.y).toFloat()
            val dz = (a1.z - a0.z).toFloat()
            val invLen = 1.0f / kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-4f)
            val nx = dx * invLen; val ny = dy * invLen; val nz = dz * invLen
            consumer.vertex(matrix, a0.x.toFloat(), a0.y.toFloat(), a0.z.toFloat())
                .color(255, 255, 255, alpha)
                .normal(normal, nx, ny, nz)
                .endVertex()
            consumer.vertex(matrix, a1.x.toFloat(), a1.y.toFloat(), a1.z.toFloat())
                .color(255, 255, 255, alpha)
                .normal(normal, nx, ny, nz)
                .endVertex()
            i += 2
        }
    }

    private fun lerpVec(a: Vec3, b: Vec3, t: Double): Vec3 = Vec3(
        a.x + (b.x - a.x) * t,
        a.y + (b.y - a.y) * t,
        a.z + (b.z - a.z) * t,
    )

    /** Linear lerp + renormalize for unit vectors. Cheap stand-in for slerp
     *  — accurate enough for the small per-tick rotations the shatter
     *  produces. */
    private fun lerpAndRenormalize(a: Vec3, b: Vec3, t: Double): Vec3 {
        val v = lerpVec(a, b, t)
        val len2 = v.x * v.x + v.y * v.y + v.z * v.z
        if (len2 < 1e-12) return a
        val inv = 1.0 / kotlin.math.sqrt(len2)
        return Vec3(v.x * inv, v.y * inv, v.z * inv)
    }

    /** Rodrigues' rotation: rotate `v` around unit `axis` by `theta`. */
    private fun rotateAroundAxis(v: Vec3, axis: Vec3, theta: Double): Vec3 {
        val cosT = kotlin.math.cos(theta)
        val sinT = kotlin.math.sin(theta)
        val k = axis  // assumed unit
        val kxv = k.cross(v)
        val kDotV = k.dot(v)
        return Vec3(
            v.x * cosT + kxv.x * sinT + k.x * kDotV * (1 - cosT),
            v.y * cosT + kxv.y * sinT + k.y * kDotV * (1 - cosT),
            v.z * cosT + kxv.z * sinT + k.z * kDotV * (1 - cosT),
        )
    }
}
