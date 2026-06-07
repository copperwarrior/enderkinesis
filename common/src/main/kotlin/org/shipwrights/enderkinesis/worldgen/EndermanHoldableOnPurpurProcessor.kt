package org.shipwrights.enderkinesis.worldgen

import com.mojang.serialization.Codec
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.ServerLevelAccessor
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import org.shipwrights.enderkinesis.registry.EKProcessors

/**
 * Structure-decoration processor: for every purpur block (block/pillar/stairs/slab) in the
 * placed structure whose direct-above neighbour is air *within the same structure footprint*,
 * replaces that air with a random block drawn from {@code #minecraft:enderman_holdable}.
 *
 * Stays inside the structure's own block list — never reads or modifies pre-existing world
 * blocks. RNG is seeded off the structure's start position, so the same world seed produces
 * the same decorations for a given roam-ship instance.
 */
object EndermanHoldableOnPurpurProcessor : StructureProcessor() {

    val CODEC: Codec<EndermanHoldableOnPurpurProcessor> =
        Codec.unit(EndermanHoldableOnPurpurProcessor)

    override fun finalizeProcessing(
        level: ServerLevelAccessor,
        startPos: BlockPos,
        pivotPos: BlockPos,
        rawBlockInfos: MutableList<StructureTemplate.StructureBlockInfo>,
        processedBlockInfos: MutableList<StructureTemplate.StructureBlockInfo>,
        settings: StructurePlaceSettings,
    ): MutableList<StructureTemplate.StructureBlockInfo> {
        val holdable = level.level.registryAccess()
            .registryOrThrow(Registries.BLOCK)
            .getTag(BlockTags.ENDERMAN_HOLDABLE)
            .orElse(null)
        if (holdable == null || holdable.size() == 0) return processedBlockInfos

        // Index processed blocks by position so we can find "the block directly above" in O(1).
        val byPos = HashMap<BlockPos, Int>(processedBlockInfos.size * 2)
        for ((i, info) in processedBlockInfos.withIndex()) byPos[info.pos] = i

        val rng = settings.getRandom(startPos)
        val mutable = processedBlockInfos.toMutableList()

        // Iterate a defensive snapshot — we mutate `mutable` in place. Each qualifying purpur
        // has a small chance of getting decorated; sparse placement reads as accent decoration
        // rather than a junk pile.
        for (info in processedBlockInfos) {
            if (!isPurpur(info.state)) continue
            val aboveIdx = byPos[info.pos.above()] ?: continue
            val above = mutable[aboveIdx]
            if (!above.state.isAir) continue
            if (rng.nextFloat() >= DECORATION_CHANCE) continue
            val pick = holdable.getRandomElement(rng).orElse(null) ?: continue
            mutable[aboveIdx] = StructureTemplate.StructureBlockInfo(
                above.pos, pick.value().defaultBlockState(), null
            )
        }
        return mutable
    }

    /** Probability, per qualifying purpur block, of decorating it with a holdable. */
    private const val DECORATION_CHANCE = 0.01f

    override fun getType(): StructureProcessorType<*> = EKProcessors.ENDERMAN_HOLDABLE_ON_PURPUR.get()

    private fun isPurpur(state: BlockState): Boolean =
        state.`is`(Blocks.PURPUR_BLOCK)
            || state.`is`(Blocks.PURPUR_PILLAR)
            || state.`is`(Blocks.PURPUR_STAIRS)
            || state.`is`(Blocks.PURPUR_SLAB)
}
