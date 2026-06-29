package org.shipwrights.enderkinesis.dimension

import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageType
import net.minecraft.world.entity.LivingEntity
import org.shipwrights.enderkinesis.EnderkinesisMod

/**
 * Mod-shared [DamageSource] factories. Damage type JSONs live under
 * `data/enderkinesis/damage_type/`; the bypasses-invulnerability tag is at
 * `data/minecraft/tags/damage_type/bypasses_invulnerability.json`.
 */
object EKDamageSources {

    /** Custom damage type used by both [YgannAbyssVoidFade] (void killplane kill) and
     *  [YgannMadnessBehavior] (water-contact + L5 passive). Death message reads
     *  "<player> succumbed to the brink" (see `death.attack.brink` in en_us.json).
     *  Tagged into `bypasses_invulnerability` so creative players still take it. */
    private val BRINK_KEY: ResourceKey<DamageType> =
        ResourceKey.create(Registries.DAMAGE_TYPE, EnderkinesisMod.id("brink"))

    fun brink(player: ServerPlayer): DamageSource {
        val registry = player.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
        return DamageSource(registry.getHolderOrThrow(BRINK_KEY))
    }

    private val LIGHT_DAMAGE_KEYS: List<ResourceKey<DamageType>> = listOf(
        ResourceKey.create(Registries.DAMAGE_TYPE, EnderkinesisMod.id("light_damage")),
        ResourceKey.create(Registries.DAMAGE_TYPE, EnderkinesisMod.id("light_damage_sunburnt")),
        ResourceKey.create(Registries.DAMAGE_TYPE, EnderkinesisMod.id("light_damage_dark")),
    )

    /** Pick one of the three light-damage variants uniformly at random. Each variant maps
     *  to a different death message via its `message_id` (see `data/enderkinesis/damage_type/`
     *  and `death.attack.light_damage*` in en_us.json). All three carry identical mechanical
     *  behaviour — magic + bypasses-armor via vanilla tag merges. */
    fun lightDamage(level: ServerLevel, target: LivingEntity): DamageSource {
        val registry = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
        val key = LIGHT_DAMAGE_KEYS[target.random.nextInt(LIGHT_DAMAGE_KEYS.size)]
        return DamageSource(registry.getHolderOrThrow(key))
    }
}
