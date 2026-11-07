package org.shipwrights.enderkinesis.registry

import dev.architectury.registry.registries.DeferredRegister
import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.core.registries.Registries
import net.minecraft.world.effect.MobEffect
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.effect.MantleOfFlameEffect
import org.shipwrights.enderkinesis.effect.MantleOfFrostEffect
import org.shipwrights.enderkinesis.effect.MantleOfLightningEffect
import org.shipwrights.enderkinesis.effect.MantleOfAbsorptionEffect
import org.shipwrights.enderkinesis.effect.MantleOfPoisonEffect
import org.shipwrights.enderkinesis.effect.MantleOfVoidEffect
import org.shipwrights.enderkinesis.effect.PaleCrownEffect
import org.shipwrights.enderkinesis.effect.AnchoredEffect
import org.shipwrights.enderkinesis.effect.AmbrosialCravingEffect
import org.shipwrights.enderkinesis.effect.CrepuscularFloatationEffect
import org.shipwrights.enderkinesis.effect.CrystallizedEffect
import org.shipwrights.enderkinesis.effect.EntangledEffect
import org.shipwrights.enderkinesis.effect.WeightedEffect
import org.shipwrights.enderkinesis.effect.DiscordEffect
import org.shipwrights.enderkinesis.effect.EnthralledEffect
import org.shipwrights.enderkinesis.effect.UlderParanoiaEffect
import org.shipwrights.enderkinesis.effect.BleedingEffect
import org.shipwrights.enderkinesis.effect.BloodboundEffect
import org.shipwrights.enderkinesis.effect.BloodSurgeEffect
import org.shipwrights.enderkinesis.effect.RotEffect
import org.shipwrights.enderkinesis.effect.HollowedEffect
import org.shipwrights.enderkinesis.effect.FrozenEffect
import org.shipwrights.enderkinesis.effect.AbsentEffect
import org.shipwrights.enderkinesis.effect.OwlsMarkEffect
import org.shipwrights.enderkinesis.effect.SselithMadnessEffect
import org.shipwrights.enderkinesis.effect.TrypoemiaEffect
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

    val TRYPOEMIA: RegistrySupplier<MobEffect> =
        EFFECTS.register("trypoemia") { TrypoemiaEffect() }

    val CRYSTALLIZED: RegistrySupplier<MobEffect> =
        EFFECTS.register("crystallized") { CrystallizedEffect() }

    val INCUBATION: RegistrySupplier<MobEffect> =
        EFFECTS.register("incubation") { org.shipwrights.enderkinesis.effect.IncubationEffect() }

    val ENTANGLED: RegistrySupplier<MobEffect> = EFFECTS.register("entangled") { EntangledEffect() }

    val WEIGHTED: RegistrySupplier<MobEffect> = EFFECTS.register("weighted") { WeightedEffect() }

    val DISCORD: RegistrySupplier<MobEffect> = EFFECTS.register("discord") { DiscordEffect() }

    val ENTHRALLED: RegistrySupplier<MobEffect> = EFFECTS.register("enthralled") { EnthralledEffect() }

    val ULDER_PARANOIA: RegistrySupplier<MobEffect> = EFFECTS.register("ulder_paranoia") { UlderParanoiaEffect() }

    val BLEEDING: RegistrySupplier<MobEffect> = EFFECTS.register("bleeding") { BleedingEffect() }

    val BLOODBOUND: RegistrySupplier<MobEffect> = EFFECTS.register("bloodbound") { BloodboundEffect() }

    val BLOOD_SURGE: RegistrySupplier<MobEffect> = EFFECTS.register("blood_surge") { BloodSurgeEffect() }

    @JvmStatic
    fun bloodSurgeEffect(): MobEffect = BLOOD_SURGE.get()

    val ROT: RegistrySupplier<MobEffect> = EFFECTS.register("rot") { RotEffect() }

    val HOLLOWED: RegistrySupplier<MobEffect> = EFFECTS.register("hollowed") { HollowedEffect() }

    val FROZEN: RegistrySupplier<MobEffect> = EFFECTS.register("frozen") { FrozenEffect() }

    val ABSENT: RegistrySupplier<MobEffect> = EFFECTS.register("absent") { AbsentEffect() }

    val ANCHORED: RegistrySupplier<MobEffect> = EFFECTS.register("anchored") { AnchoredEffect() }

    val PALE_CROWN: RegistrySupplier<MobEffect> = EFFECTS.register("pale_crown") { PaleCrownEffect() }

    val MANTLE_OF_FLAME: RegistrySupplier<MobEffect> = EFFECTS.register("mantle_of_flame") { MantleOfFlameEffect() }

    val MANTLE_OF_FROST: RegistrySupplier<MobEffect> = EFFECTS.register("mantle_of_frost") { MantleOfFrostEffect() }

    val MANTLE_OF_LIGHTNING: RegistrySupplier<MobEffect> = EFFECTS.register("mantle_of_lightning") { MantleOfLightningEffect() }

    val MANTLE_OF_ABSORPTION: RegistrySupplier<MobEffect> = EFFECTS.register("mantle_of_absorption") { MantleOfAbsorptionEffect() }

    val MANTLE_OF_POISON: RegistrySupplier<MobEffect> = EFFECTS.register("mantle_of_poison") { MantleOfPoisonEffect() }

    val MANTLE_OF_VOID: RegistrySupplier<MobEffect> = EFFECTS.register("mantle_of_void") { MantleOfVoidEffect() }

    fun register() = EFFECTS.register()
}
