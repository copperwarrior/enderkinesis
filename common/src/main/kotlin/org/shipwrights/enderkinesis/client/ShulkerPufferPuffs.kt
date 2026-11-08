package org.shipwrights.enderkinesis.client

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import org.joml.Vector3d
import org.shipwrights.enderkinesis.blockentity.ShulkerPufferBlockEntity
import org.shipwrights.enderkinesis.blockentity.ShulkerPufferClientRegistry
import org.shipwrights.enderkinesis.registry.EKParticles
import org.valkyrienskies.mod.common.getShipManagingPos

/** Puffs on the pulses of the puffer loops, and bursts on the pulses of the one-shot sounds. Client thread only. */
internal object ShulkerPufferPuffs {

    /** The pulse spacing of loop.ogg and loop_fuel.ogg at pitch 1. */
    private const val LOOP_PULSE_TICKS = 6f
    private const val FUEL_PULSE_TICKS = 2f
    /** Vanilla drops particles past 32 blocks from the camera. */
    private const val RANGE_SQR = 32.0 * 32.0
    /** Initial speed in blocks per tick at full power. Power scales it 50–100 %. */
    private const val UNFUELED_SPEED = 0.25
    private const val FUELED_SPEED = 0.5
    /** Out past the front face, so the model does not hide the puff. */
    private const val NOZZLE_OFFSET = 0.55
    private const val JITTER = 0.04
    /** VS2 ship velocity is in m/s. Particle velocity is in blocks per tick. */
    private const val METRES_PER_SEC_TO_BLOCKS_PER_TICK = 1.0 / 20.0

    /** Puffs at the pulse ticks of a one-shot sound. [fuel] null uses the fuel state of the puffer. */
    private class Burst(val ticks: IntArray, val counts: IntArray, val strengths: FloatArray, val fuel: Boolean?) {
        init {
            require(ticks.size == counts.size && ticks.size == strengths.size)
        }
    }

    /** Indexed by the BURST_ kinds of [ShulkerPufferBlockEntity]. */
    private val BURSTS = arrayOf(
        Burst(intArrayOf(0, 6), intArrayOf(1, 1), floatArrayOf(1f, 0.8f), null),
        Burst(intArrayOf(0, 6, 13), intArrayOf(1, 1, 1), floatArrayOf(1f, 0.7f, 0.4f), null),
        Burst(intArrayOf(3, 5, 6), intArrayOf(1, 3, 3), floatArrayOf(0.8f, 1f, 1f), true),
        Burst(intArrayOf(0, 6, 13), intArrayOf(1, 1, 1), floatArrayOf(0.6f, 0.4f, 0.25f), false),
    )

    private val nozzle = Vector3d()
    private val eject = Vector3d()
    private val shipVelocity = Vector3d()

    /** Call after the loops tick, so a loop that starts this tick puffs this tick. */
    fun tick(level: ClientLevel, player: Player) {
        for (be in ShulkerPufferClientRegistry.all(level)) {
            if (be.isRemoved) continue
            if (be.burstKind == ShulkerPufferBlockEntity.BURST_NONE && ShulkerPufferSounds.power(be) <= 0) continue
            ShulkerPufferSounds.worldCentre(level, be, nozzle)
            val inRange = player.distanceToSqr(nozzle.x, nozzle.y, nozzle.z) <= RANGE_SQR
            val bursting = tickBurst(level, be, inRange)
            tickLoop(level, be, inRange && !bursting)
        }
    }

    private fun tickBurst(level: ClientLevel, be: ShulkerPufferBlockEntity, inRange: Boolean): Boolean {
        val burst = BURSTS.getOrNull(be.burstKind) ?: return false
        val age = be.burstAge++
        if (inRange) {
            val fuel = burst.fuel ?: ShulkerPufferSounds.fueled(be)
            for (i in burst.ticks.indices) {
                if (burst.ticks[i] == age) puff(level, be, fuel, burst.strengths[i], burst.counts[i])
            }
        }
        if (age >= burst.ticks.last()) be.burstKind = ShulkerPufferBlockEntity.BURST_NONE
        return true
    }

    /** The countdown runs in loop time, so pitch changes the puff rate as it changes the pulse rate. */
    private fun tickLoop(level: ClientLevel, be: ShulkerPufferBlockEntity, emit: Boolean) {
        val power = ShulkerPufferSounds.power(be)
        if (power <= 0) return
        val fuel = ShulkerPufferSounds.fueled(be)
        if (fuel != be.puffFueled) {
            be.puffFueled = fuel
            be.puffCountdown = 0f
        }
        be.puffCountdown -= ShulkerPufferSounds.activePitch(be) ?: ShulkerPufferSounds.targetPitch(power)
        if (be.puffCountdown > 0f) return
        be.puffCountdown += if (fuel) FUEL_PULSE_TICKS else LOOP_PULSE_TICKS
        if (emit) puff(level, be, fuel, 1f, 1)
    }

    private fun puff(level: ClientLevel, be: ShulkerPufferBlockEntity, fuel: Boolean, strength: Float, count: Int) {
        val pos = be.blockPos
        val facing = be.blockState.getValue(BlockStateProperties.FACING)
        val power = ShulkerPufferSounds.power(be)
        val speed = (if (fuel) FUELED_SPEED else UNFUELED_SPEED) * (0.5 + power / 15.0 * 0.5) * strength
        nozzle.set(
            pos.x + 0.5 + facing.stepX * NOZZLE_OFFSET,
            pos.y + 0.5 + facing.stepY * NOZZLE_OFFSET,
            pos.z + 0.5 + facing.stepZ * NOZZLE_OFFSET,
        )
        eject.set(facing.stepX * speed, facing.stepY * speed, facing.stepZ * speed)
        shipVelocity.set(0.0, 0.0, 0.0)
        val ship = level.getShipManagingPos(pos)
        if (ship != null) {
            ship.shipToWorld.transformPosition(nozzle)
            ship.transform.shipToWorldRotation.transform(eject)
            // Exhaust leaves with the nozzle's point velocity, v + ω × r.
            val v = ship.velocity
            val w = ship.angularVelocity
            val com = ship.transform.positionInWorld
            val rx = nozzle.x - com.x()
            val ry = nozzle.y - com.y()
            val rz = nozzle.z - com.z()
            shipVelocity.set(
                v.x() + w.y() * rz - w.z() * ry,
                v.y() + w.z() * rx - w.x() * rz,
                v.z() + w.x() * ry - w.y() * rx,
            ).mul(METRES_PER_SEC_TO_BLOCKS_PER_TICK)
        }
        val random = level.random
        repeat(count) {
            level.addParticle(
                EKParticles.shulkerPuffer(),
                nozzle.x + (random.nextDouble() - 0.5) * JITTER,
                nozzle.y + (random.nextDouble() - 0.5) * JITTER,
                nozzle.z + (random.nextDouble() - 0.5) * JITTER,
                shipVelocity.x + eject.x,
                shipVelocity.y + eject.y,
                shipVelocity.z + eject.z,
            )
        }
    }
}
