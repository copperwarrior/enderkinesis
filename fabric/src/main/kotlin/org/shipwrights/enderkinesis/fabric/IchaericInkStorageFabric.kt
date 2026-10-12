package org.shipwrights.enderkinesis.fabric

import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant
import net.fabricmc.fabric.api.transfer.v1.fluid.base.EmptyItemFluidStorage
import net.fabricmc.fabric.api.transfer.v1.fluid.base.FullItemFluidStorage
import net.minecraft.world.item.Items
import org.shipwrights.enderkinesis.registry.EKFluids
import org.shipwrights.enderkinesis.registry.EKItems

/** An Ichaeric Ink Bottle holds one bottle (1/3 bucket) of ink; tanks can drain it to a glass bottle or fill one. */
object IchaericInkStorageFabric {
    fun init() {
        FluidStorage.combinedItemApiProvider(EKItems.ICHAERIC_INK_BOTTLE.get()).register { context ->
            FullItemFluidStorage(
                context, Items.GLASS_BOTTLE, FluidVariant.of(EKFluids.ICHAERIC_INK.get()), FluidConstants.BOTTLE,
            )
        }
        FluidStorage.combinedItemApiProvider(Items.GLASS_BOTTLE).register { context ->
            EmptyItemFluidStorage(
                context, EKItems.ICHAERIC_INK_BOTTLE.get(), EKFluids.ICHAERIC_INK.get(), FluidConstants.BOTTLE,
            )
        }
    }
}
