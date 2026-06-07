package org.shipwrights.enderkinesis

import dev.architectury.registry.ReloadListenerRegistry
import net.minecraft.server.packs.PackType
import net.minecraft.resources.ResourceLocation
import org.shipwrights.enderkinesis.compat.create.CreateCompat
import org.shipwrights.enderkinesis.dimension.DimensionFilter
import org.shipwrights.enderkinesis.dimension.SselithChatTeleport
import org.shipwrights.enderkinesis.dimension.SselithMadness
import org.shipwrights.enderkinesis.dimension.SselithMadnessChat
import org.shipwrights.enderkinesis.item.GuideToStratus
import org.shipwrights.enderkinesis.item.TomeOfBindingItem
import org.shipwrights.enderkinesis.item.TomeOfChainingItem
import org.shipwrights.enderkinesis.item.TomeOfElasticityItem
import org.shipwrights.enderkinesis.item.TomeOfSignalItem
import org.shipwrights.enderkinesis.item.TomeOfDisintegrationItem
import org.shipwrights.enderkinesis.item.TomeOfHingingItem
import org.shipwrights.enderkinesis.item.TomeOfPullingItem
import org.shipwrights.enderkinesis.item.TomeOfPushingItem
import org.shipwrights.enderkinesis.item.TomeOfTransportationItem
import org.shipwrights.enderkinesis.item.TomeOfVacuumItem
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
        EKItems.register()
        EKCreativeTab.register()
        EKFeatures.register()
        EKParticles.register()
        EKEffects.register()
        EKProcessors.register()
        EKChunkGenerators.register()
        EKEntities.register()
        EKPoiTypes.register()

        // Transient: the lattice re-asserts the attachment while active, so it never needs persisting.
        vsCore.registerAttachment(
            vsCore.newAttachmentRegistrationBuilder(CrepusculiteLatticeForceInducer::class.java)
                .useTransientSerializer()
                .build()
        )
        // Wylland Tome — physics-side spring/damper on the grabbed ship.
        // Transient: the player has to be actively holding the tome for
        // the attachment to do anything; no reason to persist it.
        vsCore.registerAttachment(
            vsCore.newAttachmentRegistrationBuilder(WyllandTomeForceInducer::class.java)
                .useTransientSerializer()
                .build()
        )

        // Optional Create-mod interop. Registers a MovementBehaviour for the Void Harness so
        // the block keeps pulling nearby VS2 ships while it's part of a Create contraption.
        // No-op when Create isn't installed (the call is gated on Platform.isModLoaded).
        CreateCompat.init()

        // Data-driven dimension filter used by the Almanac / Ender Astrolabe.
        ReloadListenerRegistry.register(PackType.SERVER_DATA, DimensionFilter, id("dimension_filter"))

        // Heart of the Wild — global growth queue + break-listener.
        // Drives plant growth in Wohlonnogondonia (constant baseline,
        // every Heart in the dimension is an extra consumer) and on
        // any ship that hosts at least one Heart.
        org.shipwrights.enderkinesis.block.HeartOfTheWildManager.init()

        // Wohlonnogondonia portals — permanent anchor points
        // registered by the heart-candle ritual. Persistent
        // SavedData per dimension; the manager handles particle
        // emission and entity teleport every server tick.
        org.shipwrights.enderkinesis.block.WohlonnogondoniaPortalManager.init()

        // Wohlonnogondonia biome spread + per-tile conversion
        // via the converts_to_* tags. 3 biome cells spread per
        // minute in non-Wohlon dimensions; 1 tainted chunk
        // force-loaded per in-game hour for offline processing;
        // tagged blocks in Wohlon biome get random-ticked into
        // their target form.
        org.shipwrights.enderkinesis.block.WohlonnogondoniaSpreader.init()

        // Debug commands — `/wohlon setcell` paints a single
        // biome cell at the command source's position with
        // proper taint tracking, since vanilla `/fillbiome`
        // bypasses our spreader bookkeeping.
        org.shipwrights.enderkinesis.command.WohlonDebugCommands.init()

        // Heart-candle tree grower — when the Wohlon ritual
        // completes, fits a ground plane around the candle,
        // builds a tree skeleton along the plane normal, and
        // queues per-tick bud / leaf placements that sequence
        // along branch paths so each bud's support has matured
        // by the time the next one lands. Persistent SavedData
        // per dimension.
        org.shipwrights.enderkinesis.block.WohlonnogondoniaTreeGrower.init()

        // World-root grower — Overworld-side feature triggered by
        // Wohlon biome spread reaching a fresh chunk. Per 3×3-chunk
        // region, mirrors the chunkgen's surface-root tunnel paths
        // (sphere-carved roots + airborne vine drips), ticked in
        // over time with the bud → mature pipeline. Each placed
        // voxel paints biome along the wave — roots are biome
        // carriers. Persistent SavedData per dimension.
        org.shipwrights.enderkinesis.block.WohlonnogondoniaWorldRootGrower.init()

        // Ygann's Abyss — reject world-frame block placements at intent time, AND globally
        // random-tick any non-shipyard strays into oblivion (catches command/explosion/
        // pre-existing data sources the placement event can't see).
        YgannAbyssGuard.init()
        YgannAbyssBlockSweeper.init()
        // Replace vanilla void damage in the Abyss with a slow fade-to-black + lethal
        // "met with a terrible fate" hit.
        YgannAbyssVoidFade.init()
        // Apply / stack the Ygann Madness effect every time a player leaves the Abyss.
        YgannMadnessOnExit.init()
        // Active behaviours of Ygann Madness (random teleport, water damage, enderman
        // aggression, passive damage + death-spawn). See class doc for level table.
        YgannMadnessBehavior.init()

        // Sselith's Repertory — vertical wrap-around. Entities and VS2 ships that drop
        // past y=-256 are teleported to y=+256 (same x/z, same velocity, no fall damage),
        // and void damage is suppressed in the dimension. Effectively an infinite-vertical
        // cylinder.
        SselithVoidWrap.init()

        // Sselith Madness — a hidden effect that grows level 1→5 with time in the
        // Repertory and decays slowly once you leave. Level behaviours are gated by
        // amplifier in dedicated handlers; this just manages the level.
        SselithMadness.init()

        // Chat-phrase teleport — "vraestmorocht-schest-kelkargh-skarn-moroch" (any case) sends
        // a player to (0,2,0) in Sselith when they're within 2 blocks of 4+ bookshelves
        // (world + ship), and back to where they came from when uttered in Sselith.
        SselithChatTeleport.init()

        // Sselith Madness chat corruption — replaces a level-scaled share of words
        // in a maddened player's messages with their Sselith translation. Registered
        // after the teleport handler so the teleport phrase is still detected before
        // this suppresses + re-broadcasts the corrupted line.
        SselithMadnessChat.init()

        // Wylland Tome — "gravity gun" for ships and entities. Server tick
        // applies the spring forces; the network module routes client-side
        // grab/release/scroll inputs.
        WyllandTomeManager.init()
        WyllandTomeNetwork.init()

        // Guide to Stratus — right-click a crepusculite lattice to raise its virtual-ocean sea
        // level a step, left-click to lower it.
        GuideToStratus.init()

        // Tome suite — register each tome's beam accent colour so the orb-network renderer can
        // tint a slice of glyphs in the owning tome's hue, plus any per-tome orb-network
        // behavior (e.g. Binding's VS joint lifecycle). Each tome contributes its own entry
        // here as the suite grows.
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

        // Almanac dimension tracking is event-driven: AlmanacOfEverywhereItem.inventoryTick handles
        // items held in an inventory, EntityChangeDimensionMixin handles dropped item entities.
    }
}
