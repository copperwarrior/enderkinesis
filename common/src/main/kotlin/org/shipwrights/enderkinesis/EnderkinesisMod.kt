package org.shipwrights.enderkinesis

import dev.architectury.registry.ReloadListenerRegistry
import net.minecraft.server.packs.PackType
import net.minecraft.resources.ResourceLocation
import org.shipwrights.enderkinesis.compat.create.CreateCompat
import org.shipwrights.enderkinesis.dimension.DimensionFilter
import org.shipwrights.enderkinesis.dimension.SselithChatTeleport
import org.shipwrights.enderkinesis.dimension.SselithMadness
import org.shipwrights.enderkinesis.dimension.SselithMadnessChat
import org.shipwrights.enderkinesis.dimension.SselithMadnessTomeSummon
import org.shipwrights.enderkinesis.item.GuideToStratus
import org.shipwrights.enderkinesis.item.TomeOfBindingItem
import org.shipwrights.enderkinesis.item.TomeOfChainingItem
import org.shipwrights.enderkinesis.item.TomeOfCouplingItem
import org.shipwrights.enderkinesis.item.TomeOfElasticityItem
import org.shipwrights.enderkinesis.item.TomeOfSignalItem
import org.shipwrights.enderkinesis.item.TomeOfSpringItem
import org.shipwrights.enderkinesis.item.TomeOfDisintegrationItem
import org.shipwrights.enderkinesis.item.TomeOfHingingItem
import org.shipwrights.enderkinesis.item.TomeOfPullingItem
import org.shipwrights.enderkinesis.item.TomeOfPushingItem
import org.shipwrights.enderkinesis.item.TomeOfTransportationItem
import org.shipwrights.enderkinesis.item.TomeOfVacuumItem
import org.shipwrights.enderkinesis.item.TomeOfVentriloquismItem
import org.shipwrights.enderkinesis.item.WyllandTomeManager
import org.shipwrights.enderkinesis.item.WyllandTomeNetwork
import org.shipwrights.enderkinesis.dimension.SselithVoidWrap
import org.shipwrights.enderkinesis.dimension.YgannAbyssBlockSweeper
import org.shipwrights.enderkinesis.dimension.YgannAbyssGuard
import org.shipwrights.enderkinesis.dimension.YgannAbyssVoidFade
import org.shipwrights.enderkinesis.dimension.YgannMadnessBehavior
import org.shipwrights.enderkinesis.dimension.YgannMadnessOnExit
import org.shipwrights.enderkinesis.physics.CrepusculiteLatticeForceInducer
import org.shipwrights.enderkinesis.physics.WyllandTomeForceInducer
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.shipwrights.enderkinesis.registry.EKChunkGenerators
import org.shipwrights.enderkinesis.registry.EKEffects
import org.shipwrights.enderkinesis.registry.EKEntities
import org.shipwrights.enderkinesis.registry.EKFeatures
import org.shipwrights.enderkinesis.registry.EKParticles
import org.shipwrights.enderkinesis.registry.EKProcessors
import org.shipwrights.enderkinesis.registry.EKCreativeTab
import org.shipwrights.enderkinesis.registry.EKItems
import org.shipwrights.enderkinesis.registry.EKPoiTypes
import org.shipwrights.enderkinesis.registry.EKSounds
import org.valkyrienskies.mod.common.vsCore

object EnderkinesisMod {
    const val MOD_ID = "enderkinesis"

    fun id(path: String): ResourceLocation = ResourceLocation(MOD_ID, path)

    @JvmStatic
    fun init() {
        // Order matters: blocks before items (block items reference blocks); sounds before
        // items (RecordItem's constructor resolves the SoundEvent eagerly); tab last.
        // GameRules must register before any save loads so existing worlds pick the default.
        org.shipwrights.enderkinesis.registry.EKGameRules.init()

        EKBlocks.register()
        EKBlockEntities.register()
        EKSounds.register()
        // Instruments resolve their sound-event holder at construction time, so must come after sounds.
        org.shipwrights.enderkinesis.registry.EKInstruments.register()
        EKItems.register()
        EKCreativeTab.register()
        // Trades depend on EKItems being registered (the scroll is the
        // trade output); vanilla TRADES map is mutated directly so this
        // takes effect for all subsequent villager refreshes.
        org.shipwrights.enderkinesis.registry.EKVillagerTrades.init()
        EKFeatures.register()
        EKParticles.register()
        EKEffects.register()
        EKProcessors.register()
        org.shipwrights.enderkinesis.registry.EKStructures.register()
        org.shipwrights.enderkinesis.registry.EKStructurePlacements.register()
        EKChunkGenerators.register()
        EKEntities.register()
        EKPoiTypes.register()

        org.shipwrights.enderkinesis.block.WohlonnogondoniaCatastrophe.registerLifecycleHooks()

        // Transient: the lattice re-asserts the attachment while active, so it never needs persisting.
        vsCore.registerAttachment(
            vsCore.newAttachmentRegistrationBuilder(CrepusculiteLatticeForceInducer::class.java)
                .useTransientSerializer()
                .build()
        )
        // Transient: only live while the player is actively holding the tome.
        vsCore.registerAttachment(
            vsCore.newAttachmentRegistrationBuilder(WyllandTomeForceInducer::class.java)
                .useTransientSerializer()
                .build()
        )
        // Transient: re-touched by void hooks every game tick while a ship is in their cone.
        vsCore.registerAttachment(
            vsCore.newAttachmentRegistrationBuilder(org.shipwrights.enderkinesis.physics.VoidGravityController::class.java)
                .useTransientSerializer()
                .build()
        )

        CreateCompat.init()

        ReloadListenerRegistry.register(PackType.SERVER_DATA, DimensionFilter, id("dimension_filter"))

        org.shipwrights.enderkinesis.blockentity.CrystalExplosiveCollisionRouter.register()
        org.shipwrights.enderkinesis.block.HeartOfTheWildManager.init()
        org.shipwrights.enderkinesis.block.WohlonnogondoniaPortalManager.init()
        org.shipwrights.enderkinesis.block.WohlonnogondoniaSpreader.init()
        org.shipwrights.enderkinesis.command.WohlonDebugCommands.init()
        org.shipwrights.enderkinesis.block.WohlonnogondoniaTreeGrower.init()
        org.shipwrights.enderkinesis.block.WohlonnogondoniaWorldRootGrower.init()

        YgannAbyssGuard.init()
        YgannAbyssBlockSweeper.init()
        YgannAbyssVoidFade.init()
        YgannMadnessOnExit.init()
        YgannMadnessBehavior.init()

        SselithVoidWrap.init()
        SselithMadness.init()
        SselithMadnessTomeSummon.init()
        SselithChatTeleport.init()
        // Must register after SselithChatTeleport so the teleport phrase is detected
        // before this suppresses and re-broadcasts the corrupted line.
        SselithMadnessChat.init()

        WyllandTomeManager.init()
        WyllandTomeNetwork.init()

        org.shipwrights.enderkinesis.item.CrepusculiteCharmManager.init()

        GuideToStratus.init()

        org.shipwrights.enderkinesis.item.ScrollOfUnravelling.init()

        TomeOfSignalItem.registerBeamPalette()
        TomeOfBindingItem.registerBeamPalette()
        TomeOfChainingItem.registerBeamPalette()
        TomeOfElasticityItem.registerBeamPalette()
        TomeOfTransportationItem.registerBeamPalette()
        TomeOfVacuumItem.registerBeamPalette()
        TomeOfPullingItem.registerBeamPalette()
        TomeOfPushingItem.registerBeamPalette()
        TomeOfHingingItem.registerBeamPalette()
        TomeOfDisintegrationItem.registerBeamPalette()
        TomeOfVentriloquismItem.registerBeamPalette()
        TomeOfSpringItem.registerBeamPalette()
        TomeOfCouplingItem.registerBeamPalette()
    }
}
