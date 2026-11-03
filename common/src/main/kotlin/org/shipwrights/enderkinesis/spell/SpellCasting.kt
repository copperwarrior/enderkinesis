package org.shipwrights.enderkinesis.spell

import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.item.AegisBox
import org.shipwrights.enderkinesis.item.BeamTrace
import org.shipwrights.enderkinesis.item.SunderingBeamTrace
import kotlin.math.roundToInt

object SpellCasting {
    const val PULSE_TICKS = 10
    /** A Beam or Spray applies this often, at this share of a pulse, so a sweep reaches what it crosses. */
    const val STREAM_TICKS = 2
    const val STREAM_SHARE = STREAM_TICKS.toFloat() / PULSE_TICKS

    /** Server and client share this, so a drawn beam starts at the hand that casts it. */
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

    /** Aims at the crosshair block, so a cast from the hand lands where the caster looks. */
    fun handAim(entity: LivingEntity, origin: Vec3, partialTick: Float = 1f): Vec3 {
        val eye = entity.getEyePosition(partialTick)
        val view = entity.getViewVector(partialTick)
        val hit = entity.level().clip(ClipContext(eye, eye.add(view.scale(AIM_CONVERGE)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity))
        val distance = if (hit.type == HitResult.Type.MISS) AIM_CONVERGE else hit.location.distanceTo(eye).coerceAtLeast(AIM_MIN)
        return eye.add(view.scale(distance)).subtract(origin).normalize()
    }

    private val UP = Vec3(0.0, 1.0, 0.0)
    private const val HAND_FORWARD = 0.45
    private const val HAND_SIDE = 0.36
    private const val HAND_DROP = 0.35
    private const val PLAYER_HEIGHT = 1.8f
    private const val AIM_CONVERGE = 64.0
    private const val AIM_MIN = 2.0

    fun holdLimitTicks(spell: Spell): Int {
        val m = spell.magnitude(spell.shapeElement ?: return 0).coerceIn(1f, 7f)
        return ((10f + (m - 1f) * 10f / 6f) * 20f).roundToInt()
    }

    /** A full Bolt needs 1.5 s of draw and aim, and spends a full use, so it hits harder. */
    const val BOLT_DAMAGE = 2.5f

    /** Eased in as a bow draw, so a quick tap never does more damage than a full draw. */
    fun boltPower(charge: Float): Float = BOLT_DAMAGE * (charge * charge + 2f * charge) / 3f

    /** [area] marks a cast centred on the caster. */
    fun burst(ctx: SpellContext, spell: Spell, centre: Vec3, area: Boolean, scale: Float = 1f, waves: Boolean = area) {
        if (area) SpellVfx.runeCircle(ctx.level, centre, spell.markColour())
        ctx.elements = spell.magnitudes.keys
        for ((element, magnitude) in spell.magnitudes) {
            val m = magnitude * scale
            ElementEffects.of(element).apply(ctx, Delivery.Sphere(centre, SpellCurves.areaRadius(m), area, waves), m)
        }
    }

    private fun applyAll(ctx: SpellContext, spell: Spell, delivery: Delivery, scale: Float) {
        ctx.elements = spell.magnitudes.keys
        for ((element, magnitude) in spell.magnitudes) ElementEffects.of(element).apply(ctx, delivery, magnitude * scale)
    }

    /** [hit] is shared by all targets of one sweep, so chains never hit the same entity twice. */
    fun strikeEntity(ctx: SpellContext, spell: Spell, target: Entity, scale: Float, hit: MutableSet<Entity> = HashSet()) {
        when (spell.shape ?: return) {
            Shape.BOLT -> burst(ctx, spell, target.position().add(0.0, target.bbHeight * 0.5, 0.0), area = false, scale)
            Shape.BEAM, Shape.SPRAY -> applyAll(ctx, spell, Delivery.SingleEntity(target), scale)
            Shape.CHAIN -> {
                val chaos = spell.magnitude(spell.shapeElement!!) * scale
                val targets = chainFrom(ctx, target as? LivingEntity ?: return, chaos, hit, spell.magnitudes.keys)
                applyAll(ctx, spell, Delivery.Group(targets, target.position()), scale)
            }
        }
    }

    fun strikeBlock(ctx: SpellContext, spell: Spell, pos: BlockPos, face: Direction, location: Vec3, scale: Float) {
        SpellVfx.blockStrike(ctx.level, location, spell)
        val struck = ctx.level.getBlockState(pos)
        if (!struck.isAir) ctx.level.sendParticles(BlockParticleOption(ParticleTypes.BLOCK, struck), location.x, location.y, location.z, 10, 0.1, 0.1, 0.1, 0.15)
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
                val targets = chainFrom(ctx, first, chaos, HashSet(), spell.magnitudes.keys)
                chainArcs(ctx, location, targets.take(1), spell.magnitudes.keys, targets.size)
                applyAll(ctx, spell, Delivery.Group(targets, first.position()), scale)
            }
        }
    }

    /** [ctx] must be pulsed. A Beam comes here only for a tap; a held Beam goes through [beamTick]. */
    fun pulse(ctx: SpellContext, spell: Spell, origin: Vec3, look: Vec3) {
        val shapeElement = spell.shapeElement ?: return
        val magnitude = spell.magnitude(shapeElement)
        val mirrors = BeamManager.mirrors(ctx.level, ctx.caster)
        when (shapeElement.shape) {
            Shape.BEAM -> {
                val trace = SunderingBeamTrace.trace(ctx.level, ctx.caster, origin, look, beamReach(magnitude), mirrors, BEAM_HALF_WIDTH)
                val lines = beamLines(trace)
                for (line in lines) applyAll(ctx, spell, line, 1f)
                touchSound(ctx, spell) { beamTouch(ctx, lines, trace.finalBlockPos != null) }
            }
            Shape.SPRAY -> {
                val cone = spray(origin, look, magnitude, mirrors)
                applyAll(ctx, spell, cone, 1f)
                touchSound(ctx, spell) { sprayTouch(ctx, cone) }
            }
            Shape.CHAIN -> {
                val group = chain(ctx, origin, look, magnitude, spell.magnitudes.keys) ?: return
                applyAll(ctx, spell, group, 1f)
            }
            Shape.BOLT -> return
        }
    }

    fun beamReach(magnitude: Float): Double = 10.0 + 5.0 * magnitude

    /** When [apply], the elements apply only up to where the beam feeds a merged beam. */
    fun beamTick(ctx: SpellContext, spell: Spell, caster: LivingEntity, origin: Vec3, look: Vec3, apply: Boolean) {
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
        if (apply) {
            val lines = beamLines(acting)
            for (line in lines) applyAll(ctx, spell, line, 1f)
            touchSound(ctx, spell) { beamTouch(ctx, lines, acting.finalBlockPos != null) }
        }
    }

    /** One touch sound for each pulse, at the first thing that the stream touches. */
    private inline fun touchSound(ctx: SpellContext, spell: Spell, touch: () -> Vec3?) {
        if (!ctx.beat || !SpellSounds.hasAny(spell)) return
        val at = touch() ?: return
        SpellSounds.forEach(spell) { sounds, volume -> SpellSounds.play(ctx.level, at, sounds.touch, volume) }
    }

    private fun beamTouch(ctx: SpellContext, lines: List<Delivery.Line>, struckBlock: Boolean): Vec3? {
        for (line in lines) {
            line.living(ctx).minByOrNull { it.distanceToSqr(line.from) }?.let { return centre(it) }
        }
        return if (struckBlock) lines.lastOrNull()?.to else null
    }

    private fun sprayTouch(ctx: SpellContext, cone: Delivery.Cone): Vec3? {
        cone.living(ctx).minByOrNull { it.distanceToSqr(cone.origin) }?.let { return centre(it) }
        val caster = ctx.caster ?: return null
        val end = cone.origin.add(cone.direction.scale(cone.length))
        val hit = ctx.level.clip(ClipContext(cone.origin, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, caster))
        return if (hit.type == HitResult.Type.BLOCK) hit.location else null
    }

    /** The last line ends just inside a struck block, so that block takes the block effects. */
    fun beamLines(trace: BeamTrace): List<Delivery.Line> = trace.segments.mapIndexed { i, seg ->
        val bite = i == trace.segments.lastIndex && trace.finalBlockPos != null
        Delivery.Line(seg.start, if (bite) seg.end.add(seg.direction.scale(BLOCK_BITE)) else seg.end, BEAM_HALF_WIDTH)
    }

    fun sprayLength(magnitude: Float): Double = 3.0 + magnitude

    fun spray(origin: Vec3, look: Vec3, magnitude: Float, blockers: List<AegisBox.Frame>): Delivery.Cone =
        Delivery.Cone(origin, look, sprayLength(magnitude), SPRAY_HALF_ANGLE, blockers)

    fun chainTargets(magnitude: Float): Int = 1 + magnitude.roundToInt().coerceAtLeast(1)

    /** An arc never crosses an Aegis box. It takes the nearest target on the near side, or stops. */
    fun chain(ctx: SpellContext, origin: Vec3, look: Vec3, magnitude: Float, payload: Collection<Element> = emptyList()): Delivery.Group? {
        val mirrors = BeamManager.mirrors(ctx.level, ctx.caster)
        val cone = Delivery.Cone(origin, look, CHAIN_RANGE, CHAIN_HALF_ANGLE, mirrors)
        val first = cone.living(ctx).minByOrNull { it.distanceToSqr(origin) }
        if (first == null) {
            // With no target, arcs still go into the air and stop at the first Aegis box.
            repeat(EMPTY_ARCS) {
                val dir = look.add(
                    ctx.level.random.nextGaussian() * 0.35, ctx.level.random.nextGaussian() * 0.35, ctx.level.random.nextGaussian() * 0.35,
                ).normalize()
                var length = CHAIN_RANGE * (0.3 + 0.4 * ctx.level.random.nextDouble())
                var struck: AegisBox.IntersectResult? = null
                for (frame in mirrors) AegisBox.rayIntersect(origin, dir, frame)?.let { if (it.distance < length) { length = it.distance; struck = it } }
                arc(ctx, origin, origin.add(dir.scale(length)), payload, 4, EMPTY_ARCS)
                struck?.let { SpellVfx.aegisStrike(ctx.level, origin.add(dir.scale(length)), it.normal) }
            }
            return null
        }
        val targets = chainFrom(ctx, first, magnitude, HashSet(), payload)
        chainArcs(ctx, origin, targets.take(1), payload, targets.size)
        return Delivery.Group(targets, first.position())
    }

    /** The caller draws the arc to [first]. [hit] collects every entity struck. */
    fun chainFrom(ctx: SpellContext, first: LivingEntity, magnitude: Float, hit: MutableSet<Entity>, payload: Collection<Element> = emptyList()): List<LivingEntity> {
        if (!hit.add(first)) return emptyList()
        val mirrors = BeamManager.mirrors(ctx.level, ctx.caster)
        val targets = mutableListOf(first)
        var from: LivingEntity = first
        while (targets.size < chainTargets(magnitude)) {
            val fromCentre = centre(from)
            val reachable = ctx.level.getEntitiesOfClass(LivingEntity::class.java, from.boundingBox.inflate(CHAIN_JUMP)) {
                it.isAlive && !it.isSpectator && it !in hit && !ctx.isCaster(it) &&
                    it.distanceToSqr(from) <= CHAIN_JUMP * CHAIN_JUMP && from.hasLineOfSight(it)
            }.sortedBy { it.distanceToSqr(from) }
            val next = reachable.firstOrNull { !Delivery.shadowed(fromCentre, centre(it), mirrors) }
            // A nearer target behind an Aegis still draws an arc, which strikes the Aegis face.
            reachable.firstOrNull()?.takeIf { it !== next }?.let { behind -> strikeAegis(ctx, fromCentre, centre(behind), mirrors) }
            next ?: break
            hit += next
            targets += next
            from = next
        }
        chainArcs(ctx, first.position().add(0.0, first.bbHeight * 0.5, 0.0), targets.drop(1), payload, targets.size)
        return targets
    }

    /** [links] is the number of arcs in the whole chain. */
    private fun chainArcs(ctx: SpellContext, origin: Vec3, targets: List<Entity>, payload: Collection<Element>, links: Int) {
        var from = origin
        for (target in targets) {
            val to = target.position().add(0.0, target.bbHeight * 0.5, 0.0)
            arc(ctx, from, to, payload, ARC_LIFE, links)
            from = to
        }
    }

    private fun arc(ctx: SpellContext, from: Vec3, to: Vec3, payload: Collection<Element>, life: Int, links: Int) {
        if (payload.isEmpty()) SpellVfx.arc(ctx.level, from, to, Element.CHAOS.color, life)
        else SpellVfx.chainArc(ctx.level, from, to, payload, life, links)
    }

    private fun centre(entity: Entity): Vec3 = entity.position().add(0.0, entity.bbHeight * 0.5, 0.0)

    private fun strikeAegis(ctx: SpellContext, from: Vec3, to: Vec3, mirrors: List<AegisBox.Frame>) {
        val span = to.subtract(from)
        val dist = span.length()
        if (dist < 1.0e-6) return
        val dir = span.scale(1.0 / dist)
        val hit = mirrors.mapNotNull { AegisBox.rayIntersect(from, dir, it) }.filter { it.distance < dist }.minByOrNull { it.distance } ?: return
        val at = from.add(dir.scale(hit.distance))
        SpellVfx.arc(ctx.level, from, at, Element.CHAOS.color, ARC_LIFE)
        SpellVfx.aegisStrike(ctx.level, at, hit.normal)
    }

    private const val BEAM_HALF_WIDTH = 0.5
    private const val BLOCK_BITE = 0.1
    private const val EMPTY_ARCS = 3
    /** A pulse's arcs last until the next pulse. */
    private const val ARC_LIFE = 8
    private val SPRAY_HALF_ANGLE = Math.toRadians(25.0)
    private const val CHAIN_RANGE = 12.0
    private val CHAIN_HALF_ANGLE = Math.toRadians(30.0)
    private const val CHAIN_JUMP = 6.0
}
