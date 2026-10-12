package org.shipwrights.enderkinesis

import dev.architectury.registry.ReloadListenerRegistry
import net.minecraft.server.packs.PackType
import net.minecraft.resources.ResourceLocation
import org.shipwrights.enderkinesis.compat.create.CreateCompat
import org.shipwrights.enderkinesis.dimension.DimensionFilter
import org.shipwrights.enderkinesis.dimension.SselithChatTeleport
import org.shipwrights.enderkinesis.dimension.SselithMadness
import org.shipwrights.enderkinesis.dimension.SselithRepertoryChunkGenerator
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

        // Fluids before blocks: on Fabric, ArchitecturyLiquidBlock reads its fluid in the constructor.
        org.shipwrights.enderkinesis.registry.EKFluids.register()
        EKBlocks.register()
        EKBlockEntities.register()
        // SSELITH_LECTERN subclasses LecternBlock and shares vanilla's
        // LecternBlockEntity type. The BE type's `validBlocks` is an immutable
        // set built at vanilla init time, so we rebuild it with our block
        // included — otherwise BlockEntityType.isValid rejects the BE on chunk
        // reload and the book on every Sselith lectern silently disappears.
        // `listen`, not `get`: on Forge the block does not exist until the registry event.
        EKBlocks.SSELITH_LECTERN.listen(::augmentLecternValidBlocks)
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
        org.shipwrights.enderkinesis.registry.EKLootFunctions.register()
        org.shipwrights.enderkinesis.registry.EKStructures.register()
        org.shipwrights.enderkinesis.registry.EKStructurePlacements.register()
        EKChunkGenerators.register()
        EKEntities.register()
        org.shipwrights.enderkinesis.dimension.WohlonWorldSeed.init()
        EKPoiTypes.register()

        org.shipwrights.enderkinesis.block.WohlonnogondoniaCatastrophe.registerLifecycleHooks()
        org.shipwrights.enderkinesis.block.WogorBudTargetData.init()

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
        // Saved with the ship (default serializer), so a stale hold restores after a crash.
        vsCore.registerAttachment(org.shipwrights.enderkinesis.physics.ShipGravityHold::class.java)
        // Saved: the multiplier must survive a reload. VS2 throws on setAttachment for a class that is not registered.
        vsCore.registerAttachment(org.shipwrights.enderkinesis.item.DensityAttachment::class.java)
        // Transient: BindingRootsMerger overwrites the force each server tick from the live
        // pair set; stale state across reload is harmless because the merger re-scans anyway.
        vsCore.registerAttachment(
            vsCore.newAttachmentRegistrationBuilder(org.shipwrights.enderkinesis.physics.BindingRootsPullAttachment::class.java)
                .useTransientSerializer()
                .build()
        )
        // Transient: RodLevitationAttachment lives for the 4-second lift window OR a
        // single phys tick of nudge. Either way it doesn't need to survive a reload.
        vsCore.registerAttachment(
            vsCore.newAttachmentRegistrationBuilder(org.shipwrights.enderkinesis.physics.RodLevitationAttachment::class.java)
                .useTransientSerializer()
                .build()
        )
        // Transient: recoil impulses are queued at fire time and consumed within a tick.
        vsCore.registerAttachment(
            vsCore.newAttachmentRegistrationBuilder(org.shipwrights.enderkinesis.physics.LauncherRecoilAttachment::class.java)
                .useTransientSerializer()
                .build()
        )
        // Transient: the constriliar re-targets its back-ship controller every game tick.
        vsCore.registerAttachment(
            vsCore.newAttachmentRegistrationBuilder(org.shipwrights.enderkinesis.physics.ConstriliarShipController::class.java)
                .useTransientSerializer()
                .build()
        )
        // Transient: rebuilt from the Moth Cloth block registry on world load;
        // no state to persist beyond the shipId which is stored in the ctor arg.
        vsCore.registerAttachment(
            vsCore.newAttachmentRegistrationBuilder(org.shipwrights.enderkinesis.physics.MothClothForceInducer::class.java)
                .useTransientSerializer()
                .build()
        )
        // NOTE: no custom BlockStateInfoProvider for the platform — one existed briefly
        // and its generic `solid` type outranked the datapack resolver's per-state
        // SHAPE, voxelizing the plate as a full cube. forceSolidOn() + the context-split
        // outline + the vs_mass entry drive the whole chain through the resolver now.

        // Orb of Potential gravity cancel. Non-ship VS Bodies can't host
        // a `ShipPhysicsListener`, so the per-tick counter-force is
        // applied through the global `vsApi.physTickEvent` against the
        // per-dimension orb registry. The registry itself is persisted
        // via [OrbWorldData] and rehydrated into memory each time a
        // server level loads.
        org.shipwrights.enderkinesis.body.OrbGravityCanceller.register()
        dev.architectury.event.events.common.LifecycleEvent.SERVER_LEVEL_LOAD.register { level ->
            org.shipwrights.enderkinesis.body.OrbBodyRegistry.rehydrate(level)
        }
        // Drain Sselith deferred-spawn queue (catalogers + paintings
        // enqueued during chunk gen) at a small per-tick budget so
        // the server thread doesn't spike when a batch of chunks
        // reaches SPAWN status the same tick.
        org.shipwrights.enderkinesis.dimension.SselithPendingSpawns.init()
        // Send the orb table to each player when they first see a
        // dimension's chunks (either on initial login or after a
        // dimension change). LevelChangedEvent fires for both.
        dev.architectury.event.events.common.PlayerEvent.CHANGE_DIMENSION.register { player, _, _ ->
            org.shipwrights.enderkinesis.body.OrbBodyNetwork.sendFullList(player, player.serverLevel())
        }
        dev.architectury.event.events.common.PlayerEvent.PLAYER_JOIN.register { player ->
            org.shipwrights.enderkinesis.body.OrbBodyNetwork.sendFullList(player, player.serverLevel())
        }
        // A respawn into a different dimension is not a CHANGE_DIMENSION event.
        dev.architectury.event.events.common.PlayerEvent.PLAYER_RESPAWN.register { player, _ ->
            org.shipwrights.enderkinesis.body.OrbBodyNetwork.sendFullList(player, player.serverLevel())
        }

        CreateCompat.init()

        ReloadListenerRegistry.register(PackType.SERVER_DATA, DimensionFilter, id("dimension_filter"))
        ReloadListenerRegistry.register(PackType.SERVER_DATA, org.shipwrights.enderkinesis.item.Scraps, id("scraps"))
        ReloadListenerRegistry.register(
            PackType.SERVER_DATA, org.shipwrights.enderkinesis.block.InegricRetortRates, id("inegric_retort"),
        )

        org.shipwrights.enderkinesis.blockentity.CrystalExplosiveCollisionRouter.register()
        org.shipwrights.enderkinesis.block.HeartOfTheWildManager.init()
        org.shipwrights.enderkinesis.block.WohlonnogondoniaPortalManager.init()
        org.shipwrights.enderkinesis.dimension.SselithMothSpawner.init()
        org.shipwrights.enderkinesis.dimension.MothClothShipRegistry.init()
        org.shipwrights.enderkinesis.dimension.SselithBlockTracker.init()
        org.shipwrights.enderkinesis.remnant.IrreverantRemnantManager.init()
        org.shipwrights.enderkinesis.block.WohlonnogondoniaSpreader.init()
        org.shipwrights.enderkinesis.block.BindingRootsMerger.init()
        org.shipwrights.enderkinesis.command.WohlonDebugCommands.init()
        org.shipwrights.enderkinesis.command.AegisDebugCommands.init()
        org.shipwrights.enderkinesis.block.WohlonnogondoniaTreeGrower.init()
        org.shipwrights.enderkinesis.block.WohlonnogondoniaWorldRootGrower.init()
        org.shipwrights.enderkinesis.entity.WikLakConstruction.init()
        org.shipwrights.enderkinesis.util.ShipMoves.init()
        org.shipwrights.enderkinesis.worldgen.YgannAnchorShipifyProcessor.init()
        org.shipwrights.enderkinesis.worldgen.RoamingEndShipShipifyProcessor.init()
        org.shipwrights.enderkinesis.util.ServerTicks.init()
        org.shipwrights.enderkinesis.entity.WikLakDeathRedirect.init()
        org.shipwrights.enderkinesis.entity.WohlonAnimalConversion.init()
        org.shipwrights.enderkinesis.item.GamylanCageManager.init()
        // Wand of Ga'Mylan capture pipeline.

        YgannAbyssGuard.init()
        YgannAbyssBlockSweeper.init()
        YgannAbyssVoidFade.init()
        YgannMadnessOnExit.init()
        YgannMadnessBehavior.init()

        SselithVoidWrap.init()
        org.shipwrights.enderkinesis.dimension.SselithParallel.init()
        org.shipwrights.enderkinesis.command.RepertoryDebugCommands.init()
        org.shipwrights.enderkinesis.block.ScribesDeskNetwork.init()
        SselithMadness.init()
        SselithRepertoryChunkGenerator.init()
        SselithMadnessTomeSummon.init()
        SselithChatTeleport.init()
        org.shipwrights.enderkinesis.sselith.SselithEclipse.init()
        org.shipwrights.enderkinesis.command.SselithEclipseDebugCommands.init()
        org.shipwrights.enderkinesis.command.SselithFogDebugCommands.init()

        // Sureibjin drown-to-wake exit.
        org.shipwrights.enderkinesis.dimension.SureibjinExit.init()

        // Sureibjin block-uninteractable enforcement — the dream coast is
        // walk-only; break/place/use/left-click are all cancelled.
        org.shipwrights.enderkinesis.dimension.SureibjinBlockGuard.init()
        // Must register after SselithChatTeleport so the teleport phrase is detected
        // before this suppresses and re-broadcasts the corrupted line.
        SselithMadnessChat.init()

        WyllandTomeManager.init()
        WyllandTomeNetwork.init()
        org.shipwrights.enderkinesis.item.DensityNetwork.init()
        org.shipwrights.enderkinesis.item.DensityManager.init()
        org.shipwrights.enderkinesis.item.ScalesNetwork.init()
        org.shipwrights.enderkinesis.item.RecitalNetwork.init()
        org.shipwrights.enderkinesis.item.CommandNetwork.init()
        org.shipwrights.enderkinesis.item.VanillaCommandables.init()
        org.shipwrights.enderkinesis.dimension.YgannBarnacleSpreader.init()
        org.shipwrights.enderkinesis.dimension.YgannDiscoveries.init()
        org.shipwrights.enderkinesis.item.StaffOfCommandItem.initEvents()
        org.shipwrights.enderkinesis.item.ShipCloakingTracker.init()
        org.shipwrights.enderkinesis.item.SunderingManager.init()
        registerSessionCleanup()
        org.shipwrights.enderkinesis.item.ConcealmentNetwork.init()
        org.shipwrights.enderkinesis.item.RodOfLevitationManager.init()
        org.shipwrights.enderkinesis.item.ScryingClientNetwork.init()
        org.shipwrights.enderkinesis.scrying.ScryingSessionManager.init()

        org.shipwrights.enderkinesis.item.CrepusculiteCharmManager.init()

        GuideToStratus.init()
        org.shipwrights.enderkinesis.block.AncriteEye.init()

        org.shipwrights.enderkinesis.item.ScrollOfUnravelling.init()
        org.shipwrights.enderkinesis.util.SculkSpread.init()

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

    /** Rebuild [net.minecraft.world.level.block.entity.BlockEntityType.LECTERN]'s
     *  immutable `validBlocks` set to include [EKBlocks.SSELITH_LECTERN] so the
     *  Sselith Lectern can carry the vanilla {@code LecternBlockEntity} across
     *  save/load. Vanilla builds the set once via
     *  {@code BlockEntityType.Builder.build} and never mutates it again; we
     *  reach in through
     *  [org.shipwrights.enderkinesis.mixin.BlockEntityTypeValidBlocksAccessor]
     *  (a `@Mutable @Accessor` mixin) to swap the field for a new set
     *  containing the original entries plus ours. */
    private fun augmentLecternValidBlocks(lectern: net.minecraft.world.level.block.Block) {
        val lecternType = net.minecraft.world.level.block.entity.BlockEntityType.LECTERN
        val accessor = lecternType as org.shipwrights.enderkinesis.mixin.BlockEntityTypeValidBlocksAccessor
        val merged: MutableSet<net.minecraft.world.level.block.Block> = HashSet(accessor.validBlocks)
        merged.add(lectern)
        accessor.validBlocks = java.util.Collections.unmodifiableSet(merged)
    }

    /** In singleplayer the JVM lives across worlds, and VS2 ship ids start again in each save. */
    private fun registerSessionCleanup() {
        dev.architectury.event.events.common.LifecycleEvent.SERVER_STOPPING.register { server ->
            org.shipwrights.enderkinesis.blockentity.EnderAstrolabeBlockEntity.restoreFrozenRiders(server)
        }
        dev.architectury.event.events.common.LifecycleEvent.SERVER_STOPPED.register { _ ->
            org.shipwrights.enderkinesis.item.GamylanCageManager.clearSession()
            org.shipwrights.enderkinesis.util.SculkSpread.clearSession()
            org.shipwrights.enderkinesis.blockentity.CrystalExplosiveCollisionRouter.clearSession()
            org.shipwrights.enderkinesis.blockentity.EyeroscopeBlockEntity.clearSession()
            org.shipwrights.enderkinesis.item.RodOfLevitationManager.clearSession()
            org.shipwrights.enderkinesis.item.CrepusculiteCharmManager.clearSession()
            org.shipwrights.enderkinesis.item.ScalesManager.clearSession()
            org.shipwrights.enderkinesis.dimension.MothClothShipRegistry.clearSession()
            org.shipwrights.enderkinesis.item.VentriloquismNetwork.clearSession()
            org.shipwrights.enderkinesis.command.AegisDebugCommands.clearSession()
            org.shipwrights.enderkinesis.physics.LatticeRegistry.dropLevels(clientSide = false)
            org.shipwrights.enderkinesis.blockentity.OrbOfLinkingClientRegistry.dropLevels(clientSide = false)
        }
    }
}
