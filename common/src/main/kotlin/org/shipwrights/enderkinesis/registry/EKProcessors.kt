package org.shipwrights.enderkinesis.registry

import dev.architectury.registry.registries.DeferredRegister
import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.worldgen.HutWitchProcessor
import org.shipwrights.enderkinesis.worldgen.RoamingEndShipShipifyProcessor
import org.shipwrights.enderkinesis.worldgen.SeedLecternAlmanacProcessor
import org.shipwrights.enderkinesis.worldgen.SselithRuinLecternProcessor
import org.shipwrights.enderkinesis.worldgen.YgannAnchorShipifyProcessor

/**
 * Custom structure processors. Each entry registers a [StructureProcessorType] so the processor
 * can be referenced from a data-pack processor list / inline `processors` field.
 */
object EKProcessors {
    val PROCESSORS: DeferredRegister<StructureProcessorType<*>> =
        DeferredRegister.create(EnderkinesisMod.MOD_ID, Registries.STRUCTURE_PROCESSOR)

    /** Roaming End Ship → VS2 ship + random yaw. See [RoamingEndShipShipifyProcessor]. */
    val SHIPIFY: RegistrySupplier<StructureProcessorType<RoamingEndShipShipifyProcessor>> =
        PROCESSORS.register("shipify_roaming_end_ship") {
            StructureProcessorType<RoamingEndShipShipifyProcessor> { RoamingEndShipShipifyProcessor.CODEC }
        }

    /** Stamps an Almanac of Everywhere (pre-loaded with the Ygann's Abyss entry) onto every
     *  lectern the structure places. See [SeedLecternAlmanacProcessor]. */
    val SEED_LECTERN_ALMANAC: RegistrySupplier<StructureProcessorType<SeedLecternAlmanacProcessor>> =
        PROCESSORS.register("seed_lectern_almanac") {
            StructureProcessorType<SeedLecternAlmanacProcessor> { SeedLecternAlmanacProcessor.CODEC }
        }

    /** Ygann Anchor → VS2 ship + random yaw + small (±5°) pitch + small (±5°) roll. See
     *  [YgannAnchorShipifyProcessor]. */
    val YGANN_ANCHOR_SHIPIFY: RegistrySupplier<StructureProcessorType<YgannAnchorShipifyProcessor>> =
        PROCESSORS.register("shipify_ygann_anchor") {
            StructureProcessorType<YgannAnchorShipifyProcessor> { YgannAnchorShipifyProcessor.CODEC }
        }

    /** Drops a signed Sselith-translated `passage_into_sselith` book on
     *  every lectern the Sselith Ruin places, attributed to a random
     *  Sselith research-circle member. See [SselithRuinLecternProcessor]. */
    val SSELITH_RUIN_LECTERN: RegistrySupplier<StructureProcessorType<SselithRuinLecternProcessor>> =
        PROCESSORS.register("sselith_ruin_lectern") {
            StructureProcessorType<SselithRuinLecternProcessor> { SselithRuinLecternProcessor.CODEC }
        }

    val HUT_WITCH: RegistrySupplier<StructureProcessorType<HutWitchProcessor>> =
        PROCESSORS.register("hut_witch") {
            StructureProcessorType<HutWitchProcessor> { HutWitchProcessor.CODEC }
        }

    fun register() = PROCESSORS.register()
}
