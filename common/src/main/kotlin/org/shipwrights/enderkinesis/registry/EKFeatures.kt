package org.shipwrights.enderkinesis.registry

import dev.architectury.registry.registries.DeferredRegister
import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.configurations.GeodeConfiguration
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.worldgen.CrepusculiteGeodeFeature
import org.shipwrights.enderkinesis.worldgen.HugeWohlonMushroomFeature
import org.shipwrights.enderkinesis.worldgen.WohlonDeadwoodFeature
import org.shipwrights.enderkinesis.worldgen.WohlonHugeFungiFeature
import org.shipwrights.enderkinesis.worldgen.WohlonTrunkBaseFeature
import org.shipwrights.enderkinesis.worldgen.WohlonUnderstoryFeature

object EKFeatures {
    val FEATURES: DeferredRegister<Feature<*>> =
        DeferredRegister.create(EnderkinesisMod.MOD_ID, Registries.FEATURE)

    /** Referenced by `data/enderkinesis/worldgen/configured_feature/crepusculite_geode.json`. */
    val CREPUSCULITE_GEODE: RegistrySupplier<Feature<*>> =
        FEATURES.register("crepusculite_geode") { CrepusculiteGeodeFeature(GeodeConfiguration.CODEC) }

    val WOHLON_UNDERSTORY: RegistrySupplier<Feature<*>> =
        FEATURES.register("wohlon_understory") { WohlonUnderstoryFeature(NoneFeatureConfiguration.CODEC) }

    val WOHLON_TRUNK_BASES: RegistrySupplier<Feature<*>> =
        FEATURES.register("wohlon_trunk_bases") { WohlonTrunkBaseFeature(NoneFeatureConfiguration.CODEC) }

    val WOHLON_DEADWOOD: RegistrySupplier<Feature<*>> =
        FEATURES.register("wohlon_deadwood") { WohlonDeadwoodFeature(NoneFeatureConfiguration.CODEC) }

    val HUGE_WOHLON_LUMPY_MUSHROOM: RegistrySupplier<Feature<*>> =
        FEATURES.register("huge_wohlon_lumpy_mushroom") {
            HugeWohlonMushroomFeature(NoneFeatureConfiguration.CODEC, HugeWohlonMushroomFeature.Shape.LUMPY)
        }

    val HUGE_WOHLON_CUP_MUSHROOM: RegistrySupplier<Feature<*>> =
        FEATURES.register("huge_wohlon_cup_mushroom") {
            HugeWohlonMushroomFeature(NoneFeatureConfiguration.CODEC, HugeWohlonMushroomFeature.Shape.CUP)
        }

    val WOHLON_HUGE_FUNGI: RegistrySupplier<Feature<*>> =
        FEATURES.register("wohlon_huge_fungi") { WohlonHugeFungiFeature(NoneFeatureConfiguration.CODEC) }

    fun register() = FEATURES.register()
}
