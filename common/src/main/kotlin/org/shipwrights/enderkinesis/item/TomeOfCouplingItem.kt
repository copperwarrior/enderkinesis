package org.shipwrights.enderkinesis.item

import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import org.shipwrights.enderkinesis.blockentity.OrbOfLinkingBlockEntity
import org.valkyrienskies.mod.common.getShipManagingPos
import org.shipwrights.enderkinesis.item.TomePulseProfile
import org.shipwrights.enderkinesis.item.TomePulseProfiles

/**
 * Tome of Coupling — strong distance joint
 * ([org.valkyrienskies.core.internal.joints.VSDistanceJoint] with both `min` and `max` bounds
 * pinned to the same captured length). The orbs are held at an exact constant distance: they
 * cannot draw closer, cannot separate further, but are free to rotate around the line
 * joining them. A rigid coupling rod between two ships.
 *
 *  - **world ↔ world**: refused.
 *  - **world ↔ ship**: ship locked at a fixed world-space distance from a world anchor;
 *    free to swing around it.
 *  - **ship ↔ ship (different ships)**: rigid axle between the two ships.
 *  - **same ship**: refused — a coupling needs two distinct bodies.
 *
 * Distinct from Chaining (one-sided rope, slack inside), Binding (full weld, no rotation
 * either), and Spring (oscillates around a rest length).
 *
 * Gestures, busy-orb refusal, 64-block cap, and selection clearing all inherit from
 * [LinkingTomeItem]. Joint lifecycle lives in [CouplingTomeOrbBehavior].
 */
class TomeOfCouplingItem(properties: Properties) : LinkingTomeItem(properties) {

    override val tomeKind: ResourceLocation = CouplingTomeOrbBehavior.tomeKind
    override val langPrefix: String = "item.enderkinesis.tome_of_coupling"

    override fun writtenBook(stack: ItemStack): ItemStack =
        TomeLore.fromSselithBook("Tome of Coupling", "The Rigger", "tome_of_coupling")

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
        val TOME_KIND: ResourceLocation = CouplingTomeOrbBehavior.tomeKind

        /** Accent colour the orb-network beam carries. Polished steel — reads as a rigid
         *  machined rod, distinct from Chaining's hemp brown and Spring's mint. */
        const val BEAM_COLOR: Int = 0xA8B5C0

        fun registerBeamPalette() {
            TomeBeamPalette.register(TOME_KIND, BEAM_COLOR)
            TomeOrbBehaviors.register(TOME_KIND, CouplingTomeOrbBehavior)
            TomePulseProfiles.register(
                TOME_KIND,
                TomePulseProfile(
                    progression = 1.3, cohesion = 0.85, frequency = 1.8, reciprocal = true,
                ),
            )
        }
    }
}
