package org.shipwrights.enderkinesis.spell.signature

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.entity.SignatureEntity
import org.shipwrights.enderkinesis.spell.Signature
import org.shipwrights.enderkinesis.spell.Spell
import org.shipwrights.enderkinesis.spell.SpellCasting
import org.shipwrights.enderkinesis.spell.SpellContext
import org.valkyrienskies.mod.common.world.clipIncludeShips
import java.util.UUID

/**
 * One Forward cast of a signature: who, from which hand, and how strongly. [power] is a
 * release's charge, 0..1 ([Signatures.charge]); a hold that has only just started is at its
 * weakest.
 */
class SignatureCast(
    val level: ServerLevel, val caster: LivingEntity, val hand: InteractionHand,
    val spell: Spell, val signature: Signature, val power: Float,
) {
    /** Where the cast leaves the hand, now. */
    val origin: Vec3 get() = SpellCasting.handOrigin(caster, hand)

    /** The unit direction from [origin] to what the caster looks at. */
    val aim: Vec3 get() = SpellCasting.handAim(caster, origin)

    fun ctx(): SpellContext = SpellContext(level, caster, origin)

    /** What's under the caster's crosshair within [range]: the first block (a ship's too) or
     *  creature along their view, or the point that far out if nothing is. */
    fun aimed(range: Double): Aimed {
        val eye = caster.eyePosition
        val view = caster.getViewVector(1f)
        var end = eye.add(view.scale(range))
        val hit = level.clipIncludeShips(ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, caster))
        val blockHit = hit.takeIf { it.type == HitResult.Type.BLOCK }
        if (blockHit != null) end = blockHit.location
        val entityHit = ProjectileUtil.getEntityHitResult(level, caster, eye, end, caster.boundingBox.expandTowards(end.subtract(eye)).inflate(1.0)) {
            it is LivingEntity && it.isAlive && !it.isSpectator && it !== caster
        }
        if (entityHit != null) return Aimed(entityHit.location, entityHit.entity as LivingEntity, null, null)
        return Aimed(end, null, blockHit?.blockPos, blockHit?.direction)
    }

    /** The point [Aimed.point] lands on; [entity] is a creature it struck; [block] and [face]
     *  a block it struck (in a ship's yard when it's a ship's). */
    data class Aimed(val point: Vec3, val entity: LivingEntity?, val block: BlockPos?, val face: Direction?)
}

/**
 * What a [Signature] does on the server. One object serves every cast of its signature; a
 * cast's own state lives on its [SignatureEntity] ([SignatureEntity.state]).
 */
abstract class SignatureBehaviour {
    /** [Signature.Style.RELEASE]: the cast leaves the hand, [SignatureCast.power] from its charge.
     *  [Signature.Style.CHARGE], [Signature.Style.HELD]: the hold starts. Returns its entity,
     *  already added to the level, or null if it did everything at once. */
    abstract fun start(cast: SignatureCast): SignatureEntity?

    /** Each server tick of its entity. */
    open fun tick(e: SignatureEntity) {}

    /** CHARGE and HELD: each server tick while the cast is held, [held] ticks in. */
    open fun held(e: SignatureEntity, cast: SignatureCast, held: Int) {}

    /** CHARGE and HELD: let go after [held] ticks. By default it ends there. */
    open fun released(e: SignatureEntity, cast: SignatureCast, held: Int) {
        end(e)
        e.discard()
    }

    /** It ends: its life ran out, or a hold was let go. Not called when it's cut short otherwise. */
    open fun end(e: SignatureEntity) {}

    /** Spawns a [SignatureEntity] for [cast] at [at], living [life] ticks. */
    protected fun spawn(cast: SignatureCast, at: Vec3, life: Int, setup: SignatureEntity.() -> Unit = {}): SignatureEntity {
        val e = SignatureEntity(cast, at, life)
        e.setup()
        cast.level.addFreshEntity(e)
        return e
    }
}

/** Every signature's behaviour. */
object SignatureBehaviours {
    private val ALL: Map<Signature, SignatureBehaviour> by lazy {
        ReferenceSignatures.ALL + BodySignatures.ALL + WaveSignatures.ALL + FieldSignatures.ALL + LinkSignatures.ALL
    }

    fun of(signature: Signature): SignatureBehaviour = ALL[signature] ?: Fizzle

    /** A signature with nothing behind it yet: it fizzles in the hand. */
    private object Fizzle : SignatureBehaviour() {
        override fun start(cast: SignatureCast): SignatureEntity? {
            val at = cast.origin
            cast.level.playSound(null, at.x, at.y, at.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.5f, 1.4f)
            return null
        }
    }
}

/**
 * Where a scroll's Forward cast hands over to its signature: [useTick] each tick it's held,
 * [release] when it's let go, [abandon] when it's dropped or switched away from.
 */
object Signatures {
    /** Each caster's live CHARGE or HELD signature. Server thread only. */
    private val holds = HashMap<UUID, SignatureEntity>()

    /** A release's power from how long it was charged, as a Bolt's: full at [FULL_CHARGE_TICKS]. */
    fun charge(held: Int): Float = (held.toFloat() / FULL_CHARGE_TICKS).coerceIn(MIN_CHARGE, 1f)

    fun useTick(level: ServerLevel, caster: LivingEntity, hand: InteractionHand, spell: Spell, signature: Signature, held: Int) {
        if (signature.style == Signature.Style.RELEASE) return
        val behaviour = SignatureBehaviours.of(signature)
        val cast = SignatureCast(level, caster, hand, spell, signature, charge(held))
        // Started once, as the hold starts; one that has ended itself isn't started again.
        if (held == 0) {
            holds.remove(caster.uuid)?.discard()
            holds[caster.uuid] = behaviour.start(cast) ?: return
            return
        }
        val live = holds[caster.uuid] ?: return
        if (live.isRemoved) return
        behaviour.held(live, cast, held)
    }

    fun release(level: ServerLevel, caster: LivingEntity, hand: InteractionHand, spell: Spell, signature: Signature, held: Int) {
        val behaviour = SignatureBehaviours.of(signature)
        val cast = SignatureCast(level, caster, hand, spell, signature, charge(held))
        if (signature.style == Signature.Style.RELEASE) {
            behaviour.start(cast)
            return
        }
        // A tap, let go before the first tick held it: it starts and is let go at once.
        val live = holds.remove(caster.uuid) ?: if (held == 0) behaviour.start(cast) else null
        if (live != null && !live.isRemoved) behaviour.released(live, cast, held)
    }

    /** The cast was abandoned without a release: whatever it held simply ends. */
    fun abandon(caster: LivingEntity) {
        holds.remove(caster.uuid)?.discard()
    }

    const val FULL_CHARGE_TICKS = 30
    const val MIN_CHARGE = 0.25f
}
