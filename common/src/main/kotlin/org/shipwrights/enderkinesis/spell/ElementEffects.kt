package org.shipwrights.enderkinesis.spell

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.sounds.SoundEvents
import net.minecraft.tags.BlockTags
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.item.FallingBlockEntity
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
import org.joml.Vector3f
import org.shipwrights.enderkinesis.dimension.EKDamageSources
import org.shipwrights.enderkinesis.effect.FrozenEffect
import org.shipwrights.enderkinesis.registry.EKEffects
import org.valkyrienskies.mod.api.isBlockInShipyard
import kotlin.math.roundToInt
import org.shipwrights.enderkinesis.registry.EKParticles

/** One element's effect: what it does to the entities and blocks of a [Delivery] at a given
 *  magnitude, plus its particles and sound. */
fun interface ElementEffect {
    fun apply(ctx: SpellContext, delivery: Delivery, magnitude: Float)
}

/**
 * The element effects. A Mystic Gem's burst is exactly one of these as an Area cast at
 * magnitude 1, so at magnitude 1 every number here matches the old gems, apart from the
 * spec's changes: Growth no longer poisons, Purity crystallizes instead of withering, and
 * Blood's drain and buff use the new values.
 *
 * Gravity's and Chaos's block effects only happen on a [Delivery.SingleBlock] hit, never
 * across an area.
 */
object ElementEffects {

    fun of(element: Element): ElementEffect = when (element) {
        Element.HEAT -> HEAT
        Element.COLD -> COLD
        Element.GROWTH -> GROWTH
        Element.PURITY -> PURITY
        Element.GRAVITY -> GRAVITY
        Element.CHAOS -> CHAOS
        Element.BLOOD -> BLOOD
        // The fusions' and Sundering's effects aren't implemented yet.
        Element.SUNDERING -> SUNDERING
        Element.SUNFIRE -> FusionEffects.SUNFIRE
        Element.POISON -> FusionEffects.POISON
        Element.VOID -> FusionEffects.VOID
    }

    private val NONE = ElementEffect { _, _, _ -> }

    // -----------------------------------------------------------------------
    // Sundering — only reaches a spell through a beam merged with the Staff of Sundering's.
    // Magnitude is the staff's ramp stage, 1–4.
    // -----------------------------------------------------------------------

    /** Damage per stage each application, about the staff's own half-second at that stage. */
    private const val SUNDERING_DAMAGE_PER_STAGE = 1.25f

    private val SUNDERING = ElementEffect { ctx, delivery, magnitude ->
        val level = ctx.level
        val source = ctx.damage(DamageTypes.INDIRECT_MAGIC)
        for (target in delivery.entities(ctx)) {
            target.setSecondsOnFire(magnitude.roundToInt())
            target.hurt(source, SUNDERING_DAMAGE_PER_STAGE * magnitude)
        }
        SpellFx.burst(level, delivery, ParticleTypes.FLAME, 20)
    }

    // -----------------------------------------------------------------------
    // Heat — fire damage and burning, lights candles, sparse surface fire
    // -----------------------------------------------------------------------

    /** 1-in-N chance per air-above-sturdy block. 12 ≈ a couple of fires per burst on flat
     *  ground, sparse enough not to feel like a flamethrower. */
    private const val SURFACE_FIRE_DENOMINATOR = 12

    private val HEAT = ElementEffect { ctx, delivery, magnitude ->
        val level = ctx.level
        val burnSeconds = (4f * SpellCurves.curve(magnitude)).roundToInt()
        for (target in delivery.living(ctx)) {
            target.setSecondsOnFire(burnSeconds)
            target.hurt(ctx.damage(DamageTypes.IN_FIRE), ctx.damageAmount(4f, magnitude))
        }
        for (pos in delivery.blocks(level)) {
            val state = level.getBlockState(pos)
            when {
                state.hasProperty(CandleBlock.LIT) && !state.getValue(CandleBlock.LIT) ->
                    ctx.setBlock(pos, state.setValue(CandleBlock.LIT, true))
                state.hasProperty(CandleCakeBlock.LIT) && !state.getValue(CandleCakeBlock.LIT) ->
                    ctx.setBlock(pos, state.setValue(CandleCakeBlock.LIT, true))
                // Sturdy top face below is what BaseFireBlock uses for "can rest here", so the
                // fire survives at least one tick.
                state.isAir && delivery.scatterRoll(level.random, SURFACE_FIRE_DENOMINATOR) -> {
                    val below = pos.below()
                    if (level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
                        ctx.setBlock(pos, Blocks.FIRE.defaultBlockState())
                    }
                }
            }
        }
        // A FLAME shell with high outward speed reads as a detonation; SMALL_FLAME gives the
        // slower trailing embers.
        NamedEffects.heat(ctx, delivery)
        SpellFx.outwardBlast(level, delivery, ParticleTypes.FLAME, 100, 0.6)
        SpellFx.burst(level, delivery, ParticleTypes.SMALL_FLAME, 60)
        SpellFx.sound(level, delivery, SoundEvents.BLAZE_SHOOT)
    }

    // -----------------------------------------------------------------------
    // Cold — freeze damage and ticks, Skeleton → Stray, freezes fluids
    // -----------------------------------------------------------------------

    /** Freeze ticks per magnitude for one-shot deliveries; pulsed shapes give half. */
    private const val FREEZE_DOSE = 200
    private const val PULSED_FREEZE_DOSE = 100

    private val TINTED_SNOW = DustParticleOptions(Vector3f(0.78f, 0.88f, 1.0f), 1.5f)

    private val COLD = ElementEffect { ctx, delivery, magnitude ->
        val level = ctx.level
        val dose = ((if (ctx.pulsed) PULSED_FREEZE_DOSE else FREEZE_DOSE) * magnitude).roundToInt()
        for (living in delivery.living(ctx)) {
            // Convert before the damage: convertTo discards the skeleton and returns a fresh
            // stray, which should be the one that takes the hit.
            val target = if (living is Skeleton && living.javaClass == Skeleton::class.java) {
                living.convertTo(EntityType.STRAY, true) ?: living
            } else living
            target.ticksFrozen += dose
            // Frozen: enough built-up cold freezes the target solid. Vanilla drains the ticks.
            val frozen = EKEffects.FROZEN.get()
            if (target.ticksFrozen >= FrozenEffect.THRESHOLD && !target.hasEffect(frozen)) {
                target.addEffect(MobEffectInstance(frozen, SpellCurves.ticks(3f, 0.5f, magnitude)), ctx.caster)
            }
            target.hurt(ctx.damage(DamageTypes.FREEZE), ctx.damageAmount(3f, magnitude))
        }
        for (pos in delivery.blocks(level)) {
            val frozen = FluidFreezing.frozen(level.getBlockState(pos)) ?: continue
            ctx.setBlock(pos, frozen)
        }
        // SNOWFLAKE carries the visible swirl, TINTED_SNOW dust the cyan-blue tint.
        NamedEffects.cold(ctx, delivery)
        SpellFx.swirl(level, delivery, ParticleTypes.SNOWFLAKE, 100, 1.0)
        SpellFx.swirl(level, delivery, TINTED_SNOW, 140, 1.0)
        SpellFx.sound(level, delivery, SoundEvents.GLASS_BREAK, pitch = 0.7f)
        SpellFx.sound(level, delivery, SoundEvents.POWDER_SNOW_STEP, pitch = 0.5f)
    }

    // -----------------------------------------------------------------------
    // Growth — bone meal on 1 block in 3
    // -----------------------------------------------------------------------

    /** 3 → ~33 % coverage, a sprinkling rather than instant overgrowth. */
    private const val BONEMEAL_DENOMINATOR = 3

    private val GROWTH = ElementEffect { ctx, delivery, magnitude ->
        val level = ctx.level
        for (pos in delivery.blocks(level)) {
            if (!delivery.scatterRoll(level.random, BONEMEAL_DENOMINATOR)) continue
            if (!ctx.mayChange(pos, level.getBlockState(pos))) continue
            BoneMealItem.growCrop(ItemStack(Items.BONE_MEAL), level, pos)
        }
        NamedEffects.growth(ctx, delivery, magnitude)
        SpellFx.burst(level, delivery, ParticleTypes.HAPPY_VILLAGER, 80)
        SpellFx.burst(level, delivery, ParticleTypes.COMPOSTER, 50)
        SpellFx.sound(level, delivery, SoundEvents.SLIME_SQUISH, pitch = 1.5f)
    }

    // -----------------------------------------------------------------------
    // Purity — Crystallized; cleanses soils, logs, coral and organics
    // -----------------------------------------------------------------------

    private val PURITY = ElementEffect { ctx, delivery, magnitude ->
        val level = ctx.level
        // Cleanse first, so the scroll never strips what it applies.
        NamedEffects.cleanse(ctx, delivery)
        val crystallized = EKEffects.CRYSTALLIZED.get()
        for (target in delivery.living(ctx)) {
            target.addEffect(
                MobEffectInstance(crystallized, SpellCurves.ticks(4f, 1f, magnitude), SpellCurves.toAmplifier(magnitude)),
                ctx.caster,
            )
        }
        for (pos in delivery.blocks(level)) {
            val state = level.getBlockState(pos)
            val transformed = purityTransform(state)
            when {
                transformed != null -> {
                    if (ctx.setBlock(pos, transformed)) SpellFx.blockMark(level, ParticleTypes.END_ROD, pos)
                }
                purityDestroys(state) && ctx.mayChange(pos, Blocks.AIR.defaultBlockState()) -> {
                    // dropBlock=false still gives break particles and sound.
                    level.destroyBlock(pos, false)
                    SpellFx.blockMark(level, ParticleTypes.END_ROD, pos)
                }
            }
        }
        NamedEffects.purity(ctx, delivery, magnitude)
        SpellFx.outwardBlast(level, delivery, ParticleTypes.END_ROD, 80, 0.5)
        SpellFx.burst(level, delivery, ParticleTypes.GLOW, 60)
        SpellFx.sound(level, delivery, SoundEvents.AMETHYST_BLOCK_CHIME, pitch = 1.2f)
    }

    /** Surface- and biome-block conversions, or null when none applies (the destroy path
     *  handles those). Keeps AXIS for stripped logs, and WATERLOGGED + HORIZONTAL_FACING for
     *  dead coral so a wall fan stays a wall fan on the same face. */
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
        // AxeItem.STRIPPABLES is vanilla's axe-on-log mapping, so mod-added logs come for free.
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

    /** Organics that just go away rather than decompose to a less-living variant. */
    private fun purityDestroys(state: BlockState): Boolean {
        val block = state.block
        if (state.`is`(BlockTags.LEAVES) ||
            block is BushBlock ||                           // grass, flowers, saplings, crops, ferns, small mushrooms, sweet berries…
            block is VineBlock ||
            block is HugeMushroomBlock ||                   // red/brown mushroom blocks, mushroom stem
            state.`is`(BlockTags.WART_BLOCKS) ||
            state.`is`(BlockTags.UNDERWATER_BONEMEALS)      // kelp, sea pickle
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

    // -----------------------------------------------------------------------
    // Gravity — pulls every entity toward the centre; a struck block falls
    // -----------------------------------------------------------------------

    /** Pull speed at the edge of the effect, falling off linearly toward the centre. */
    private const val PULL_STRENGTH = 0.6

    private val GRAVITY = ElementEffect { ctx, delivery, magnitude ->
        val level = ctx.level
        // Every entity, not just living ones: items, XP, projectiles, boats and minecarts too.
        val reach = (delivery as? Delivery.Sphere)?.radius ?: SpellCurves.BASE_RADIUS
        for (entity in delivery.entities(ctx)) {
            val toCentre = pullCentre(ctx, delivery, entity.position()).subtract(entity.position())
            val dist = toCentre.length()
            if (dist < 1.0e-3) continue
            val factor = PULL_STRENGTH * (dist / reach).coerceAtMost(1.0)
            entity.deltaMovement = entity.deltaMovement.add(toCentre.scale(factor / dist))
            entity.hurtMarked = true
        }
        if (delivery is Delivery.SingleBlock) dropBlock(ctx, delivery.pos)
        // Particles converge from the perimeter to match the pull; REVERSE_PORTAL marks the
        // collapse point.
        NamedEffects.gravity(ctx, delivery, magnitude)
        SpellFx.inwardCollapse(level, delivery, ParticleTypes.PORTAL, 160, 2.0)
        SpellFx.burst(level, delivery, ParticleTypes.REVERSE_PORTAL, 30)
        SpellFx.sound(level, delivery, SoundEvents.ENDERMAN_TELEPORT, pitch = 0.6f)
    }

    /** Where Gravity pulls an entity at [from]: the sphere's centre, the nearest point on a
     *  line, or the cast point for a single target. */
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

    /** The struck block falls if it's a full cube, has no block entity, and is breakable. */
    fun dropBlock(ctx: SpellContext, pos: BlockPos) {
        val level = ctx.level
        val state = level.getBlockState(pos)
        if (!canMove(ctx, pos, state) || !state.isCollisionShapeFullBlock(level, pos)) return
        // A falling block can't leave a ship: it would fall through the shipyard instead.
        if (level.isBlockInShipyard(pos)) return
        if (!ctx.mayChange(pos, Blocks.AIR.defaultBlockState())) return
        FallingBlockEntity.fall(level, pos, state)
    }

    /** A block the spell can pick up: solid, breakable, and without a block entity. */
    private fun canMove(ctx: SpellContext, pos: BlockPos, state: BlockState): Boolean =
        !state.isAir && !state.hasBlockEntity() && state.getDestroySpeed(ctx.level, pos) >= 0f &&
            state.fluidState.isEmpty

    // -----------------------------------------------------------------------
    // Chaos — cycles living entities' positions; a struck block swaps
    // -----------------------------------------------------------------------

    /** Tries at finding a swap partner before the block swap gives up. */
    private const val SWAP_ATTEMPTS = 16

    private val CHAOS = ElementEffect { ctx, delivery, magnitude ->
        val level = ctx.level
        // An Area cast puts its caster in the cycle; with one mob nearby that swaps the two.
        val includeCaster = delivery is Delivery.Sphere && delivery.area
        val entities = delivery.living(ctx, includeCaster)
        if (entities.size >= 2) {
            // One-cycle permutation: entity i takes entity i+1's position, the last wraps to
            // the first. Positions are captured up front, and n ≥ 2 leaves no fixed points.
            val positions = entities.map { it.position() }
            for (i in entities.indices) {
                val target = positions[(i + 1) % positions.size]
                entities[i].teleportTo(target.x, target.y, target.z)
                level.sendParticles(ParticleTypes.PORTAL, target.x, target.y + 1.0, target.z, 30, 0.5, 1.0, 0.5, 0.05)
            }
        }
        if (delivery is Delivery.SingleBlock) swapBlock(ctx, delivery.pos, magnitude)
        NamedEffects.chaos(ctx, delivery, magnitude)
        SpellFx.burst(level, delivery, ParticleTypes.REVERSE_PORTAL, 60)
        SpellFx.sound(level, delivery, SoundEvents.ENDERMAN_TELEPORT, pitch = if (entities.size >= 2) 0.7f else 1.4f)
    }

    /** Swaps the struck block with a random block within 2 + magnitude. Neither may have a
     *  block entity. */
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
            level.sendParticles(ParticleTypes.PORTAL, other.x + 0.5, other.y + 0.5, other.z + 0.5, 20, 0.4, 0.4, 0.4, 0.1)
            return
        }
    }

    // -----------------------------------------------------------------------
    // Blood — drains living non-undead; the recipient gets Regeneration and Health Boost
    // -----------------------------------------------------------------------

    private const val DRAIN_DAMAGE = 4f
    /** Buff per drained target, and the cap per magnitude, in ticks. */
    private const val BUFF_TICKS_PER_DRAIN = 60
    private const val BUFF_CAP_TICKS_PER_MAGNITUDE = 200

    private val BLOOD_DUST = DustParticleOptions(Vector3f(0.85f, 0.10f, 0.10f), 1.4f)

    private val BLOOD = ElementEffect { ctx, delivery, magnitude ->
        val level = ctx.level
        // Undead have no life-force: they are never drained and never fed.
        val living = delivery.living(ctx).filter { !SpellContext.isUndead(it) }
        val recipient = bloodRecipient(ctx, living)
        val drainedTargets = ArrayList<LivingEntity>()
        for (target in living) {
            if (target === recipient) continue
            // `hurt` is false for invulnerable, i-framed or immune targets, which then don't
            // count toward the buff.
            if (target.hurt(ctx.damage(EKDamageSources.BLOOD_DRAIN_KEY), ctx.damageAmount(DRAIN_DAMAGE, magnitude))) drainedTargets += target
        }
        val drained = drainedTargets.size
        // Enthralled and Bloodbound tie the drained to the caster, or a rune's owner.
        NamedEffects.blood(ctx, drainedTargets, ctx.caster ?: ctx.buffRecipient, magnitude)
        if (drained == 0) {
            SpellFx.burst(level, delivery, ParticleTypes.SMOKE, 40)
            SpellFx.sound(level, delivery, SoundEvents.WITHER_HURT, pitch = 1.4f)
            return@ElementEffect
        }
        if (recipient != null && recipient.isAlive && !SpellContext.isUndead(recipient)) {
            val cap = (BUFF_CAP_TICKS_PER_MAGNITUDE * magnitude).roundToInt()
            val duration = (BUFF_TICKS_PER_DRAIN * drained).coerceAtMost(cap)
            val amplifier = SpellCurves.tierAmplifier(magnitude)
            recipient.addEffect(MobEffectInstance(MobEffects.HEALTH_BOOST, duration, amplifier), ctx.caster)
            recipient.addEffect(MobEffectInstance(MobEffects.REGENERATION, duration, amplifier), ctx.caster)
        }
        SpellFx.burst(level, delivery, EKParticles.bloodDrip(), 80)
        SpellFx.burst(level, delivery, BLOOD_DUST, 100)
        SpellFx.sound(level, delivery, SoundEvents.AMETHYST_BLOCK_RESONATE, pitch = 0.6f)
    }

    /** The caster, an override (a rune's owner), or else the living non-undead target
     *  nearest the cast point, who is then spared the drain. */
    private fun bloodRecipient(ctx: SpellContext, candidates: List<LivingEntity>): LivingEntity? =
        ctx.buffRecipient ?: ctx.caster
            ?: if (ctx.nearestRecipient) candidates.minByOrNull { it.position().distanceToSqr(ctx.castPoint) } else null
}
