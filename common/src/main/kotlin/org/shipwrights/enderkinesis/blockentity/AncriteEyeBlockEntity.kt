package org.shipwrights.enderkinesis.blockentity

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import org.shipwrights.enderkinesis.block.AncriteEyeBlock
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import org.valkyrienskies.core.api.ships.Ship

/**
 * Block entity for the Ancrite Eye. The dial position itself lives on the blockstate
 * ([AncriteEyeBlock.POWER]). This BE exposes the [Commandable] hook for the Staff of
 * Command and tracks one piece of NBT — [savedPower] — used by the momentary-press
 * flow: when a button-press jumps the dial to max, the previous level is stashed
 * here so the scheduled tick can restore it.
 *
 *  - `savedPower >= 0` → a press is in flight; the scheduled tick will revert to this.
 *  - `savedPower < 0`  → no press pending; the dial is at rest.
 */
class AncriteEyeBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(EKBlockEntities.ANCRITE_EYE.get(), pos, state), Commandable {

    var savedPower: Int = -1

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        if (savedPower >= 0) tag.putInt(SAVED_POWER_TAG, savedPower)
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)
        savedPower =
            if (tag.contains(SAVED_POWER_TAG, Tag.TAG_INT.toInt())) tag.getInt(SAVED_POWER_TAG) else -1
    }

    override fun executeStaffCommand(player: Player, hostShip: Ship?, contextStack: ItemStack) {
        val lvl = level ?: return
        if (lvl.isClientSide) return
        AncriteEyeBlock.pressButton(lvl, blockPos, blockState, this)
    }

    companion object {
        const val SAVED_POWER_TAG: String = "SavedPower"
    }
}
