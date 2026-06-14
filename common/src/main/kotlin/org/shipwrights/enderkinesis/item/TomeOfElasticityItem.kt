package org.shipwrights.enderkinesis.item

import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import org.shipwrights.enderkinesis.blockentity.OrbOfLinkingBlockEntity
import org.valkyrienskies.mod.common.getShipManagingPos
import org.shipwrights.enderkinesis.item.TomePulseProfile
import org.shipwrights.enderkinesis.item.TomePulseProfiles

/**
 * Tome of Elasticity — a **spring** between two orbs. Each (send, receiver) pair becomes a
 * [org.valkyrienskies.core.internal.joints.VSDistanceJoint] with `min == max == restLength`
 * plus stiffness/damping sized off the connected bodies' reduced mass, so the spring's natural
 * frequency stays the same regardless of ship size. Bodies that drift away from the rest
 * length are pulled back; bodies pushed together are pushed apart.
 *
 *  - **world ↔ world**: refused (nothing to constrain).
 *  - **world ↔ ship**: pins the ship to a virtual spring anchored at the world orb's
 *    position; the ship can bob around the anchor at the spring's natural frequency.
 *  - **ship ↔ ship**: both ships free, coupled by a spring of fixed rest length.
 *  - **same ship**: refused — can't spring a body to itself.
 *
 * Rest length, like Chaining, is locked in at link time and persisted per-pair so chunk
 * reloads rebind to the same length. Everything else (gestures, 64-block cap, toggle-off,
 * sneak-drop) inherits from [LinkingTomeItem]; spring construction lives in
 * [ElasticityTomeOrbBehavior].
 */
class TomeOfElasticityItem(properties: Properties) : LinkingTomeItem(properties) {

    override val tomeKind: ResourceLocation = ElasticityTomeOrbBehavior.tomeKind
    override val langPrefix: String = "item.enderkinesis.tome_of_elasticity"

    override fun writtenBook(stack: ItemStack): ItemStack =
        TomeLore.fromSselithBook("Tome of Elasticity", "The Stretcher", "tome_of_elasticity")

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
        val TOME_KIND: ResourceLocation = ElasticityTomeOrbBehavior.tomeKind

        /** Accent colour the orb-network beam carries (0xRRGGBB). Electric cyan — reads as
         *  kinetic energy / springiness, and contrasts maximally against the existing palette. */
        const val BEAM_COLOR: Int = 0x40C4E0

        fun registerBeamPalette() {
            TomeBeamPalette.register(TOME_KIND, BEAM_COLOR)
            TomeOrbBehaviors.register(TOME_KIND, ElasticityTomeOrbBehavior)
            TomePulseProfiles.register(
                TOME_KIND,
                TomePulseProfile(
                    progression = 1.5, cohesion = 0.85, frequency = 1.0, reciprocal = true,
                ),
            )
        }
    }
}
