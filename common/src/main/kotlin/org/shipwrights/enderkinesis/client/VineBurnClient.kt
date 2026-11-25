package org.shipwrights.enderkinesis.client

import dev.architectury.event.EventResult
import dev.architectury.event.events.common.InteractionEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.world.item.FlintAndSteelItem

/** Flames, smoke and the fire loop at the burning links of the drawn vines. */
object VineBurnClient {
    private const val MAX_FRONTS_PER_TICK = 12
    private const val EFFECT_DISTANCE = 48.0
    private const val FLAME_CHANCE = 2
    private const val SMOKE_CHANCE = 3
    private const val JITTER = 0.15
    private const val RISE = 0.02

    private var budget = 0

    fun init() {
        // Stops the client's own flint use on the block behind the vine (a short ghost fire); the server lights the vine.
        InteractionEvent.RIGHT_CLICK_BLOCK.register { player, hand, _, _ ->
            val aimed = player.level().isClientSide && player.getItemInHand(hand).item is FlintAndSteelItem &&
                VineCutHighlight.pickAtCrosshair(Minecraft.getInstance()) != null
            if (aimed) EventResult.interruptTrue() else EventResult.pass()
        }
    }

    fun beginTick() {
        budget = MAX_FRONTS_PER_TICK
        pruneBurnLoops()
    }

    fun clear() = clearBurnLoops()

    /** Effects at the [burning] links of vine [chainId], whose [count] chain nodes are in [p]. */
    fun effects(level: ClientLevel, chainId: Long, p: DoubleArray, count: Int, burning: IntArray) {
        val camera = Minecraft.getInstance().gameRenderer.mainCamera.position
        val random = level.random
        for (front in burning.indices) {
            if (budget <= 0) return
            val link = burning[front]
            if (link !in 0 until count - 1) continue
            val k = link * 3
            val x = (p[k] + p[k + 3]) * 0.5
            val y = (p[k + 1] + p[k + 4]) * 0.5
            val z = (p[k + 2] + p[k + 5]) * 0.5
            if (camera.distanceToSqr(x, y, z) > EFFECT_DISTANCE * EFFECT_DISTANCE) continue
            budget--
            if (random.nextInt(FLAME_CHANCE) == 0) {
                level.addParticle(ParticleTypes.FLAME, x + jitter(level), y + jitter(level), z + jitter(level), 0.0, RISE, 0.0)
            }
            if (random.nextInt(SMOKE_CHANCE) == 0) {
                level.addParticle(ParticleTypes.SMOKE, x + jitter(level), y + JITTER, z + jitter(level), 0.0, RISE, 0.0)
            }
            level.keepBurnLoop(chainId, front, x, y, z)
        }
    }

    private fun jitter(level: ClientLevel): Double = (level.random.nextDouble() * 2 - 1) * JITTER
}
