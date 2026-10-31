package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import dev.architectury.networking.NetworkManager
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector3d
import org.shipwrights.enderkinesis.barnacle.YgannBarnacleDeathNetwork
import org.shipwrights.enderkinesis.barnacle.YgannBarnacleGeometry
import org.shipwrights.enderkinesis.barnacle.YgannBarnacleGeometry.BPath
import org.shipwrights.enderkinesis.blockentity.YgannBarnacleBlockEntity
import org.shipwrights.enderkinesis.registry.EKSounds
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.toWorldCoordinates

/**
 * Detached shrivel-out death for the Ygann Barnacle. The block breaks normally; the
 * server fires [YgannBarnacleDeathNetwork.DEATH] and this plays the ~2 s wither +
 * ender particles from a level-render pass — no block kept alive, no BE, no fighting
 * the client's break prediction. State arrives on the wire; the tendril routes come from the
 * client barnacle's fresh walk, or are re-walked once (hull still intact), and serve the whole animation.
 * The same effect dissolves the part of a limb that a cut took off ([addSever]).
 */
object YgannBarnacleDeathRenderer {

    /** Each path withers from [frontStart] (path-local px) out to [reveal]: from 0 for a death, from
     *  the cut for a cut limb. A path with no part in that range draws nothing. */
    private class Death(
        val pos: BlockPos, val paths: List<BPath>, val reveal: DoubleArray, val frontStart: DoubleArray,
        val startGameTime: Long,
    ) {
        /** Game time of each path's next sever grain; staggered so the limbs do not start together. */
        val nextGrain = LongArray(paths.size) { startGameTime + (it * GRAIN_STAGGER) % GRAIN_MIN_GAP }
        var grainTick = Long.MIN_VALUE
        var grainsThisTick = 0
        var particleTick = Long.MIN_VALUE
    }

    /** A walk that a client barnacle held when it was removed, kept briefly for its death notice. */
    private class KnownWalk(
        val pos: BlockPos, val seed: Long, val paths: List<BPath>, val checkedAt: Long, val removedAt: Long,
    )

    // Client-thread only (network receiver posts via Minecraft.execute; render reads on
    // the same render thread), so a plain list is safe.
    private val active = ArrayList<Death>()
    private val known = ArrayDeque<KnownWalk>()
    private val scratchPos = Vector3d()
    private val scratchNrm = Vector3d()
    private val scratchWorld = Vector3d()

    fun initClient() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, YgannBarnacleDeathNetwork.DEATH) { buf, _ ->
            val pos = buf.readBlockPos()
            val seed = buf.readLong()
            val facing = Direction.values()[buf.readByte().toInt()]
            val count = buf.readVarInt().coerceIn(0, YgannBarnacleGeometry.MAX_TENTACLES)
            val reveal = DoubleArray(count) { buf.readFloat().toDouble() }
            Minecraft.getInstance().execute {
                val level = Minecraft.getInstance().level ?: return@execute
                val paths = knownPaths(level, pos, seed)
                if (paths != null) {
                    startDeath(level, pos, paths, reveal)
                } else {
                    YgannBarnacleClientWork.requestDeathWalk(level, pos, seed, facing) { walked ->
                        startDeath(level, pos, walked, reveal)
                    }
                }
                val core = level.toWorldCoordinates(Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5))
                level.playLocalSound(
                    core.x, core.y, core.z, EKSounds.YGANN_BARNACLE_DEATH.get(), SoundSource.BLOCKS, 1f, 1f, false,
                )
            }
        }
    }

    private fun startDeath(level: Level, pos: BlockPos, paths: List<BPath>, reveal: DoubleArray) {
        val fitted = DoubleArray(paths.size) { if (it < reveal.size) reveal[it] else 0.0 }
        active.add(Death(pos, paths, fitted, DoubleArray(paths.size), level.gameTime))
    }

    /** The routes of the barnacle at [pos], when the client checked them against the hull just now. */
    private fun knownPaths(level: Level, pos: BlockPos, seed: Long): List<BPath>? {
        val now = level.gameTime
        val be = level.getBlockEntity(pos) as? YgannBarnacleBlockEntity
        if (be != null && be.seed == seed && !be.clientWalkQueued) {
            be.clientPendingWalk?.let { return it.paths }
            val walk = be.clientWalk
            if (walk != null && now - be.clientHullCheckedAt in 0..FRESH_WALK_TICKS) return walk.paths
        }
        for (k in known) {
            if (k.pos == pos && k.seed == seed && now - k.checkedAt in 0..FRESH_WALK_TICKS) return k.paths
        }
        return null
    }

    /** Client barnacle removal: keeps its walk for a death notice that may follow. */
    fun rememberWalk(pos: BlockPos, seed: Long, walk: YgannBarnacleGeometry.Walk, checkedAt: Long, now: Long) {
        while (known.isNotEmpty() && (known.size >= MAX_KNOWN || now - known.first().removedAt !in 0..FRESH_WALK_TICKS)) {
            known.removeFirst()
        }
        known.addLast(KnownWalk(pos.immutable(), seed, walk.paths, checkedAt, now))
    }

    /** The parts of a living barnacle's limbs that a cut took off: each path from [from] to [to]. */
    fun addSever(pos: BlockPos, paths: List<BPath>, from: DoubleArray, to: DoubleArray, gameTime: Long) {
        active.add(Death(pos, paths, to.copyOf(paths.size), from.copyOf(paths.size), gameTime))
    }

    fun onDisconnect() {
        active.clear()
        known.clear()
        YgannBarnacleClientWork.clear()
    }

    fun renderAll(
        poseStack: PoseStack, buffers: MultiBufferSource,
        cameraX: Double, cameraY: Double, cameraZ: Double, partialTicks: Float,
    ) {
        if (active.isEmpty()) return
        val level = Minecraft.getInstance().level ?: return
        val now = level.gameTime
        val time = now + partialTicks.toDouble()

        val it = active.iterator()
        while (it.hasNext()) {
            val death = it.next()
            val progress = (time - death.startGameTime) / DEATH_DURATION_TICKS
            // A negative value is an effect from a world with a higher game time.
            if (progress >= 1.0 || progress < 0.0) { it.remove(); continue }
            renderDeath(level, poseStack, buffers, death, progress, time, cameraX, cameraY, cameraZ)
        }
    }

    private fun renderDeath(
        level: Level, poseStack: PoseStack, buffers: MultiBufferSource,
        death: Death, progress: Double, time: Double,
        cameraX: Double, cameraY: Double, cameraZ: Double,
    ) {
        val pos = death.pos
        val ship = level.getShipManagingPos(pos) as? ClientShip
        val travel = YgannBarnacleColonyDraw.smooth(progress) *
            (YgannBarnacleGeometry.TENTACLE_LEN_PX + WITHER_BAND_PX) * DEATH_FRONT_OVERSHOOT
        val light = LevelRenderer.getLightColor(level, pos)

        poseStack.pushPose()
        if (ship != null) {
            // Do the huge world offset in DOUBLE (translate args), then apply only the
            // ship ROTATION in the float pose. Folding shipToWorld's world translation
            // into a float Matrix4f loses ~cm precision and jitters violently.
            val origin = Vector3d(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())
            ship.renderTransform.shipToWorld.transformPosition(origin)
            poseStack.translate(origin.x - cameraX, origin.y - cameraY, origin.z - cameraZ)
            val r = ship.renderTransform.shipToWorldRotation
            poseStack.mulPose(Quaternionf(r.x().toFloat(), r.y().toFloat(), r.z().toFloat(), r.w().toFloat()))
        } else {
            poseStack.translate(pos.x - cameraX, pos.y - cameraY, pos.z - cameraZ)
        }

        // No central node here — the block already broke; only the tendrils shrivel.
        // Base (barnacle texture) is intact only AHEAD of the crumble band; the band
        // itself is owned by the dissolve pass, and behind the front is gone.
        val consumer = buffers.getBuffer(YgannBarnacleColonyDraw.RENDER_TYPE)
        for (i in death.paths.indices) {
            val bp = death.paths[i]
            val reveal = death.reveal[i]
            if (reveal > 0.5 && reveal > death.frontStart[i]) {
                val baseFront = death.frontStart[i] + travel + DISSOLVE_BAND_PX
                YgannBarnacleColonyDraw.drawTentacle(
                    consumer, poseStack, light, bp.points, bp.breaks, bp.phase, bp.originArc, reveal, time, baseFront,
                )
            }
        }
        // Disintegration: dragon-death alpha-test shader crumbles the segments in the
        // band just ahead of the cut, flushed so its own texture stays separate.
        val dissolveConsumer = buffers.getBuffer(YgannBarnacleColonyDraw.DISSOLVE_TYPE)
        for (i in death.paths.indices) {
            val bp = death.paths[i]
            val reveal = death.reveal[i]
            if (reveal > 0.5 && reveal > death.frontStart[i]) {
                YgannBarnacleColonyDraw.drawTentacleDissolve(
                    dissolveConsumer, poseStack, light, bp.points, bp.breaks, bp.phase, bp.originArc, reveal, time,
                    death.frontStart[i] + travel, DISSOLVE_BAND_PX,
                )
            }
        }
        (buffers as? MultiBufferSource.BufferSource)?.endBatch(YgannBarnacleColonyDraw.DISSOLVE_TYPE)
        poseStack.popPose()

        emitParticles(level, death, travel, ship)
    }

    /** Light ender particles at the LEADING edge of the crumble band — where each
     *  segment BEGINS disintegrating (not the trailing cut where it finishes). */
    private fun emitParticles(level: Level, death: Death, travel: Double, ship: ClientShip?) {
        val pos = death.pos
        val w = scratchWorld
        val now = level.gameTime
        if (death.grainTick != now) {
            death.grainTick = now
            death.grainsThisTick = 0
        }
        // Once per tick, so the particle rate does not follow the frame rate.
        val particleTick = death.particleTick != now
        death.particleTick = now
        for (i in death.paths.indices) {
            val bp = death.paths[i]
            val startFront = death.frontStart[i] + travel + DISSOLVE_BAND_PX
            if (startFront < 0.5 || startFront >= death.reveal[i]) continue
            if (now >= death.nextGrain[i] && death.grainsThisTick < MAX_GRAINS_PER_TICK) {
                death.nextGrain[i] = now + GRAIN_MIN_GAP + level.random.nextInt(GRAIN_GAP_SPREAD)
                death.grainsThisTick++
                playGrain(level, pos, bp, startFront, ship, w)
            }
            if (!particleTick || level.random.nextFloat() > PARTICLE_CHANCE_PER_TICK) continue
            val local = sampleLocal(bp, startFront) ?: continue
            w.set(pos.x + local.x, pos.y + local.y, pos.z + local.z)
            ship?.renderTransform?.shipToWorld?.transformPosition(w)
            level.addParticle(
                ParticleTypes.PORTAL, w.x, w.y, w.z,
                (level.random.nextDouble() - 0.5) * 0.4,
                level.random.nextDouble() * 0.3,
                (level.random.nextDouble() - 0.5) * 0.4,
            )
        }
    }

    /** A grain plays at the front, so the sound travels with the dissolve. */
    private fun playGrain(level: Level, pos: BlockPos, bp: BPath, front: Double, ship: ClientShip?, w: Vector3d) {
        val local = sampleLocal(bp, front) ?: return
        w.set(pos.x + local.x, pos.y + local.y, pos.z + local.z)
        ship?.renderTransform?.shipToWorld?.transformPosition(w)
        level.playLocalSound(w.x, w.y, w.z, EKSounds.YGANN_BARNACLE_SEVER.get(), SoundSource.BLOCKS, 1f, 1f, false)
    }

    /** Block-local point on [bp] at arc length [arc], in a scratch vector. */
    private fun sampleLocal(bp: BPath, arc: Double): Vector3d? {
        if (bp.points.size < 2) return null
        YgannBarnacleGeometry.sampleAt(bp.points, arc, scratchPos, scratchNrm)
        return scratchPos
    }

    private const val DEATH_DURATION_TICKS = 65.0
    private const val WITHER_BAND_PX = 26.0
    private const val DEATH_FRONT_OVERSHOOT = 1.1
    /** Crackle band ahead of the cut where segments disintegrate (px). */
    private const val DISSOLVE_BAND_PX = 28.0
    /** 0.16 per limb per frame at 60 fps, as a per-tick chance (three frames per tick). */
    private const val PARTICLE_CHANCE_PER_TICK = 0.48f
    /** A held walk serves a death notice only when the client checked it against the hull this recently (ticks). */
    private const val FRESH_WALK_TICKS = 8L
    private const val MAX_KNOWN = 16
    /** Sever grains: 4-5 ticks apart on each limb, and a cap for a death that dissolves every limb at once. */
    private const val GRAIN_MIN_GAP = 4L
    private const val GRAIN_GAP_SPREAD = 2
    private const val GRAIN_STAGGER = 3L
    private const val MAX_GRAINS_PER_TICK = 3
}
