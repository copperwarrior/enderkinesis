package org.shipwrights.enderkinesis.worldgen

import com.mojang.serialization.Codec
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LecternBlock
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import org.shipwrights.enderkinesis.dimension.AlmanacData
import org.shipwrights.enderkinesis.dimension.YgannAbyss
import org.shipwrights.enderkinesis.registry.EKItems
import org.shipwrights.enderkinesis.registry.EKProcessors

/**
 * Seeds every lectern in the structure with an [EKItems.ALMANAC_OF_EVERYWHERE] whose
 * `VisitedDimensions` list already contains [YgannAbyss.ID].
 *
 * This is the *only* legitimate way an Almanac of Everywhere gains the Ygann's Abyss entry —
 * see [AlmanacData.addVisited], which refuses to record that dimension from normal player
 * flows. So the only path to the Abyss is finding this almanac on a lectern inside the
 * Roaming End Ship and reading the listed dimension into an Ender Astrolabe.
 *
 * Implementation: per-block transform via [processBlock]. When the block is a lectern, we
 * rewrite its [StructureTemplate.StructureBlockInfo] with `has_book=true` and a BE NBT
 * payload `{ Book: <almanac item NBT>, Page: 0, id: "minecraft:lectern" }`. The structure
 * template engine carries this NBT through placement and into the resulting lectern's
 * BlockEntity — and, because the Roaming End Ship is later assembled into a VS2 ship, the
 * book travels with the lectern into the shipyard.
 */
object SeedLecternAlmanacProcessor : StructureProcessor() {

    val CODEC: Codec<SeedLecternAlmanacProcessor> = Codec.unit(SeedLecternAlmanacProcessor)

    override fun processBlock(
        level: LevelReader,
        offset: BlockPos,
        pos: BlockPos,
        rawInfo: StructureTemplate.StructureBlockInfo,
        info: StructureTemplate.StructureBlockInfo,
        settings: StructurePlaceSettings,
    ): StructureTemplate.StructureBlockInfo {
        if (!info.state.`is`(Blocks.LECTERN)) return info

        val almanac = ItemStack(EKItems.ALMANAC_OF_EVERYWHERE.get())
        // `addEntryTrusted` bypasses the hidden-dimension filter that `addVisited` enforces —
        // this is the trusted seeding path that gates entry to the Abyss.
        AlmanacData.addEntryTrusted(almanac, YgannAbyss.ID)

        val beNbt = CompoundTag().apply {
            put("Book", almanac.save(CompoundTag()))
            putInt("Page", 0)
            // BE id isn't strictly required (StructureTemplate stamps it), but explicit is safe.
            putString("id", "minecraft:lectern")
        }
        val withBook = info.state.setValue(LecternBlock.HAS_BOOK, true)
        return StructureTemplate.StructureBlockInfo(info.pos, withBook, beNbt)
    }

    override fun getType(): StructureProcessorType<*> = EKProcessors.SEED_LECTERN_ALMANAC.get()
}
