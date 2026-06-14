package org.shipwrights.enderkinesis.registry

import dev.architectury.registry.registries.DeferredRegister
import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacementType
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.worldgen.SselithRuinPlacement

/**
 * Custom structure placement types. Datapack `worldgen/structure_set/...`
 * files reference these via their `placement.type` field — e.g.
 * `"type": "enderkinesis:sselith_ruin"`.
 */
object EKStructurePlacements {
    val PLACEMENTS: DeferredRegister<StructurePlacementType<*>> =
        DeferredRegister.create(EnderkinesisMod.MOD_ID, Registries.STRUCTURE_PLACEMENT)

    /** [SselithRuinPlacement] — pairs every ancient city with exactly one
     *  ruin candidate at `acChunk + (6, 6)`. */
    val SSELITH_RUIN: RegistrySupplier<StructurePlacementType<SselithRuinPlacement>> =
        PLACEMENTS.register("sselith_ruin") {
            StructurePlacementType<SselithRuinPlacement> { SselithRuinPlacement.CODEC }
        }

    fun register() = PLACEMENTS.register()
}
