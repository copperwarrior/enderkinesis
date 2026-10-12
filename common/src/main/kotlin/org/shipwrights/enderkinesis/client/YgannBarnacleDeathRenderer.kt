package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import dev.architectury.networking.NetworkManager
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.world.level.Level
import org.joml.Quaternionf
import org.joml.Vector3d
import org.shipwrights.enderkinesis.barnacle.YgannBarnacleDeathNetwork
import org.shipwrights.enderkinesis.barnacle.YgannBarnacleGeometry
import org.shipwrights.enderkinesis.barnacle.YgannBarnacleGeometry.BPath
import org.shipwrights.enderkinesis.blockentity.YgannBarnacleBlockEntity
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.mod.common.getShipManagingPos

/**
 * Detached shrivel-out death for the Ygann Barnacle. The block breaks normally; the
 * server fires [YgannBarnacleDeathNetwork.DEATH] and this plays the ~2 s wither +
 * ender particles from a level-render pass — no block kept alive, no BE, no fighting
 * the client's break prediction. State arrives on the wire; the tendril routes are
 * re-walked once on the client (hull still intact) and reused for the whole animation.
 */
object YgannBarnacleDeathRenderer {

    private class Death(
        val pos: BlockPos, val facing: Direction, val growthPx: Double,
        val paths: List<BPath>, val startGameTime: Long,
    )

    // Client-thread only (network receiver posts via Minecraft.execute; render reads on
    // the same render thread), so a plain list is safe.
    private val active = ArrayList<Death>()

    fun initClient() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, YgannBarnacleDeathNetwork.DEATH) { buf, _ ->
            val pos = buf.readBlockPos()
            val seed = buf.readLong()
            val facing = Direction.values()[buf.readByte().toInt()]
            val growthPx = buf.readDouble()
            Minecraft.getInstance().execute {
                val level = Minecraft.getInstance().level ?: return@execute
                val paths = YgannBarnacleGeometry.walk(level, pos, seed, facing)
                active.add(Death(pos, facing, growthPx, paths, level.gameTime))
            }
        }
    }

    fun onDisconnect() = active.clear()

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
        val ease = YgannBarnacleColonyDraw.smooth(progress)
        val witherFront = ease * (YgannBarnacleBlockEntity.TENTACLE_LEN_PX + WITHER_BAND_PX) * DEATH_FRONT_OVERSHOOT
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
        val baseFront = witherFront + DISSOLVE_BAND_PX
        val consumer = buffers.getBuffer(YgannBarnacleColonyDraw.RENDER_TYPE)
        for (bp in death.paths) {
            val reveal = death.growthPx - bp.startArc
            if (reveal > 0.5) {
                YgannBarnacleColonyDraw.drawTentacle(
                    consumer, poseStack, light, bp.points, bp.phase, bp.originArc, reveal, time, baseFront,
                )
            }
        }
        // Disintegration: dragon-death alpha-test shader crumbles the segments in the
        // band just ahead of the cut, flushed so its own texture stays separate.
        val dissolveConsumer = buffers.getBuffer(YgannBarnacleColonyDraw.DISSOLVE_TYPE)
        for (bp in death.paths) {
            val reveal = death.growthPx - bp.startArc
            if (reveal > 0.5) {
                YgannBarnacleColonyDraw.drawTentacleDissolve(
                    dissolveConsumer, poseStack, light, bp.points, bp.phase, bp.originArc, reveal, time, witherFront, DISSOLVE_BAND_PX,
                )
            }
        }
        (buffers as? MultiBufferSource.BufferSource)?.endBatch(YgannBarnacleColonyDraw.DISSOLVE_TYPE)
        poseStack.popPose()

        emitParticles(level, death, witherFront, ship)
    }

    /** Light ender particles at the LEADING edge of the crumble band — where each
     *  segment BEGINS disintegrating (not the trailing cut where it finishes). */
    private fun emitParticles(level: Level, death: Death, witherFront: Double, ship: ClientShip?) {
        val startFront = witherFront + DISSOLVE_BAND_PX
        if (startFront < 0.5) return
        val pos = death.pos
        val w = Vector3d()
        for (bp in death.paths) {
            val reveal = death.growthPx - bp.startArc
            if (startFront >= reveal) continue
            if (level.random.nextFloat() > PARTICLE_CHANCE) continue
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

    /** Block-local point on [bp] at arc length [arc]. */
    private fun sampleLocal(bp: BPath, arc: Double): Vector3d? {
        val pts = bp.points
        if (pts.size < 2) return null
        for (i in 1 until pts.size) {
            val b = pts[i]
            if (b.arcPx >= arc) {
                val a = pts[i - 1]
                val f = ((arc - a.arcPx) / (b.arcPx - a.arcPx)).coerceIn(0.0, 1.0)
                return Vector3d(a.pos).lerp(b.pos, f)
            }
        }
        return Vector3d(pts.last().pos)
    }

    private const val DEATH_DURATION_TICKS = 65.0
    private const val WITHER_BAND_PX = 26.0
    private const val DEATH_FRONT_OVERSHOOT = 1.1
    /** Crackle band ahead of the cut where segments disintegrate (px). */
    private const val DISSOLVE_BAND_PX = 28.0
    private const val PARTICLE_CHANCE = 0.16f
}
