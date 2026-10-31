package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.TagKey
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.MushroomBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature

/** Survives in any light on Wohlon mud, moss and rotting wogor wood; elsewhere the vanilla darkness rule applies. */
class WohlonMushroomBlock(properties: Properties, hugeFeature: ResourceKey<ConfiguredFeature<*, *>>) :
    MushroomBlock(properties, hugeFeature) {

    @Deprecated("Deprecated in Java")
    override fun canSurvive(state: BlockState, level: LevelReader, pos: BlockPos): Boolean {
        val below = pos.below()
        val ground = level.getBlockState(below)
        if (ground.`is`(FUNGUS_SUBSTRATE) && ground.isFaceSturdy(level, below, Direction.UP)) return true
        return super.canSurvive(state, level, pos)
    }

    companion object {
        val FUNGUS_SUBSTRATE: TagKey<Block> =
            TagKey.create(Registries.BLOCK, ResourceLocation("enderkinesis", "wohlon_fungus_substrate"))

        fun hugeFeature(path: String): ResourceKey<ConfiguredFeature<*, *>> =
            ResourceKey.create(Registries.CONFIGURED_FEATURE, ResourceLocation("enderkinesis", path))
    }
}
