package org.shipwrights.enderkinesis.registry

import dev.architectury.registry.registries.DeferredRegister
import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.core.registries.Registries
import net.minecraft.world.effect.MobEffect
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.effect.FlameCloakEffect
import org.shipwrights.enderkinesis.effect.FrostCloakEffect
import org.shipwrights.enderkinesis.effect.LightningCloakEffect
import org.shipwrights.enderkinesis.effect.AbsorptionCloakEffect
import org.shipwrights.enderkinesis.effect.PoisonCloakEffect
import org.shipwrights.enderkinesis.effect.VoidCloakEffect
import org.shipwrights.enderkinesis.effect.HaloEffect
import org.shipwrights.enderkinesis.effect.AnchoredEffect
import org.shipwrights.enderkinesis.effect.AmbrosialCravingEffect
import org.shipwrights.enderkinesis.effect.CrepuscularFloatationEffect
import org.shipwrights.enderkinesis.effect.CrystallizedEffect
import org.shipwrights.enderkinesis.effect.EntangledEffect
import org.shipwrights.enderkinesis.effect.WeightedEffect
import org.shipwrights.enderkinesis.effect.DiscordEffect
import org.shipwrights.enderkinesis.effect.EnthralledEffect
import org.shipwrights.enderkinesis.effect.BleedingEffect
import org.shipwrights.enderkinesis.effect.BloodboundEffect
import org.shipwrights.enderkinesis.effect.BloodSurgeEffect
import org.shipwrights.enderkinesis.effect.RotEffect
import org.shipwrights.enderkinesis.effect.HollowedEffect
import org.shipwrights.enderkinesis.effect.FrozenEffect
import org.shipwrights.enderkinesis.effect.AbsentEffect
import org.shipwrights.enderkinesis.effect.OwlsMarkEffect
import org.shipwrights.enderkinesis.effect.SselithMadnessEffect
import org.shipwrights.enderkinesis.effect.YgannMadnessEffect

/** Status effects added by Enderkinesis. */
object EKEffects {
    val EFFECTS: DeferredRegister<MobEffect> =
        DeferredRegister.create(EnderkinesisMod.MOD_ID, Registries.MOB_EFFECT)

    /** Grace timer marking an entity as "recently floating" on the virtual ocean. */
    val CREPUSCULAR_FLOATATION: RegistrySupplier<MobEffect> =
        EFFECTS.register("crepuscular_floatation") { CrepuscularFloatationEffect() }

    /** Stacking after-effect applied on leaving Ygann's Abyss; see [YgannMadnessEffect]. */
    val YGANN_MADNESS: RegistrySupplier<MobEffect> =
        EFFECTS.register("ygann_madness") { YgannMadnessEffect() }

    /** Creeping madness that grows with time in Sselith's Repertory; see
     *  [SselithMadnessEffect] and [org.shipwrights.enderkinesis.dimension.SselithMadness]. */
    val SSELITH_MADNESS: RegistrySupplier<MobEffect> =
        EFFECTS.register("sselith_madness") { SselithMadnessEffect() }

    /** Java-callable static accessor for [SSELITH_MADNESS]'s resolved
     *  [MobEffect] — used by client mixins that need to check the effect
     *  without bouncing through the Kotlin object's instance getter. */
    @JvmStatic
    fun sselithMadnessEffect(): MobEffect = SSELITH_MADNESS.get()

    /** Permanent-until-death debuff applied by Wey'ye fruit. See
     *  [AmbrosialCravingEffect]. */
    val AMBROSIAL_CRAVING: RegistrySupplier<MobEffect> =
        EFFECTS.register("ambrosial_craving") { AmbrosialCravingEffect() }

    /** Java-callable static accessor for [AMBROSIAL_CRAVING]. */
    @JvmStatic
    fun ambrosialCravingEffect(): MobEffect = AMBROSIAL_CRAVING.get()

    val OWLS_MARK: RegistrySupplier<MobEffect> =
        EFFECTS.register("owls_mark") { OwlsMarkEffect() }

    /** Purity's damage over time that shatters when it ends; see [CrystallizedEffect]. */
    val CRYSTALLIZED: RegistrySupplier<MobEffect> =
        EFFECTS.register("crystallized") { CrystallizedEffect() }

    /** Growth: roots hold the mob in place. See [EntangledEffect]. */
    val ENTANGLED: RegistrySupplier<MobEffect> = EFFECTS.register("entangled") { EntangledEffect() }

    /** Gravity: no jumping, sinks, no knockback, double fall damage. See [WeightedEffect]. */
    val WEIGHTED: RegistrySupplier<MobEffect> = EFFECTS.register("weighted") { WeightedEffect() }

    /** Chaos: mobs turn on random targets. See [DiscordEffect]. */
    val DISCORD: RegistrySupplier<MobEffect> = EFFECTS.register("discord") { DiscordEffect() }

    /** Blood: the mob fights for its master. See [EnthralledEffect]. */
    val ENTHRALLED: RegistrySupplier<MobEffect> = EFFECTS.register("enthralled") { EnthralledEffect() }

    /** Blood: damage over time while moving. See [BleedingEffect]. */
    val BLEEDING: RegistrySupplier<MobEffect> = EFFECTS.register("bleeding") { BleedingEffect() }

    /** Blood: takes part of its binder's damage. See [BloodboundEffect]. */
    val BLOODBOUND: RegistrySupplier<MobEffect> = EFFECTS.register("bloodbound") { BloodboundEffect() }

    /** Blood: extra hearts for whoever the drain feeds, fading when it ends. See [BloodSurgeEffect]. */
    val BLOOD_SURGE: RegistrySupplier<MobEffect> = EFFECTS.register("blood_surge") { BloodSurgeEffect() }

    /** Java-callable static accessor for [BLOOD_SURGE]. */
    @JvmStatic
    fun bloodSurgeEffect(): MobEffect = BLOOD_SURGE.get()

    /** Poison: a spreading venom that also harms undead. See [RotEffect]. */
    val ROT: RegistrySupplier<MobEffect> = EFFECTS.register("rot") { RotEffect() }

    /** Void: no healing from any source. See [HollowedEffect]. */
    val HOLLOWED: RegistrySupplier<MobEffect> = EFFECTS.register("hollowed") { HollowedEffect() }

    /** Cold: frozen solid as a physics object. See [FrozenEffect]. */
    val FROZEN: RegistrySupplier<MobEffect> = EFFECTS.register("frozen") { FrozenEffect() }

    /** Void: out of the world. See [AbsentEffect]. */
    val ABSENT: RegistrySupplier<MobEffect> = EFFECTS.register("absent") { AbsentEffect() }

    /** Gravity's self-cast: no knockback or spell pulls, sinks, a little slower. See [AnchoredEffect]. */
    val ANCHORED: RegistrySupplier<MobEffect> = EFFECTS.register("anchored") { AnchoredEffect() }

    /** Sunfire's self-cast: undead nearby burn and are pushed away. See [HaloEffect]. */
    val HALO: RegistrySupplier<MobEffect> = EFFECTS.register("halo") { HaloEffect() }


    /** Heat's self-cast: burns whatever comes close, thaws you. See [FlameCloakEffect]. */
    val FLAME_CLOAK: RegistrySupplier<MobEffect> = EFFECTS.register("flame_cloak") { FlameCloakEffect() }

    /** Cold's self-cast: chills and slows whatever comes close, keeps you from burning. See [FrostCloakEffect]. */
    val FROST_CLOAK: RegistrySupplier<MobEffect> = EFFECTS.register("frost_cloak") { FrostCloakEffect() }

    /** Chaos's self-cast: arcs into whatever comes close. See [LightningCloakEffect]. */
    val LIGHTNING_CLOAK: RegistrySupplier<MobEffect> = EFFECTS.register("lightning_cloak") { LightningCloakEffect() }

    /** Blood's self-cast: draws a little life from whatever comes very close. See [AbsorptionCloakEffect]. */
    val ABSORPTION_CLOAK: RegistrySupplier<MobEffect> = EFFECTS.register("absorption_cloak") { AbsorptionCloakEffect() }

    /** Poison's self-cast: poisons whatever comes close, keeps poison off you. See [PoisonCloakEffect]. */
    val POISON_CLOAK: RegistrySupplier<MobEffect> = EFFECTS.register("poison_cloak") { PoisonCloakEffect() }

    /** Void's self-cast: lashes enemies nearby with tentacles. See [VoidCloakEffect]. */
    val VOID_CLOAK: RegistrySupplier<MobEffect> = EFFECTS.register("void_cloak") { VoidCloakEffect() }

    fun register() = EFFECTS.register()
}
