package org.shipwrights.enderkinesis.registry

import dev.architectury.registry.registries.DeferredRegister
import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.configurations.GeodeConfiguration
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.worldgen.CrepusculiteGeodeFeature

object EKFeatures {
    val FEATURES: DeferredRegister<Feature<*>> =
        DeferredRegister.create(EnderkinesisMod.MOD_ID, Registries.FEATURE)

    /** Referenced by `data/enderkinesis/worldgen/configured_feature/crepusculite_geode.json`. */
    val CREPUSCULITE_GEODE: RegistrySupplier<Feature<*>> =
        FEATURES.register("crepusculite_geode") { CrepusculiteGeodeFeature(GeodeConfiguration.CODEC) }

    fun register() = FEATURES.register()
}
