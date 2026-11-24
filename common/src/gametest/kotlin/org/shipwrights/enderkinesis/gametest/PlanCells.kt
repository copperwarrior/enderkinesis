package org.shipwrights.enderkinesis.gametest

import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Blocks
import org.shipwrights.enderkinesis.construction.BuildLog
import org.shipwrights.enderkinesis.construction.PlanAnchor
import org.shipwrights.enderkinesis.construction.PlanCost
import org.shipwrights.enderkinesis.construction.PlanRef
import org.shipwrights.enderkinesis.construction.PlanSections
import org.shipwrights.enderkinesis.construction.PlanStorage
import org.shipwrights.enderkinesis.construction.PlanUploadStream
import org.shipwrights.enderkinesis.construction.cellWorldPos
import org.shipwrights.enderkinesis.construction.cleanPlanName
import org.shipwrights.enderkinesis.construction.convertPlanFile
import org.shipwrights.enderkinesis.construction.isKeep
import org.shipwrights.enderkinesis.construction.planCost
import org.shipwrights.enderkinesis.construction.planMatches
import org.shipwrights.enderkinesis.construction.stateAt
import java.nio.file.Files
import java.util.TreeMap

/**
 * Stores the structure file [id] as a plan of [owner], as a finished upload does (PlanUploads.checkUpload and
 * finishCheck): the same conversion, check and store, with no network transfer. On the server thread, as a test step.
 */
internal fun importPlan(server: MinecraftServer, owner: ServerPlayer, id: ResourceLocation): PlanRef {
    val resource = server.resourceManager.getResource(id).orElse(null) ?: gameTestFail("no structure file $id")
    val bytes = resource.open().use { it.readAllBytes() }
    val scratch = PlanStorage.uploadDir(server)
    Files.createDirectories(scratch)
    val source = Files.createTempFile(scratch, owner.uuid.toString(), ".part")
    val converted = source.resolveSibling(source.fileName.toString() + ".plan")
    try {
        Files.write(source, bytes)
        val check = convertPlanFile(source, converted, scratch, server.fixerUpper, owner.hasPermissions(2)) { false }
        if (!check.accepted) gameTestFail("the plan check refused $id: ${check.issues}")
        val name = cleanPlanName(id.path.substringAfterLast('/'))
        return when (val stored = PlanStorage.store(server, owner.uuid, PlanUploadStream.sha256Hex(bytes), name, check, converted)) {
            is PlanStorage.Store.Stored -> stored.ref
            is PlanStorage.Store.Duplicate -> stored.ref
            else -> gameTestFail("cannot store the plan $id: $stored")
        }
    } finally {
        Files.deleteIfExists(source)
        Files.deleteIfExists(converted)
    }
}

/** What a plan costs by [planCost], in plan order, and the cells that no build can place. */
internal class PlanMaterials(val items: Map<Item, Int>, val refused: List<String>) {
    fun stacks(): List<ItemStack> = items.flatMap { (item, count) ->
        val max = item.maxStackSize
        (0 until count step max).map { ItemStack(item, minOf(max, count - it)) }
    }
}

internal fun planMaterials(sections: PlanSections): PlanMaterials {
    val items = LinkedHashMap<Item, Int>()
    val refused = ArrayList<String>()
    for (i in 0 until sections.nonEmptyCount) {
        val index = sections.nonEmptyIndex(i)
        val section = sections.sectionAt(index) ?: continue
        val origin = sections.grid.origin(index)
        section.forEachNonAir { x, y, z, state ->
            when (val cost = state.planCost()) {
                is PlanCost.Items -> {
                    items.merge(cost.item, cost.count, Integer::sum)
                    cost.extra?.let { items.merge(it, cost.extraCount, Integer::sum) }
                }
                PlanCost.Refused -> refused.add("${BuildLog.blockName(state)} at plan ${origin.offset(x, y, z).toShortString()}")
                PlanCost.Free -> Unit
            }
        }
    }
    return PlanMaterials(items, refused)
}

/** The plan cells whose world block fails the job's own [planMatches] rule, counted by plan section index. */
internal class CellCheck(val wrong: Int, private val perSection: Map<Int, Int>, private val samples: List<String>) {
    fun describe(sections: PlanSections): String = buildString {
        val grid = sections.grid
        appendLine("plan cells that do not match the paid state: $wrong")
        for ((index, count) in perSection) {
            appendLine("  section $index (${grid.sectionX(index)}, ${grid.sectionY(index)}, ${grid.sectionZ(index)}): $count remaining")
        }
        for (sample in samples) appendLine("  e.g. $sample")
    }
}

private const val MAX_SAMPLES = 8

/** One pass over the plan box; bounded by the plan size. */
internal fun checkPlanCells(level: ServerLevel, sections: PlanSections, anchor: PlanAnchor): CellCheck {
    val size = sections.size
    val perSection = TreeMap<Int, Int>()
    val samples = ArrayList<String>()
    var wrong = 0
    val air = Blocks.AIR.defaultBlockState()
    for (y in 0 until size.y) for (z in 0 until size.z) for (x in 0 until size.x) {
        if (sections.isKeep(x, y, z)) continue
        val want = sections.stateAt(x, y, z) ?: air
        val pos = anchor.cellWorldPos(BlockPos(x, y, z))
        val have = level.getBlockState(pos)
        if (planMatches(want, have)) continue
        wrong++
        perSection.merge(sections.grid.index(x shr 4, y shr 4, z shr 4), 1, Integer::sum)
        if (samples.size < MAX_SAMPLES) samples.add("${pos.toShortString()} wants $want, has $have")
    }
    return CellCheck(wrong, perSection, samples)
}
