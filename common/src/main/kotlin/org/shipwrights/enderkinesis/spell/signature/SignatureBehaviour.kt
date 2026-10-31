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

/** [power] is the charge in 0..1 from [Signatures.charge]; a hold that just started has the minimum. */
class SignatureCast(
    val level: ServerLevel, val caster: LivingEntity, val hand: InteractionHand,
    val spell: Spell, val signature: Signature, val power: Float,
) {
    val origin: Vec3 get() = SpellCasting.handOrigin(caster, hand)

    val aim: Vec3 get() = SpellCasting.handAim(caster, origin)

    fun ctx(): SpellContext = SpellContext(level, caster, origin)

    /** The first block (ship blocks too) or creature under the crosshair within [range], else the point at [range]. */
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

    /** [block] and [face] are in shipyard coordinates when the hit block is on a ship. */
    data class Aimed(val point: Vec3, val entity: LivingEntity?, val block: BlockPos?, val face: Direction?)
}

/** One object serves every cast of its signature, so keep per-cast state on [SignatureEntity.state]. */
abstract class SignatureBehaviour {
    /** Returns the entity, already added to the level, or null when the cast did all its work at once. */
    abstract fun start(cast: SignatureCast): SignatureEntity?

    open fun tick(e: SignatureEntity) {}

    open fun held(e: SignatureEntity, cast: SignatureCast, held: Int) {}

    open fun released(e: SignatureEntity, cast: SignatureCast, held: Int) {
        end(e)
        e.discard()
    }

    /** Only the end of its life or a released hold calls this; other removals do not. */
    open fun end(e: SignatureEntity) {}

    protected fun spawn(cast: SignatureCast, at: Vec3, life: Int, setup: SignatureEntity.() -> Unit = {}): SignatureEntity {
        val e = SignatureEntity(cast, at, life)
        e.setup()
        cast.level.addFreshEntity(e)
        return e
    }
}

object SignatureBehaviours {
    private val ALL: Map<Signature, SignatureBehaviour> by lazy {
        ReferenceSignatures.ALL + BodySignatures.ALL + WaveSignatures.ALL + FieldSignatures.ALL + LinkSignatures.ALL
    }

    fun of(signature: Signature): SignatureBehaviour = ALL[signature] ?: Fizzle

    private object Fizzle : SignatureBehaviour() {
        override fun start(cast: SignatureCast): SignatureEntity? {
            val at = cast.origin
            cast.level.playSound(null, at.x, at.y, at.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.5f, 1.4f)
            return null
        }
    }
}

object Signatures {
    /** Server thread only. */
    private val holds = HashMap<UUID, SignatureEntity>()

    fun charge(held: Int): Float = (held.toFloat() / FULL_CHARGE_TICKS).coerceIn(MIN_CHARGE, 1f)

    fun useTick(level: ServerLevel, caster: LivingEntity, hand: InteractionHand, spell: Spell, signature: Signature, held: Int) {
        if (signature.style == Signature.Style.RELEASE) return
        val behaviour = SignatureBehaviours.of(signature)
        val cast = SignatureCast(level, caster, hand, spell, signature, charge(held))
        // Start only at held == 0, so a hold that ended itself does not start again.
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
        // A tap released before the first held tick starts and releases at once.
        val live = holds.remove(caster.uuid) ?: if (held == 0) behaviour.start(cast) else null
        if (live != null && !live.isRemoved) behaviour.released(live, cast, held)
    }

    fun abandon(caster: LivingEntity) {
        holds.remove(caster.uuid)?.discard()
    }

    fun clearSession() = holds.clear()

    const val FULL_CHARGE_TICKS = 30
    const val MIN_CHARGE = 0.25f
}
