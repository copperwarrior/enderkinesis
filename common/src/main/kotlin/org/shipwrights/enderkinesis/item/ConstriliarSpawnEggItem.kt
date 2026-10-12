package org.shipwrights.enderkinesis.item

import dev.architectury.core.item.ArchitecturySpawnEggItem
import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.item.Item
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.gameevent.GameEvent
import org.shipwrights.enderkinesis.entity.ConstriliarEntity

/** Spawn egg that immediately binds the spawned Constriliar to the placing player. */
class ConstriliarSpawnEggItem(
    private val type: RegistrySupplier<EntityType<ConstriliarEntity>>,
    primary: Int,
    secondary: Int,
    props: Item.Properties,
) : ArchitecturySpawnEggItem(type, primary, secondary, props) {

    override fun useOn(context: UseOnContext): InteractionResult {
        val level = context.level
        if (level !is ServerLevel) return InteractionResult.SUCCESS
        val stack = context.itemInHand
        val clicked = context.clickedPos
        val spawnPos = if (level.getBlockState(clicked).getCollisionShape(level, clicked).isEmpty) {
            clicked
        } else {
            clicked.relative(context.clickedFace)
        }
        val entity = type.get().spawn(
            level, stack, context.player, spawnPos, MobSpawnType.SPAWN_EGG,
            true, spawnPos != clicked && context.clickedFace == Direction.UP,
        ) ?: return InteractionResult.FAIL
        entity.ownerUUID = context.player?.uuid
        // Face the cardinal direction the spawner is in, seen from the entity.
        context.player?.let { p ->
            val yaw = Direction.getNearest(
                (p.x - entity.x).toFloat(), 0f, (p.z - entity.z).toFloat(),
            ).toYRot()
            entity.moveTo(entity.x, entity.y, entity.z, yaw, 0f)
            entity.yBodyRot = yaw
            entity.yHeadRot = yaw
        }
        entity.startSpawnAnimation()
        stack.shrink(1)
        level.gameEvent(context.player, GameEvent.ENTITY_PLACE, spawnPos)
        return InteractionResult.CONSUME
    }
}
