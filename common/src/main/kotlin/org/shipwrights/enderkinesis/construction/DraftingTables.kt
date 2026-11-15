package org.shipwrights.enderkinesis.construction

import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Container
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.state.properties.ChestType
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.block.DraftingTableBlock
import org.shipwrights.enderkinesis.blockentity.ClawhodBlockEntity
import org.shipwrights.enderkinesis.blockentity.DraftingTableBlockEntity
import org.shipwrights.enderkinesis.item.PlanPlacement
import org.shipwrights.enderkinesis.item.StaffOfCommandItem
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.shipwrights.enderkinesis.util.CrossFrameBlocks
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.isBlockInShipyard
import org.valkyrienskies.mod.common.toWorldCoordinates
import java.util.UUID

// Server side of the drafting table. Every order from a client names the table, and the server checks it again.

/** The user's rule: a build takes from any container within this many blocks of its table. */
internal const val TABLE_SUPPLY_RADIUS = 8.0
private const val TABLE_USE_DISTANCE_SQ = 8.0 * 8.0
private const val KEY = "message.enderkinesis.drafting_table."

private fun note(key: String, vararg args: Any): Component = Component.translatable(KEY + key, *args)

/** A right-click on the table at [pos]: a staff that holds Clawhods hands them to the table; else the sketchbook opens. */
fun ServerLevel.useDraftingTable(pos: BlockPos, player: ServerPlayer, hand: InteractionHand) {
    val table = getBlockEntity(pos) as? DraftingTableBlockEntity ?: return
    val held = player.getItemInHand(hand)
    val clawhodId = BuiltInRegistries.BLOCK.getKey(EKBlocks.CLAWHOD.get())
    if (held.item is StaffOfCommandItem && StaffOfCommandItem.readEntries(held).any { it.blockId == clawhodId }) {
        assignCrew(player, pos, table, held)
        return
    }
    dropMissingPlan(table)
    DraftingTableNetwork.openSketchbook(player, view(pos, table, Component.empty()))
}

/**
 * The user's process: Clawhods go on the staff first, then the staff hands them all to a table. The Clawhods of this
 * level leave the staff and work for the table; they join its job now when it runs. Other staff entries stay.
 */
private fun ServerLevel.assignCrew(player: ServerPlayer, pos: BlockPos, table: DraftingTableBlockEntity, staff: ItemStack) {
    val clawhodId = BuiltInRegistries.BLOCK.getKey(EKBlocks.CLAWHOD.get())
    val dim = dimension().location()
    val data = BuildJobs.get(this)
    val job = data.jobAt(pos)
    val entries = StaffOfCommandItem.readEntries(staff)
    val kept = ArrayList<StaffOfCommandItem.Companion.Entry>(entries.size)
    var count = 0
    for (entry in entries) {
        if (entry.blockId != clawhodId || entry.dim != dim) {
            kept.add(entry)
            continue
        }
        // An unloaded Clawhod is assigned by its cell; a loaded cell with no Clawhod is a stale entry and goes.
        val clawhod = if (isLoaded(entry.pos)) getBlockEntity(entry.pos) as? ClawhodBlockEntity ?: continue else null
        data.assignCrew(entry.pos, table.tableId)
        if (clawhod != null && job != null) clawhod.ensureWorker().join(job)
        count++
    }
    StaffOfCommandItem.writeEntries(staff, kept)
    player.displayClientMessage(if (count == 0) note("no_crew") else note("crew_assigned", count), true)
}

/** A plan the server no longer has is dropped with its placement. */
private fun ServerLevel.dropMissingPlan(table: DraftingTableBlockEntity) {
    val ref = table.plan ?: return
    val stored = PlanStorage.find(server, ref.owner, ref.hash)
    if (stored != null && PlanStorage.sections(server, stored) != null) return
    table.hold(null, TablePlacement.NONE)
}

/** The table at [pos] when [player] may use it now; the position comes from the client. */
internal fun ServerLevel.tableFor(player: ServerPlayer, pos: BlockPos): DraftingTableBlockEntity? {
    if (player.serverLevel() !== this || !isLoaded(pos)) return null
    if (player.distanceToSqr(toWorldCoordinates(Vec3.atCenterOf(pos))) > TABLE_USE_DISTANCE_SQ) return null
    if (!mayEdit(this, player, pos)) return null
    return getBlockEntity(pos) as? DraftingTableBlockEntity
}

/** The table's state for its screen, with [note] as the last answer. */
internal fun ServerLevel.view(pos: BlockPos, table: DraftingTableBlockEntity, note: Component): TableView {
    val ship = if (isBlockInShipyard(pos)) getShipManagingPos(pos) else null
    val shipSize = ship?.shipAABB?.let { Vec3i(it.maxX() - it.minX() + 1, it.maxY() - it.minY() + 1, it.maxZ() - it.minZ() + 1) }
    val data = BuildJobs.get(this)
    return TableView(
        pos, table.plan, table.placement, data.jobAt(pos) != null, shipSize,
        table.suppliesAt(gameTime) { suppliesNear(pos, buildTuning().maxLinkedSupplies).size }, data.crewOf(table.tableId).size, note,
    )
}

private fun ServerLevel.answer(player: ServerPlayer, pos: BlockPos, table: DraftingTableBlockEntity, note: Component, open: Boolean = false) =
    DraftingTableNetwork.sendState(player, view(pos, table, note), open)

/** The sketchbook pick: only one of the player's own stored plans. A new plan gets a first placement beside the table. */
internal fun ServerLevel.choosePlan(player: ServerPlayer, pos: BlockPos, wanted: PlanRef) {
    val table = tableFor(player, pos) ?: return
    val stored = PlanStorage.list(server, player.uuid).firstOrNull { it.hash == wanted.hash }
    if (stored == null) return answer(player, pos, table, note("unknown_plan"), open = true)
    if (table.plan?.key != stored.key) {
        val front = getBlockState(pos).getValue(DraftingTableBlock.FACING)
        val placement = TablePlacement.initial(stored.size, front) ?: TablePlacement(Vec3i(0, 1, 0), Rotation.NONE, Mirror.NONE)
        stopFor(pos)
        table.hold(stored, placement)
    }
    answer(player, pos, table, note("chosen", stored.name), open = true)
}

/** A new offset, turn or mirror from the screen; refused past the reach and world limits. */
internal fun ServerLevel.placePlan(player: ServerPlayer, pos: BlockPos, placement: TablePlacement) {
    val table = tableFor(player, pos) ?: return
    val ref = table.plan ?: return answer(player, pos, table, note("no_plan"))
    if (placement == table.placement) return answer(player, pos, table, Component.empty())
    val refusal = planRefusal(pos, ref, placement)
    if (refusal != null) return answer(player, pos, table, note(refusal))
    // A job builds one placement; the user starts the moved plan with Build.
    val stopped = stopFor(pos)
    table.move(placement)
    answer(player, pos, table, if (stopped) note("moved_stopped") else Component.empty())
}

/** The message key that refuses [placement] of a plan of [size] at the table [pos], or null. */
internal fun ServerLevel.tableRefusal(pos: BlockPos, size: Vec3i, placement: TablePlacement): String? {
    val box = placement.box(pos, size)
    val onShip = isBlockInShipyard(pos)
    val claim = if (onShip) getShipManagingPos(pos)?.chunkClaim else null
    return when {
        !placement.inReach(size) -> "too_far"
        box.minY() < minBuildHeight || box.maxY() >= maxBuildHeight -> "out_of_world"
        // Shipyard cells are far past the world border; the ship's own claim bounds them instead.
        onShip && (claim == null || !claim.contains(box.minX() shr 4, box.minZ() shr 4) ||
            !claim.contains(box.maxX() shr 4, box.maxZ() shr 4)) -> "off_ship"
        !onShip && (!worldBorder.isWithinBounds(box.minX().toDouble(), box.minZ().toDouble()) ||
            !worldBorder.isWithinBounds(box.maxX() + 1.0, box.maxZ() + 1.0)) -> "out_of_world"
        else -> null
    }
}

/** [tableRefusal], and the user's rule that a placement may not remove the table. */
private fun ServerLevel.planRefusal(pos: BlockPos, ref: PlanRef, placement: TablePlacement): String? =
    tableRefusal(pos, ref.size, placement) ?: "removes_table".takeIf { removesTable(pos, ref, placement) }

/** True when the plan cell on the table holds air or another block. Structure void or the table itself keeps it. */
private fun ServerLevel.removesTable(pos: BlockPos, ref: PlanRef, placement: TablePlacement): Boolean {
    val local = placement.anchor(dimension(), pos, ref.size).cellLocalPos(pos)
    val size = ref.size
    if (local.x !in 0 until size.x || local.y !in 0 until size.y || local.z !in 0 until size.z) return false
    val plan = PlanStorage.sections(server, ref) ?: return false
    if (plan.isKeep(local.x, local.y, local.z)) return false
    return plan.stateAt(local.x, local.y, local.z)?.`is`(EKBlocks.DRAFTING_TABLE.get()) != true
}

/** Build: starts or rejoins the table's job, with the containers near it and its assigned Clawhods. */
internal fun ServerLevel.buildAt(player: ServerPlayer, pos: BlockPos) {
    val table = tableFor(player, pos) ?: return
    dropMissingPlan(table)
    val ref = table.plan ?: return answer(player, pos, table, note("no_plan"))
    planRefusal(pos, ref, table.placement)?.let { return answer(player, pos, table, note(it)) }
    val anchor = table.placement.anchor(dimension(), pos, ref.size)
    val result = BuildJobs.start(this, player, pos, table.tableId, ref, anchor, suppliesNear(pos, buildTuning().maxLinkedSupplies))
    if (result !is StartResult.Started) return answer(player, pos, table, result.message())
    val crew = loadedCrew(table.tableId)
    BuildJobs.get(this).job(result.jobId)?.let { job -> for (clawhod in crew) clawhod.ensureWorker().join(job) }
    answer(player, pos, table, if (crew.isEmpty()) note("no_clawhod") else result.message())
}

/** Stop: the table's job ends; its Clawhods fly home and stay assigned. */
internal fun ServerLevel.stopAt(player: ServerPlayer, pos: BlockPos) {
    val table = tableFor(player, pos) ?: return
    answer(player, pos, table, if (stopFor(pos)) note("stopped") else Component.empty())
}

private fun ServerLevel.stopFor(pos: BlockPos): Boolean = BuildJobs.cancelTable(this, pos)

/** The job of a table that moved starts again at [pos], for the same owner, with no new order. */
internal fun ServerLevel.restartTable(owner: UUID, pos: BlockPos, table: DraftingTableBlockEntity) {
    val ref = table.plan ?: return
    if (planRefusal(pos, ref, table.placement) != null) return
    val stored = PlanStorage.find(server, ref.owner, ref.hash) ?: return
    val anchor = table.placement.anchor(dimension(), pos, ref.size)
    val supplies = suppliesNear(pos, buildTuning().maxLinkedSupplies)
    val job = BuildJobs.get(this).openJob(this, owner, pos, table.tableId, stored, anchor, supplies)
    for (clawhod in loadedCrew(table.tableId)) clawhod.ensureWorker().join(job)
}

/** The assigned Clawhods that are loaded now; an assignment whose cell holds no Clawhod is dropped. */
private fun ServerLevel.loadedCrew(tableId: UUID): List<ClawhodBlockEntity> {
    val data = BuildJobs.get(this)
    val out = ArrayList<ClawhodBlockEntity>()
    for (cell in data.crewOf(tableId)) {
        if (!isLoaded(cell)) continue
        val clawhod = getBlockEntity(cell) as? ClawhodBlockEntity
        if (clawhod == null) data.dropCrew(cell) else out.add(clawhod)
    }
    return out
}

/**
 * Capture ship: saves the ship's block box as a plan through the capture writer. The plan keeps the ship's own
 * axes, and the table keeps the offset to it, so the plan lies exactly on the ship.
 */
internal fun ServerLevel.captureShip(player: ServerPlayer, pos: BlockPos, name: String) {
    val table = tableFor(player, pos) ?: return
    val aabb = (if (isBlockInShipyard(pos)) getShipManagingPos(pos) else null)?.shipAABB
        ?: return answer(player, pos, table, note("not_on_ship"))
    // VS2 shipAABB maxima are inclusive.
    val box = BoundingBox(aabb.minX(), aabb.minY(), aabb.minZ(), aabb.maxX(), aabb.maxY(), aabb.maxZ())
    val size = Vec3i(box.xSpan, box.ySpan, box.zSpan)
    val base = PlanPlacement.baseOf(BlockPos(box.minX(), box.minY(), box.minZ()), size, Rotation.NONE, Mirror.NONE)
    val placement = TablePlacement(base.subtract(pos), Rotation.NONE, Mirror.NONE)
    tableRefusal(pos, size, placement)?.let { return answer(player, pos, table, note(it)) }
    val clean = cleanPlanName(name)
    val dimension = dimension()
    val refusal = PlanCaptures.start(player, this, box, clean) { online, ref ->
        server.getLevel(dimension)?.captured(online, pos, ref, placement)
    }
    val said = if (refusal != null) Component.translatable(CAPTURE_KEY + refusal, PlanLimits.QUOTA) else note("capture_started", clean)
    answer(player, pos, table, said, open = true)
}

private const val CAPTURE_KEY = "item.enderkinesis.staff_of_command.build."

private fun ServerLevel.captured(player: ServerPlayer?, pos: BlockPos, ref: PlanRef, placement: TablePlacement) {
    if (!isLoaded(pos)) return
    val table = getBlockEntity(pos) as? DraftingTableBlockEntity ?: return
    stopFor(pos)
    table.hold(ref, placement)
    player?.let { DraftingTableNetwork.sendState(it, view(pos, table, note("ship_captured", ref.name)), open = false) }
}

/**
 * The containers whose cell middle is within [TABLE_SUPPLY_RADIUS] of the table, in the world and on ships, nearest
 * first, at most [max]. A double chest counts once. A fixed box of cells, so the cost is bounded.
 */
internal fun ServerLevel.suppliesNear(table: BlockPos, max: Int): List<BlockPos> {
    val near = CrossFrameBlocks(this, Vec3.atCenterOf(table), TABLE_SUPPLY_RADIUS)
    val found = ArrayList<Pair<BlockPos, Double>>()
    near.forEachWithin(TABLE_SUPPLY_RADIUS) { cell, _ ->
        if (!isLoaded(cell) || getBlockEntity(cell) !is Container) return@forEachWithin
        val state = getBlockState(cell)
        if (state.block is ChestBlock && state.getValue(ChestBlock.TYPE) == ChestType.RIGHT) return@forEachWithin
        val w = near.worldCentre
        found.add(cell.immutable() to near.visitedMiddle.distanceSquared(w.x, w.y, w.z))
    }
    found.sortBy { it.second }
    return found.take(max).map { it.first }
}
