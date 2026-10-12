package org.shipwrights.enderkinesis.forge

import net.minecraft.core.Direction
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.common.capabilities.Capability
import net.minecraftforge.common.capabilities.ForgeCapabilities
import net.minecraftforge.common.capabilities.ICapabilityProvider
import net.minecraftforge.common.util.LazyOptional
import net.minecraftforge.event.AttachCapabilitiesEvent
import net.minecraftforge.fluids.FluidStack
import net.minecraftforge.fluids.capability.IFluidHandler
import net.minecraftforge.fluids.capability.IFluidHandlerItem
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.registry.EKFluids
import org.shipwrights.enderkinesis.registry.EKItems

/** An Ichaeric Ink Bottle holds one bottle of ink; tanks can drain it to a glass bottle or fill one. */
object IchaericInkStorageForge {

    // 1/3 bucket, the vanilla bottle, in whole millibuckets.
    private const val BOTTLE_MB = 333

    private val ID = EnderkinesisMod.id("ichaeric_ink_bottle")

    fun init() {
        MinecraftForge.EVENT_BUS.addGenericListener(ItemStack::class.java) { event: AttachCapabilitiesEvent<ItemStack> ->
            val stack = event.`object`
            // `isPresent`: a different mod can make a stack before the item registry event.
            val ink = EKItems.ICHAERIC_INK_BOTTLE
            if (stack.`is`(Items.GLASS_BOTTLE) || (ink.isPresent && stack.`is`(ink.get()))) {
                event.addCapability(ID, Provider(stack))
            }
        }
    }

    private class Provider(stack: ItemStack) : ICapabilityProvider {
        private val handler: LazyOptional<IFluidHandlerItem> = LazyOptional.of { BottleHandler(stack) }

        override fun <T> getCapability(cap: Capability<T>, side: Direction?): LazyOptional<T> =
            ForgeCapabilities.FLUID_HANDLER_ITEM.orEmpty(cap, handler)
    }

    private class BottleHandler(private var container: ItemStack) : IFluidHandlerItem {

        private val ink get() = EKFluids.ICHAERIC_INK.get()

        private fun isFull() = container.`is`(EKItems.ICHAERIC_INK_BOTTLE.get())

        override fun getContainer(): ItemStack = container

        override fun getTanks(): Int = 1

        override fun getFluidInTank(tank: Int): FluidStack = if (isFull()) FluidStack(ink, BOTTLE_MB) else FluidStack.EMPTY

        override fun getTankCapacity(tank: Int): Int = BOTTLE_MB

        override fun isFluidValid(tank: Int, stack: FluidStack): Boolean = stack.fluid.isSame(ink)

        // Forge item handlers act on a single item, as buckets do.
        override fun fill(resource: FluidStack, action: IFluidHandler.FluidAction): Int {
            if (container.count != 1 || isFull() || !isFluidValid(0, resource) || resource.amount < BOTTLE_MB) return 0
            if (action.execute()) container = ItemStack(EKItems.ICHAERIC_INK_BOTTLE.get())
            return BOTTLE_MB
        }

        override fun drain(resource: FluidStack, action: IFluidHandler.FluidAction): FluidStack =
            if (isFluidValid(0, resource)) drain(resource.amount, action) else FluidStack.EMPTY

        override fun drain(maxDrain: Int, action: IFluidHandler.FluidAction): FluidStack {
            if (container.count != 1 || !isFull() || maxDrain < BOTTLE_MB) return FluidStack.EMPTY
            if (action.execute()) container = ItemStack(Items.GLASS_BOTTLE)
            return FluidStack(ink, BOTTLE_MB)
        }
    }
}
