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
import net.minecraft.world.entity.MobType
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
 * - [buffRecipient] overrides who gets Blood's buff (a rune's online owner).
 */
class SpellContext(
    val level: ServerLevel,
    val caster: LivingEntity?,
    val castPoint: Vec3,
    val owner: UUID? = caster?.uuid,
    val pulsed: Boolean = false,
    val buffRecipient: LivingEntity? = null,
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

    /** [base] damage at [magnitude], reduced for a pulse of a held cast. */
    fun damageAmount(base: Float, magnitude: Float): Float =
        SpellCurves.damage(base, magnitude) * (if (pulsed) PULSE_DAMAGE else 1f)

    fun isCaster(entity: Entity): Boolean = caster != null && entity === caster

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

    /** Sets [pos] to [state] if [mayChange] allows; returns whether it did. */
    fun setBlock(pos: BlockPos, state: BlockState): Boolean =
        mayChange(pos, state) && level.setBlock(pos, state, 3)

    companion object {
        /** A held cast's per-pulse share of a one-shot cast's damage. A full-length Chain
         *  fires up to 40 pulses. */
        const val PULSE_DAMAGE = 0.25f

        fun isUndead(entity: LivingEntity): Boolean = entity.mobType == MobType.UNDEAD
    }
}
