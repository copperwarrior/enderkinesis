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

/** A null [owner] skips claim checks. A Beam or Spray applies at [share] of a pulse, so amounts
 *  and rolls scale by [share] ([damageAmount], [portion], [chance], [once], [hurt]). */
class SpellContext(
    val level: ServerLevel,
    val caster: LivingEntity?,
    val castPoint: Vec3,
    val owner: UUID? = caster?.uuid,
    val pulsed: Boolean = false,
    val buffRecipient: LivingEntity? = null,
    val nearestRecipient: Boolean = true, // Runes turn this off, so the buff of an offline owner fizzles.
    val share: Float = 1f,
    val beat: Boolean = true, // False between pulses: volumes and loop sounds come only on the pulse.
    val use: SpellUse = SpellUse.FORWARD,
    val power: Float = 1f,
    val surfaceFire: Boolean = true, // False for a bolt that another spell fires; that spell counts its own fire.
    val charge: Float = 1f, // Only an Area release is charged; gems and runes are always full.
) {
    /** A fake player when the owner is offline. */
    private val ownerPlayer: ServerPlayer? by lazy {
        val uuid = owner ?: return@lazy null
        level.server.playerList.getPlayer(uuid)?.let { return@lazy it }
        val profile = level.server.profileCache?.get(uuid)?.orElse(null) ?: GameProfile(uuid, null)
        SpellPlatform.fakePlayer(level, profile)
    }

    fun damage(type: ResourceKey<DamageType>): DamageSource {
        val holder = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(type)
        return DamageSource(holder, null as Entity?, caster)
    }

    fun damageAmount(base: Float, magnitude: Float): Float =
        SpellCurves.damage(base, magnitude) * (if (pulsed) PULSE_DAMAGE else 1f) * share * power

    /** A streamed share hits through vanilla i-frames and restores them after, so the next hit is
     *  not easier. Knockback scales by the share. */
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

    /** Roll for the fraction, so the shares add up to [amount] over a pulse. */
    fun portion(amount: Float): Int {
        val scaled = amount * share
        val whole = Mth.floor(scaled)
        return whole + if (level.random.nextFloat() < scaled - whole) 1 else 0
    }

    /** Passes with [perPulse] chance over all the applications of one pulse. */
    fun chance(perPulse: Float): Boolean {
        if (share >= 1f) return level.random.nextFloat() < perPulse
        if (perPulse >= 1f) return true
        return level.random.nextFloat() < 1f - Math.pow(1.0 - perPulse, share.toDouble()).toFloat()
    }

    /** For things that occur once a pulse, such as a teleport or a shuffle. */
    fun once(): Boolean = share >= 1f || level.random.nextFloat() < share

    fun isCaster(entity: Entity): Boolean = caster != null && entity === caster

    /** Growth and Purity, cast Forward, harm only these targets. */
    fun isFoe(target: LivingEntity): Boolean =
        !isCaster(target) && (target is Enemy || (caster != null && target is Mob && target.target === caster))

    /** Checks the owner's permissions as for a change by hand: spawn protection, then the loader's
     *  break and place events. */
    fun mayChange(pos: BlockPos, newState: BlockState): Boolean {
        val player = ownerPlayer ?: return owner == null
        if (!level.mayInteract(player, pos)) return false
        val old = level.getBlockState(pos)
        if (!old.isAir && old.fluidState.isEmpty && !SpellPlatform.mayBreak(level, pos, old, player)) return false
        if (!newState.isAir && !SpellPlatform.mayPlace(level, pos, newState, player)) return false
        return true
    }

    /** The element that applies now. The block change visual depends on it. */
    var element: Element? = null

    val environment: Map<Element, Float> by lazy {
        caster?.let { ElementalEnvironment.of(level, it) } ?: ElementalEnvironment.at(level, castPoint)
    }

    /** Purity's Cleanse does not remove what these elements apply. */
    var elements: Set<Element> = emptySet()

    /** [directed] false when [castPoint] is not where the change comes from. The client then picks a random side. */
    fun setBlock(pos: BlockPos, state: BlockState, directed: Boolean = true): Boolean {
        if (!mayChange(pos, state)) return false
        val old = level.getBlockState(pos)
        if (!level.setBlock(pos, state, 3)) return false
        if (!old.isAir && old.block != state.block) SpellNetwork.queueBlockShift(level, pos, old, castPoint, element, directed)
        return true
    }

    companion object {
        /** Two pulses a second give 1.4 × one-shot damage each second, which is level with a
         *  Sharpness V netherite sword. */
        const val PULSE_DAMAGE = 0.7f

        fun isUndead(entity: LivingEntity): Boolean = entity.mobType == MobType.UNDEAD

        /** No spell can control, hold, or move these bosses. */
        val BOSSES: TagKey<EntityType<*>> = TagKey.create(Registries.ENTITY_TYPE, EnderkinesisMod.id("spell_bosses"))

        fun isBoss(entity: Entity): Boolean = entity.type.`is`(BOSSES)
    }
}

/** Forward and Area casts reach friends too, so they carry safe effects. Runes and imbues are
 *  [hostile] and carry the effects for enemies. Casts with no scroll in hand count as Forward. */
enum class SpellUse {
    FORWARD, AREA, RUNE, IMBUE;

    val hostile: Boolean get() = this == RUNE || this == IMBUE
}
