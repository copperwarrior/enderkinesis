package org.shipwrights.enderkinesis.blockentity

import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import org.valkyrienskies.core.api.ships.Ship

/** BlockEntity contract for blocks that can be driven by the Staff of Command.
 *  The staff stores a list of positions (one per right-click-add); when the wielder
 *  right-clicks open air, the staff iterates the list and calls
 *  [executeStaffCommand] on every entry whose BE implements this interface.
 *
 *  @param hostShip the VS2 ship this BE sits on, or `null` if it's a world block.
 *                   Used by implementers to convert their own [pos] to world coords
 *                   without a level lookup.
 *  @param contextStack the player's *other-hand* stack — empty for the default
 *                       behaviour, populated when the wielder wants to modify the
 *                       command (e.g. holding a torch picks a different sub-mode). */
interface Commandable {
    fun executeStaffCommand(player: Player, hostShip: Ship?, contextStack: ItemStack)
}
