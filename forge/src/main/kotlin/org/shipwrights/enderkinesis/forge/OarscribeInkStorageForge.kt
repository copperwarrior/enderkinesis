package org.shipwrights.enderkinesis.forge

import net.minecraft.core.Direction
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.common.capabilities.Capability
import net.minecraftforge.common.capabilities.ForgeCapabilities
import net.minecraftforge.common.capabilities.ICapabilityProvider
import net.minecraftforge.common.util.LazyOptional
import net.minecraftforge.event.AttachCapabilitiesEvent
import net.minecraftforge.fluids.FluidStack
import net.minecraftforge.fluids.capability.IFluidHandler
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.blockentity.OarscribeBlockEntity
import org.shipwrights.enderkinesis.registry.EKFluids

/** Pipes can fill an Oarscribe with Ichaeric ink. Common code keeps the amount in droplets, 81 to a millibucket. */
object OarscribeInkStorageForge {

    private const val DROPLETS_PER_MB = OarscribeBlockEntity.INK_PER_BUCKET / 1000

    private val ID = EnderkinesisMod.id("oarscribe_ink")

    fun init() {
        MinecraftForge.EVENT_BUS.addGenericListener(BlockEntity::class.java) { event: AttachCapabilitiesEvent<BlockEntity> ->
            val oarscribe = event.`object` as? OarscribeBlockEntity ?: return@addGenericListener
            val handler = LazyOptional.of<IFluidHandler> { InkTank(oarscribe) }
            event.addCapability(ID, object : ICapabilityProvider {
                override fun <T> getCapability(cap: Capability<T>, side: Direction?): LazyOptional<T> =
                    ForgeCapabilities.FLUID_HANDLER.orEmpty(cap, handler)
            })
            event.addListener(handler::invalidate)
        }
    }

    private class InkTank(private val oarscribe: OarscribeBlockEntity) : IFluidHandler {

        private val ink get() = EKFluids.ICHAERIC_INK.get()

        override fun getTanks(): Int = 1

        override fun getFluidInTank(tank: Int): FluidStack {
            val mb = (oarscribe.ink / DROPLETS_PER_MB).toInt()
            return if (mb > 0) FluidStack(ink, mb) else FluidStack.EMPTY
        }

        override fun getTankCapacity(tank: Int): Int = (OarscribeBlockEntity.INK_CAPACITY / DROPLETS_PER_MB).toInt()

        override fun isFluidValid(tank: Int, stack: FluidStack): Boolean = stack.fluid.isSame(ink)

        override fun fill(resource: FluidStack, action: IFluidHandler.FluidAction): Int {
            if (resource.isEmpty || !isFluidValid(0, resource)) return 0
            val mb = minOf(resource.amount.toLong(), oarscribe.inkSpace / DROPLETS_PER_MB)
            if (mb > 0 && action.execute()) oarscribe.fillInk(mb * DROPLETS_PER_MB)
            return mb.toInt()
        }

        override fun drain(resource: FluidStack, action: IFluidHandler.FluidAction): FluidStack = FluidStack.EMPTY

        override fun drain(maxDrain: Int, action: IFluidHandler.FluidAction): FluidStack = FluidStack.EMPTY
    }
}
