package org.shipwrights.enderkinesis.item

import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.blockentity.OrbOfLinkingBlockEntity

/**
 * Tome of Ventriloquism — sounds played inside a [VentriloquismNetwork.CAPTURE_RADIUS] bubble
 * around a SEND orb are mirrored to every linked RECEIVE orb at the same relative offset. The
 * channel filter and replay live in `ServerLevelVentriloquismSoundMixin`; this behavior just
 * keeps [VentriloquismNetwork] in sync with the orb's outgoing-link state so the mixin's
 * per-sound iteration stays cheap.
 *
 *  - [onLinked] / [onLoad] — first outgoing link → register.
 *  - [onUnlinking] — last outgoing link → deregister. The BE removes the link *after* this
 *    hook returns, so the check is "outgoing peers will become empty once we leave".
 */
object VentriloquismTomeOrbBehavior : TomeOrbBehavior {

    override val tomeKind: ResourceLocation = EnderkinesisMod.id("tome_of_ventriloquism")

    override fun onLinked(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity, receiverPos: BlockPos) {
        VentriloquismNetwork.register(level, sendBe.blockPos)
    }

    override fun onUnlinking(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity, receiverPos: BlockPos) {
        if (sendBe.outgoingPeers(tomeKind).size <= 1) {
            VentriloquismNetwork.unregister(level, sendBe.blockPos)
        }
    }

    override fun onLoad(level: ServerLevel, be: OrbOfLinkingBlockEntity) {
        if (be.outgoingPeers(tomeKind).isNotEmpty()) {
            VentriloquismNetwork.register(level, be.blockPos)
        }
    }
}
