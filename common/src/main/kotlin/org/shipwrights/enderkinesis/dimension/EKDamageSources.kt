package org.shipwrights.enderkinesis.dimension

import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageType
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
}
