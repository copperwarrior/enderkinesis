package org.shipwrights.enderkinesis.client

import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.TamableAnimal
import net.minecraft.world.entity.monster.Zombie
import net.minecraft.world.entity.player.Player
import org.shipwrights.enderkinesis.registry.EKEffects
import java.util.WeakHashMap

/**
 * Discord (Chaos) on the local player: their allies, other players and their own tamed
 * animals, are drawn as zombies. Each ally gets a client-only zombie that copies its
 * position, facing and walk, and is drawn in its place.
 */
object DiscordDisguise {

    private class StandIn(val zombie: Zombie, var lastTick: Int = -1)

    private val standIns = WeakHashMap<Entity, StandIn>()

    /** The zombie to draw instead of [entity], or null to draw it as it is. */
    @JvmStatic
    fun standIn(entity: Entity): Entity? {
        val player = Minecraft.getInstance().player ?: return null
        if (entity === player || !player.hasEffect(EKEffects.DISCORD.get())) return null
        val ally = entity is Player || (entity is TamableAnimal && entity.isOwnedBy(player))
        if (!ally) return null
        val standIn = standIns.getOrPut(entity) { StandIn(Zombie(EntityType.ZOMBIE, entity.level())) }
        copyPose(entity, standIn)
        return standIn.zombie
    }

    private fun copyPose(entity: Entity, standIn: StandIn) {
        val zombie = standIn.zombie
        zombie.setPos(entity.x, entity.y, entity.z)
        zombie.xo = entity.xo; zombie.yo = entity.yo; zombie.zo = entity.zo
        zombie.yRot = entity.yRot; zombie.yRotO = entity.yRotO
        zombie.xRot = entity.xRot; zombie.xRotO = entity.xRotO
        zombie.tickCount = entity.tickCount
        zombie.setOnGround(entity.onGround())
        if (entity is LivingEntity) {
            zombie.yBodyRot = entity.yBodyRot; zombie.yBodyRotO = entity.yBodyRotO
            zombie.yHeadRot = entity.yHeadRot; zombie.yHeadRotO = entity.yHeadRotO
            // Advance the zombie's walk once per tick at the ally's walking speed.
            if (standIn.lastTick != entity.tickCount) {
                standIn.lastTick = entity.tickCount
                zombie.walkAnimation.update(entity.walkAnimation.speed(), 1f)
            }
        }
    }

    fun reset() = standIns.clear()
}
