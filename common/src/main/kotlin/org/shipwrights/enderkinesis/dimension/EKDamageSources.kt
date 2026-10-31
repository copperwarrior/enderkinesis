package org.shipwrights.enderkinesis.dimension

import net.minecraft.world.entity.Entity
import net.minecraft.world.effect.MobEffect
import org.shipwrights.enderkinesis.effect.SpellCredit
import net.minecraft.core.Holder
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.Level
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

    /** [lightDamage] dealt by [attacker] (a caster), who is named in the death message and gets
     *  the kill. */
    fun lightDamage(level: ServerLevel, target: LivingEntity, attacker: Entity?): DamageSource {
        val registry = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
        val key = LIGHT_DAMAGE_KEYS[target.random.nextInt(LIGHT_DAMAGE_KEYS.size)]
        return DamageSource(registry.getHolderOrThrow(key), attacker)
    }

    /** Used by the Wand of Ga'Mylan when it kills a low-HP player —
     *  death message reads "<attacker> brought light into <victim>'s
     *  layer" (`death.attack.gamylan_light`). Attacker is carried in the
     *  DamageSource so vanilla can interpolate it into the message. */
    private val GAMYLAN_LIGHT_KEY: ResourceKey<DamageType> =
        ResourceKey.create(Registries.DAMAGE_TYPE, EnderkinesisMod.id("gamylan_light"))

    fun gamylanLight(level: ServerLevel, attacker: ServerPlayer): DamageSource {
        val registry = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
        return DamageSource(registry.getHolderOrThrow(GAMYLAN_LIGHT_KEY), attacker)
    }

    /** Contact with the Sselith Remnant's field — no attacker entity, the
     *  mass is a pseudo-entity with nothing to blame. */
    private val SSELITH_REMNANT_KEY: ResourceKey<DamageType> =
        ResourceKey.create(Registries.DAMAGE_TYPE, EnderkinesisMod.id("irreverant_remnant"))

    fun irreverantRemnant(level: ServerLevel): DamageSource {
        val registry = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
        return DamageSource(registry.getHolderOrThrow(SSELITH_REMNANT_KEY))
    }

    /** Purity's Crystallized effect and its shatter. Bypasses armour (vanilla tag merge);
     *  death message `death.attack.crystallization`. */
    val CRYSTALLIZATION_KEY: ResourceKey<DamageType> =
        ResourceKey.create(Registries.DAMAGE_TYPE, EnderkinesisMod.id("crystallization"))

    /** Blood's drain. Magic that bypasses armour, like vanilla magic, with its own death
     *  message (`death.attack.blood_drain`, `.player` when it has a caster). */
    val BLOOD_DRAIN_KEY: ResourceKey<DamageType> =
        ResourceKey.create(Registries.DAMAGE_TYPE, EnderkinesisMod.id("blood_drain"))

    /** Blood's Bleeding: magic that bypasses armour, with its own death message. */
    val BLEEDING_KEY: ResourceKey<DamageType> = ResourceKey.create(Registries.DAMAGE_TYPE, EnderkinesisMod.id("bleeding"))

    /** Chaos, dealt to everything a Chaos spell throws about. Magic that bypasses armour,
     *  with its own death message (`death.attack.chaos`, `.player` when it has a caster). */
    val CHAOS_KEY: ResourceKey<DamageType> =
        ResourceKey.create(Registries.DAMAGE_TYPE, EnderkinesisMod.id("chaos"))

    /** Heat's fire: burns like fire (fire immunity stops it), with its own death message. */
    val HEAT_KEY: ResourceKey<DamageType> = ResourceKey.create(Registries.DAMAGE_TYPE, EnderkinesisMod.id("heat"))

    /** Cold's frost: counts as freezing, like powder snow, with its own death message. */
    val COLD_KEY: ResourceKey<DamageType> = ResourceKey.create(Registries.DAMAGE_TYPE, EnderkinesisMod.id("cold"))

    /** Sunfire's light. Magic that bypasses armour; undead take it double (see FusionEffects). */
    val SUNFIRE_KEY: ResourceKey<DamageType> = ResourceKey.create(Registries.DAMAGE_TYPE, EnderkinesisMod.id("sunfire"))

    /** Poison's Rot. Magic that bypasses armour. */
    val ROT_KEY: ResourceKey<DamageType> = ResourceKey.create(Registries.DAMAGE_TYPE, EnderkinesisMod.id("rot"))

    /** Growth's life, which burns the undead. Magic that bypasses armour. */
    val GROWTH_KEY: ResourceKey<DamageType> = ResourceKey.create(Registries.DAMAGE_TYPE, EnderkinesisMod.id("growth"))

    /** Gravity's crush: what Weighted adds on top of fall damage. Bypasses armour, as falls do. */
    val GRAVITY_KEY: ResourceKey<DamageType> = ResourceKey.create(Registries.DAMAGE_TYPE, EnderkinesisMod.id("gravity"))

    /** Void's touch of the Abyss. Magic that bypasses armour. */
    val VOID_KEY: ResourceKey<DamageType> = ResourceKey.create(Registries.DAMAGE_TYPE, EnderkinesisMod.id("void"))

    /** A source of the mod's damage type [key] with no attacker, for status effects. */
    fun of(level: Level, key: ResourceKey<DamageType>): DamageSource =
        DamageSource(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(key))

    /** A source of [key] for the status effect [effect] hurting [entity], credited to whoever
     *  put the effect on it, so the caster gets the kill. */
    fun ofEffect(entity: LivingEntity, effect: MobEffect, key: ResourceKey<DamageType>): DamageSource =
        EffectDamage(entity.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(key), SpellCredit.caster(entity, effect))

    /** A status effect's damage: the caster who gave the effect is named, for the kill, but
     *  it's the effect hurting, not them, so it knocks nothing back (LivingEntitySpellEffectsMixin). */
    class EffectDamage(type: Holder<DamageType>, caster: Entity?) : DamageSource(type, null as Entity?, caster)

    fun crystallization(level: ServerLevel): DamageSource {
        val registry = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
        return DamageSource(registry.getHolderOrThrow(CRYSTALLIZATION_KEY))
    }
}
