package org.shipwrights.enderkinesis.item

import net.minecraft.stats.Stats
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.EntitySelector
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.vehicle.Boat
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.gameevent.GameEvent
import net.minecraft.world.phys.HitResult
import org.shipwrights.enderkinesis.entity.WogorBoat
import org.shipwrights.enderkinesis.entity.WogorChestBoat
import org.shipwrights.enderkinesis.registry.EKEntities

/** Port of vanilla BoatItem.use: its private getBoat() only builds vanilla boat entities. */
class WogorBoatItem(private val hasChest: Boolean, properties: Properties) : Item(properties) {
    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        val hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.ANY)
        if (hit.type != HitResult.Type.BLOCK) return InteractionResultHolder.pass(stack)

        val view = player.getViewVector(1.0f)
        val eye = player.eyePosition
        val nearby = level.getEntities(
            player,
            player.boundingBox.expandTowards(view.scale(PLACE_REACH)).inflate(1.0),
            EntitySelector.NO_SPECTATORS.and { it.isPickable },
        )
        if (nearby.any { it.boundingBox.inflate(it.pickRadius.toDouble()).contains(eye) }) {
            return InteractionResultHolder.pass(stack)
        }

        val boat = createBoat(level, hit)
        boat.yRot = player.yRot
        if (!level.noCollision(boat, boat.boundingBox)) return InteractionResultHolder.fail(stack)
        if (!level.isClientSide) {
            level.addFreshEntity(boat)
            level.gameEvent(player, GameEvent.ENTITY_PLACE, hit.location)
            if (!player.abilities.instabuild) stack.shrink(1)
        }
        player.awardStat(Stats.ITEM_USED.get(this))
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide)
    }

    private fun createBoat(level: Level, hit: HitResult): Boat {
        val boat: Boat =
            if (hasChest) WogorChestBoat(EKEntities.WOGOR_CHEST_BOAT.get(), level)
            else WogorBoat(EKEntities.WOGOR_BOAT.get(), level)
        val pos = hit.location
        boat.setPos(pos.x, pos.y, pos.z)
        boat.xo = pos.x
        boat.yo = pos.y
        boat.zo = pos.z
        return boat
    }

    private companion object {
        const val PLACE_REACH = 5.0
    }
}
