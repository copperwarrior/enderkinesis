package org.shipwrights.enderkinesis.registry

import dev.architectury.core.fluid.ArchitecturyFlowingFluid
import dev.architectury.core.fluid.ArchitecturyFluidAttributes
import dev.architectury.core.fluid.SimpleArchitecturyFluidAttributes
import dev.architectury.registry.registries.DeferredRegister
import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.material.FlowingFluid
import net.minecraft.world.level.material.Fluid
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.block.IchaericInkFlowingFluid

object EKFluids {
    val FLUIDS: DeferredRegister<Fluid> = DeferredRegister.create(EnderkinesisMod.MOD_ID, Registries.FLUID)

    // Slower than water (5) and faster than overworld lava (30); spreads as far as overworld lava and never refills.
    val ICHAERIC_INK_ATTRIBUTES: ArchitecturyFluidAttributes =
        SimpleArchitecturyFluidAttributes.ofSupplier({ ICHAERIC_INK_FLOWING }, { ICHAERIC_INK })
            .blockSupplier { EKBlocks.ICHAERIC_INK }
            .bucketItemSupplier { EKItems.ICHAERIC_INK_BUCKET }
            .sourceTexture(EnderkinesisMod.id("block/ichaeric_ink_still"))
            .flowingTexture(EnderkinesisMod.id("block/ichaeric_ink_flow"))
            // The renderer draws this on sides facing glass or leaves; left unset it is null and crashes chunk rebuilds.
            .overlayTexture(EnderkinesisMod.id("block/ichaeric_ink_overlay"))
            .tickDelay(15)
            .slopeFindDistance(2)
            .dropOff(2)
            .convertToSource(false)

    val ICHAERIC_INK: RegistrySupplier<FlowingFluid> =
        FLUIDS.register("ichaeric_ink") { ArchitecturyFlowingFluid.Source(ICHAERIC_INK_ATTRIBUTES) }

    val ICHAERIC_INK_FLOWING: RegistrySupplier<FlowingFluid> =
        FLUIDS.register("flowing_ichaeric_ink") { IchaericInkFlowingFluid(ICHAERIC_INK_ATTRIBUTES) }

    fun register() = FLUIDS.register()
}
