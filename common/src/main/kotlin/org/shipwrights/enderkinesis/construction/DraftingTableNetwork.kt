package org.shipwrights.enderkinesis.construction

import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import org.shipwrights.enderkinesis.EnderkinesisMod

/** What the table screen shows. [shipSize] is the ship's block box when the table is on a ship. */
class TableView(
    val pos: BlockPos,
    val plan: PlanRef?,
    val placement: TablePlacement,
    val building: Boolean,
    val shipSize: Vec3i?,
    val supplies: Int,
    val crew: Int,
    val note: Component,
)

/**
 * - [OPEN_SKETCHBOOK] (S2C): the table state and the player's stored plans; the sketchbook opens.
 * - [STATE] (S2C): the table state after an order; it opens the table screen when asked.
 * - The C2S orders name the table; the server checks the player, the table and every value again.
 */
object DraftingTableNetwork {
    val OPEN_SKETCHBOOK: ResourceLocation = EnderkinesisMod.id("drafting_table/open_sketchbook")
    val STATE: ResourceLocation = EnderkinesisMod.id("drafting_table/state")
    val ASK_SKETCHBOOK: ResourceLocation = EnderkinesisMod.id("drafting_table/ask_sketchbook")
    val CHOOSE: ResourceLocation = EnderkinesisMod.id("drafting_table/choose")
    val PLACE: ResourceLocation = EnderkinesisMod.id("drafting_table/place")
    val BUILD: ResourceLocation = EnderkinesisMod.id("drafting_table/build")
    val STOP: ResourceLocation = EnderkinesisMod.id("drafting_table/stop")
    val CAPTURE_SHIP: ResourceLocation = EnderkinesisMod.id("drafting_table/capture_ship")

    fun init() {
        onTable(ASK_SKETCHBOOK, { }) { player, pos, _ ->
            val table = tableFor(player, pos) ?: return@onTable
            openSketchbook(player, view(pos, table, Component.empty()))
        }
        onTable(CHOOSE, FriendlyByteBuf::readPlanRef) { player, pos, ref -> ref?.let { choosePlan(player, pos, it) } }
        onTable(PLACE, FriendlyByteBuf::readTablePlacement) { player, pos, placement -> placePlan(player, pos, placement) }
        onTable(BUILD, { }) { player, pos, _ -> buildAt(player, pos) }
        onTable(STOP, { }) { player, pos, _ -> stopAt(player, pos) }
        onTable(CAPTURE_SHIP, { it.readUtf(PlanRef.MAX_NAME) }) { player, pos, name -> captureShip(player, pos, name) }
    }

    /** One C2S order: the table pos, then [read]; [handle] runs on the server thread in the player's level. */
    private inline fun <T> onTable(
        id: ResourceLocation,
        crossinline read: (FriendlyByteBuf) -> T,
        crossinline handle: ServerLevel.(ServerPlayer, BlockPos, T) -> Unit,
    ) {
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, id) { buf, ctx ->
            val pos = buf.readBlockPos()
            val value = read(buf)
            val player = ctx.player as? ServerPlayer ?: return@registerReceiver
            ctx.queue { player.serverLevel().handle(player, pos, value) }
        }
    }

    fun openSketchbook(player: ServerPlayer, view: TableView) {
        val plans = PlanStorage.list(player.server, player.uuid).take(PlanNetwork.MAX_LISTED_PLANS)
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeTableView(view)
        buf.writeVarInt(plans.size)
        for (ref in plans) buf.writePlanRef(ref)
        NetworkManager.sendToPlayer(player, OPEN_SKETCHBOOK, buf)
    }

    fun sendState(player: ServerPlayer, view: TableView, open: Boolean) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeTableView(view)
        buf.writeBoolean(open)
        NetworkManager.sendToPlayer(player, STATE, buf)
    }

    /** Client: an order with no value. */
    fun send(id: ResourceLocation, pos: BlockPos, write: (FriendlyByteBuf) -> Unit = { }) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeBlockPos(pos)
        write(buf)
        NetworkManager.sendToServer(id, buf)
    }
}

fun FriendlyByteBuf.writeTableView(view: TableView) {
    writeBlockPos(view.pos)
    writeBoolean(view.plan != null)
    view.plan?.let(::writePlanRef)
    writeTablePlacement(view.placement)
    writeBoolean(view.building)
    writeBoolean(view.shipSize != null)
    view.shipSize?.let {
        writeVarInt(it.x)
        writeVarInt(it.y)
        writeVarInt(it.z)
    }
    writeVarInt(view.supplies)
    writeVarInt(view.crew)
    writeComponent(view.note)
}

fun FriendlyByteBuf.readTableView(): TableView {
    val pos = readBlockPos()
    val plan = if (readBoolean()) readPlanRef() else null
    val placement = readTablePlacement()
    val building = readBoolean()
    val shipSize = if (readBoolean()) Vec3i(readVarInt(), readVarInt(), readVarInt()) else null
    return TableView(pos, plan, placement, building, shipSize, readVarInt(), readVarInt(), readComponent())
}
