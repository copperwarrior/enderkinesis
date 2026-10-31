package org.shipwrights.enderkinesis.spell

import net.minecraft.core.BlockPos
import net.minecraft.tags.BlockTags
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.VineBlock
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.dimension.EKDamageSources
import org.shipwrights.enderkinesis.effect.RotEffect
import org.shipwrights.enderkinesis.entity.SingularityEntity
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.shipwrights.enderkinesis.registry.EKEffects
import kotlin.math.roundToInt

/** The fusions' effects. A fusion is its own element, not a mix of its parents. */
object FusionEffects {

    // -----------------------------------------------------------------------
    // Sunfire — the sun. Base: fire, doubled against undead, whose fire immunity doesn't
    // help them. Daylight, Phantom Light, Searing Clear.
    // -----------------------------------------------------------------------

    private const val PHANTOM_LIGHT_SPACING = 4

    private val GRASSES by lazy { setOf(Blocks.GRASS, Blocks.FERN, Blocks.TALL_GRASS, Blocks.LARGE_FERN) }

    val SUNFIRE = ElementEffect { ctx, delivery, magnitude ->
        val level = ctx.level
        val burnSeconds = (4f * SpellCurves.curve(magnitude)).roundToInt()
        val daylightSeconds = (SpellCurves.ticks(4f, 2f, magnitude) / 20f).roundToInt()
        for (target in delivery.living(ctx)) {
            if (SpellContext.isUndead(target)) {
                // Daylight: they burn as under the sun, helmet or shade or not. Fire can't hurt
                // the fire-immune (wither skeletons, the Wither), so the sun's light does it
                // instead: the burn's whole second-a-point at once.
                target.setSecondsOnFire(maxOf(burnSeconds, daylightSeconds))
                val burn = if (target.fireImmune()) maxOf(burnSeconds, daylightSeconds).toFloat() * (if (ctx.pulsed) SpellContext.PULSE_DAMAGE else 1f) * ctx.share else 0f
                ctx.hurt(target, ctx.damage(EKDamageSources.SUNFIRE_KEY), ctx.damageAmount(4f, magnitude) * 2f + burn)
            } else {
                target.setSecondsOnFire(burnSeconds)
                ctx.hurt(target, ctx.damage(EKDamageSources.SUNFIRE_KEY), ctx.damageAmount(4f, magnitude))
            }
        }
        for (pos in delivery.blocks(level)) searingClear(ctx, pos)
        phantomLights(ctx, delivery, magnitude)
    }

    /** Searing Clear: grass, leaves and vines burn away to bare dirt, without spreading fire. */
    private fun searingClear(ctx: SpellContext, pos: BlockPos) {
        val state = ctx.level.getBlockState(pos)
        when {
            state.`is`(Blocks.GRASS_BLOCK) || state.`is`(Blocks.PODZOL) || state.`is`(Blocks.MYCELIUM) ->
                ctx.setBlock(pos, Blocks.DIRT.defaultBlockState())
            state.`is`(BlockTags.LEAVES) || state.block is VineBlock || state.block in GRASSES ->
                ctx.setBlock(pos, Blocks.AIR.defaultBlockState())
        }
    }

    /** Phantom Light: up to 2 + magnitude lights per application, at least
     *  [PHANTOM_LIGHT_SPACING] blocks from any other, counting ones already placed. An Area
     *  cast rings the caster; a beam spaces them along its line. */
    private fun phantomLights(ctx: SpellContext, delivery: Delivery, magnitude: Float) {
        val level = ctx.level
        val light = EKBlocks.PHANTOM_LIGHT.get()
        var candidates = delivery.blocks(level).filter { level.getBlockState(it).isAir }
        if (delivery is Delivery.Sphere) {
            candidates = if (delivery.area) {
                candidates.filter {
                    val d = Vec3.atCenterOf(it).distanceTo(delivery.center)
                    d >= delivery.radius * 0.5 && d <= delivery.radius * 0.8
                }.shuffled()
            } else candidates.shuffled()
        }
        var placed = 0
        val cap = 2 + magnitude.toInt()
        // Every light already about, found once rather than around every candidate.
        val lights = NamedEffects.find(level, delivery.bounds().inflate(PHANTOM_LIGHT_SPACING.toDouble()), light).toMutableList()
        val spacing2 = PHANTOM_LIGHT_SPACING * PHANTOM_LIGHT_SPACING
        for (pos in candidates) {
            if (placed >= cap) break
            if (lights.any { it.distSqr(pos) < spacing2 }) continue
            if (ctx.setBlock(pos, light.defaultBlockState())) {
                placed++
                lights += pos
            }
        }
    }

    // -----------------------------------------------------------------------
    // Poison — contagion. Base: venom, Rot damage to everything it touches. Miasma, Rot
    // (Contagion is Rot's own spread).
    // -----------------------------------------------------------------------

    /** The venom's sting on whatever the spell touches, as Rot damage (it harms undead too). */
    private const val VENOM_DAMAGE = 4f

    /** Everything the spell touches takes its venom. An imbued blow then Rots what it strikes,
     *  for certain; every other use leaves Miasma, as does an imbued shot that strikes no
     *  creature (a missed arrow), so it's never wasted. Rot comes from one blow rather than a
     *  roll each pulse, so it lasts long: 10 s at magnitude 1, 22 s at 7, at the spell's tier
     *  (Rot I at magnitude 1–2, II at 3–4, III from 5). Another blow on a carrier deepens its
     *  Rot by a level, and keeps the longer time, so it's never cut short. */
    val POISON = ElementEffect { ctx, delivery, magnitude ->
        val struck = delivery.living(ctx)
        for (target in struck) ctx.hurt(target, ctx.damage(EKDamageSources.ROT_KEY), ctx.damageAmount(VENOM_DAMAGE, magnitude))
        if (ctx.use != SpellUse.IMBUE || struck.isEmpty()) {
            miasma(ctx, delivery, magnitude)
            return@ElementEffect
        }
        val rot = EKEffects.ROT.get()
        val ticks = SpellCurves.ticks(8f, 2f, magnitude)
        for (target in struck) {
            if (!target.isAlive) continue
            val current = target.getEffect(rot)
            val level = if (current == null) SpellCurves.tierAmplifier(magnitude)
                else minOf(maxOf(current.amplifier, SpellCurves.tierAmplifier(magnitude)) + 1, RotEffect.MAX_LEVEL)
            target.addEffect(MobEffectInstance(rot, maxOf(ticks, current?.duration ?: 0), level), ctx.caster)
        }
    }

    /**
     * Miasma: Poison's gas, left in the air ([GasField]). A Spray thickens the gas in its whole
     * cone a little each pulse; anything else leaves a dome of it (where a Beam ends, round each
     * Chain target, a small burst rolling out from an Area cast, impact or rune): a little each
     * pulse when held, or at once ([GasField.ONCE_FILL_PER_TICK] for each tick the Miasma once lasted). A Spray
     * also blows its gas forward ([GasField.spray]), and an Area cast's bursts outward.
     */
    private fun miasma(ctx: SpellContext, delivery: Delivery, magnitude: Float) {
        val level = ctx.level
        val radius = 1.5 + 0.3 * magnitude
        val amount = if (ctx.pulsed) GasField.PULSE_ADD * ctx.share else (SpellCurves.ticks(6f, 2f, magnitude) * GasField.ONCE_FILL_PER_TICK).coerceAtMost(1f)
        when (delivery) {
            is Delivery.Cone -> GasField.spray(level, delivery.origin, delivery.direction, delivery.length, delivery.halfAngle, amount)
            is Delivery.Line -> GasField.addDome(level, delivery.to, radius, amount)
            // An Area cast, a Bolt's impact or a rune: a small burst, rolling outward.
            is Delivery.Sphere -> GasField.addBurst(level, delivery.anchor, radius, amount)
            is Delivery.Group -> for (target in delivery.targets) GasField.addDome(level, target.position(), radius, amount)
            is Delivery.SingleEntity -> GasField.addDome(level, delivery.entity.position(), radius, amount)
            is Delivery.SingleBlock -> GasField.addDome(level, delivery.anchor, radius, amount)
        }
    }

    private const val VOID_DAMAGE = 3f

    // -----------------------------------------------------------------------
    // Void — the Abyss. Singularity, Hollowed, Absent, Abyssal Pocket.
    // -----------------------------------------------------------------------

    val VOID = ElementEffect { ctx, delivery, magnitude ->
        val level = ctx.level
        val hollowed = EKEffects.HOLLOWED.get()
        for (target in delivery.living(ctx)) {
            ctx.hurt(target, ctx.damage(EKDamageSources.VOID_KEY), ctx.damageAmount(VOID_DAMAGE, magnitude))
            target.addEffect(MobEffectInstance(hollowed, SpellCurves.ticks(4f, 2f, magnitude)), ctx.caster)
        }
        when (ctx.use) {
            SpellUse.FORWARD -> pocket(ctx, delivery, magnitude)
            // Singularity: on the caster for an Area cast, on whoever tripped a rune. Creatures
            // drawn into its core go Absent.
            SpellUse.AREA, SpellUse.RUNE -> if (delivery is Delivery.Sphere) {
                val radius = 3.0 + 0.5 * magnitude
                level.addFreshEntity(SingularityEntity(
                    level, delivery.anchor, radius, SpellCurves.ticks(3f, 0.5f, magnitude),
                    ctx.caster?.uuid, SpellCurves.ticks(1.5f, 0.5f, magnitude),
                ))
            }
            // Absent: only what the blow struck, the target nearest where it burst, never the
            // caster. One chosen target, so a little longer than the vortex's: 2.5 s to 5.5 s.
            // An imbued shot that strikes no creature opens a Pocket where it lands instead, as
            // a Forward cast would, so it's never wasted.
            SpellUse.IMBUE -> {
                val living = delivery.living(ctx)
                val struck = if (delivery is Delivery.Sphere) living.minByOrNull { it.position().distanceToSqr(delivery.center) } else living.firstOrNull()
                if (struck != null) struck.addEffect(MobEffectInstance(EKEffects.ABSENT.get(), SpellCurves.ticks(2f, 0.5f, magnitude)), ctx.caster)
                else pocket(ctx, delivery, magnitude)
            }
        }
    }

    /**
     * Void's Abyssal Pocket for a Forward cast, by its shape: where a Bolt bursts, where a Beam
     * ends, where a Spray's middle first meets a block, under each creature a Chain strikes; or
     * on the block a missed imbued shot struck. A held shape opens it once a pulse, which keeps
     * the pocket open while it's held there.
     */
    private fun pocket(ctx: SpellContext, delivery: Delivery, magnitude: Float) {
        if (!ctx.beat) return
        when (delivery) {
            is Delivery.Sphere -> AbyssalPockets.open(ctx, delivery.center, magnitude)
            is Delivery.Line -> AbyssalPockets.open(ctx, delivery.to, magnitude)
            is Delivery.Cone -> {
                val end = delivery.origin.add(delivery.direction.scale(delivery.length))
                val hit = ctx.level.clip(ClipContext(delivery.origin, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null))
                if (hit.type == HitResult.Type.BLOCK) AbyssalPockets.open(ctx, Vec3.atCenterOf(hit.blockPos), magnitude)
            }
            is Delivery.Group -> for (target in delivery.targets) AbyssalPockets.open(ctx, target.position().subtract(0.0, 0.5, 0.0), magnitude)
            is Delivery.SingleEntity -> AbyssalPockets.open(ctx, delivery.entity.position().subtract(0.0, 0.5, 0.0), magnitude)
            is Delivery.SingleBlock -> AbyssalPockets.open(ctx, Vec3.atCenterOf(delivery.pos), magnitude)
        }
    }
}
