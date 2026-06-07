package org.shipwrights.enderkinesis.item

import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import org.shipwrights.enderkinesis.blockentity.OrbOfLinkingBlockEntity
import org.valkyrienskies.mod.common.getShipManagingPos

/**
 * Tome of Chaining — loose [org.valkyrienskies.core.internal.joints.VSDistanceJoint] (rope)
 * between two orbs. The orbs' bodies can swing, drift, and float freely up to the rope's
 * length; beyond that the rope hits its limit and pulls them back.
 *
 *  - **world ↔ world**: refused (nothing to constrain).
 *  - **world ↔ ship**: anchors the ship to a fixed world point; the ship can drift up to
 *    `ropeLength` away from the world anchor.
 *  - **ship ↔ ship (different ships)**: both ships free, but tethered at a max distance.
 *  - **same ship**: refused — a rope can't constrain a body to itself.
 *
 * Rope length is locked in at link time and is the current world-space distance between the
 * two orbs. To get a longer rope, place orbs farther apart before linking. Persisted on the
 * SEND BE so chunk-reload rebinds at the same length, not the current geometry.
 *
 * Gestures, busy-orb refusal, 64-block cap, and selection-clearing all inherit from
 * [LinkingTomeItem]. Joint lifecycle (build/release/rebind) lives in [ChainingTomeOrbBehavior].
 */
class TomeOfChainingItem(properties: Properties) : LinkingTomeItem(properties) {

    override val tomeKind: ResourceLocation = ChainingTomeOrbBehavior.tomeKind
    override val langPrefix: String = "item.enderkinesis.tome_of_chaining"

    override fun writtenBook(stack: ItemStack): ItemStack =
        TomeLore.fromSselithBook("Tome of Chaining", "The Rigger", "tome_of_chaining")

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
        val TOME_KIND: ResourceLocation = ChainingTomeOrbBehavior.tomeKind

        /** Accent colour the orb-network beam carries (0xRRGGBB). Hemp brown for Chaining —
         *  reads as fibrous rope and is distinct from Signal red and Binding near-black. */
        const val BEAM_COLOR: Int = 0x8B5A2B

        /** Register accent colour and orb-network behavior. Called from common mod init. */
        fun registerBeamPalette() {
            TomeBeamPalette.register(TOME_KIND, BEAM_COLOR)
            TomeOrbBehaviors.register(TOME_KIND, ChainingTomeOrbBehavior)
        }
    }
}
