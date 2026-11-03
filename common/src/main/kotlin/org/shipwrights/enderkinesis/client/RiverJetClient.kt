package org.shipwrights.enderkinesis.client

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.tags.FluidTags
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.item.IncantationOfTheRiverItem
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider

/** Incantation of the River client tick: the pressure-washer spray at the impact ([RiverWasher]), bubbles under
 *  water, and drops shed along the jet. */
object RiverJetClient {
    private const val BUBBLES_PER_TICK = 6
    private const val DRIPS_PER_TICK = 2
    private const val IMPACT_JITTER = 0.2
    private const val BUBBLE_SPEED = 0.15

    fun spawn(player: Player) {
        val level = player.level()
        val rnd = level.random
        val jet = IncantationOfTheRiverItem.trace(level, player, 1f)
        val end = jet.end
        val palm = IncantationOfWindsClient.castingPalm(player, 1f).position
        recoil(player)
        val normal = impactNormal(level, jet, end.subtract(palm).normalize())
        if (normal != null) {
            if (level.getFluidState(BlockPos.containing(end)).`is`(FluidTags.WATER)) {
                repeat(BUBBLES_PER_TICK) {
                    val v = normal.scale(BUBBLE_SPEED)
                    level.addParticle(
                        ParticleTypes.BUBBLE,
                        end.x + (rnd.nextDouble() - 0.5) * IMPACT_JITTER,
                        end.y + (rnd.nextDouble() - 0.5) * IMPACT_JITTER,
                        end.z + (rnd.nextDouble() - 0.5) * IMPACT_JITTER,
                        v.x, v.y, v.z,
                    )
                }
            } else {
                RiverWasher.spray(player, end, normal, onBlock = isWorldBlockHit(level, jet))
            }
        }
        repeat(DRIPS_PER_TICK) {
            val t = rnd.nextDouble()
            level.addParticle(
                ParticleTypes.FALLING_WATER,
                palm.x + (end.x - palm.x) * t,
                palm.y + (end.y - palm.y) * t,
                palm.z + (end.z - palm.z) * t,
                0.0, 0.0, 0.0,
            )
        }
    }

    /** The local player's own movement is client-side, so the recoil goes here. It works only for a fall, elytra
     *  flight or floating in water, never on the ground, while rising through air, or in creative flight. On a ship
     *  the server pushes the ship instead. */
    private fun recoil(player: Player) {
        if (player !== Minecraft.getInstance().player) return
        if (player.abilities.flying) return
        val floating = player.isInWater && !player.onGround()
        val falling = !player.onGround() && player.deltaMovement.y < 0.0
        if (!player.isFallFlying && !floating && !falling) return
        val info = (player as? IEntityDraggingInformationProvider)?.draggingInformation
        if (info != null && info.isEntityBeingDraggedByAShip()) return
        player.deltaMovement = player.deltaMovement.subtract(player.lookAngle.scale(IncantationOfTheRiverItem.RECOIL))
    }

    /** World normal of the hit surface, away from it, or null on a miss. VS2: a ship hit's face is in ship axes, so
     *  the jet's reverse stands in for it, as on an entity. */
    private fun impactNormal(level: Level, jet: IncantationOfTheRiverItem.Jet, axis: Vec3): Vec3? {
        val block = jet.block
        if (block != null && level.getShipManagingPos(block.blockPos) == null) return Vec3.atLowerCornerOf(block.direction.normal)
        return if (block == null && jet.entity == null) null else axis.scale(-1.0)
    }

    private fun isWorldBlockHit(level: Level, jet: IncantationOfTheRiverItem.Jet): Boolean {
        val block = jet.block ?: return false
        return level.getShipManagingPos(block.blockPos) == null
    }
}
