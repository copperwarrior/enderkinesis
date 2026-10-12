package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import dev.architectury.event.events.client.ClientTickEvent
import java.util.concurrent.CopyOnWriteArrayList
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.joml.Quaternionf

/** Cube-shard shatter for a broken Ga'Mylan cage. Same shape as
 *  [AegisClient]'s shatter — N cubes scatter inside the cage's local
 *  AABB, each tumbles around a random axis under gravity, bounces off
 *  the first solid block below it, and settles when its post-bounce Y
 *  velocity falls below a threshold. Faces use the cage's translucent
 *  gradient + thick line outline (so debris reads as the cage's
 *  signature glass), with the Wohlon-blue palette for the crystal cage
 *  and orange for the Tribulla bottle. */
object GamylanCageShatter {

    /** Cubes spawned per shatter. ~Aegis's count, scaled down to match
     *  the cage's smaller volume relative to a player's shield. */
    private const val SHATTER_CUBE_COUNT: Int = 16

    /** Half-edge range per cube — random uniform. */
    private const val SHATTER_CUBE_HALF_EDGE_MIN: Double = 0.05
    private const val SHATTER_CUBE_HALF_EDGE_MAX: Double = 0.14

    /** Initial outward radial speed (blocks/tick). */
    private const val SHATTER_OUTWARD_SPEED: Double = 0.12
    /** Initial vertical kick — small puff up before gravity wins. */
    private const val SHATTER_UPWARD_KICK: Double = 0.05
    /** Per-tick tumble rate range (radians). */
    private const val SHATTER_ROT_RATE: Double = 0.35

    /** Gravity in blocks/tick² applied to each unsettled shard. */
    private const val SHATTER_GRAVITY: Double = 0.04
    /** Energy retained on each Y bounce (`-1 .. 1`). */
    private const val SHATTER_BOUNCE_DAMP: Double = 0.40
    /** Horizontal friction multiplier per bounce contact. */
    private const val SHATTER_GROUND_FRICTION: Double = 0.6
    /** Post-bounce |vy| below which the shard freezes in place. */
    private const val SHATTER_SETTLE_VY: Double = 0.05

    /** Fade duration. 80 ticks ≈ 4 s — enough to see the cloud land,
     *  short enough that the world isn't littered with bottle bits. */
    private const val SHATTER_DURATION_TICKS: Long = 80L

    /** Per-face corner triplets (sx, sy, sz) in `±1` form. Multiplied
     *  by `halfEdge × axis*` to get world corners on a tilted cube. */
    private val CUBE_FACES: Array<IntArray> = arrayOf(
        intArrayOf(-1, -1, -1,  -1, -1, +1,  -1, +1, +1,  -1, +1, -1),  // -X
        intArrayOf(+1, -1, -1,  +1, +1, -1,  +1, +1, +1,  +1, -1, +1),  // +X
        intArrayOf(-1, -1, -1,  +1, -1, -1,  +1, -1, +1,  -1, -1, +1),  // -Y
        intArrayOf(-1, +1, -1,  -1, +1, +1,  +1, +1, +1,  +1, +1, -1),  // +Y
        intArrayOf(-1, -1, -1,  -1, +1, -1,  +1, +1, -1,  +1, -1, -1),  // -Z
        intArrayOf(-1, -1, +1,  +1, -1, +1,  +1, +1, +1,  -1, +1, +1),  // +Z
    )

    /** 12 edges of a cube — index pairs into a per-shard corner array. */
    private val CUBE_EDGES: IntArray = intArrayOf(
        0, 1, 2, 3, 4, 5, 6, 7,    // X-aligned
        0, 2, 1, 3, 4, 6, 5, 7,    // Y-aligned
        0, 4, 1, 5, 2, 6, 3, 7,    // Z-aligned
    )

    /** Outer face alpha at fade=0 — matches the cage crystal's
     *  GLOW_OUTER_ALPHA so shatter debris reads as the same surface. */
    private const val GLOW_OUTER_ALPHA: Float = 200f

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

    private data class ShatterState(
        val shards: List<Shard>,
        val startGameTime: Long,
        val faceR: Int, val faceG: Int, val faceB: Int,
        val lineR: Int, val lineG: Int, val lineB: Int,
    )

    /** CoW so the render loop can iterate without locking against
     *  spawn / tick mutations. */
    private val states: MutableList<ShatterState> = CopyOnWriteArrayList()

    @Volatile
    private var ticking: Boolean = false

    fun init() {
        if (ticking) return
        ticking = true
        ClientTickEvent.CLIENT_LEVEL_POST.register { level -> tick(level) }
    }

    fun isEmpty(): Boolean = states.isEmpty()

    /** Seed a shatter at the given world position. `axisX/Y/Z` give the
     *  body's initial basis (so shards inherit cage orientation), and
     *  `halfX/Y/Z` are the half-extents of the cage's local AABB
     *  (already scaled by `liveScale` from the caller). */
    fun spawn(
        worldX: Double, worldY: Double, worldZ: Double,
        halfX: Double, halfY: Double, halfZ: Double,
        rotation: Quaternionf,
        faceR: Int, faceG: Int, faceB: Int,
        lineR: Int, lineG: Int, lineB: Int,
        count: Int = SHATTER_CUBE_COUNT,
    ) {
        val level = Minecraft.getInstance().level ?: return
        val rand = level.random

        // Body basis in world frame.
        val bx = org.joml.Vector3f(1f, 0f, 0f); rotation.transform(bx)
        val by = org.joml.Vector3f(0f, 1f, 0f); rotation.transform(by)
        val bz = org.joml.Vector3f(0f, 0f, 1f); rotation.transform(bz)
        val axisX = Vec3(bx.x.toDouble(), bx.y.toDouble(), bx.z.toDouble())
        val axisY = Vec3(by.x.toDouble(), by.y.toDouble(), by.z.toDouble())
        val axisZ = Vec3(bz.x.toDouble(), bz.y.toDouble(), bz.z.toDouble())

        val centre = Vec3(worldX, worldY, worldZ)
        val shards = ArrayList<Shard>(count)
        repeat(count) {
            // Box-local random position, then push through the body
            // basis so shards spawn inside the (possibly tumbled) cage.
            val lx = (rand.nextDouble() * 2 - 1) * halfX
            val ly = (rand.nextDouble() * 2 - 1) * halfY
            val lz = (rand.nextDouble() * 2 - 1) * halfZ
            val sp = Vec3(
                centre.x + axisX.x * lx + axisY.x * ly + axisZ.x * lz,
                centre.y + axisX.y * lx + axisY.y * ly + axisZ.y * lz,
                centre.z + axisX.z * lx + axisY.z * ly + axisZ.z * lz,
            )

            // Radial outward velocity + a small upward kick.
            val outRaw = sp.subtract(centre)
            val outward = if (outRaw.lengthSqr() < 1e-6) axisY else outRaw.normalize()
            val vel = outward.scale(SHATTER_OUTWARD_SPEED).add(Vec3(0.0, SHATTER_UPWARD_KICK, 0.0))

            // Random unit tumble axis.
            var rax: Double; var ray: Double; var raz: Double
            do {
                rax = rand.nextDouble() * 2 - 1
                ray = rand.nextDouble() * 2 - 1
                raz = rand.nextDouble() * 2 - 1
            } while (rax * rax + ray * ray + raz * raz < 1e-6)
            val rl = kotlin.math.sqrt(rax * rax + ray * ray + raz * raz)
            val rotAxis = Vec3(rax / rl, ray / rl, raz / rl)
            val omega = (rand.nextDouble() * 2 - 1) * SHATTER_ROT_RATE

            val halfEdge = SHATTER_CUBE_HALF_EDGE_MIN +
                rand.nextDouble() * (SHATTER_CUBE_HALF_EDGE_MAX - SHATTER_CUBE_HALF_EDGE_MIN)

            shards.add(
                Shard(
                    halfEdge = halfEdge,
                    pos = sp,
                    vel = vel,
                    axisX = axisX,
                    axisY = axisY,
                    axisZ = axisZ,
                    rotAxis = rotAxis,
                    omega = omega,
                ),
            )
        }
        states.add(
            ShatterState(
                shards = shards,
                startGameTime = level.gameTime,
                faceR = faceR, faceG = faceG, faceB = faceB,
                lineR = lineR, lineG = lineG, lineB = lineB,
            ),
        )
    }

    private fun tick(level: ClientLevel) {
        if (states.isEmpty()) return
        val now = level.gameTime
        val iter = states.iterator()
        val expired = ArrayList<ShatterState>(0)
        while (iter.hasNext()) {
            val state = iter.next()
            if (now - state.startGameTime >= SHATTER_DURATION_TICKS) {
                expired.add(state)
                continue
            }
            for (shard in state.shards) {
                shard.lastPos = shard.pos
                shard.lastAxisX = shard.axisX
                shard.lastAxisY = shard.axisY
                shard.lastAxisZ = shard.axisZ
                if (shard.settled) continue
                shard.vel = Vec3(shard.vel.x, shard.vel.y - SHATTER_GRAVITY, shard.vel.z)
                val nextPos = shard.pos.add(shard.vel)
                val bounced = tryBounce(level, shard.pos, nextPos, shard)
                if (!bounced) shard.pos = nextPos
                shard.axisX = rotateAroundAxis(shard.axisX, shard.rotAxis, shard.omega)
                shard.axisY = rotateAroundAxis(shard.axisY, shard.rotAxis, shard.omega)
                shard.axisZ = rotateAroundAxis(shard.axisZ, shard.rotAxis, shard.omega)
            }
        }
        if (expired.isNotEmpty()) states.removeAll(expired)
    }

    private fun tryBounce(level: ClientLevel, cur: Vec3, next: Vec3, shard: Shard): Boolean {
        if (shard.vel.y >= 0.0) return false
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

    /** Rodrigues rotation of `v` around unit-length `axis` by `theta`. */
    private fun rotateAroundAxis(v: Vec3, axis: Vec3, theta: Double): Vec3 {
        if (theta == 0.0) return v
        val cos = kotlin.math.cos(theta)
        val sin = kotlin.math.sin(theta)
        val dot = v.x * axis.x + v.y * axis.y + v.z * axis.z
        val crossX = axis.y * v.z - axis.z * v.y
        val crossY = axis.z * v.x - axis.x * v.z
        val crossZ = axis.x * v.y - axis.y * v.x
        return Vec3(
            v.x * cos + crossX * sin + axis.x * dot * (1 - cos),
            v.y * cos + crossY * sin + axis.y * dot * (1 - cos),
            v.z * cos + crossZ * sin + axis.z * dot * (1 - cos),
        )
    }

    fun render(poseStack: PoseStack, camera: Camera, partialTick: Float) {
        if (states.isEmpty()) return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val buffer = mc.renderBuffers().bufferSource()
        val camPos = camera.position

        poseStack.pushPose()
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z)
        val matrix = poseStack.last().pose()
        val normal = poseStack.last().normal()
        val t = partialTick.toDouble()
        val corners = Array(8) { Vec3.ZERO }

        // Pass 1: translucent face glow for every cube.
        val faceConsumer = buffer.getBuffer(GamylanCageRenderType.FACE_GLOW)
        for (state in states) {
            val faceAlpha = faceAlphaFor(state, level, partialTick)
            if (faceAlpha == 0) continue
            for (shard in state.shards) {
                computeShardCorners(shard, t, corners)
                emitCubeFaces(faceConsumer, matrix, corners, state.faceR, state.faceG, state.faceB, faceAlpha)
            }
        }
        // Pass 2: line wireframes — caching faceConsumer would be stale
        // here, the fallback builder reformats on getBuffer for a new
        // render type. We never touch faceConsumer after this point.
        val lineConsumer = buffer.getBuffer(GamylanCageRenderType.LINES)
        for (state in states) {
            val edgeAlpha = edgeAlphaFor(state, level, partialTick)
            if (edgeAlpha == 0) continue
            for (shard in state.shards) {
                computeShardCorners(shard, t, corners)
                emitCubeEdges(lineConsumer, matrix, normal, corners, state.lineR, state.lineG, state.lineB, edgeAlpha)
            }
        }

        poseStack.popPose()
    }

    private fun faceAlphaFor(state: ShatterState, level: ClientLevel, partialTick: Float): Int {
        val elapsed = (level.gameTime - state.startGameTime).toFloat() + partialTick
        if (elapsed >= SHATTER_DURATION_TICKS) return 0
        val progress = (elapsed / SHATTER_DURATION_TICKS).coerceIn(0f, 1f)
        return (GLOW_OUTER_ALPHA * (1f - progress)).toInt().coerceIn(0, 255)
    }

    private fun edgeAlphaFor(state: ShatterState, level: ClientLevel, partialTick: Float): Int {
        val elapsed = (level.gameTime - state.startGameTime).toFloat() + partialTick
        if (elapsed >= SHATTER_DURATION_TICKS) return 0
        val progress = (elapsed / SHATTER_DURATION_TICKS).coerceIn(0f, 1f)
        return ((1f - progress) * 255f).toInt().coerceIn(0, 255)
    }

    private fun computeShardCorners(shard: Shard, t: Double, out: Array<Vec3>) {
        val centre = lerpVec(shard.lastPos, shard.pos, t)
        val ax = lerpAndRenormalize(shard.lastAxisX, shard.axisX, t)
        val ay = lerpAndRenormalize(shard.lastAxisY, shard.axisY, t)
        val az = lerpAndRenormalize(shard.lastAxisZ, shard.axisZ, t)
        for (idx in 0 until 8) {
            val sx = if (idx and 1 != 0) shard.halfEdge else -shard.halfEdge
            val sy = if (idx and 2 != 0) shard.halfEdge else -shard.halfEdge
            val sz = if (idx and 4 != 0) shard.halfEdge else -shard.halfEdge
            out[idx] = Vec3(
                centre.x + ax.x * sx + ay.x * sy + az.x * sz,
                centre.y + ax.y * sx + ay.y * sy + az.y * sz,
                centre.z + ax.z * sx + ay.z * sy + az.z * sz,
            )
        }
    }

    private fun emitCubeFaces(
        v: VertexConsumer, matrix: Matrix4f,
        corners: Array<Vec3>,
        r: Int, g: Int, b: Int, alpha: Int,
    ) {
        for (face in CUBE_FACES) {
            var i = 0
            while (i < 12) {
                var idx = 0
                if (face[i] > 0) idx = idx or 1
                if (face[i + 1] > 0) idx = idx or 2
                if (face[i + 2] > 0) idx = idx or 4
                val c = corners[idx]
                v.vertex(matrix, c.x.toFloat(), c.y.toFloat(), c.z.toFloat())
                    .color(r, g, b, alpha).endVertex()
                i += 3
            }
        }
    }

    private fun emitCubeEdges(
        v: VertexConsumer, matrix: Matrix4f, normal: Matrix3f,
        corners: Array<Vec3>,
        r: Int, g: Int, b: Int, alpha: Int,
    ) {
        var i = 0
        while (i < CUBE_EDGES.size) {
            val a = corners[CUBE_EDGES[i]]
            val b2 = corners[CUBE_EDGES[i + 1]]
            val dx = (b2.x - a.x).toFloat()
            val dy = (b2.y - a.y).toFloat()
            val dz = (b2.z - a.z).toFloat()
            val invLen = 1f / kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-4f)
            val nx = dx * invLen; val ny = dy * invLen; val nz = dz * invLen
            v.vertex(matrix, a.x.toFloat(), a.y.toFloat(), a.z.toFloat())
                .color(r, g, b, alpha).normal(normal, nx, ny, nz).endVertex()
            v.vertex(matrix, b2.x.toFloat(), b2.y.toFloat(), b2.z.toFloat())
                .color(r, g, b, alpha).normal(normal, nx, ny, nz).endVertex()
            i += 2
        }
    }

    private fun lerpVec(a: Vec3, b: Vec3, t: Double): Vec3 = Vec3(
        a.x + (b.x - a.x) * t,
        a.y + (b.y - a.y) * t,
        a.z + (b.z - a.z) * t,
    )

    private fun lerpAndRenormalize(a: Vec3, b: Vec3, t: Double): Vec3 {
        val mixed = lerpVec(a, b, t)
        val l = mixed.length()
        return if (l < 1e-9) b else mixed.scale(1.0 / l)
    }

    /** In singleplayer the JVM lives across worlds, and VS2 ids start again in each save. */
    fun clearSession() {
        states.clear()
    }
}
