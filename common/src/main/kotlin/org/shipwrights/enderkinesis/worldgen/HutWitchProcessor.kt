package org.shipwrights.enderkinesis.worldgen

import com.mojang.serialization.Codec
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.level.ServerLevelAccessor
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import org.shipwrights.enderkinesis.compat.distanthorizons.DistantHorizonsCompat
import org.shipwrights.enderkinesis.registry.EKProcessors

/** Puts one persistent witch on a free floor cell beside the structure's lectern. */
object HutWitchProcessor : StructureProcessor() {

    val CODEC: Codec<HutWitchProcessor> = Codec.unit(HutWitchProcessor)

    override fun finalizeProcessing(
        level: ServerLevelAccessor,
        startPos: BlockPos,
        pivotPos: BlockPos,
        rawBlockInfos: MutableList<StructureTemplate.StructureBlockInfo>,
        processedBlockInfos: MutableList<StructureTemplate.StructureBlockInfo>,
        settings: StructurePlaceSettings,
    ): MutableList<StructureTemplate.StructureBlockInfo> {
        if (DistantHorizonsCompat.isLodGeneration()) return processedBlockInfos
        val lectern = processedBlockInfos.firstOrNull { it.state.`is`(Blocks.LECTERN) } ?: return processedBlockInfos
        val states = HashMap<BlockPos, BlockState>(processedBlockInfos.size)
        for (info in processedBlockInfos) states[info.pos] = info.state
        val spot = Direction.Plane.HORIZONTAL.firstNotNullOfOrNull { dir ->
            lectern.pos.relative(dir).takeIf { isStandable(states, it) }
        } ?: return processedBlockInfos
        // A multi-chunk template is placed once per chunk; only the chunk that holds the spot spawns.
        val box = settings.boundingBox
        if (box != null && !box.isInside(spot)) return processedBlockInfos
        val witch = EntityType.WITCH.create(level.level) ?: return processedBlockInfos
        witch.setPersistenceRequired()
        witch.moveTo(spot.x + 0.5, spot.y.toDouble(), spot.z + 0.5, 0f, 0f)
        witch.finalizeSpawn(level, level.getCurrentDifficultyAt(spot), MobSpawnType.STRUCTURE, null, null)
        level.addFreshEntityWithPassengers(witch)
        return processedBlockInfos
    }

    private fun isStandable(states: Map<BlockPos, BlockState>, pos: BlockPos): Boolean {
        val feet = states[pos] ?: return false
        val head = states[pos.above()] ?: return false
        val floor = states[pos.below()] ?: return false
        return feet.isAir && head.isAir && !floor.isAir
    }

    override fun getType(): StructureProcessorType<*> = EKProcessors.HUT_WITCH.get()
}
