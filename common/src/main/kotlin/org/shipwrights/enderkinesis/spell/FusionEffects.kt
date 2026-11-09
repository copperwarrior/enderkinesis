package org.shipwrights.enderkinesis.spell

import org.valkyrienskies.mod.common.toWorldCoordinates
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
import org.shipwrights.enderkinesis.registry.EKSounds
import kotlin.math.roundToInt
import org.shipwrights.enderkinesis.util.colliderClip

object FusionEffects {

    private const val PHANTOM_LIGHT_SPACING = 4

    private val GRASSES by lazy { setOf(Blocks.GRASS, Blocks.FERN, Blocks.TALL_GRASS, Blocks.LARGE_FERN) }

    val SUNFIRE = ElementEffect { ctx, delivery, magnitude ->
        val level = ctx.level
        val burnSeconds = (4f * SpellCurves.curve(magnitude)).roundToInt()
        val daylightSeconds = (SpellCurves.ticks(4f, 2f, magnitude) / 20f).roundToInt()
        for (target in delivery.living(ctx)) {
            if (SpellContext.isUndead(target)) {
                // Fire cannot hurt fire-immune undead (the Wither), so add the burn at once, one point for each second.
                target.setSecondsOnFire(maxOf(burnSeconds, daylightSeconds))
                val burn = if (target.fireImmune()) maxOf(burnSeconds, daylightSeconds).toFloat() * (if (ctx.pulsed) SpellContext.PULSE_DAMAGE else 1f) * ctx.share else 0f
                if (ctx.hurt(target, ctx.damage(EKDamageSources.SUNFIRE_KEY), ctx.damageAmount(4f, magnitude) * 2f + burn)) {
                    SpellSounds.smite(level, target, SpellSounds.magnitudeVolume(magnitude))
                }
            } else {
                target.setSecondsOnFire(burnSeconds)
                ctx.hurt(target, ctx.damage(EKDamageSources.SUNFIRE_KEY), ctx.damageAmount(4f, magnitude))
            }
        }
        val scorched = SpellSounds.FirstChange(ctx, EKSounds.SPELL_SUNFIRE_SCORCH, SpellSounds.magnitudeVolume(magnitude))
        for (pos in delivery.blocks(level)) if (searingClear(ctx, pos)) scorched.at(pos)
        phantomLights(ctx, delivery, magnitude)
    }

    private fun searingClear(ctx: SpellContext, pos: BlockPos): Boolean {
        val state = ctx.level.getBlockState(pos)
        return when {
            state.`is`(Blocks.GRASS_BLOCK) || state.`is`(Blocks.PODZOL) || state.`is`(Blocks.MYCELIUM) ->
                ctx.setBlock(pos, Blocks.DIRT.defaultBlockState())
            state.`is`(BlockTags.LEAVES) || state.block is VineBlock || state.block in GRASSES ->
                ctx.setBlock(pos, Blocks.AIR.defaultBlockState())
            else -> false
        }
    }

    private fun phantomLights(ctx: SpellContext, delivery: Delivery, magnitude: Float) {
        val level = ctx.level
        val light = EKBlocks.PHANTOM_LIGHT.get()
        var candidates = delivery.blocks(level).filter { level.getBlockState(it).isAir }
        if (delivery is Delivery.Sphere) {
            val centre = level.toWorldCoordinates(delivery.center)
            candidates = if (delivery.area) {
                candidates.filter {
                    // World frame: ship candidates have shipyard positions.
                    val d = level.toWorldCoordinates(Vec3.atCenterOf(it)).distanceTo(centre)
                    d >= delivery.radius * 0.5 && d <= delivery.radius * 0.8
                }.shuffled()
            } else candidates.shuffled()
        }
        var placed = 0
        val cap = 2 + magnitude.toInt()
        // Find the existing lights once, not around each candidate.
        val lights = NamedEffects.find(level, delivery.bounds().inflate(PHANTOM_LIGHT_SPACING.toDouble()), light)
            .mapTo(ArrayList()) { level.toWorldCoordinates(Vec3.atCenterOf(it)) }
        val spacing2 = PHANTOM_LIGHT_SPACING * PHANTOM_LIGHT_SPACING
        val sound = SpellSounds.FirstChange(ctx, EKSounds.SPELL_SUNFIRE_PHANTOM_LIGHT, SpellSounds.magnitudeVolume(magnitude))
        for (pos in candidates) {
            if (placed >= cap) break
            val at = level.toWorldCoordinates(Vec3.atCenterOf(pos))
            if (lights.any { it.distanceToSqr(at) < spacing2 }) continue
            if (ctx.setBlock(pos, light.defaultBlockState())) {
                placed++
                lights += at
                sound.at(pos)
            }
        }
    }

    private const val VENOM_DAMAGE = 4f

    /** An imbued shot that strikes no creature leaves Miasma, so it is never wasted.
     *  Another blow on a carrier adds one Rot level and keeps the longer time. */
    val CORRUPTION = ElementEffect { ctx, delivery, magnitude ->
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

    private fun miasma(ctx: SpellContext, delivery: Delivery, magnitude: Float) {
        val level = ctx.level
        val radius = 1.5 + 0.3 * magnitude
        val amount = if (ctx.pulsed) GasField.PULSE_ADD * ctx.share else (SpellCurves.ticks(6f, 2f, magnitude) * GasField.ONCE_FILL_PER_TICK).coerceAtMost(1f)
        when (delivery) {
            is Delivery.Cone -> GasField.spray(level, delivery.origin, delivery.direction, delivery.length, delivery.halfAngle, amount)
            is Delivery.Line -> GasField.addDome(level, delivery.to, radius, amount)
            is Delivery.Sphere -> GasField.addBurst(level, delivery.anchor, radius, amount)
            is Delivery.Group -> for (target in delivery.targets) GasField.addDome(level, target.position(), radius, amount)
            is Delivery.SingleEntity -> GasField.addDome(level, delivery.entity.position(), radius, amount)
            is Delivery.SingleBlock -> GasField.addDome(level, delivery.anchor, radius, amount)
        }
    }

    private const val VOID_DAMAGE = 3f

    val VOID = ElementEffect { ctx, delivery, magnitude ->
        val level = ctx.level
        val hollowed = EKEffects.HOLLOWED.get()
        for (target in delivery.living(ctx)) {
            ctx.hurt(target, ctx.damage(EKDamageSources.VOID_KEY), ctx.damageAmount(VOID_DAMAGE, magnitude))
            target.addEffect(MobEffectInstance(hollowed, SpellCurves.ticks(4f, 2f, magnitude)), ctx.caster)
        }
        when (ctx.use) {
            SpellUse.FORWARD -> pocket(ctx, delivery, magnitude)
            SpellUse.AREA, SpellUse.RUNE -> if (delivery is Delivery.Sphere) {
                val radius = 3.0 + 0.5 * magnitude
                level.addFreshEntity(SingularityEntity(
                    level, delivery.anchor, radius, SpellCurves.ticks(3f, 0.5f, magnitude),
                    ctx.caster?.uuid, SpellCurves.ticks(1.5f, 0.5f, magnitude),
                ))
            }
            // An imbued shot that strikes no creature opens a Pocket, so it is never wasted.
            SpellUse.IMBUE -> {
                val living = delivery.living(ctx)
                val struck = if (delivery is Delivery.Sphere) living.minByOrNull { it.position().distanceToSqr(delivery.center) } else living.firstOrNull()
                if (struck != null) struck.addEffect(MobEffectInstance(EKEffects.ABSENT.get(), SpellCurves.ticks(2f, 0.5f, magnitude)), ctx.caster)
                else pocket(ctx, delivery, magnitude)
            }
        }
    }

    // A held shape opens the pocket once each pulse, which keeps it open.
    private fun pocket(ctx: SpellContext, delivery: Delivery, magnitude: Float) {
        if (!ctx.beat) return
        val opened = SpellSounds.FirstChange(ctx, EKSounds.SPELL_VOID_POCKET_OPEN, SpellSounds.magnitudeVolume(magnitude))
        fun open(at: Vec3) {
            if (AbyssalPockets.open(ctx, at, magnitude)) opened.at(BlockPos.containing(at))
        }
        when (delivery) {
            is Delivery.Sphere -> open(delivery.center)
            is Delivery.Line -> open(delivery.to)
            is Delivery.Cone -> {
                val end = delivery.origin.add(delivery.direction.scale(delivery.length))
                val hit = ctx.level.clip(colliderClip(delivery.origin, end))
                if (hit.type == HitResult.Type.BLOCK) open(Vec3.atCenterOf(hit.blockPos))
            }
            is Delivery.Group -> for (target in delivery.targets) open(target.position().subtract(0.0, 0.5, 0.0))
            is Delivery.SingleEntity -> open(delivery.entity.position().subtract(0.0, 0.5, 0.0))
            is Delivery.SingleBlock -> open(Vec3.atCenterOf(delivery.pos))
        }
    }
}
