package org.shipwrights.enderkinesis.item

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtUtils
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.state.BlockState
import org.shipwrights.enderkinesis.entity.ConstriliarCollision
import org.valkyrienskies.mod.common.BlockStateInfo
import org.valkyrienskies.mod.common.config.MassDatapackResolver
import org.shipwrights.enderkinesis.item.StrikeReadout.Companion.line
import org.shipwrights.enderkinesis.item.StrikeReadout.Companion.round
import org.valkyrienskies.mod.common.config.VSGameConfig

data class BlockPhysicsReadout(
    val state: BlockState,
    override val owner: String,
    override val sketchSeed: Int,
    val mass: Double,
    val friction: Double,
    val elasticity: Double,
    val shipCollision: Boolean,
    val hardness: Float,
    val blastResistance: Float,
    val slipperiness: Float,
    val speedFactor: Float,
    val jumpFactor: Float,
) : StrikeReadout {
    override val title: Component
        get() = state.block.name

    override fun rounded(): Component = Component.translatable(
        "message.enderkinesis.silverpoint.rounded",
        title,
        round(mass, 0),
        round(friction, 2),
        round(elasticity, 2),
        round(hardness.toDouble(), 1),
        round(blastResistance.toDouble(), 1),
    )

    override val sketch: SketchSpec
        get() = SketchSpec(SketchSubject.Block(state), sketchSeed, StrikeReadout.SKETCH_SIZE, notes = true)

    override fun lines(): List<Component> = listOf(
        line("mass", mass.toString()),
        line("friction", friction.toString()),
        line("elasticity", elasticity.toString()),
        line("ship_collision", Component.translatable(if (shipCollision) "gui.yes" else "gui.no")),
        line("hardness", hardness.toString()),
        line("blast_resistance", blastResistance.toString()),
        line("slipperiness", slipperiness.toString()),
        line("speed_factor", speedFactor.toString()),
        line("jump_factor", jumpFactor.toString()),
    )

    override fun save(): CompoundTag = CompoundTag().apply {
        putString(KEY_BLOCK, BuiltInRegistries.BLOCK.getKey(state.block).toString())
        put(KEY_STATE, NbtUtils.writeBlockState(state))
        putString(StrikeReadout.KEY_OWNER, owner)
        putInt(StrikeReadout.KEY_SKETCH_SEED, sketchSeed)
        putDouble(KEY_MASS, mass)
        putDouble(KEY_FRICTION, friction)
        putDouble(KEY_ELASTICITY, elasticity)
        putBoolean(KEY_SHIP_COLLISION, shipCollision)
        putFloat(KEY_HARDNESS, hardness)
        putFloat(KEY_BLAST, blastResistance)
        putFloat(KEY_SLIP, slipperiness)
        putFloat(KEY_SPEED, speedFactor)
        putFloat(KEY_JUMP, jumpFactor)
    }

    companion object {
        private const val KEY_BLOCK = "block"
        private const val KEY_STATE = "state"
        private const val KEY_MASS = "mass"
        private const val KEY_FRICTION = "friction"
        private const val KEY_ELASTICITY = "elasticity"
        private const val KEY_SHIP_COLLISION = "ship_collision"
        private const val KEY_HARDNESS = "hardness"
        private const val KEY_BLAST = "blast_resistance"
        private const val KEY_SLIP = "slipperiness"
        private const val KEY_SPEED = "speed_factor"
        private const val KEY_JUMP = "jump_factor"

        // Unlisted blocks get VS2's config defaults when the resolver builds the physics state.
        fun of(level: BlockGetter, pos: BlockPos, state: BlockState, owner: String, sketchSeed: Int): BlockPhysicsReadout {
            val block = state.block
            return BlockPhysicsReadout(
                state = state,
                owner = owner,
                sketchSeed = sketchSeed,
                mass = BlockStateInfo.get(state)?.first ?: VSGameConfig.SERVER.defaultBlockMass,
                friction = MassDatapackResolver.getBlockStateFriction(state)
                    ?: VSGameConfig.SERVER.defaultBlockFriction,
                elasticity = MassDatapackResolver.getBlockStateElasticity(state)
                    ?: VSGameConfig.SERVER.defaultBlockElasticity,
                shipCollision = !ConstriliarCollision.shipPassable(state),
                hardness = state.getDestroySpeed(level, pos),
                blastResistance = block.explosionResistance,
                slipperiness = block.friction,
                speedFactor = block.speedFactor,
                jumpFactor = block.jumpFactor,
            )
        }

        fun load(tag: CompoundTag): BlockPhysicsReadout? {
            val id = ResourceLocation.tryParse(tag.getString(KEY_BLOCK)) ?: return null
            val state = if (tag.contains(KEY_STATE, Tag.TAG_COMPOUND.toInt())) {
                NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), tag.getCompound(KEY_STATE))
            } else {
                BuiltInRegistries.BLOCK.get(id).defaultBlockState()
            }
            return BlockPhysicsReadout(
                state = state,
                owner = tag.getString(StrikeReadout.KEY_OWNER),
                sketchSeed = tag.getInt(StrikeReadout.KEY_SKETCH_SEED),
                mass = tag.getDouble(KEY_MASS),
                friction = tag.getDouble(KEY_FRICTION),
                elasticity = tag.getDouble(KEY_ELASTICITY),
                shipCollision = tag.getBoolean(KEY_SHIP_COLLISION),
                hardness = tag.getFloat(KEY_HARDNESS),
                blastResistance = tag.getFloat(KEY_BLAST),
                slipperiness = tag.getFloat(KEY_SLIP),
                speedFactor = tag.getFloat(KEY_SPEED),
                jumpFactor = tag.getFloat(KEY_JUMP),
            )
        }
    }
}
