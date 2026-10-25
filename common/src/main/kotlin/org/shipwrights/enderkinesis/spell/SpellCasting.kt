package org.shipwrights.enderkinesis.spell

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import kotlin.math.roundToInt

/**
 * Turns a [Spell] into element effects for each cast mode. Every element on the scroll
 * applies at its own magnitude; the Forward shape only decides the region.
 *
 * - **Area:** a sphere around the caster per element, radius from that element's magnitude.
 * - **Bolt:** the same spheres around the impact (see [SpellBoltEntity][org.shipwrights.enderkinesis.entity.SpellBoltEntity]).
 * - **Beam, Chain, Spray:** held; each [PULSE_TICKS] re-applies every element as a pulse.
 */
object SpellCasting {
    /** Held shapes re-apply every 0.5 s. */
    const val PULSE_TICKS = 10

    /** Ticks a Forward cast can be held: 10 s at magnitude 1, rising linearly to 20 s at 7,
     *  from the magnitude of the element that chose the shape. */
    fun holdLimitTicks(spell: Spell): Int {
        val m = spell.magnitude(spell.shapeElement ?: return 0).coerceIn(1f, 7f)
        return ((10f + (m - 1f) * 10f / 6f) * 20f).roundToInt()
    }

    /** One sphere per element around [centre], each at its magnitude × [scale]. [area] marks
     *  a cast centred on the caster. */
    fun burst(ctx: SpellContext, spell: Spell, centre: Vec3, area: Boolean, scale: Float = 1f) {
        for ((element, magnitude) in spell.magnitudes) {
            val m = magnitude * scale
            ElementEffects.of(element).apply(ctx, Delivery.Sphere(centre, SpellCurves.areaRadius(m), area), m)
        }
    }

    private fun applyAll(ctx: SpellContext, spell: Spell, delivery: Delivery, scale: Float) {
        for ((element, magnitude) in spell.magnitudes) ElementEffects.of(element).apply(ctx, delivery, magnitude * scale)
    }

    /**
     * The spell striking one entity directly (an imbued hit), by the spell's shape, at
     * magnitude × [scale]: a Bolt detonates on it, a Beam or Spray hits only it, and a Chain
     * chains on from it. [hit] is shared by every target of one sweep, so chains never hit
     * the same entity twice.
     */
    fun strikeEntity(ctx: SpellContext, spell: Spell, target: Entity, scale: Float, hit: MutableSet<Entity> = HashSet()) {
        when (spell.shape ?: return) {
            Shape.BOLT -> burst(ctx, spell, target.position().add(0.0, target.bbHeight * 0.5, 0.0), area = false, scale)
            Shape.BEAM, Shape.SPRAY -> applyAll(ctx, spell, Delivery.SingleEntity(target), scale)
            Shape.CHAIN -> {
                val chaos = spell.magnitude(spell.shapeElement!!) * scale
                val targets = chainFrom(ctx, target as? LivingEntity ?: return, chaos, hit)
                applyAll(ctx, spell, Delivery.Group(targets, target.position()), scale)
            }
        }
    }

    /**
     * The spell striking one block (an imbued projectile's missed shot), so it's never
     * wasted: a Bolt detonates at [location] and the struck block then falls if it still
     * qualifies; a Beam or Spray applies each element's block effect to the block; a Chain
     * jumps from the impact to the nearest entity and chains on.
     */
    fun strikeBlock(ctx: SpellContext, spell: Spell, pos: BlockPos, face: Direction, location: Vec3, scale: Float) {
        when (spell.shape ?: return) {
            Shape.BOLT -> {
                burst(ctx, spell, location, area = false, scale)
                ElementEffects.dropBlock(ctx, pos)
            }
            Shape.BEAM, Shape.SPRAY -> applyAll(ctx, spell, Delivery.SingleBlock(pos, face), scale)
            Shape.CHAIN -> {
                val first = ctx.level.getEntitiesOfClass(LivingEntity::class.java, AABB(location, location).inflate(CHAIN_JUMP)) {
                    it.isAlive && !it.isSpectator && !ctx.isCaster(it) && it.distanceToSqr(location) <= CHAIN_JUMP * CHAIN_JUMP
                }.minByOrNull { it.distanceToSqr(location) } ?: return
                val chaos = spell.magnitude(spell.shapeElement!!) * scale
                val targets = chainFrom(ctx, first, chaos, HashSet())
                chainArcs(ctx, location, targets.take(1))
                applyAll(ctx, spell, Delivery.Group(targets, first.position()), scale)
            }
        }
    }

    /** One pulse of a held Beam, Chain or Spray cast from [origin] along the unit vector
     *  [look]. [ctx] must be pulsed. */
    fun pulse(ctx: SpellContext, spell: Spell, origin: Vec3, look: Vec3) {
        val shapeElement = spell.shapeElement ?: return
        val delivery = when (shapeElement.shape) {
            Shape.BEAM -> beam(ctx, origin, look, spell.magnitude(shapeElement))
            Shape.SPRAY -> spray(origin, look, spell.magnitude(shapeElement))
            Shape.CHAIN -> chain(ctx, origin, look, spell.magnitude(shapeElement)) ?: return
            Shape.BOLT -> return
        }
        for ((element, magnitude) in spell.magnitudes) ElementEffects.of(element).apply(ctx, delivery, magnitude)
    }

    // -----------------------------------------------------------------------
    // Beam
    // -----------------------------------------------------------------------

    fun beamReach(magnitude: Float): Double = 10.0 + 5.0 * magnitude

    /** The beam's line: from [origin] to the first block in its way, or its full reach. It
     *  ends just inside a struck block, so that block takes the block effects. */
    fun beam(ctx: SpellContext, origin: Vec3, look: Vec3, magnitude: Float): Delivery.Line {
        val end = origin.add(look.scale(beamReach(magnitude)))
        val hit = ctx.level.clip(ClipContext(origin, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null))
        val to = if (hit.type == HitResult.Type.MISS) end else hit.location.add(look.scale(BLOCK_BITE))
        return Delivery.Line(origin, to, BEAM_HALF_WIDTH)
    }

    /** Thin dust along a held beam between pulses, in the shape element's colour. */
    fun beamTrail(ctx: SpellContext, line: Delivery.Line, element: Element) {
        val dust = dust(element)
        val length = line.from.distanceTo(line.to)
        val steps = (length / TRAIL_SPACING).toInt().coerceAtLeast(1)
        for (i in 1..steps) {
            val p = line.from.lerp(line.to, i.toDouble() / steps)
            ctx.level.sendParticles(dust, p.x, p.y, p.z, 1, 0.02, 0.02, 0.02, 0.0)
        }
    }

    // -----------------------------------------------------------------------
    // Spray
    // -----------------------------------------------------------------------

    fun sprayLength(magnitude: Float): Double = 3.0 + magnitude

    fun spray(origin: Vec3, look: Vec3, magnitude: Float): Delivery.Cone =
        Delivery.Cone(origin, look, sprayLength(magnitude), SPRAY_HALF_ANGLE)

    // -----------------------------------------------------------------------
    // Chain
    // -----------------------------------------------------------------------

    /** Living entities a Chain pulse hits: 1 + the Chaos magnitude, rounded. */
    fun chainTargets(magnitude: Float): Int = 1 + magnitude.roundToInt().coerceAtLeast(1)

    /** The nearest living target in a cone in front of [origin], then jumps to the nearest
     *  living entity not yet hit near the last one, each in sight of the one before. One
     *  pulse never hits an entity twice. Null when the cone holds no target. */
    fun chain(ctx: SpellContext, origin: Vec3, look: Vec3, magnitude: Float): Delivery.Group? {
        val cone = Delivery.Cone(origin, look, CHAIN_RANGE, CHAIN_HALF_ANGLE)
        val first = cone.living(ctx).minByOrNull { it.distanceToSqr(origin) } ?: return null
        val targets = chainFrom(ctx, first, magnitude, HashSet())
        chainArcs(ctx, origin, targets.take(1))
        return Delivery.Group(targets, first.position())
    }

    /** [first] and up to [chainTargets] − 1 jumps from it, skipping anything in [hit], which
     *  collects every entity struck. Jumps show their arcs; the arc to [first] is the
     *  caller's. Empty when [first] was already hit. */
    fun chainFrom(ctx: SpellContext, first: LivingEntity, magnitude: Float, hit: MutableSet<Entity>): List<LivingEntity> {
        if (!hit.add(first)) return emptyList()
        val targets = mutableListOf(first)
        var from: LivingEntity = first
        while (targets.size < chainTargets(magnitude)) {
            val next = ctx.level.getEntitiesOfClass(LivingEntity::class.java, from.boundingBox.inflate(CHAIN_JUMP)) {
                it.isAlive && !it.isSpectator && it !in hit && !ctx.isCaster(it) &&
                    it.distanceToSqr(from) <= CHAIN_JUMP * CHAIN_JUMP && from.hasLineOfSight(it)
            }.minByOrNull { it.distanceToSqr(from) } ?: break
            hit += next
            targets += next
            from = next
        }
        chainArcs(ctx, first.position().add(0.0, first.bbHeight * 0.5, 0.0), targets.drop(1))
        return targets
    }

    private fun chainArcs(ctx: SpellContext, origin: Vec3, targets: List<Entity>) {
        var from = origin
        for (target in targets) {
            val to = target.position().add(0.0, target.bbHeight * 0.5, 0.0)
            val steps = (from.distanceTo(to) / TRAIL_SPACING).toInt().coerceAtLeast(1)
            for (i in 1..steps) {
                val p = from.lerp(to, i.toDouble() / steps)
                ctx.level.sendParticles(ParticleTypes.ELECTRIC_SPARK, p.x, p.y, p.z, 1, 0.08, 0.08, 0.08, 0.0)
            }
            from = to
        }
    }

    fun dust(element: Element): DustParticleOptions {
        val c = element.color
        return DustParticleOptions(
            Vector3f(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f), 1f,
        )
    }

    private const val BEAM_HALF_WIDTH = 0.5
    private const val BLOCK_BITE = 0.1
    private const val TRAIL_SPACING = 0.75
    private val SPRAY_HALF_ANGLE = Math.toRadians(25.0)
    private const val CHAIN_RANGE = 12.0
    private val CHAIN_HALF_ANGLE = Math.toRadians(30.0)
    private const val CHAIN_JUMP = 6.0
}
