package org.shipwrights.enderkinesis.item

import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import org.shipwrights.enderkinesis.blockentity.OrbOfLinkingBlockEntity
import org.valkyrienskies.mod.common.getShipManagingPos
import org.shipwrights.enderkinesis.item.TomePulseProfile
import org.shipwrights.enderkinesis.item.TomePulseProfiles

/**
 * Tome of Spring — true linear spring ([org.valkyrienskies.core.internal.joints.VSSpringJoint])
 * between two orbs. Bodies oscillate along the orb-to-orb axis around the rest length captured
 * at link time; rotation is free, so they can spin around the spring axis while bouncing.
 *
 *  - **world ↔ world**: refused (nothing to spring against).
 *  - **world ↔ ship**: ship bounces against a fixed world point — suspension-style.
 *  - **ship ↔ ship (different ships)**: the two ships oscillate against each other.
 *  - **same ship**: refused — a spring needs two distinct bodies.
 *
 * Distinct from Elasticity (6-DOF soft weld) and Chaining (one-sided rope limit, no springback):
 * Spring is a real 1D restoring force along the link axis, both compressive and tensile.
 *
 * Gestures, busy-orb refusal, 64-block cap, and selection clearing all inherit from
 * [LinkingTomeItem]. Joint lifecycle lives in [SpringTomeOrbBehavior].
 */
class TomeOfSpringItem(properties: Properties) : LinkingTomeItem(properties) {

    override val tomeKind: ResourceLocation = SpringTomeOrbBehavior.tomeKind
    override val langPrefix: String = "item.enderkinesis.tome_of_spring"

    override fun writtenBook(stack: ItemStack): ItemStack =
        TomeLore.fromSselithBook("Tome of Spring", "The Rigger", "tome_of_spring")

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
        val TOME_KIND: ResourceLocation = SpringTomeOrbBehavior.tomeKind

        /** Accent colour the orb-network beam carries. Mint green — reads as coiled metal,
         *  distinct from Elasticity's cyan and Chaining's hemp brown. */
        const val BEAM_COLOR: Int = 0x64D58C

        fun registerBeamPalette() {
            TomeBeamPalette.register(TOME_KIND, BEAM_COLOR)
            TomeOrbBehaviors.register(TOME_KIND, SpringTomeOrbBehavior)
            TomePulseProfiles.register(
                TOME_KIND,
                TomePulseProfile(
                    progression = 0.5, cohesion = 0.85, frequency = 1.8, reciprocal = true,
                ),
            )
        }
    }
}
