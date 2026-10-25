package org.shipwrights.enderkinesis.spell

import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.sounds.SoundEvents
import net.minecraft.tags.BlockTags
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.AreaEffectCloud
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.VineBlock
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
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
                // Daylight: they burn as under the sun, helmet or shade or not.
                target.setSecondsOnFire(maxOf(burnSeconds, daylightSeconds))
                target.hurt(ctx.damage(DamageTypes.MAGIC), ctx.damageAmount(4f, magnitude) * 2f)
            } else {
                target.setSecondsOnFire(burnSeconds)
                target.hurt(ctx.damage(DamageTypes.IN_FIRE), ctx.damageAmount(4f, magnitude))
            }
        }
        for (pos in delivery.blocks(level)) searingClear(ctx, pos)
        phantomLights(ctx, delivery, magnitude)
        SpellFx.outwardBlast(level, delivery, ParticleTypes.END_ROD, 60, 0.5)
        SpellFx.burst(level, delivery, ParticleTypes.FLAME, 40)
        SpellFx.sound(level, delivery, SoundEvents.BEACON_ACTIVATE, pitch = 1.4f)
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
        for (pos in candidates) {
            if (placed >= cap) break
            if (NamedEffects.near(ctx, pos, light, PHANTOM_LIGHT_SPACING)) continue
            if (ctx.setBlock(pos, light.defaultBlockState())) placed++
        }
    }

    // -----------------------------------------------------------------------
    // Poison — contagion. Miasma, Rot (Contagion is Rot's own spread).
    // -----------------------------------------------------------------------

    val POISON = ElementEffect { ctx, delivery, magnitude ->
        val level = ctx.level
        val rot = EKEffects.ROT.get()
        val chance = (0.3f + 0.1f * magnitude).coerceAtMost(0.9f)
        for (target in delivery.living(ctx)) {
            // A new hit never resets a Rot already spreading.
            if (target.hasEffect(rot) || level.random.nextFloat() >= chance) continue
            target.addEffect(MobEffectInstance(rot, SpellCurves.ticks(6f, 2f, magnitude), 0), ctx.caster)
        }
        for (point in miasmaPoints(ctx, delivery)) miasma(ctx, point, magnitude, extend = delivery is Delivery.Line)
        SpellFx.burst(level, delivery, SpellCasting.dust(Element.POISON), 60)
        SpellFx.sound(level, delivery, SoundEvents.SLIME_SQUISH_SMALL, pitch = 0.6f)
    }

    /** Where Miasma clouds go: the impact or the caster, the end of a beam, a random spot in a
     *  spray, each chain target, or the struck target or face. */
    private fun miasmaPoints(ctx: SpellContext, delivery: Delivery): List<Vec3> = when (delivery) {
        is Delivery.Sphere -> listOf(delivery.center)
        is Delivery.Line -> listOf(delivery.to)
        is Delivery.Cone -> {
            val t = ctx.level.random.nextDouble()
            listOf(delivery.origin.add(delivery.direction.scale(delivery.length * (0.3 + 0.7 * t))))
        }
        is Delivery.Group -> delivery.targets.map { it.position() }
        is Delivery.SingleEntity -> listOf(delivery.entity.position())
        is Delivery.SingleBlock -> listOf(delivery.anchor)
    }

    /**
     * A lingering poison cloud. Clouds never overlap: where one already is, a beam extends it
     * and anything else leaves it be. A caster keeps at most 3 + magnitude clouds at once.
     */
    private fun miasma(ctx: SpellContext, point: Vec3, magnitude: Float, extend: Boolean) {
        val level = ctx.level
        val radius = (1.5 + 0.3 * magnitude).toFloat()
        val ticks = SpellCurves.ticks(6f, 2f, magnitude)
        val nearby = level.getEntities(EntityType.AREA_EFFECT_CLOUD, AABB(point, point).inflate(radius * 2.0 + 1.0)) { true }
        val overlapping = nearby.firstOrNull { it.position().distanceTo(point) < radius + it.radius }
        if (overlapping != null) {
            if (extend) overlapping.duration = maxOf(overlapping.duration, overlapping.tickCount + ticks)
            return
        }
        val caster = ctx.caster
        if (caster != null) {
            val owned = level.getEntities(EntityType.AREA_EFFECT_CLOUD, AABB(caster.position(), caster.position()).inflate(OWNED_SEARCH)) {
                it.owner === caster
            }
            if (owned.size >= 3 + magnitude.toInt()) return
        }
        val cloud = AreaEffectCloud(level, point.x, point.y, point.z)
        cloud.radius = radius
        cloud.radiusPerTick = 0f
        cloud.duration = ticks
        cloud.setWaitTime(0)
        cloud.setFixedColor(Element.POISON.color)
        cloud.addEffect(MobEffectInstance(MobEffects.POISON, MIASMA_POISON_TICKS, 0))
        caster?.let { cloud.owner = it }
        level.addFreshEntity(cloud)
    }

    private const val OWNED_SEARCH = 64.0
    private const val MIASMA_POISON_TICKS = 100

    // -----------------------------------------------------------------------
    // Void — the Abyss. Singularity, Hollowed. (Absent and Abyssal Pocket come later.)
    // -----------------------------------------------------------------------

    val VOID = ElementEffect { ctx, delivery, magnitude ->
        val level = ctx.level
        val hollowed = EKEffects.HOLLOWED.get()
        for (target in delivery.living(ctx)) {
            target.addEffect(MobEffectInstance(hollowed, SpellCurves.ticks(4f, 2f, magnitude)), ctx.caster)
        }
        // Singularity: a vortex at the impact, or on the caster for an Area cast.
        if (delivery is Delivery.Sphere) {
            val radius = 3.0 + 0.5 * magnitude
            level.addFreshEntity(SingularityEntity(level, delivery.anchor, radius, SpellCurves.ticks(3f, 0.5f, magnitude)))
        }
        SpellFx.inwardCollapse(level, delivery, ParticleTypes.REVERSE_PORTAL, 120, 1.5)
        SpellFx.sound(level, delivery, SoundEvents.ENDERMAN_TELEPORT, pitch = 0.4f)
    }
}
