package org.shipwrights.enderkinesis.spell

import net.minecraft.tags.FluidTags
import org.shipwrights.enderkinesis.registry.EKSounds
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.tags.BlockTags
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.item.FallingBlockEntity
import org.shipwrights.enderkinesis.registry.EKParticles
import net.minecraft.world.entity.monster.Skeleton
import net.minecraft.world.item.AxeItem
import net.minecraft.world.item.BoneMealItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.BushBlock
import net.minecraft.world.level.block.CandleBlock
import net.minecraft.world.level.block.CandleCakeBlock
import net.minecraft.world.level.block.HugeMushroomBlock
import net.minecraft.world.level.block.VineBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.dimension.EKDamageSources
import org.shipwrights.enderkinesis.effect.FrozenEffect
import org.shipwrights.enderkinesis.registry.EKEffects
import org.valkyrienskies.mod.api.isBlockInShipyard
import kotlin.math.roundToInt

fun interface ElementEffect {
    fun apply(ctx: SpellContext, delivery: Delivery, magnitude: Float)
}

/** A Ulder Gem burst is a one-glyph Area cast, so a change here also changes the gems at magnitude 1. */
object ElementEffects {

    fun of(element: Element): ElementEffect {
        val effect = base(element)
        return ElementEffect { ctx, delivery, given ->
            val magnitude = given + (ctx.environment[element] ?: 0f)
            ctx.element = element
            try {
                effect.apply(ctx, delivery, magnitude)
            } finally {
                ctx.element = null
            }
            // Show the volume once for each pulse, not once for each share of a pulse.
            if (ctx.beat) SpellVfx.element(ctx, delivery, element, magnitude)
        }
    }

    private fun base(element: Element): ElementEffect = when (element) {
        Element.PYRE -> PYRE
        Element.WINTER -> WINTER
        Element.GENESIS -> GENESIS
        Element.QUINTESSENCE -> QUINTESSENCE
        Element.GRAVITY -> GRAVITY
        Element.CHAOS -> CHAOS
        Element.BLOOD -> BLOOD
        Element.SUNDERING -> SUNDERING
        Element.SUNFIRE -> FusionEffects.SUNFIRE
        Element.CORRUPTION -> FusionEffects.CORRUPTION
        Element.VOID -> FusionEffects.VOID
    }

    private val NONE = ElementEffect { _, _, _ -> }

    /** Magnitude is the ramp stage (1-4) of a merged Staff of Sundering beam. This value is near the staff's half-second damage. */
    private const val SUNDERING_DAMAGE_PER_STAGE = 1.25f

    private val SUNDERING = ElementEffect { ctx, delivery, magnitude ->
        val source = ctx.damage(DamageTypes.INDIRECT_MAGIC)
        for (target in delivery.entities(ctx)) {
            target.setSecondsOnFire(magnitude.roundToInt())
            ctx.hurt(target, source, SUNDERING_DAMAGE_PER_STAGE * magnitude * ctx.share)
        }
    }

    /** 60 gives scattered fires on flat ground, not a carpet of fire. */
    private const val SURFACE_FIRE_DENOMINATOR = 60

    private val PYRE = ElementEffect { ctx, delivery, magnitude ->
        val level = ctx.level
        val burnSeconds = (4f * SpellCurves.curve(magnitude)).roundToInt()
        for (target in delivery.living(ctx)) {
            target.setSecondsOnFire(burnSeconds)
            ctx.hurt(target, ctx.damage(EKDamageSources.PYRE_KEY), ctx.damageAmount(4f, magnitude))
        }
        for (pos in delivery.blocks(level)) {
            val state = level.getBlockState(pos)
            when {
                state.hasProperty(CandleBlock.LIT) && !state.getValue(CandleBlock.LIT) ->
                    ctx.setBlock(pos, state.setValue(CandleBlock.LIT, true))
                state.hasProperty(CandleCakeBlock.LIT) && !state.getValue(CandleCakeBlock.LIT) ->
                    ctx.setBlock(pos, state.setValue(CandleCakeBlock.LIT, true))
                // BaseFireBlock needs a sturdy top face below, else the fire goes out on the next tick.
                ctx.surfaceFire && state.isAir && delivery.scatterRoll(ctx, SURFACE_FIRE_DENOMINATOR) -> {
                    val below = pos.below()
                    if (level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
                        ctx.setBlock(pos, Blocks.FIRE.defaultBlockState())
                    }
                }
            }
        }
        NamedEffects.heat(ctx, delivery)
    }

    private const val FREEZE_DOSE = 200
    private const val PULSED_FREEZE_DOSE = 100
    private const val MAX_FREEZE_TICKS = FrozenEffect.THRESHOLD + 400

    private val WINTER = ElementEffect { ctx, delivery, magnitude ->
        val level = ctx.level
        val perPulse = (if (ctx.pulsed) PULSED_FREEZE_DOSE else FREEZE_DOSE) * magnitude
        for (living in delivery.living(ctx)) {
            val dose = ctx.portion(perPulse)
            // Convert before the damage: convertTo discards the skeleton, so the new stray must take the hit.
            val target = if (living is Skeleton && living.javaClass == Skeleton::class.java) {
                living.convertTo(EntityType.STRAY, true) ?: living
            } else living
            // Cap the dose: vanilla thaws 2 ticks for each tick.
            target.ticksFrozen = (target.ticksFrozen + dose).coerceAtMost(maxOf(target.ticksFrozen, MAX_FREEZE_TICKS))
            val frozen = EKEffects.FROZEN.get()
            if (target.ticksFrozen >= FrozenEffect.THRESHOLD && !target.hasEffect(frozen)) {
                target.addEffect(MobEffectInstance(frozen, SpellCurves.ticks(3f, 0.5f, magnitude)), ctx.caster)
            }
            ctx.hurt(target, ctx.damage(EKDamageSources.WINTER_KEY), ctx.damageAmount(3f, magnitude))
        }
        val volume = SpellSounds.magnitudeVolume(magnitude)
        val water = SpellSounds.FirstChange(ctx, EKSounds.SPELL_WINTER_WATER_FREEZE, volume)
        val lava = SpellSounds.FirstChange(ctx, EKSounds.SPELL_WINTER_LAVA_FREEZE, volume)
        for (pos in delivery.blocks(level)) {
            val old = level.getBlockState(pos)
            val frozen = FluidFreezing.frozen(old) ?: continue
            if (!ctx.setBlock(pos, frozen)) continue
            (if (old.fluidState.`is`(FluidTags.LAVA)) lava else water).at(pos)
        }
        NamedEffects.cold(ctx, delivery)
    }

    /** 3 gives approximately 33 % coverage, not instant overgrowth. */
    private const val BONEMEAL_DENOMINATOR = 3

    private val GENESIS = ElementEffect { ctx, delivery, magnitude ->
        val level = ctx.level
        // NamedEffects.growth does not heal the targets that this loop hurts.
        for (target in delivery.living(ctx)) {
            if (growthBurns(ctx, target)) ctx.hurt(target, ctx.damage(EKDamageSources.GENESIS_KEY), ctx.damageAmount(GROWTH_DAMAGE, magnitude))
        }
        for (pos in delivery.blocks(level)) {
            if (!delivery.scatterRoll(ctx, BONEMEAL_DENOMINATOR)) continue
            if (!ctx.mayChange(pos, level.getBlockState(pos))) continue
            if (BoneMealItem.growCrop(ItemStack(Items.BONE_MEAL), level, pos)) level.levelEvent(1505, pos, 0)
        }
        NamedEffects.growth(ctx, delivery, magnitude)
    }

    private const val GROWTH_DAMAGE = 3f

    fun growthBurns(ctx: SpellContext, target: LivingEntity): Boolean =
        SpellContext.isUndead(target) || (ctx.use == SpellUse.FORWARD && ctx.isFoe(target))

    private val QUINTESSENCE = ElementEffect { ctx, delivery, magnitude ->
        val level = ctx.level
        // Forward and Area casts also reach friends, so they cleanse. A Forward cast still crystallizes foes.
        val (foes, others) = delivery.living(ctx).partition { ctx.use.hostile || (ctx.use == SpellUse.FORWARD && ctx.isFoe(it)) }
        val crystallized = EKEffects.CRYSTALLIZED.get()
        for (target in foes) {
            if (ctx.beat) target.addEffect(
                MobEffectInstance(crystallized, SpellCurves.ticks(4f, 1f, magnitude), SpellCurves.toAmplifier(magnitude)),
                ctx.caster,
            )
            ctx.hurt(target, ctx.damage(EKDamageSources.CRYSTALLIZATION_KEY), ctx.damageAmount(PURITY_DAMAGE, magnitude))
        }
        NamedEffects.cleanse(ctx, others)
        val volume = SpellSounds.magnitudeVolume(magnitude)
        val purified = SpellSounds.FirstChange(ctx, EKSounds.SPELL_QUINTESSENCE_PURIFY_BLOCK, volume)
        val purged = SpellSounds.FirstChange(ctx, EKSounds.SPELL_QUINTESSENCE_PURGE, volume)
        for (pos in delivery.blocks(level)) {
            val state = level.getBlockState(pos)
            val transformed = purityTransform(state)
            when {
                transformed != null -> {
                    if (ctx.setBlock(pos, transformed)) {
                        SpellFx.blockMark(level, ParticleTypes.END_ROD, pos)
                        purified.at(pos)
                    }
                }
                purityDestroys(state) && ctx.mayChange(pos, Blocks.AIR.defaultBlockState()) -> {
                    level.destroyBlock(pos, false)
                    SpellFx.blockMark(level, ParticleTypes.END_ROD, pos)
                    purged.at(pos)
                }
            }
        }
        NamedEffects.purity(ctx, delivery, magnitude)
    }

    private const val PURITY_DAMAGE = 3f

    /** Keep WATERLOGGED and HORIZONTAL_FACING on dead coral, so that a wall fan stays on the same face. */
    private fun purityTransform(state: BlockState): BlockState? {
        val block = state.block
        if (block == Blocks.GRASS_BLOCK || block == Blocks.PODZOL ||
            block == Blocks.MYCELIUM || block == Blocks.DIRT_PATH
        ) {
            return Blocks.DIRT.defaultBlockState()
        }
        if (block == Blocks.CRIMSON_NYLIUM || block == Blocks.WARPED_NYLIUM) {
            return Blocks.NETHERRACK.defaultBlockState()
        }
        // AxeItem.STRIPPABLES also includes logs from other mods.
        AxeItem.STRIPPABLES[block]?.let { stripped ->
            var newState = stripped.defaultBlockState()
            if (state.hasProperty(BlockStateProperties.AXIS) && newState.hasProperty(BlockStateProperties.AXIS)) {
                newState = newState.setValue(BlockStateProperties.AXIS, state.getValue(BlockStateProperties.AXIS))
            }
            return newState
        }
        CORAL_LIVE_TO_DEAD[block]?.let { dead ->
            var newState = dead.defaultBlockState()
            if (state.hasProperty(BlockStateProperties.WATERLOGGED) && newState.hasProperty(BlockStateProperties.WATERLOGGED)) {
                newState = newState.setValue(BlockStateProperties.WATERLOGGED, state.getValue(BlockStateProperties.WATERLOGGED))
            }
            if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING) && newState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                newState = newState.setValue(BlockStateProperties.HORIZONTAL_FACING, state.getValue(BlockStateProperties.HORIZONTAL_FACING))
            }
            return newState
        }
        return null
    }

    private fun purityDestroys(state: BlockState): Boolean {
        val block = state.block
        if (state.`is`(BlockTags.LEAVES) ||
            block is BushBlock ||
            block is VineBlock ||
            block is HugeMushroomBlock ||
            state.`is`(BlockTags.WART_BLOCKS) ||
            state.`is`(BlockTags.UNDERWATER_BONEMEALS)
        ) return true
        return block == Blocks.MOSS_BLOCK ||
            block == Blocks.MOSS_CARPET ||
            block == Blocks.MELON ||
            block == Blocks.PUMPKIN ||
            block == Blocks.CARVED_PUMPKIN ||
            block == Blocks.JACK_O_LANTERN
    }

    private val CORAL_LIVE_TO_DEAD: Map<Block, Block> = mapOf(
        Blocks.TUBE_CORAL_BLOCK to Blocks.DEAD_TUBE_CORAL_BLOCK,
        Blocks.BRAIN_CORAL_BLOCK to Blocks.DEAD_BRAIN_CORAL_BLOCK,
        Blocks.BUBBLE_CORAL_BLOCK to Blocks.DEAD_BUBBLE_CORAL_BLOCK,
        Blocks.FIRE_CORAL_BLOCK to Blocks.DEAD_FIRE_CORAL_BLOCK,
        Blocks.HORN_CORAL_BLOCK to Blocks.DEAD_HORN_CORAL_BLOCK,
        Blocks.TUBE_CORAL to Blocks.DEAD_TUBE_CORAL,
        Blocks.BRAIN_CORAL to Blocks.DEAD_BRAIN_CORAL,
        Blocks.BUBBLE_CORAL to Blocks.DEAD_BUBBLE_CORAL,
        Blocks.FIRE_CORAL to Blocks.DEAD_FIRE_CORAL,
        Blocks.HORN_CORAL to Blocks.DEAD_HORN_CORAL,
        Blocks.TUBE_CORAL_FAN to Blocks.DEAD_TUBE_CORAL_FAN,
        Blocks.BRAIN_CORAL_FAN to Blocks.DEAD_BRAIN_CORAL_FAN,
        Blocks.BUBBLE_CORAL_FAN to Blocks.DEAD_BUBBLE_CORAL_FAN,
        Blocks.FIRE_CORAL_FAN to Blocks.DEAD_FIRE_CORAL_FAN,
        Blocks.HORN_CORAL_FAN to Blocks.DEAD_HORN_CORAL_FAN,
        Blocks.TUBE_CORAL_WALL_FAN to Blocks.DEAD_TUBE_CORAL_WALL_FAN,
        Blocks.BRAIN_CORAL_WALL_FAN to Blocks.DEAD_BRAIN_CORAL_WALL_FAN,
        Blocks.BUBBLE_CORAL_WALL_FAN to Blocks.DEAD_BUBBLE_CORAL_WALL_FAN,
        Blocks.FIRE_CORAL_WALL_FAN to Blocks.DEAD_FIRE_CORAL_WALL_FAN,
        Blocks.HORN_CORAL_WALL_FAN to Blocks.DEAD_HORN_CORAL_WALL_FAN,
    )

    /** Pull speed at the edge. It decreases linearly to zero at the centre. */
    private const val PULL_STRENGTH = 0.6
    private const val GRAVITY_CRUSH_DAMAGE = 3f

    private val GRAVITY = ElementEffect { ctx, delivery, magnitude ->
        val level = ctx.level
        // Pull all entities, not only living ones.
        val reach = (delivery as? Delivery.Sphere)?.radius ?: SpellCurves.BASE_RADIUS
        for (entity in delivery.entities(ctx)) {
            if (NamedEffects.anchored(entity)) continue
            val toCentre = pullCentre(ctx, delivery, entity.position()).subtract(entity.position())
            val dist = toCentre.length()
            if (dist < 1.0e-3) continue
            val factor = PULL_STRENGTH * (dist / reach).coerceAtMost(1.0) * ctx.share
            entity.deltaMovement = entity.deltaMovement.add(toCentre.scale(factor / dist))
            entity.hurtMarked = true
        }
        // Crush also the anchored targets that the pull ignores.
        for (target in delivery.living(ctx)) ctx.hurt(target, ctx.damage(EKDamageSources.GRAVITY_KEY), ctx.damageAmount(GRAVITY_CRUSH_DAMAGE, magnitude))
        if (delivery is Delivery.SingleBlock) dropBlock(ctx, delivery.pos)
        NamedEffects.gravity(ctx, delivery, magnitude)
    }

    private fun pullCentre(ctx: SpellContext, delivery: Delivery, from: Vec3): Vec3 = when (delivery) {
        is Delivery.Sphere -> delivery.center
        is Delivery.Line -> {
            val seg = delivery.to.subtract(delivery.from)
            val len2 = seg.lengthSqr()
            val t = if (len2 < 1.0e-6) 0.0 else (from.subtract(delivery.from).dot(seg) / len2).coerceIn(0.0, 1.0)
            delivery.from.add(seg.scale(t))
        }
        is Delivery.Cone -> delivery.origin
        is Delivery.SingleEntity, is Delivery.SingleBlock, is Delivery.Group -> ctx.castPoint
    }

    fun dropBlock(ctx: SpellContext, pos: BlockPos) {
        val level = ctx.level
        val state = level.getBlockState(pos)
        if (!canMove(ctx, pos, state) || !state.isCollisionShapeFullBlock(level, pos)) return
        // A FallingBlockEntity on a ship falls through the shipyard, not the world.
        if (level.isBlockInShipyard(pos)) return
        if (!ctx.mayChange(pos, Blocks.AIR.defaultBlockState())) return
        val falling = FallingBlockEntity.fall(level, pos, state)
        SpellVfx.press(level, pos, falling.id)
        SpellSounds.play(level, Vec3.atCenterOf(pos), EKSounds.SPELL_GRAVITY_BLOCK_PRESS)
    }

    private fun canMove(ctx: SpellContext, pos: BlockPos, state: BlockState): Boolean =
        !state.isAir && !state.hasBlockEntity() && state.getDestroySpeed(ctx.level, pos) >= 0f &&
            state.fluidState.isEmpty

    private const val SWAP_ATTEMPTS = 16
    private const val CHAOS_DAMAGE = 3f

    private val CHAOS = ElementEffect { ctx, delivery, magnitude ->
        val level = ctx.level
        // Only an Area cast cycles, and it includes the caster, so one nearby mob swaps with the caster.
        val cycles = ctx.use == SpellUse.AREA
        val entities = if (cycles) delivery.living(ctx, includeCaster = true).filter { NamedEffects.mayTeleport(ctx.level, it) } else emptyList()
        if (entities.size >= 2 && ctx.once()) {
            // Capture all positions before the first teleport.
            val positions = entities.map { it.position() }
            for (i in entities.indices) {
                val target = positions[(i + 1) % positions.size]
                SpellVfx.arc(level, positions[i].add(0.0, entities[i].bbHeight * 0.5, 0.0), target.add(0.0, entities[i].bbHeight * 0.5, 0.0), Element.CHAOS.color)
                entities[i].teleportTo(target.x, target.y, target.z)
            }
            SpellSounds.play(level, (delivery as? Delivery.Sphere)?.center ?: positions[0], EKSounds.SPELL_CHAOS_SWAP, SpellSounds.magnitudeVolume(magnitude))
        }
        for (target in delivery.living(ctx)) ctx.hurt(target, ctx.damage(EKDamageSources.CHAOS_KEY), ctx.damageAmount(CHAOS_DAMAGE, magnitude))
        if (delivery is Delivery.SingleBlock) swapBlock(ctx, delivery.pos, magnitude)
        NamedEffects.chaos(ctx, delivery, magnitude)
    }

    private fun swapBlock(ctx: SpellContext, pos: BlockPos, magnitude: Float) {
        val level = ctx.level
        val state = level.getBlockState(pos)
        if (!canMove(ctx, pos, state)) return
        val radius = (2f + magnitude).roundToInt()
        repeat(SWAP_ATTEMPTS) {
            val other = pos.offset(
                level.random.nextInt(radius * 2 + 1) - radius,
                level.random.nextInt(radius * 2 + 1) - radius,
                level.random.nextInt(radius * 2 + 1) - radius,
            )
            if (other == pos) return@repeat
            val otherState = level.getBlockState(other)
            if (otherState == state || !canMove(ctx, other, otherState)) return@repeat
            if (!ctx.mayChange(pos, otherState) || !ctx.mayChange(other, state)) return
            level.setBlock(pos, otherState, 3)
            level.setBlock(other, state, 3)
            SpellVfx.blockSwap(level, pos, other, state, otherState)
            SpellSounds.play(level, Vec3.atCenterOf(pos), EKSounds.SPELL_CHAOS_SWAP, SpellSounds.magnitudeVolume(magnitude))
            val a = net.minecraft.world.phys.Vec3.atCenterOf(pos)
            val b = net.minecraft.world.phys.Vec3.atCenterOf(other)
            SpellVfx.arc(level, a, b, Element.CHAOS.color, 8)
            for (c in listOf(a, b)) level.sendParticles(EKParticles.mote(SpellMote.CHAOS_GLYPH), c.x, c.y, c.z, 10, 0.4, 0.3, 0.4, 0.05)
            return
        }
    }

    private const val DRAIN_DAMAGE = 4f
    private const val BUFF_TICKS_PER_DRAIN = 60
    private const val BUFF_CAP_TICKS_PER_MAGNITUDE = 200

    private val BLOOD = ElementEffect { ctx, delivery, magnitude ->
        val level = ctx.level
        val living = delivery.living(ctx).filter { !SpellContext.isUndead(it) }
        val recipient = bloodRecipient(ctx, living)
        val master = (ctx.caster ?: ctx.buffRecipient)?.uuid
        val drainedTargets = ArrayList<LivingEntity>()
        for (target in living) {
            if (target === recipient) continue
            if (master != null && BloodPacts.isThrallOf(target, master)) continue
            // A target that the hurt call does not damage gives no buff.
            if (ctx.hurt(target, ctx.damage(EKDamageSources.BLOOD_DRAIN_KEY), ctx.damageAmount(DRAIN_DAMAGE, magnitude))) drainedTargets += target
        }
        val drained = drainedTargets.size
        NamedEffects.blood(ctx, drainedTargets, ctx.caster ?: ctx.buffRecipient, magnitude)
        val sink = recipient?.takeIf { it.isAlive && ctx.beat }
        if (sink != null) {
            val to = sink.position().add(0.0, sink.bbHeight * 0.6, 0.0)
            for (target in drainedTargets) {
                SpellVfx.stream(level, target.position().add(0.0, target.bbHeight * 0.6, 0.0), to, Element.BLOOD.color)
            }
            if (drainedTargets.isNotEmpty()) SpellSounds.play(level, to, EKSounds.SPELL_BLOOD_STREAM, SpellSounds.magnitudeVolume(magnitude))
        }
        if (drained == 0) {
            if (ctx.beat) {
                SpellVfx.fizzle(level, delivery.anchor)
                SpellSounds.play(level, delivery.anchor, EKSounds.SPELL_BLOOD_FIZZLE, SpellSounds.magnitudeVolume(magnitude))
            }
            return@ElementEffect
        }
        if (recipient != null && recipient.isAlive && !SpellContext.isUndead(recipient)) {
            val cap = (BUFF_CAP_TICKS_PER_MAGNITUDE * magnitude).roundToInt()
            val duration = (BUFF_TICKS_PER_DRAIN * drained).coerceAtMost(cap)
            val amplifier = SpellCurves.tierAmplifier(magnitude)
            recipient.addEffect(MobEffectInstance(EKEffects.BLOOD_SURGE.get(), duration, amplifier), ctx.caster)
            recipient.addEffect(MobEffectInstance(MobEffects.REGENERATION, duration, amplifier), ctx.caster)
        }
    }

    /** A nearest-target recipient is not drained, because the drain loop skips the recipient. */
    private fun bloodRecipient(ctx: SpellContext, candidates: List<LivingEntity>): LivingEntity? =
        ctx.buffRecipient ?: ctx.caster
            ?: if (ctx.nearestRecipient) candidates.minByOrNull { it.position().distanceToSqr(ctx.castPoint) } else null
}
