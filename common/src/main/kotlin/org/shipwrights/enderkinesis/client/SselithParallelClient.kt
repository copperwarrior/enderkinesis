package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientPlayerEvent
import dev.architectury.networking.NetworkManager
import java.util.UUID
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.entity.Entity
import org.shipwrights.enderkinesis.dimension.SselithParallel
import org.shipwrights.enderkinesis.dimension.SselithParallelView
import org.shipwrights.enderkinesis.dimension.SselithRepertory
import org.shipwrights.enderkinesis.entity.Cataloger
import org.shipwrights.enderkinesis.registry.EKEntities
import org.shipwrights.enderkinesis.sselith.BookshelfPage

/** Receives the parallel table, and draws players on other parallels as client-only Catalogers. */
object SselithParallelClient {

    // Client thread only. One stand-in per mirrored player, reused every frame.
    private val standIns = HashMap<UUID, Cataloger>()

    // Client thread only: whether this client holds the bookshelf page tables (only while in Sselith).
    private var holdsPageTables = false

    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SselithParallel.SYNC) { buf, ctx ->
            val (replaceAll, entries) = SselithParallel.readPacket(buf)
            ctx.queue { SselithParallelView.apply(replaceAll, entries) }
        }
        ClientPlayerEvent.CLIENT_PLAYER_JOIN.register {
            SselithParallelView.localPlayer = it.uuid
            updatePageTables(it)
        }
        // Also fires on a dimension change, with the new player already in the new level.
        ClientPlayerEvent.CLIENT_PLAYER_RESPAWN.register { _, newPlayer ->
            // A stand-in holds the level it was made in.
            standIns.clear()
            updatePageTables(newPlayer)
        }
        ClientPlayerEvent.CLIENT_PLAYER_QUIT.register {
            SselithParallelView.clear()
            standIns.clear()
            updatePageTables(null)
        }
    }

    private fun updatePageTables(player: LocalPlayer?) {
        val inSselith = player?.level()?.dimension() == SselithRepertory.LEVEL_KEY
        if (inSselith == holdsPageTables) return
        holdsPageTables = inSselith
        if (inSselith) BookshelfPage.acquire() else BookshelfPage.release()
    }

    /** The Cataloger to draw in place of [entity], or null to draw [entity] itself. */
    @JvmStatic
    fun standIn(entity: Entity): Entity? {
        if (entity !is AbstractClientPlayer || !SselithParallelView.isOtherParallel(entity)) return null
        var standIn = standIns[entity.uuid]
        if (standIn == null || standIn.level() !== entity.level()) {
            standIn = EKEntities.CATALOGER.get().create(entity.level()) ?: return null
            standIns[entity.uuid] = standIn
        }
        mirror(standIn, entity)
        return standIn
    }

    private fun mirror(standIn: Cataloger, player: AbstractClientPlayer) {
        if (standIn.tickCount != player.tickCount) {
            standIn.tickCount = player.tickCount
            standIn.advanceWalkAnimation(player)
        }
        standIn.setPos(player.x, player.y, player.z)
        standIn.xo = player.xo
        standIn.yo = player.yo
        standIn.zo = player.zo
        standIn.xOld = player.xOld
        standIn.yOld = player.yOld
        standIn.zOld = player.zOld
        standIn.yRot = player.yRot
        standIn.yRotO = player.yRotO
        standIn.xRot = player.xRot
        standIn.xRotO = player.xRotO
        standIn.yBodyRot = player.yBodyRot
        standIn.yBodyRotO = player.yBodyRotO
        standIn.yHeadRot = player.yHeadRot
        standIn.yHeadRotO = player.yHeadRotO
        standIn.isInvisible = player.isInvisible || player.isSpectator
    }
}
