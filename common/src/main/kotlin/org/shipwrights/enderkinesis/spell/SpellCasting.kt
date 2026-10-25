package org.shipwrights.enderkinesis.spell

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import org.shipwrights.enderkinesis.item.AegisBox
import org.shipwrights.enderkinesis.item.BeamTrace
import org.shipwrights.enderkinesis.item.SunderingBeamTrace
import kotlin.math.roundToInt
import net.minecraft.sounds.SoundSource
import org.shipwrights.enderkinesis.registry.EKSounds

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

    /** Where a cast from [hand] starts: just in front of that hand, on the side its arm is
     *  on. Server and client share this, so a drawn beam leaves the hand it's cast from. */
    fun handOrigin(entity: LivingEntity, hand: InteractionHand, partialTick: Float = 1f): Vec3 {
        val look = entity.getViewVector(partialTick)
        val flat = look.cross(UP)
        val right = if (flat.lengthSqr() < 1.0e-6) Vec3(-Math.cos(Math.toRadians(entity.yRot.toDouble())), 0.0, -Math.sin(Math.toRadians(entity.yRot.toDouble()))) else flat.normalize()
        val arm = if (hand == InteractionHand.MAIN_HAND) entity.mainArm else entity.mainArm.opposite
        val side = if (arm == HumanoidArm.RIGHT) 1.0 else -1.0
        return entity.getEyePosition(partialTick)
            .add(look.scale(HAND_FORWARD))
            .add(right.scale(HAND_SIDE * side))
            .add(0.0, -HAND_DROP * entity.bbHeight / PLAYER_HEIGHT, 0.0)
    }

    /** Aim from [origin] at the point [AIM_CONVERGE] blocks along the caster's view, so a cast
     *  from the hand still lands where the caster looks. */
    fun handAim(entity: LivingEntity, origin: Vec3, partialTick: Float = 1f): Vec3 =
        entity.getEyePosition(partialTick).add(entity.getViewVector(partialTick).scale(AIM_CONVERGE)).subtract(origin).normalize()

    private val UP = Vec3(0.0, 1.0, 0.0)
    private const val HAND_FORWARD = 0.45
    private const val HAND_SIDE = 0.36
    private const val HAND_DROP = 0.35
    private const val PLAYER_HEIGHT = 1.8f
    private const val AIM_CONVERGE = 16.0

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

    /** One pulse of a Chain or Spray cast from [origin] along the unit vector [look], or a
     *  single application of a Beam (a tap, let go before it was ever held). [ctx] must be
     *  pulsed. A held Beam goes through [beamTick] instead. */
    fun pulse(ctx: SpellContext, spell: Spell, origin: Vec3, look: Vec3) {
        val shapeElement = spell.shapeElement ?: return
        val magnitude = spell.magnitude(shapeElement)
        val mirrors = BeamManager.mirrors(ctx.level, ctx.caster)
        when (shapeElement.shape) {
            Shape.BEAM -> {
                val trace = SunderingBeamTrace.trace(ctx.level, ctx.caster, origin, look, beamReach(magnitude), mirrors, BEAM_HALF_WIDTH)
                for (line in beamLines(trace)) applyAll(ctx, spell, line, 1f)
            }
            Shape.SPRAY -> {
                applyAll(ctx, spell, spray(origin, look, magnitude, mirrors), 1f)
                ctx.level.playSound(null, origin.x, origin.y, origin.z, EKSounds.SPRAY_LOOP.get(), SoundSource.PLAYERS, LOOP_VOLUME, 1f)
            }
            Shape.CHAIN -> {
                val group = chain(ctx, origin, look, magnitude) ?: return
                applyAll(ctx, spell, group, 1f)
                val at = group.anchor
                ctx.level.playSound(null, at.x, at.y, at.z, EKSounds.CHAIN_ZAP.get(), SoundSource.PLAYERS, ZAP_VOLUME, 1.6f)
            }
            Shape.BOLT -> return
        }
    }

    // -----------------------------------------------------------------------
    // Beam
    // -----------------------------------------------------------------------

    fun beamReach(magnitude: Float): Double = 10.0 + 5.0 * magnitude

    /**
     * One tick of a held Beam: traces it from [origin] along [look], mirrored off other
     * casters' Aegis boxes, and registers it with [BeamManager] for crossings and loops.
     * When [pulse], every element applies along the beam, up to where it feeds a merged beam.
     */
    fun beamTick(ctx: SpellContext, spell: Spell, caster: LivingEntity, origin: Vec3, look: Vec3, pulse: Boolean) {
        val shapeElement = spell.shapeElement ?: return
        val level = ctx.level
        val reach = beamReach(spell.magnitude(shapeElement))
        val trace = SunderingBeamTrace.trace(level, caster, origin, look, reach, BeamManager.mirrors(level, caster), BEAM_HALF_WIDTH)
        BeamManager.register(
            BeamManager.Beam(caster.uuid, level, caster, spell.glyphs, 0f, trace, reach, BEAM_HALF_WIDTH, fromStaff = false) {
                caster.releaseUsingItem()
            },
        )
        val acting = BeamTrace(
            BeamManager.clip(caster.uuid, trace),
            if (BeamManager.isCut(caster.uuid)) null else trace.finalBlockPos,
        )
        if (pulse) {
            for (line in beamLines(acting)) applyAll(ctx, spell, line, 1f)
            level.playSound(null, origin.x, origin.y, origin.z, EKSounds.BEAM_LOOP.get(), SoundSource.PLAYERS, LOOP_VOLUME, 1.2f)
        }
    }

    /** One line per segment of a traced beam. The last ends just inside a struck block, so
     *  that block takes the block effects. */
    fun beamLines(trace: BeamTrace): List<Delivery.Line> = trace.segments.mapIndexed { i, seg ->
        val bite = i == trace.segments.lastIndex && trace.finalBlockPos != null
        Delivery.Line(seg.start, if (bite) seg.end.add(seg.direction.scale(BLOCK_BITE)) else seg.end, BEAM_HALF_WIDTH)
    }

    // -----------------------------------------------------------------------
    // Spray
    // -----------------------------------------------------------------------

    fun sprayLength(magnitude: Float): Double = 3.0 + magnitude

    /** The Spray's cone. Aegis boxes in [blockers] shadow everything behind them. */
    fun spray(origin: Vec3, look: Vec3, magnitude: Float, blockers: List<AegisBox.Frame>): Delivery.Cone =
        Delivery.Cone(origin, look, sprayLength(magnitude), SPRAY_HALF_ANGLE, blockers)

    // -----------------------------------------------------------------------
    // Chain
    // -----------------------------------------------------------------------

    /** Living entities a Chain pulse hits: 1 + the Chaos magnitude, rounded. */
    fun chainTargets(magnitude: Float): Int = 1 + magnitude.roundToInt().coerceAtLeast(1)

    /** The nearest living target in a cone in front of [origin], then jumps to the nearest
     *  living entity not yet hit near the last one, each in sight of the one before. One
     *  pulse never hits an entity twice. An arc never crosses an Aegis box: it takes the
     *  nearest target on the near side instead, or stops. Null when no target is reachable. */
    fun chain(ctx: SpellContext, origin: Vec3, look: Vec3, magnitude: Float): Delivery.Group? {
        val mirrors = BeamManager.mirrors(ctx.level, ctx.caster)
        val cone = Delivery.Cone(origin, look, CHAIN_RANGE, CHAIN_HALF_ANGLE, mirrors)
        val first = cone.living(ctx).minByOrNull { it.distanceToSqr(origin) }
        if (first == null) {
            // Nothing to strike: the lightning still lashes out into the air.
            repeat(EMPTY_ARCS) {
                val dir = look.add(
                    ctx.level.random.nextGaussian() * 0.35, ctx.level.random.nextGaussian() * 0.35, ctx.level.random.nextGaussian() * 0.35,
                ).normalize()
                SpellVfx.arc(ctx.level, origin, origin.add(dir.scale(CHAIN_RANGE * (0.3 + 0.4 * ctx.level.random.nextDouble()))), Element.CHAOS.color, 4)
            }
            return null
        }
        val targets = chainFrom(ctx, first, magnitude, HashSet())
        chainArcs(ctx, origin, targets.take(1))
        return Delivery.Group(targets, first.position())
    }

    /** [first] and up to [chainTargets] − 1 jumps from it, skipping anything in [hit], which
     *  collects every entity struck. Jumps show their arcs; the arc to [first] is the
     *  caller's. Empty when [first] was already hit. */
    fun chainFrom(ctx: SpellContext, first: LivingEntity, magnitude: Float, hit: MutableSet<Entity>): List<LivingEntity> {
        if (!hit.add(first)) return emptyList()
        val mirrors = BeamManager.mirrors(ctx.level, ctx.caster)
        val targets = mutableListOf(first)
        var from: LivingEntity = first
        while (targets.size < chainTargets(magnitude)) {
            val fromCentre = centre(from)
            val next = ctx.level.getEntitiesOfClass(LivingEntity::class.java, from.boundingBox.inflate(CHAIN_JUMP)) {
                it.isAlive && !it.isSpectator && it !in hit && !ctx.isCaster(it) &&
                    it.distanceToSqr(from) <= CHAIN_JUMP * CHAIN_JUMP && from.hasLineOfSight(it) &&
                    !Delivery.shadowed(fromCentre, centre(it), mirrors)
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
            SpellVfx.arc(ctx.level, from, to, Element.CHAOS.color, ARC_LIFE)
            from = to
        }
    }

    private fun centre(entity: Entity): Vec3 = entity.position().add(0.0, entity.bbHeight * 0.5, 0.0)

    fun dust(element: Element): DustParticleOptions = dust(element.color)

    fun dust(rgb: Int): DustParticleOptions = DustParticleOptions(
        Vector3f(((rgb shr 16) and 0xFF) / 255f, ((rgb shr 8) and 0xFF) / 255f, (rgb and 0xFF) / 255f), 1f,
    )

    private const val BEAM_HALF_WIDTH = 0.5
    private const val BLOCK_BITE = 0.1
    private const val EMPTY_ARCS = 3
    /** A pulse's arcs last until the next pulse. */
    private const val ARC_LIFE = 8
    private const val LOOP_VOLUME = 0.5f
    private const val ZAP_VOLUME = 0.3f
    private val SPRAY_HALF_ANGLE = Math.toRadians(25.0)
    private const val CHAIN_RANGE = 12.0
    private val CHAIN_HALF_ANGLE = Math.toRadians(30.0)
    private const val CHAIN_JUMP = 6.0
}
