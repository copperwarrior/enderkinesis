package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.IntegerProperty
import org.joml.Vector3dc
import org.shipwrights.enderkinesis.blockentity.FractalProjectorBlockEntity
import org.shipwrights.enderkinesis.registry.EKBlockEntities

/**
 * Fractal Projector — placeholder marker for an unobtainable structural
 * block used inside the Sselith Globe. Painted by Sselith worldgen
 * (the `sselith_globe.nbt` structure replacement); when the chunk
 * loads and the BE first ticks it spawns an Orb of Potential VS body
 * at this position and replaces itself with air, so the block never
 * persists in the runtime world.
 *
 * The [FRACTAL_TYPE] blockstate property is retained for
 * compatibility with structure NBTs that may bake a value in, but its
 * runtime value is unused — the orb's fractal pattern is derived
 * purely from the spawn anchor via [computeFractalType] so identical
 * positions always produce the same pattern.
 */
class FractalProjectorBlock(properties: Properties) : Block(properties), EntityBlock {

    init {
        registerDefaultState(stateDefinition.any().setValue(FRACTAL_TYPE, 0))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FRACTAL_TYPE)
    }

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.INVISIBLE

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        FractalProjectorBlockEntity(pos, state)

    @Suppress("UNCHECKED_CAST")
    override fun <T : BlockEntity> getTicker(
        level: Level, state: BlockState, type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? =
        if (!level.isClientSide && type == EKBlockEntities.FRACTAL_PROJECTOR.get()) {
            BlockEntityTicker<T> { lvl, pos, st, be ->
                FractalProjectorBlockEntity.serverTick(lvl, pos, st, be as FractalProjectorBlockEntity)
            }
        } else null

    companion object {
        /** Number of fractal variants — must match the dispatcher in
         *  `rendertype_fractal_projector_fractal.fsh`.
         *
         *  0 = Guillitte (cosmic filaments)
         *  1 = Menger sponge (cubic crystalline lattice)
         *  2 = Hypertexture (sparse Worley tendrils)
         *  3 = F1 Worley (cellular blobs)
         *  4 = DNA Helix (multiple double-helix strands)
         *  5 = Hypercube (animated tesseract projection)
         *  6 = Alligator (multi-octave Worley peaks)
         *  7 = Eye (iris + pupil facing the camera) */
        const val FRACTAL_TYPE_COUNT: Int = 8

        val FRACTAL_TYPE: IntegerProperty =
            IntegerProperty.create("fractal_type", 0, FRACTAL_TYPE_COUNT - 1)

        /** Stable per-anchor fractal pattern. SplitMix64 mixer over the
         *  three integer axes — `BlockPos.asLong() mod N` would sample
         *  only the low 12 bits (`y mod N`), so the mixer is needed for
         *  full 3D variation. Used on both sides so identical positions
         *  agree without needing the value over the wire. */
        @JvmStatic
        fun computeFractalType(anchor: Vector3dc): Int {
            val x = Math.floor(anchor.x()).toLong()
            val y = Math.floor(anchor.y()).toLong()
            val z = Math.floor(anchor.z()).toLong()
            var h = x * 0x9E3779B97F4A7C15UL.toLong()
            h = h xor (y * 0xBF58476D1CE4E5B9UL.toLong())
            h = h xor (z * 0x94D049BB133111EBUL.toLong())
            h = (h xor (h ushr 30)) * 0xBF58476D1CE4E5B9UL.toLong()
            h = (h xor (h ushr 27)) * 0x94D049BB133111EBUL.toLong()
            h = h xor (h ushr 31)
            return h.mod(FRACTAL_TYPE_COUNT.toLong()).toInt()
        }
    }
}
