package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientPlayerEvent
import dev.architectury.networking.NetworkManager
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import org.shipwrights.enderkinesis.blockentity.ClientDraftingTables
import org.shipwrights.enderkinesis.construction.DraftingTableNetwork
import org.shipwrights.enderkinesis.construction.PlanAnchor
import org.shipwrights.enderkinesis.construction.PlanNetwork
import org.shipwrights.enderkinesis.construction.PlanRef
import org.shipwrights.enderkinesis.construction.TablePlacement
import org.shipwrights.enderkinesis.construction.readPlanRef
import org.shipwrights.enderkinesis.construction.readTableView
import org.shipwrights.enderkinesis.construction.writePlanRef
import org.shipwrights.enderkinesis.construction.writeTablePlacement

/** Client side of the drafting table: the sketchbook, the table screen and its pending placement for the ghost. */
object DraftingTableClient {

    /** The plan and pending anchor the open table screen shows, in place of that table's own ghost. */
    class Preview(val table: BlockPos, val plan: PlanRef, val anchor: PlanAnchor)

    // Render and client thread only; the table screen sets it.
    var preview: Preview? = null
        private set

    internal fun showPreview(next: Preview?) {
        preview = next
    }

    fun init() {
        ClientPlayerEvent.CLIENT_PLAYER_QUIT.register {
            preview = null
            ClientDraftingTables.loaded.clear()
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, DraftingTableNetwork.OPEN_SKETCHBOOK) { buf, ctx ->
            val view = buf.readTableView()
            val count = buf.readVarInt().coerceIn(0, PlanNetwork.MAX_LISTED_PLANS)
            val plans = ArrayList<PlanRef>(count)
            repeat(count) { buf.readPlanRef()?.let(plans::add) }
            ctx.queue {
                Minecraft.getInstance().setScreen(
                    PlanChoiceScreen(
                        plans,
                        onPick = { choose(view.pos, it) },
                        // A table with a plan goes on to its screen, so Build is one click away.
                        onCancel = { if (view.plan != null) Minecraft.getInstance().setScreen(DraftingTableScreen(view)) },
                    ),
                )
            }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, DraftingTableNetwork.STATE) { buf, ctx ->
            val view = buf.readTableView()
            val open = buf.readBoolean()
            ctx.queue {
                val mc = Minecraft.getInstance()
                val screen = mc.screen
                if (screen is DraftingTableScreen && screen.table == view.pos) {
                    screen.update(view)
                } else if (open) {
                    mc.setScreen(DraftingTableScreen(view))
                }
            }
        }
    }

    private fun choose(table: BlockPos, ref: PlanRef) = DraftingTableNetwork.send(DraftingTableNetwork.CHOOSE, table) { it.writePlanRef(ref) }

    fun place(table: BlockPos, placement: TablePlacement) =
        DraftingTableNetwork.send(DraftingTableNetwork.PLACE, table) { it.writeTablePlacement(placement) }

    fun build(table: BlockPos) = DraftingTableNetwork.send(DraftingTableNetwork.BUILD, table)

    fun stop(table: BlockPos) = DraftingTableNetwork.send(DraftingTableNetwork.STOP, table)

    fun askSketchbook(table: BlockPos) = DraftingTableNetwork.send(DraftingTableNetwork.ASK_SKETCHBOOK, table)

    fun captureShip(table: BlockPos, name: String) =
        DraftingTableNetwork.send(DraftingTableNetwork.CAPTURE_SHIP, table) { it.writeUtf(name, PlanRef.MAX_NAME) }
}
