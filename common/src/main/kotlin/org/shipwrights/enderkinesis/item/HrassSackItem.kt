package org.shipwrights.enderkinesis.item

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.UseAnim
import net.minecraft.world.level.Level
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import org.shipwrights.enderkinesis.block.GasVentBlock
import org.shipwrights.enderkinesis.spell.GasField
import org.shipwrights.enderkinesis.spell.GasJetTuning
import org.shipwrights.enderkinesis.spell.GasJets
import org.shipwrights.enderkinesis.spell.GasKind
import org.shipwrights.enderkinesis.spell.SpellCasting
import org.valkyrienskies.mod.common.toWorldCoordinates

/** Creative only and never runs out. Hold use to spray Drake Breath, or to feed it into the vent you aim at. */
class HrassSackItem(properties: Properties) : Item(properties) {

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        player.startUsingItem(hand)
        return InteractionResultHolder.consume(player.getItemInHand(hand))
    }

    override fun getUseDuration(stack: ItemStack): Int = Int.MAX_VALUE

    override fun getUseAnimation(stack: ItemStack): UseAnim = UseAnim.NONE

    override fun onUseTick(level: Level, entity: LivingEntity, stack: ItemStack, remainingUseDuration: Int) {
        if (level !is ServerLevel || entity.tickCount % SpellCasting.STREAM_TICKS != 0) return
        val hit = entity.pick(VENT_REACH, 0f, false) as? BlockHitResult
        if (hit != null && hit.type == HitResult.Type.BLOCK) {
            val state = level.getBlockState(hit.blockPos)
            val vent = state.block as? GasVentBlock
            if (vent != null) {
                // The sack gives what the held spray would in the same time, but only what the vent can take; the rest stays in the sack.
                val give = vent.intakeRoom(level, hit.blockPos, state, GasKind.DRAKE_BREATH, GasJetTuning.MASS_PER_TICK * SpellCasting.STREAM_TICKS)
                if (give > 0f) GasField.add(level, vent.inputPos(state, hit.blockPos), give, GasKind.DRAKE_BREATH)
                return
            }
        }
        val origin = level.toWorldCoordinates(SpellCasting.handOrigin(entity, entity.usedItemHand))
        val look = entity.lookAngle
        GasJets.hold(
            level, entity.id.toLong(), GasKind.DRAKE_BREATH, 0L, origin.x, origin.y, origin.z,
            look.x, look.y, look.z, 1f, GasJetTuning.MASS_PER_TICK, hand = true,
        )
    }

    private companion object {
        const val VENT_REACH = 5.0
    }
}
