package org.shipwrights.enderkinesis.fabric

import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant
import net.fabricmc.fabric.api.transfer.v1.storage.StoragePreconditions
import net.fabricmc.fabric.api.transfer.v1.storage.base.InsertionOnlyStorage
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant
import org.shipwrights.enderkinesis.blockentity.OarscribeBlockEntity
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import org.shipwrights.enderkinesis.registry.EKFluids

/** Pipes can fill an Oarscribe with Ichaeric ink. Common code keeps the amount in droplets, as Fabric does. */
object OarscribeInkStorageFabric {
    fun init() {
        FluidStorage.SIDED.registerForBlockEntity({ oarscribe, _ -> InkTank(oarscribe) }, EKBlockEntities.OARSCRIBE.get())
    }

    private class InkTank(private val oarscribe: OarscribeBlockEntity) :
        SnapshotParticipant<Long>(), InsertionOnlyStorage<FluidVariant> {

        override fun insert(resource: FluidVariant, maxAmount: Long, transaction: TransactionContext): Long {
            StoragePreconditions.notBlankNotNegative(resource, maxAmount)
            if (!resource.isOf(EKFluids.ICHAERIC_INK.get())) return 0
            val accepted = minOf(maxAmount, oarscribe.inkSpace)
            if (accepted > 0) {
                updateSnapshots(transaction)
                oarscribe.restoreInk(oarscribe.ink + accepted)
            }
            return accepted
        }

        override fun createSnapshot(): Long = oarscribe.ink

        override fun readSnapshot(snapshot: Long) = oarscribe.restoreInk(snapshot)

        override fun onFinalCommit() = oarscribe.setChanged()
    }
}
