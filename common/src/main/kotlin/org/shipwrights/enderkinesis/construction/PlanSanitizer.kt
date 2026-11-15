package org.shipwrights.enderkinesis.construction

import com.mojang.datafixers.DataFixer
import com.mojang.serialization.Dynamic
import net.minecraft.SharedConstants
import net.minecraft.core.Vec3i
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.NbtUtils
import net.minecraft.util.datafix.fixes.References

/** What a plan check found. [size] is null when the plan is refused; the issues then hold the reason. */
class PlanCheck(val size: Vec3i?, val nonAir: Long, val issues: List<PlanIssue>) {
    val accepted: Boolean get() = size != null
}

private const val KEY = "message.enderkinesis.plan."
private const val MAX_NAMED_ISSUES = 8

private val AIR_NAMES = setOf("minecraft:air", "minecraft:cave_air", "minecraft:void_air")
private const val STRUCTURE_VOID_NAME = "minecraft:structure_void"
private val ADMIN_BLOCKS = setOf(
    "minecraft:command_block", "minecraft:chain_command_block", "minecraft:repeating_command_block",
    "minecraft:structure_block", "minecraft:jigsaw",
)

fun refusedPlan(reason: String, vararg args: Any) = PlanCheck(null, 0, listOf(PlanIssue(KEY + reason, args.map(Any::toString))))

private fun namespaced(name: String): String = if (':' in name) name else "minecraft:$name"

/**
 * Cleans one palette entry at a time: data-fix, structure void to [planKeepTag], unknown blocks to air, admin blocks
 * to air unless [keepAdminBlocks].
 * [fix] and [canonical] default to the vanilla data fixer and registry; tests pass plain functions.
 */
class PlanPaletteSanitizer(
    private val isKnownBlock: (String) -> Boolean,
    private val keepAdminBlocks: Boolean,
    private val fix: (CompoundTag, Int) -> CompoundTag,
    private val canonical: (CompoundTag) -> CompoundTag,
) : PlanPaletteMapper {
    val issues = ArrayList<PlanIssue>()

    override fun map(raw: CompoundTag, dataVersion: Int): CompoundTag? {
        val fixed = fix(raw, dataVersion)
        val name = namespaced(fixed.getString("Name"))
        if (name in AIR_NAMES) return null
        if (name == STRUCTURE_VOID_NAME) return planKeepTag()
        val reason = when {
            !isKnownBlock(name) -> "unknown_block"
            !keepAdminBlocks && name in ADMIN_BLOCKS -> "stripped_block"
            else -> null
        }
        if (reason != null) {
            addNamedIssue(issues, KEY + reason, name)
            return null
        }
        return canonical(fixed).takeIf { namespaced(it.getString("Name")) !in AIR_NAMES }
    }

    companion object {
        /** Worker-thread safe: only the frozen block registry and the data fixer. */
        fun vanilla(fixer: DataFixer, trusted: Boolean) = PlanPaletteSanitizer(
            ::isKnownPlanBlock,
            keepAdminBlocks = trusted,
            fix = { tag, version -> fixBlockState(fixer, tag, version) },
            canonical = { NbtUtils.writeBlockState(readPlanState(it)) },
        )

        // Structure palettes are the BLOCK_STATE type in the vanilla STRUCTURE schema, so one entry fixes alone.
        private fun fixBlockState(fixer: DataFixer, tag: CompoundTag, version: Int): CompoundTag {
            val current = SharedConstants.getCurrentVersion().dataVersion.version
            if (version >= current) return tag
            val fixed = fixer.update(References.BLOCK_STATE, Dynamic(NbtOps.INSTANCE, tag), version, current).value
            return fixed as? CompoundTag ?: tag
        }
    }
}

/** The report of a finished conversion, plus the palette issues, in the order the old check gave them. */
fun planCheckOf(result: PlanConversion, paletteIssues: List<PlanIssue>): PlanCheck {
    if (result is PlanConversion.Refused) return refusedPlan(result.reason)
    val done = result as PlanConversion.Done
    val issues = ArrayList(paletteIssues)
    if (done.dropped > 0) issues += PlanIssue(KEY + "dropped_cells", listOf(done.dropped.toString()))
    if (done.strippedData > 0) issues += PlanIssue(KEY + "stripped_block_data", listOf(done.strippedData.toString()))
    return PlanCheck(done.size, done.nonAir, issues)
}

/** One line per name, at most [MAX_NAMED_ISSUES] names, then one "more" line. */
private fun addNamedIssue(issues: MutableList<PlanIssue>, key: String, name: String) {
    if (issues.any { it.key == key && it.args.firstOrNull() == name }) return
    val named = issues.count { it.key == key }
    when {
        named < MAX_NAMED_ISSUES -> issues += PlanIssue(key, listOf(name))
        issues.none { it.key == key + "_more" } -> issues += PlanIssue(key + "_more")
    }
}
