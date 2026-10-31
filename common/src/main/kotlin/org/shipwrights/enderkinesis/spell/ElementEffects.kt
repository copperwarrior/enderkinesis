package org.shipwrights.enderkinesis.spell

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

/** One element's effect: what it does to the entities and blocks of a [Delivery] at a given
 *  magnitude, plus its particles and sound. */
fun interface ElementEffect {
    fun apply(ctx: SpellContext, delivery: Delivery, magnitude: Float)
}

/**
 * The element effects. A Mystic Gem's burst is a one-glyph scroll's Area cast, so the gems
 * do exactly what these do at magnitude 1. That includes what the scrolls added beyond the
 * old gems: Growth heals and burns the undead, Chaos and Void deal damage, Gravity crushes
 * what its collapse catches, and Weighted hurts whatever drags it about. Cast Forward, Growth
 * and Purity turn their harmful side on the caster's foes ([SpellContext.isFoe]) and keep
 * tending everyone else.
 *
 * Gravity's and Chaos's block effects only happen on a [Delivery.SingleBlock] hit, never
 * across an area.
 */
object ElementEffects {

    /** [element]'s effect, followed by the volume it leaves where it lands ([SpellVfx]). Its
     *  magnitude is raised by the elemental environment it's cast in. */
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
            // The volume a held shape leaves comes once a pulse, not with every share of one.
            if (ctx.beat) SpellVfx.element(ctx, delivery, element, magnitude)
        }
    }

    private fun base(element: Element): ElementEffect = when (element) {
        Element.HEAT -> HEAT
        Element.COLD -> COLD
        Element.GROWTH -> GROWTH
        Element.PURITY -> PURITY
        Element.GRAVITY -> GRAVITY
        Element.CHAOS -> CHAOS
        Element.BLOOD -> BLOOD
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
            ctx.hurt(target, source, SUNDERING_DAMAGE_PER_STAGE * magnitude * ctx.share)
        }
    }

    // -----------------------------------------------------------------------
    // Heat — fire damage and burning, lights candles, sparse surface fire
    // -----------------------------------------------------------------------

    /** 1-in-N chance per air-above-sturdy block. 60 leaves a fire or two from a small burst on
     *  flat ground and a handful from a full scroll's, scattered rather than a carpet. */
    private const val SURFACE_FIRE_DENOMINATOR = 60

    private val HEAT = ElementEffect { ctx, delivery, magnitude ->
        val level = ctx.level
        val burnSeconds = (4f * SpellCurves.curve(magnitude)).roundToInt()
        for (target in delivery.living(ctx)) {
            target.setSecondsOnFire(burnSeconds)
            ctx.hurt(target, ctx.damage(EKDamageSources.HEAT_KEY), ctx.damageAmount(4f, magnitude))
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

    // -----------------------------------------------------------------------
    // Cold — freeze damage and ticks, Skeleton → Stray, freezes fluids
    // -----------------------------------------------------------------------

    /** Freeze ticks per magnitude for one-shot deliveries; pulsed shapes give half. */
    private const val FREEZE_DOSE = 200
    private const val PULSED_FREEZE_DOSE = 100
    private const val MAX_FREEZE_TICKS = FrozenEffect.THRESHOLD + 400


    private val COLD = ElementEffect { ctx, delivery, magnitude ->
        val level = ctx.level
        val perPulse = (if (ctx.pulsed) PULSED_FREEZE_DOSE else FREEZE_DOSE) * magnitude
        for (living in delivery.living(ctx)) {
            val dose = ctx.portion(perPulse)
            // Convert before the damage: convertTo discards the skeleton and returns a fresh
            // stray, which should be the one that takes the hit.
            val target = if (living is Skeleton && living.javaClass == Skeleton::class.java) {
                living.convertTo(EntityType.STRAY, true) ?: living
            } else living
            // Never more than enough to keep it frozen a while: vanilla thaws 2 ticks a tick.
            target.ticksFrozen = (target.ticksFrozen + dose).coerceAtMost(maxOf(target.ticksFrozen, MAX_FREEZE_TICKS))
            // Frozen: enough built-up cold freezes the target solid. Vanilla drains the ticks.
            val frozen = EKEffects.FROZEN.get()
            if (target.ticksFrozen >= FrozenEffect.THRESHOLD && !target.hasEffect(frozen)) {
                target.addEffect(MobEffectInstance(frozen, SpellCurves.ticks(3f, 0.5f, magnitude)), ctx.caster)
            }
            ctx.hurt(target, ctx.damage(EKDamageSources.COLD_KEY), ctx.damageAmount(3f, magnitude))
        }
        for (pos in delivery.blocks(level)) {
            val frozen = FluidFreezing.frozen(level.getBlockState(pos)) ?: continue
            ctx.setBlock(pos, frozen)
        }
        NamedEffects.cold(ctx, delivery)
    }

    // -----------------------------------------------------------------------
    // Growth — bone meal on 1 block in 3
    // -----------------------------------------------------------------------

    /** 3 → ~33 % coverage, a sprinkling rather than instant overgrowth. */
    private const val BONEMEAL_DENOMINATOR = 3

    private val GROWTH = ElementEffect { ctx, delivery, magnitude ->
        val level = ctx.level
        // Life burns the undead, as Instant Health does; cast Forward, it also drags the
        // caster's foes back into the earth. Neither is healed (NamedEffects.growth).
        for (target in delivery.living(ctx)) {
            if (growthBurns(ctx, target)) ctx.hurt(target, ctx.damage(EKDamageSources.GROWTH_KEY), ctx.damageAmount(GROWTH_DAMAGE, magnitude))
        }
        for (pos in delivery.blocks(level)) {
            if (!delivery.scatterRoll(ctx, BONEMEAL_DENOMINATOR)) continue
            if (!ctx.mayChange(pos, level.getBlockState(pos))) continue
            // Vanilla's green bone-meal sparkles where it takes.
            if (BoneMealItem.growCrop(ItemStack(Items.BONE_MEAL), level, pos)) level.levelEvent(1505, pos, 0)
        }
        NamedEffects.growth(ctx, delivery, magnitude)
    }

    private const val GROWTH_DAMAGE = 3f

    /** Whom Growth hurts rather than heals: the undead, and on a Forward cast the caster's foes. */
    fun growthBurns(ctx: SpellContext, target: LivingEntity): Boolean =
        SpellContext.isUndead(target) || (ctx.use == SpellUse.FORWARD && ctx.isFoe(target))

    // -----------------------------------------------------------------------
    // Purity — Crystallized; cleanses soils, logs, coral and organics
    // -----------------------------------------------------------------------

    private val PURITY = ElementEffect { ctx, delivery, magnitude ->
        val level = ctx.level
        // A rune or an imbued blow crystallizes what it catches; Forward and Area casts, which
        // reach friends too, cleanse instead, but a Forward cast crystallizes the caster's foes.
        // Whatever it crystallizes takes the crystals' damage as they grow.
        val (foes, others) = delivery.living(ctx).partition { ctx.use.hostile || (ctx.use == SpellUse.FORWARD && ctx.isFoe(it)) }
        val crystallized = EKEffects.CRYSTALLIZED.get()
        for (target in foes) {
            // Once a pulse is enough to keep it growing.
            if (ctx.beat) target.addEffect(
                MobEffectInstance(crystallized, SpellCurves.ticks(4f, 1f, magnitude), SpellCurves.toAmplifier(magnitude)),
                ctx.caster,
            )
            ctx.hurt(target, ctx.damage(EKDamageSources.CRYSTALLIZATION_KEY), ctx.damageAmount(PURITY_DAMAGE, magnitude))
        }
        NamedEffects.cleanse(ctx, others)
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
    }

    private const val PURITY_DAMAGE = 3f

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
    // Gravity — pulls every entity toward the centre and crushes the living; a struck block falls
    // -----------------------------------------------------------------------

    /** Pull speed at the edge of the effect, falling off linearly toward the centre. */
    private const val PULL_STRENGTH = 0.6
    /** The collapse's crush, dealt to every creature it catches. */
    private const val GRAVITY_CRUSH_DAMAGE = 3f

    private val GRAVITY = ElementEffect { ctx, delivery, magnitude ->
        val level = ctx.level
        // Every entity, not just living ones: items, XP, projectiles, boats and minecarts too.
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
        // Crush: the collapse bears down on every creature it catches, held fast or not.
        for (target in delivery.living(ctx)) ctx.hurt(target, ctx.damage(EKDamageSources.GRAVITY_KEY), ctx.damageAmount(GRAVITY_CRUSH_DAMAGE, magnitude))
        if (delivery is Delivery.SingleBlock) dropBlock(ctx, delivery.pos)
        // Particles converge from the perimeter to match the pull; REVERSE_PORTAL marks the
        // collapse point.
        NamedEffects.gravity(ctx, delivery, magnitude)
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
        val falling = FallingBlockEntity.fall(level, pos, state)
        SpellVfx.press(level, pos, falling.id)
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
    private const val CHAOS_DAMAGE = 3f

    private val CHAOS = ElementEffect { ctx, delivery, magnitude ->
        val level = ctx.level
        // The cycle is the Area cast's alone, and puts its caster in it: with one mob nearby
        // that swaps the two.
        val cycles = ctx.use == SpellUse.AREA
        val entities = if (cycles) delivery.living(ctx, includeCaster = true).filter { NamedEffects.mayTeleport(ctx.level, it) } else emptyList()
        if (entities.size >= 2 && ctx.once()) {
            // One-cycle permutation: entity i takes entity i+1's position, the last wraps to
            // the first. Positions are captured up front, and n ≥ 2 leaves no fixed points.
            val positions = entities.map { it.position() }
            for (i in entities.indices) {
                val target = positions[(i + 1) % positions.size]
                // Lightning leaps along each swap before the entity follows it.
                SpellVfx.arc(level, positions[i].add(0.0, entities[i].bbHeight * 0.5, 0.0), target.add(0.0, entities[i].bbHeight * 0.5, 0.0), Element.CHAOS.color)
                entities[i].teleportTo(target.x, target.y, target.z)
            }
        }
        // Chaos damage to everything caught, never the caster.
        for (target in delivery.living(ctx)) ctx.hurt(target, ctx.damage(EKDamageSources.CHAOS_KEY), ctx.damageAmount(CHAOS_DAMAGE, magnitude))
        if (delivery is Delivery.SingleBlock) swapBlock(ctx, delivery.pos, magnitude)
        NamedEffects.chaos(ctx, delivery, magnitude)
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
            SpellVfx.blockSwap(level, pos, other, state, otherState)
            // Lightning between the two, the glyphs swirling round each.
            val a = net.minecraft.world.phys.Vec3.atCenterOf(pos)
            val b = net.minecraft.world.phys.Vec3.atCenterOf(other)
            SpellVfx.arc(level, a, b, Element.CHAOS.color, 8)
            for (c in listOf(a, b)) level.sendParticles(EKParticles.mote(SpellMote.CHAOS_GLYPH), c.x, c.y, c.z, 10, 0.4, 0.3, 0.4, 0.05)
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


    private val BLOOD = ElementEffect { ctx, delivery, magnitude ->
        val level = ctx.level
        // Undead have no life-force: they are never drained and never fed.
        val living = delivery.living(ctx).filter { !SpellContext.isUndead(it) }
        val recipient = bloodRecipient(ctx, living)
        // A master's thralls are never drained by their master's blood.
        val master = (ctx.caster ?: ctx.buffRecipient)?.uuid
        val drainedTargets = ArrayList<LivingEntity>()
        for (target in living) {
            if (target === recipient) continue
            if (master != null && BloodPacts.isThrallOf(target, master)) continue
            // `hurt` is false for invulnerable, i-framed or immune targets, which then don't
            // count toward the buff.
            if (ctx.hurt(target, ctx.damage(EKDamageSources.BLOOD_DRAIN_KEY), ctx.damageAmount(DRAIN_DAMAGE, magnitude))) drainedTargets += target
        }
        val drained = drainedTargets.size
        // Enthralled and Bloodbound tie the drained to the caster, or a rune's owner.
        NamedEffects.blood(ctx, drainedTargets, ctx.caster ?: ctx.buffRecipient, magnitude)
        // The blood visibly flows from each drained target to whoever it feeds.
        val sink = recipient?.takeIf { it.isAlive && ctx.beat }
        if (sink != null) {
            val to = sink.position().add(0.0, sink.bbHeight * 0.6, 0.0)
            for (target in drainedTargets) {
                SpellVfx.stream(level, target.position().add(0.0, target.bbHeight * 0.6, 0.0), to, Element.BLOOD.color)
            }
        }
        if (drained == 0) {
            // Nothing to drain: it fizzles, the glyphs falling grey.
            if (ctx.beat) SpellVfx.fizzle(level, delivery.anchor)
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

    /** The caster, an override (a rune's owner), or else the living non-undead target
     *  nearest the cast point, who is then spared the drain. */
    private fun bloodRecipient(ctx: SpellContext, candidates: List<LivingEntity>): LivingEntity? =
        ctx.buffRecipient ?: ctx.caster
            ?: if (ctx.nearestRecipient) candidates.minByOrNull { it.position().distanceToSqr(ctx.castPoint) } else null
}
