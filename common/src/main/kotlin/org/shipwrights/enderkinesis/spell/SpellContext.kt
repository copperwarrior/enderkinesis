package org.shipwrights.enderkinesis.spell

import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageType
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.MobType
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * Who cast a spell and from where. Spells don't need a caster: runes, merged beams and
 * (later) blocks and enemies cast them too.
 *
 * - [caster] is excluded from the spell's own effects, gets Blood's buff, and takes the kill
 *   credit. With no caster, nothing is excluded.
 * - [owner] decides claim checks; it is the caster's UUID unless the spell was stored (a rune).
 * - [castPoint] is where the spell started; see the spec's cast point table.
 * - [pulsed] is true for held shapes that re-apply every 0.5 s (Beam, Chain, Spray), which
 *   deliver smaller doses of stacking effects.
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
    /** A [DamageSource] of [type] with the caster as attacker, or none. */
    fun damage(type: ResourceKey<DamageType>): DamageSource {
        val holder = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(type)
        return DamageSource(holder, null as Entity?, caster)
    }

    fun isCaster(entity: Entity): Boolean = caster != null && entity === caster

    companion object {
        fun isUndead(entity: LivingEntity): Boolean = entity.mobType == MobType.UNDEAD
    }
}
