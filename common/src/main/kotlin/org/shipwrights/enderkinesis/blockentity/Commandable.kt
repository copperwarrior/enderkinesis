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

    /** Staff left-click ("Aim To"): orient the device toward the wielder's gaze
     *  WITHOUT actuating it — launchers store an aim pin, eyeroscopes pin their
     *  heading, constriliars turn in place. Default no-op for devices whose
     *  command is positionless (ancrite eye). */
    fun executeAimCommand(player: Player, hostShip: Ship?, contextStack: ItemStack) {}

    /** Autonomous dispatch (Shulkonaut): command this block toward a specific entity,
     *  no wielder involved. Default no-op — blocks whose command is inherently
     *  gaze/player-shaped simply don't respond to automation. */
    fun executeAutoCommand(target: net.minecraft.world.entity.Entity, hostShip: Ship?) {}

    /** Autonomous dispatch at a world POSITION (Shulkonaut's @v vessel targeting —
     *  ships aren't entities). [targetAnchor] is a SOLID block backing the position
     *  (shipyard coords for ship targets) — point-target consumers validate against a
     *  real block, exactly like the staff's gaze stamps its hit blockPos. Default no-op. */
    fun executeAutoCommandAt(
        targetPos: net.minecraft.world.phys.Vec3,
        targetAnchor: net.minecraft.core.BlockPos?,
        hostShip: Ship?,
    ) {}

    /** Autonomous AIM (Shulkonaut WATCH orders): orient toward the point WITHOUT
     *  actuating. Returns true when the device responded (drives the beat spark).
     *  Default no-op — devices with no passive orientation stay quiet under WATCH. */
    fun executeAutoAim(
        targetPos: net.minecraft.world.phys.Vec3,
        hostShip: Ship?,
    ): Boolean = false
}
