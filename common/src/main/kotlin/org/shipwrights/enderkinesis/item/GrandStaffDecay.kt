package org.shipwrights.enderkinesis.item

import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.TagKey
import net.minecraft.util.RandomSource
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.BonemealableBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.FungusBlock
import net.minecraft.world.level.block.MushroomBlock
import net.minecraft.world.level.block.state.BlockState
import org.joml.Vector3d
import org.shipwrights.enderkinesis.registry.EKEffects
import org.shipwrights.enderkinesis.registry.EKParticles
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.mod.common.getShipsIntersecting

/** What the Grand Staff of Decay does while cast: Rot on all in range, rot wood and plants, and spread fungus. */
object GrandStaffDecay {

    const val RADIUS = 12.0
    private const val CAST_INTERVAL_TICKS = 10L
    // Outlasts the gap between refreshes, so the poison ends soon after the caster lets go.
    private const val EFFECT_TICKS = 60
    private const val BLOCK_SAMPLES = 16
    private const val SPREAD_TRIES = 4
    private const val MUSHROOM_CHANCE = 0.1f
    private const val BLOCK_FEED = 0.1f
    private const val TARGET_FEED = 0.25f

    val ROTS: TagKey<Block> = TagKey.create(Registries.BLOCK, ResourceLocation("enderkinesis", "decay_rots"))
    // Wogor comes into ROTS through the vanilla log, leaf and door tags.
    val IMMUNE: TagKey<Block> = TagKey.create(Registries.BLOCK, ResourceLocation("enderkinesis", "decay_immune"))

    fun isCasting(entity: LivingEntity): Boolean =
        entity.isUsingItem && entity.useItem.item is GrandStaffOfDecayItem

    /** Server and client both use this, so the tendrils go to the same entities the rot does. */
    fun targetsOf(level: Level, caster: LivingEntity): List<LivingEntity> = GrandStaffAura.targets(level, caster, RADIUS)

    fun tickCast(level: ServerLevel, caster: LivingEntity) {
        var nutrition = 0f
        if (level.gameTime % CAST_INTERVAL_TICKS == 0L) {
            val rot = EKEffects.ROT.get()
            for (target in targetsOf(level, caster)) {
                // No swirl particles: the tendrils and spores show the effect.
                target.addEffect(MobEffectInstance(rot, EFFECT_TICKS, 0, false, false, true), caster)
                nutrition += TARGET_FEED + DecaySpoilage.spoil(target)
            }
        }
        ships.clear()
        level.getShipsIntersecting(caster.boundingBox.inflate(RADIUS)).forEach(ships::add)
        val random = level.random
        repeat(BLOCK_SAMPLES) {
            if (!sampleInSphere(caster, random, world)) return@repeat
            if (decayAt(level, caster, pos.set(world.x, world.y, world.z), random)) nutrition += BLOCK_FEED
            // Ship blocks live in the shipyard, so the world sample never finds them.
            for (ship in ships) {
                ship.transform.worldToShip.transformPosition(world, local)
                if (decayAt(level, caster, pos.set(local.x, local.y, local.z), random)) nutrition += BLOCK_FEED
            }
        }
        DecaySpoilage.feed(caster, nutrition)
    }

    // Server thread only.
    private val ships = ArrayList<Ship>()
    private val world = Vector3d()
    private val local = Vector3d()
    private val pos = BlockPos.MutableBlockPos()

    /** False when the random point falls outside the sphere. */
    private fun sampleInSphere(caster: LivingEntity, random: RandomSource, out: Vector3d): Boolean {
        val dx = (random.nextDouble() * 2.0 - 1.0) * RADIUS
        val dy = (random.nextDouble() * 2.0 - 1.0) * RADIUS
        val dz = (random.nextDouble() * 2.0 - 1.0) * RADIUS
        if (dx * dx + dy * dy + dz * dz > RADIUS * RADIUS) return false
        out.set(caster.x + dx, caster.y + dy, caster.z + dz)
        return true
    }

    /** True when a block rotted away. */
    private fun decayAt(level: ServerLevel, caster: LivingEntity, pos: BlockPos.MutableBlockPos, random: RandomSource): Boolean {
        if (!level.isLoaded(pos)) return false
        if (caster is Player && !(caster.mayBuild() && level.mayInteract(caster, pos))) return false
        return decayBlock(level, pos, random)
    }

    private fun decayBlock(level: ServerLevel, pos: BlockPos.MutableBlockPos, random: RandomSource): Boolean {
        val state = level.getBlockState(pos)
        when {
            state.`is`(IMMUNE) -> Unit
            state.block is MushroomBlock || state.block is FungusBlock -> growHuge(level, pos.immutable(), state, random)
            state.`is`(ROTS) -> {
                level.destroyBlock(pos, false)
                puff(level, pos, EKParticles.rotSpore(), 4)
                return true
            }
            state.`is`(Blocks.MYCELIUM) -> spread(level, pos, random, state) { it.`is`(Blocks.DIRT) || it.`is`(Blocks.GRASS_BLOCK) }
            state.`is`(Blocks.CRIMSON_NYLIUM) || state.`is`(Blocks.WARPED_NYLIUM) ->
                spread(level, pos, random, state) { it.`is`(Blocks.NETHERRACK) }
            state.isAir && random.nextFloat() < MUSHROOM_CHANCE -> growMushroom(level, pos, random)
        }
        return false
    }

    /** Vanilla grass-spread reach: one block out, three down, one up. */
    private inline fun spread(
        level: ServerLevel, from: BlockPos, random: RandomSource, cover: BlockState, takes: (BlockState) -> Boolean,
    ) {
        val to = BlockPos.MutableBlockPos()
        repeat(SPREAD_TRIES) {
            to.setWithOffset(from, random.nextInt(3) - 1, random.nextInt(5) - 3, random.nextInt(3) - 1)
            if (!level.isLoaded(to) || !takes(level.getBlockState(to)) || !canBreathe(level, to)) return@repeat
            level.setBlockAndUpdate(to, cover)
            puff(level, to, ParticleTypes.MYCELIUM, 6)
        }
    }

    // Vanilla rule: a covered or flooded block cannot hold mycelium or nylium.
    private fun canBreathe(level: ServerLevel, pos: BlockPos): Boolean {
        val above = pos.above()
        return !level.getBlockState(above).isSolidRender(level, above) && level.getFluidState(above).isEmpty
    }

    private fun growMushroom(level: ServerLevel, pos: BlockPos, random: RandomSource) {
        val ground = level.getBlockState(pos.below())
        val mushroom = when {
            ground.`is`(Blocks.CRIMSON_NYLIUM) -> Blocks.CRIMSON_FUNGUS
            ground.`is`(Blocks.WARPED_NYLIUM) -> Blocks.WARPED_FUNGUS
            random.nextBoolean() -> Blocks.BROWN_MUSHROOM
            else -> Blocks.RED_MUSHROOM
        }.defaultBlockState()
        if (!level.getFluidState(pos).isEmpty || !mushroom.canSurvive(level, pos)) return
        level.setBlockAndUpdate(pos, mushroom)
        puff(level, pos, EKParticles.rotSpore(), 3)
        growHuge(level, pos.immutable(), mushroom, random)
    }

    /** A mushroom with no room to grow stays small; a later sample tries again. */
    private fun growHuge(level: ServerLevel, pos: BlockPos, state: BlockState, random: RandomSource) {
        val plant = state.block as? BonemealableBlock ?: return
        if (!plant.isValidBonemealTarget(level, pos, state, false)) return
        when (plant) {
            // Not performBonemeal: that succeeds only 40% of the time.
            is MushroomBlock -> plant.growMushroom(level, pos, state, random)
            else -> plant.performBonemeal(level, random, pos, state)
        }
        if (level.getBlockState(pos) !== state) puff(level, pos, EKParticles.rotSpore(), 12)
    }

    private fun puff(level: ServerLevel, pos: BlockPos, particle: ParticleOptions, count: Int) {
        level.sendParticles(particle, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, count, 0.3, 0.3, 0.3, 0.01)
    }
}
