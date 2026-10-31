package org.shipwrights.enderkinesis.spell

import com.mojang.authlib.GameProfile
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageType
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobType
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.tags.TagKey
import net.minecraft.util.Mth
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.mixin.LivingEntityLastHurtAccessor
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * Who cast a spell and from where. Spells don't need a caster: runes, merged beams and
 * (later) blocks and enemies cast them too.
 *
 * - [caster] is excluded from the spell's own effects, gets Blood's buff, and takes the kill
 *   credit. With no caster, nothing is excluded.
 * - [owner] decides claim checks; it is the caster's UUID unless the spell was stored (a rune).
 *   An ownerless spell skips claims entirely, like an environmental hazard.
 * - [castPoint] is where the spell started; see the spec's cast point table.
 * - [pulsed] is true for held shapes that re-apply every 0.5 s (Beam, Chain, Spray). They
 *   deal [PULSE_DAMAGE] of a one-shot cast's damage and smaller doses of stacking effects.
 * - [share] is how much of a pulse this application is. A Beam or Spray applies every
 *   [SpellCasting.STREAM_TICKS] rather than once a pulse, so whatever it sweeps over is
 *   reached, each application a share of a pulse: amounts are scaled by it ([damageAmount],
 *   [portion]), chances and one-off happenings rolled for it ([chance], [once]), and hits land
 *   through i-frames ([hurt]). Held on one target, a pulse adds up to what it always did.
 * - [beat] is false for the applications between pulses: the volume each leaves and the loop
 *   sounds come only on the pulse.
 * - [power] scales the damage of a Bolt's burst by how far it was drawn
 *   ([SpellCasting.boltPower]); every other cast leaves it at 1.
 * - [surfaceFire] is false for a cast whose Heat mustn't light fire on the ground (a bolt
 *   another spell fires, which keeps its own count of the fire it leaves).
 * - [buffRecipient] overrides who gets Blood's buff (a rune's online owner).
 * - [nearestRecipient]: with no caster or override, Blood's buff goes to the living
 *   non-undead entity nearest the cast point. Runes turn this off, so an ownerless or
 *   offline-owned rune's buff fizzles.
 * - [use] is how the scroll was used, which picks each element's one complex effect.
 */
class SpellContext(
    val level: ServerLevel,
    val caster: LivingEntity?,
    val castPoint: Vec3,
    val owner: UUID? = caster?.uuid,
    val pulsed: Boolean = false,
    val buffRecipient: LivingEntity? = null,
    val nearestRecipient: Boolean = true,
    val share: Float = 1f,
    val beat: Boolean = true,
    val use: SpellUse = SpellUse.FORWARD,
    val power: Float = 1f,
    val surfaceFire: Boolean = true,
) {
    /** The owner as a player for claim checks: online, or a fake player when offline. */
    private val ownerPlayer: ServerPlayer? by lazy {
        val uuid = owner ?: return@lazy null
        level.server.playerList.getPlayer(uuid)?.let { return@lazy it }
        val profile = level.server.profileCache?.get(uuid)?.orElse(null) ?: GameProfile(uuid, null)
        SpellPlatform.fakePlayer(level, profile)
    }

    /** A [DamageSource] of [type] with the caster as attacker, or none. */
    fun damage(type: ResourceKey<DamageType>): DamageSource {
        val holder = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(type)
        return DamageSource(holder, null as Entity?, caster)
    }

    /** [base] damage at [magnitude], as [PULSE_DAMAGE] of it for a pulse of a held cast (and
     *  this application's [share] of that), and scaled by a Bolt's [power]. */
    fun damageAmount(base: Float, magnitude: Float): Float =
        SpellCurves.damage(base, magnitude) * (if (pulsed) PULSE_DAMAGE else 1f) * share * power

    /**
     * Hurts [target] with [amount] from [source]. A streamed share comes several times inside
     * vanilla's i-frames, so it lands through them and leaves the target's own as they were
     * (another hit is no easier for it), and pushes it back only by its share.
     */
    fun hurt(target: Entity, source: DamageSource, amount: Float): Boolean {
        if (share >= 1f || target !is LivingEntity) return target.hurt(source, amount)
        val frames = target.invulnerableTime
        val last = (target as LivingEntityLastHurtAccessor).`enderkinesis$getLastHurt`()
        val before = target.deltaMovement
        target.invulnerableTime = 0
        val hit = target.hurt(source, amount)
        target.invulnerableTime = frames
        (target as LivingEntityLastHurtAccessor).`enderkinesis$setLastHurt`(last)
        if (hit) target.deltaMovement = before.add(target.deltaMovement.subtract(before).scale(share.toDouble()))
        return hit
    }

    /** [amount] scaled to this application's [share], in whole units: the fraction left over
     *  is rolled for, so shares add up to [amount] over a pulse. */
    fun portion(amount: Float): Int {
        val scaled = amount * share
        val whole = Mth.floor(scaled)
        return whole + if (level.random.nextFloat() < scaled - whole) 1 else 0
    }

    /** A roll that passes with [perPulse] chance over a whole pulse's applications. */
    fun chance(perPulse: Float): Boolean {
        if (share >= 1f) return level.random.nextFloat() < perPulse
        if (perPulse >= 1f) return true
        return level.random.nextFloat() < 1f - Math.pow(1.0 - perPulse, share.toDouble()).toFloat()
    }

    /** Whether something that happens once a pulse (a teleport, a shuffle) happens on this
     *  application: always for a whole pulse, else as often as its share. */
    fun once(): Boolean = share >= 1f || level.random.nextFloat() < share

    fun isCaster(entity: Entity): Boolean = caster != null && entity === caster

    /** Whether [target] is plainly the caster's enemy: a monster, or a mob that's after the
     *  caster. The tending elements (Growth, Purity) turn their harmful side on these alone
     *  when cast Forward. */
    fun isFoe(target: LivingEntity): Boolean =
        !isCaster(target) && (target is Enemy || (caster != null && target is Mob && target.target === caster))

    /** Whether the spell may change the block at [pos] to [newState]. With an owner, this goes
     *  through their permissions as if they did it by hand: `mayInteract` (spawn protection),
     *  then the loader's break event for what's removed and its place event for what's put
     *  down. Ownerless spells may always. */
    fun mayChange(pos: BlockPos, newState: BlockState): Boolean {
        val player = ownerPlayer ?: return owner == null
        if (!level.mayInteract(player, pos)) return false
        val old = level.getBlockState(pos)
        if (!old.isAir && old.fluidState.isEmpty && !SpellPlatform.mayBreak(level, pos, old, player)) return false
        if (!newState.isAir && !SpellPlatform.mayPlace(level, pos, newState, player)) return false
        return true
    }

    /** The element whose effect is being applied, while it is: what a block change looks like
     *  depends on it. */
    var element: Element? = null

    /** The elemental environment round the caster (or, with none, the cast point): each
     *  element's effect is that much stronger, up to one magnitude ([ElementalEnvironment]). */
    val environment: Map<Element, Float> by lazy {
        caster?.let { ElementalEnvironment.of(level, it) } ?: ElementalEnvironment.at(level, castPoint)
    }

    /** Every element the spell carries, as it's applied, so Purity's Cleanse leaves alone what
     *  the rest of the spell puts on its targets. */
    var elements: Set<Element> = emptySet()

    /** Sets [pos] to [state] if [mayChange] allows; returns whether it did. A block that becomes
     *  another is seen to change, wiping away from where the spell came from. */
    fun setBlock(pos: BlockPos, state: BlockState): Boolean {
        if (!mayChange(pos, state)) return false
        val old = level.getBlockState(pos)
        if (!level.setBlock(pos, state, 3)) return false
        if (!old.isAir && old.block != state.block) SpellVfx.blockShift(level, pos, old, castPoint, element)
        return true
    }

    companion object {
        /** A held cast's per-pulse share of a one-shot cast's damage. Two pulses a second, so
         *  held on one target a shape deals 1.4 × a one-shot cast's damage a second: a full
         *  scroll's 4-base element (13.6 a cast) about 19 a second, a 3-base one about 14,
         *  level with a Sharpness V netherite sword. A full-length Chain fires up to 40 pulses. */
        const val PULSE_DAMAGE = 0.7f

        fun isUndead(entity: LivingEntity): Boolean = entity.mobType == MobType.UNDEAD

        /** Bosses no spell can take control of, hold, or move: Enthralled, Frozen, Absent,
         *  Entangled, Discord and teleports pass them by. */
        val BOSSES: TagKey<EntityType<*>> = TagKey.create(Registries.ENTITY_TYPE, EnderkinesisMod.id("spell_bosses"))

        fun isBoss(entity: Entity): Boolean = entity.type.`is`(BOSSES)
    }
}

/**
 * How a scroll was used. An attack carries at most one complex effect, and the use picks it.
 * Forward and Area casts reach whatever is in the way, friend or foe, so they carry effects that
 * are safe on anyone; a rune is a trap and an imbued weapon strikes only what its wielder
 * attacks, so they are [hostile] and carry the effects meant for enemies. Merged beams, beam
 * clashes and other casts with no scroll in hand count as Forward.
 */
enum class SpellUse {
    FORWARD, AREA, RUNE, IMBUE;

    val hostile: Boolean get() = this == RUNE || this == IMBUE
}
