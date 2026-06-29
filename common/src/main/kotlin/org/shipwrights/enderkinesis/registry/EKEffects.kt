package org.shipwrights.enderkinesis.registry

import dev.architectury.registry.registries.DeferredRegister
import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.core.registries.Registries
import net.minecraft.world.effect.MobEffect
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.effect.AmbrosialCravingEffect
import org.shipwrights.enderkinesis.effect.CrepuscularFloatationEffect
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

    fun register() = EFFECTS.register()
}
