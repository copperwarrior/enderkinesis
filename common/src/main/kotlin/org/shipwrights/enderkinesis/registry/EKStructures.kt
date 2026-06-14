package org.shipwrights.enderkinesis.registry

import dev.architectury.registry.registries.DeferredRegister
import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.levelgen.structure.StructureType
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.worldgen.SselithRuinStructure

/**
 * Custom structure types. Data-pack `worldgen/structure/...` files reference
 * these via their `type` field — e.g. `"type": "enderkinesis:sselith_ruin"`.
 */
object EKStructures {
    val STRUCTURES: DeferredRegister<StructureType<*>> =
        DeferredRegister.create(EnderkinesisMod.MOD_ID, Registries.STRUCTURE_TYPE)

    /** [SselithRuinStructure] — JigsawStructure with two extra gates in
     *  `findGenerationPoint`: an ancient-city proximity check and a cave-
     *  floor placement check. */
    val SSELITH_RUIN: RegistrySupplier<StructureType<SselithRuinStructure>> =
        STRUCTURES.register("sselith_ruin") {
            StructureType<SselithRuinStructure> { SselithRuinStructure.CODEC }
        }

    fun register() = STRUCTURES.register()
}
