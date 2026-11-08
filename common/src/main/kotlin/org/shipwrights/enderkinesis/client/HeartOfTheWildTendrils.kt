package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.Util
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector3d
import org.joml.Vector3f
import org.shipwrights.enderkinesis.block.HeartOfTheWildBlock
import org.shipwrights.enderkinesis.blockentity.HeartOfTheWildBlockEntity
import org.shipwrights.enderkinesis.blockentity.HeartOfTheWildClientRegistry
import org.shipwrights.enderkinesis.client.model.HeartOfTheWildModel
import org.shipwrights.enderkinesis.registry.EKSounds
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.mod.common.getShipManagingPos
import java.util.IdentityHashMap
import org.valkyrienskies.core.api.util.GameTickOnly

/**
 * Tendrils from each artery of a Heart of the Wild to the nearest solid block above it. An artery with no
 * block in reach has no tendril. Client thread only.
 */
@OptIn(GameTickOnly::class)
object HeartOfTheWildTendrils {

    private const val TRACK_RANGE_SQR = 64.0 * 64.0
    private const val DRAW_RANGE = 48.0
    private const val FADE_RANGE = 8.0
    private const val MAX_HEARTS = 8
    private const val CHECK_TICKS = 20L
    private const val WAIT_TICKS = 5L

    // Ray search, in blocks for a heart of size 1. The cone leans up from the artery and tilts at random.
    private const val REACH = 6.0
    private const val MIN_LENGTH = 0.4
    private const val RAYS = 10
    private const val UP_BIAS = 1.5
    private const val AXIS_TILT = 0.35
    private const val CONE_SPREAD = 0.8
    private const val MIN_RISE = 0.2
    private const val ANCHOR_DEPTH = 0.08

    private const val BOW = 0.6
    private const val LEAD = 0.35
    private const val WILD_PER_BLOCK = 0.09
    private const val WILD_MAX = 0.8
    private const val GROW_MILLIS = 1200L
    private const val RADIUS = 0.09f
    private const val GROW_VOLUME = 0.6f

    private class Tendril(val curve: TendrilCurve, val target: BlockPos, val grownAtMillis: Long)

    private class Heart(val be: HeartOfTheWildBlockEntity) {
        val pos: BlockPos = be.blockPos
        val frame = TendrilFrame(Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5))
        val tips = Array(HeartOfTheWildModel.ARTERY_COUNT) { Vector3d() }
        val dirs = Array(HeartOfTheWildModel.ARTERY_COUNT) { Vector3d() }
        val slots = arrayOfNulls<Tendril>(HeartOfTheWildModel.ARTERY_COUNT)
        val phase = Math.floorMod(HeartOfTheWildRenderer.hashPos(pos), CHECK_TICKS)
        var size = 1f
        var style = styleFor(HeartOfTheWildRenderer.modelScale(false, 1f))
        var tipsValid = false
        var scanned = false
        var nextCheckTick = 0L
        var ship: ClientShip? = null

        fun hasTendrils(): Boolean = slots.any { it != null }
    }

    private val hearts = IdentityHashMap<HeartOfTheWildBlockEntity, Heart>()
    private val selected = arrayOfNulls<Heart>(MAX_HEARTS)
    private val selectedDistSqr = DoubleArray(MAX_HEARTS)
    private val tipStack = PoseStack()
    private val tipPose = Matrix4f()
    private val tipPos = Vector3f()
    private val tipDir = Vector3f()
    private val scratch = Vector3d()

    private fun styleFor(modelScale: Float) =
        TendrilStyle(RADIUS * modelScale, WohlonRitualTendrils.HEART_COLOUR, WohlonRitualTendrils.ROOT_COLOUR)

    fun init() {
        ClientTickEvent.CLIENT_LEVEL_POST.register(::tick)
    }

    fun onDisconnect() = hearts.clear()

    /** Called by the renderer after it poses [model] for [be], so the tendrils follow the beat. */
    fun captureTips(be: HeartOfTheWildBlockEntity, model: HeartOfTheWildModel, mother: Boolean, size: Float, yawRad: Float) {
        val heart = hearts[be] ?: return
        if (size != heart.size) {
            heart.size = size
            heart.style = styleFor(HeartOfTheWildRenderer.modelScale(mother, size))
        }
        tipStack.setIdentity()
        HeartOfTheWildRenderer.poseHeart(tipStack, mother, size, yawRad)
        for (i in 0 until HeartOfTheWildModel.ARTERY_COUNT) {
            tipPose.set(tipStack.last().pose())
            model.arteryTip(i, tipPose, tipPos, tipDir)
            heart.tips[i].set(heart.pos.x + tipPos.x.toDouble(), heart.pos.y + tipPos.y.toDouble(), heart.pos.z + tipPos.z.toDouble())
            heart.dirs[i].set(tipDir.x.toDouble(), tipDir.y.toDouble(), tipDir.z.toDouble())
        }
        heart.tipsValid = true
    }

    private fun tick(level: ClientLevel) {
        val player = Minecraft.getInstance().player ?: return
        hearts.values.removeIf { it.be.isRemoved || it.be.level !== level }
        for (be in HeartOfTheWildClientRegistry.all(level)) {
            if (be.isRemoved) continue
            val ship = level.getShipManagingPos(be.blockPos) as? ClientShip
            worldCentre(be.blockPos, ship)
            if (player.distanceToSqr(scratch.x, scratch.y, scratch.z) > TRACK_RANGE_SQR) {
                hearts.remove(be)
                continue
            }
            hearts.getOrPut(be) { Heart(be) }.ship = ship
        }
        val gameTime = level.gameTime
        for (heart in hearts.values) {
            if (gameTime >= heart.nextCheckTick) check(level, heart, gameTime)
        }
    }

    private fun check(level: ClientLevel, heart: Heart, gameTime: Long) {
        val now = Util.getMillis()
        if (!heart.tipsValid || HeartOfTheWildRenderer.isGrowing(heart.be, now)) {
            heart.nextCheckTick = gameTime + WAIT_TICKS
            return
        }
        // A heart that loads with its chunk shows its tendrils grown; one the ritual made grows them now.
        val grownAt = if (!heart.scanned && heart.be.growStartMillis == HeartOfTheWildBlockEntity.NOT_GROWN) {
            now - GROW_MILLIS
        } else {
            now
        }
        for (i in heart.slots.indices) {
            val old = heart.slots[i]
            if (old != null && isSolid(level, old.target, heart.pos)) continue
            val grown = scan(level, heart, i, grownAt)
            heart.slots[i] = grown
            if (grown != null && grownAt == now) playGrow(level, heart, i)
        }
        heart.scanned = true
        heart.nextCheckTick = gameTime - gameTime % CHECK_TICKS + CHECK_TICKS + heart.phase
    }

    private fun playGrow(level: ClientLevel, heart: Heart, index: Int) {
        scratch.set(heart.tips[index])
        heart.ship?.transform?.shipToWorld?.transformPosition(scratch)
        level.playLocalSound(
            scratch.x, scratch.y, scratch.z, EKSounds.HEART_OF_THE_WILD_TENDRIL_GROW.get(), SoundSource.BLOCKS,
            GROW_VOLUME, 1f, false,
        )
    }

    private fun isSolid(level: ClientLevel, pos: BlockPos, heartPos: BlockPos): Boolean {
        val state = level.getBlockState(pos)
        return state.block !is HeartOfTheWildBlock && !state.getCollisionShape(level, pos).isEmpty && pos != heartPos
    }

    private fun scan(level: ClientLevel, heart: Heart, index: Int, grownAt: Long): Tendril? {
        val tip = heart.tips[index]
        val dir = heart.dirs[index]
        // The seed is fixed for each artery, so a heart picks the same targets each time it loads.
        val random = RandomSource.create(HeartOfTheWildRenderer.hashPos(heart.pos) * 31L + index)
        val axis = Vector3d(dir.x, maxOf(dir.y, 0.0) + UP_BIAS, dir.z).normalize()
        jitter(axis, random, AXIS_TILT)
        val range = REACH * heart.size
        val from = Vec3(tip.x, tip.y, tip.z)
        val ray = Vector3d()
        var best: BlockHitResult? = null
        var bestDist = Double.MAX_VALUE
        repeat(RAYS) {
            jitter(ray.set(axis), random, CONE_SPREAD)
            if (ray.y < MIN_RISE) return@repeat
            val hit = clip(level, from, from.add(ray.x * range, ray.y * range, ray.z * range), heart.pos) ?: return@repeat
            val dist = hit.location.distanceTo(from)
            if (dist < bestDist) {
                best = hit
                bestDist = dist
            }
        }
        val hit = best ?: return null
        if (bestDist < MIN_LENGTH * heart.size) return null
        return Tendril(curve(tip, dir, hit, from, random), hit.blockPos.immutable(), grownAt)
    }

    private fun curve(tip: Vector3d, dir: Vector3d, hit: BlockHitResult, from: Vec3, random: RandomSource): TendrilCurve {
        val toHit = hit.location.subtract(from).normalize()
        val end = Vector3d(
            hit.location.x + toHit.x * ANCHOR_DEPTH, hit.location.y + toHit.y * ANCHOR_DEPTH, hit.location.z + toHit.z * ANCHOR_DEPTH,
        )
        val length = tip.distance(end)
        // Leave along the artery, then bow to one side on the way to the block.
        val side = Vector3d(end).sub(tip).cross(0.0, 1.0, 0.0)
        if (side.lengthSquared() < 1.0e-6) side.set(1.0, 0.0, 0.0) else side.normalize()
        val bow = (random.nextDouble() - 0.5) * BOW * length
        val control = Vector3d(tip).add(end).mul(0.5)
            .fma(LEAD * length, dir)
            .fma(bow, side)
        return TendrilCurve(
            tip, control, end,
            wild = minOf(WILD_MAX, WILD_PER_BLOCK * length) * (0.7 + 0.6 * random.nextDouble()),
            seed = random.nextDouble() * 100.0,
        )
    }

    private fun jitter(v: Vector3d, random: RandomSource, amount: Double) {
        v.add((random.nextDouble() * 2.0 - 1.0) * amount, (random.nextDouble() * 2.0 - 1.0) * amount, (random.nextDouble() * 2.0 - 1.0) * amount)
            .normalize()
    }

    // Not level.clip: VS2 patches it to return ship hits in mixed frames. This stays in the heart's own frame.
    private fun clip(level: ClientLevel, from: Vec3, to: Vec3, heartPos: BlockPos): BlockHitResult? =
        BlockGetter.traverseBlocks(from, to, level, { lvl, pos ->
            val state = lvl.getBlockState(pos)
            if (pos == heartPos || state.block is HeartOfTheWildBlock) null
            else state.getCollisionShape(lvl, pos).clip(from, to, pos)
        }, { null })

    private fun worldCentre(pos: BlockPos, ship: ClientShip?) {
        scratch.set(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        ship?.renderTransform?.shipToWorld?.transformPosition(scratch)
    }

    // Called with the other world-space volumes, before the fog passes.
    @JvmStatic
    fun render(poseStack: PoseStack, projection: Matrix4f, camera: Camera) {
        val count = selectNearest(camera.position)
        if (count == 0 || !TendrilVolume.begin(poseStack, projection, camera)) return
        val now = Util.getMillis()
        for (n in 0 until count) {
            val heart = selected[n] ?: continue
            selected[n] = null
            heart.frame.toWorld = heart.ship?.renderTransform?.shipToWorld
            val dist = Math.sqrt(selectedDistSqr[n])
            val strength = ((DRAW_RANGE - dist) / FADE_RANGE).coerceIn(0.0, 1.0).toFloat()
            for (t in heart.slots) {
                if (t == null) continue
                TendrilVolume.draw(heart.frame, t.curve, reach(now, t.grownAtMillis), strength, heart.style)
            }
        }
        TendrilVolume.end()
    }

    private fun reach(now: Long, grownAt: Long): Double {
        val x = ((now - grownAt).toDouble() / GROW_MILLIS).coerceIn(0.0, 1.0)
        // Ease out: fast from the artery, slowing as it reaches the block.
        return 1.0 - (1.0 - x) * (1.0 - x)
    }

    /** Fills [selected] with the nearest hearts that have tendrils, nearest first; returns how many. */
    private fun selectNearest(cam: Vec3): Int {
        var count = 0
        for (heart in hearts.values) {
            if (!heart.tipsValid || !heart.hasTendrils()) continue
            worldCentre(heart.pos, heart.ship)
            val d = scratch.distanceSquared(cam.x, cam.y, cam.z)
            if (d > DRAW_RANGE * DRAW_RANGE) continue
            if (count == MAX_HEARTS && d >= selectedDistSqr[count - 1]) continue
            var i = if (count < MAX_HEARTS) count++ else count - 1
            while (i > 0 && selectedDistSqr[i - 1] > d) {
                selected[i] = selected[i - 1]
                selectedDistSqr[i] = selectedDistSqr[i - 1]
                i--
            }
            selected[i] = heart
            selectedDistSqr[i] = d
        }
        return count
    }
}
