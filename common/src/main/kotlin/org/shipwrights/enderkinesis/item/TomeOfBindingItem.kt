package org.shipwrights.enderkinesis.item

import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import org.shipwrights.enderkinesis.blockentity.OrbOfLinkingBlockEntity
import org.valkyrienskies.mod.common.getShipManagingPos
import org.shipwrights.enderkinesis.item.TomePulseProfile
import org.shipwrights.enderkinesis.item.TomePulseProfiles

/**
 * Tome of Binding — welds two orbs together with a [org.valkyrienskies.core.internal.joints.VSFixedJoint],
 * rigidly locking their bodies' relative pose. The accent colour the orb-network beam carries
 * is anvil black to read as a structural weld; future tomes pick different hues.
 *
 *  - **world ↔ world**: refused at link time (no body to constrain).
 *  - **world ↔ ship**: pins the ship in world space at the moment of linking. The ship can no
 *    longer translate or rotate relative to the world.
 *  - **ship ↔ ship (different ships)**: locks the two ships' relative pose; pushing one drags
 *    the other.
 *  - **same ship (both orbs on one ship)**: refused — you can't constrain a body to itself
 *    and the orbs would mislead the player about whether the link is "working".
 *
 * All other gesture behaviour (select/link/unlink/dismantle, 64-block cap, busy-orb rejection,
 * etc.) is inherited from [LinkingTomeItem]. The actual joint construction lives in
 * [BindingTomeOrbBehavior], called via the BE's lifecycle hooks.
 */
class TomeOfBindingItem(properties: Properties) : LinkingTomeItem(properties) {

    override val tomeKind: ResourceLocation = BindingTomeOrbBehavior.tomeKind
    override val langPrefix: String = "item.enderkinesis.tome_of_binding"

    override fun writtenBook(stack: ItemStack): ItemStack =
        TomeLore.fromSselithBook("Tome of Binding", "The Smith", "tome_of_binding")

    /** Tome-of-Binding-specific rejections. The generic 64-block + same-dim checks run first
     *  in [LinkingTomeItem.handlePlain]; this only catches the pose-impossible cases. */
    override fun validateLink(
        level: ServerLevel,
        sendBe: OrbOfLinkingBlockEntity,
        recvBe: OrbOfLinkingBlockEntity,
    ): String? {
        val sendShip = level.getShipManagingPos(sendBe.blockPos)
        val recvShip = level.getShipManagingPos(recvBe.blockPos)
        if (sendShip == null && recvShip == null) return "cant_world_world"
        if (sendShip != null && recvShip != null && sendShip.id == recvShip.id) return "cant_same_ship"
        return null
    }

    companion object {
        /** Identifier this tome stamps onto every link in its networks. */
        val TOME_KIND: ResourceLocation = BindingTomeOrbBehavior.tomeKind

        /** Accent colour the orb-network beam carries (0xRRGGBB). Anvil-black for the Binding
         *  tome — reads as a structural weld and contrasts strongly against Signal's red. */
        const val BEAM_COLOR: Int = 0x2B2B2B

        /** Register accent colour and orb-network behavior. Called from common mod init. */

        fun registerBeamPalette() {
            TomeBeamPalette.register(TOME_KIND, BEAM_COLOR)
            TomeOrbBehaviors.register(TOME_KIND, BindingTomeOrbBehavior)
            TomePulseProfiles.register(
                TOME_KIND,
                TomePulseProfile(
                    progression = 1.5, cohesion = 0.5, frequency = 1.8, reciprocal = true,
                ),
            )
        }
    }
}
