package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.block.OrbOfLinkingBlock
import org.shipwrights.enderkinesis.blockentity.OrbOfLinkingBlockEntity
import org.shipwrights.enderkinesis.blockentity.OrbOfLinkingClientRegistry
import org.shipwrights.enderkinesis.item.TomeBeamPalette
import org.shipwrights.enderkinesis.registry.EKParticles

/**
 * Client-side renderer for orb-of-linking beams. The orb BE can carry many links per orb
 * across multiple tomes; this tick walks every loaded orb's outgoing AND incoming maps,
 * allocating a separate [BeamPath] in [BeamRegistry] per (sendPos, tomeKind, receiverPos)
 * triple so a pair welded AND signal-wired AND chained renders three concurrent beams in
 * their respective tome colours.
 *
 * Per-tick steps:
 *  1. For each loaded orb in this level, walk both `allOutgoing()` (this orb is the SEND end)
 *     and `allIncoming()` (this orb is the RECEIVE end). The PairKey `(sendPos, tomeKind,
 *     recvPos)` is symmetric — both perspectives map to the same key, so when both endpoints
 *     are loaded the link is rendered exactly once, and when only one end is loaded the beam
 *     still renders from that end toward the absent peer's BlockPos centre.
 *  2. Update the corresponding [BeamPath]'s endpoints (using render-frame ship transforms so
 *     ships in motion don't cause beam lag), set accent colour from the tome palette, and
 *     spawn particles. Density scales with the SEND-side `POWER` if the link is Signal — the
 *     Signal beam visibly throbs at full power; other-tome beams use the idle rate.
 *  3. GC any beam id that wasn't touched this tick (link removed, orb broken, both ends
 *     unloaded).
 */
object OrbOfLinkingClient {

    /** Triple-keyed beam id map: (sendPos, tomeKind, receiverPos) → opaque BeamRegistry id. */
    private val pairIds: MutableMap<PairKey, Long> = HashMap()

    /** Reusable scratch — repopulated each tick to keep the seen-set check O(1) without
     *  re-allocating. */
    private val seenThisTick: MutableSet<Long> = HashSet()

    fun init() {
        ClientTickEvent.CLIENT_LEVEL_POST.register { level ->
            tick(level)
        }
    }

    private fun tick(level: net.minecraft.client.multiplayer.ClientLevel) {
        val mc = Minecraft.getInstance()
        if (mc.isPaused) return
        seenThisTick.clear()
        val rand = level.random

        for (orb in OrbOfLinkingClientRegistry.sendOrbs(level)) {
            val orbPos = orb.blockPos
            val orbCentre = orbRenderCentre(level, orbPos) ?: continue
            // SEND side: this orb is the source end of each outgoing link.
            for ((linkKey, _) in orb.allOutgoing()) {
                updateBeam(
                    level, rand,
                    sendPos = orbPos, sendCentre = orbCentre,
                    recvPos = linkKey.peerPos, recvCentre = null,
                    tomeKind = linkKey.tomeKind,
                )
            }
            // RECEIVE side: this orb is the destination end of each incoming link. The
            // PairKey is symmetric, so when the SEND end is also loaded the duplicate is
            // collapsed by [pairIds]; when only this end is loaded, the beam still renders
            // from here toward the absent SEND's BlockPos centre.
            for ((linkKey, _) in orb.allIncoming()) {
                updateBeam(
                    level, rand,
                    sendPos = linkKey.peerPos, sendCentre = null,
                    recvPos = orbPos, recvCentre = orbCentre,
                    tomeKind = linkKey.tomeKind,
                )
            }
        }

        // GC stale beams.
        val toRemove = ArrayList<PairKey>()
        for ((key, id) in pairIds) {
            if (id !in seenThisTick) {
                BeamRegistry.remove(id)
                toRemove.add(key)
            }
        }
        for (key in toRemove) pairIds.remove(key)
    }

    private fun updateBeam(
        level: net.minecraft.client.multiplayer.ClientLevel,
        rand: net.minecraft.util.RandomSource,
        sendPos: BlockPos, sendCentre: Vec3?,
        recvPos: BlockPos, recvCentre: Vec3?,
        tomeKind: ResourceLocation,
    ) {
        val key = PairKey(sendPos, tomeKind, recvPos)
        val id = pairIds.getOrPut(key) { BeamRegistry.allocate() }
        // Same beam may be discovered from both ends within a single tick (both endpoints
        // loaded). Skip the second visit — first writer wins — so we don't fight with
        // ourselves overwriting endpoints with the same values.
        if (id in seenThisTick) return

        // Resolve each endpoint via the precise renderer (active-face offset + ship transform)
        // when the BE is loaded; otherwise fall back to the BlockPos grid centre so the beam
        // still has a meaningful endpoint when one chunk is unloaded.
        val s = sendCentre ?: orbRenderCentreOrFallback(level, sendPos)
        val r = recvCentre ?: orbRenderCentreOrFallback(level, recvPos)
        val mid = Vec3(
            (s.x + r.x) * 0.5,
            (s.y + r.y) * 0.5 - BEAM_SAG,
            (s.z + r.z) * 0.5,
        )
        val path = BeamRegistry.get(id)
            ?: BeamRegistry.put(id, BeamPath(flowRate = BEAM_FLOW_RATE))
        path.start = s
        path.control = mid
        path.end = r
        path.radius = BEAM_RADIUS
        path.flowRate = BEAM_FLOW_RATE
        path.profile = BeamProfile.HOURGLASS
        path.alpha = BEAM_ALPHA
        val accent = TomeBeamPalette.colorFor(tomeKind)
        path.accentColor = accent
        path.accentChance = if (accent != null) TomeBeamPalette.ACCENT_CHANCE else 0.0
        seenThisTick.add(id)

        // SGA-glyph ambient beam. Density throbs with Signal-tome power on Signal links;
        // every other tome uses the idle rate. Travelling-pulse visuals (per-tome flow,
        // colour, wander) live in the separate quad-strip path in [OrbBeamLineRenderer],
        // which runs from the level-renderer hook instead of as particles.
        val signalPower = if (tomeKind == SIGNAL_TOME_KIND) {
            val recvBe = level.getBlockEntity(recvPos) as? OrbOfLinkingBlockEntity
            recvBe?.blockState?.getValue(OrbOfLinkingBlock.POWER) ?: 0
        } else 0
        val glyphCount = IDLE_PARTICLES_PER_TICK +
            ((signalPower.toDouble() / 15.0) * (ACTIVE_PARTICLES_PER_TICK - IDLE_PARTICLES_PER_TICK)).toInt()
        val idAsDouble = id.toDouble()
        repeat(glyphCount) {
            val t = rand.nextDouble()
            val theta = rand.nextDouble() * Math.PI * 2.0
            level.addParticle(
                EKParticles.enchantedBookBeam(),
                s.x, s.y, s.z,
                t, theta, idAsDouble,
            )
        }
    }

    /** Resolve an orb's render centre, falling back to the block-grid centre when the BE
     *  isn't loaded. Active-face offset + ship transform are lost in the fallback, but the
     *  beam endpoint is still close enough for the link to read at distance. */
    private fun orbRenderCentreOrFallback(
        level: net.minecraft.client.multiplayer.ClientLevel, pos: BlockPos,
    ): Vec3 = OrbClientGeometry.worldCenter(level, pos) ?: Vec3.atCenterOf(pos)

    /** Render-frame world centre of the orb at [pos]. Delegates to [OrbClientGeometry] —
     *  same helper the Tome of Transportation client uses, so both modules agree on what
     *  "where this orb visually is" means. */
    private fun orbRenderCentre(level: net.minecraft.client.multiplayer.ClientLevel, pos: BlockPos): Vec3? =
        OrbClientGeometry.worldCenter(level, pos)

    private data class PairKey(val send: BlockPos, val tome: ResourceLocation, val recv: BlockPos)

    /** Signal-tome id (matches [org.shipwrights.enderkinesis.item.SignalTomeOrbBehavior.tomeKind]).
     *  Hard-coded here to avoid pulling in the server-side behavior object on the client
     *  tick's hot path. */
    private val SIGNAL_TOME_KIND: ResourceLocation =
        ResourceLocation("enderkinesis", "tome_of_signal")

    private const val BEAM_SAG = 0.25
    private const val BEAM_RADIUS = 0.35
    private const val BEAM_FLOW_RATE = 0.08
    private const val IDLE_PARTICLES_PER_TICK = 1
    private const val ACTIVE_PARTICLES_PER_TICK = 6

    /** Per-particle alpha for orb-to-orb beams. Higher than the Wylland Tome's 0.4 light-haze
     *  so the link reads clearly at distance against varied terrain. */
    private const val BEAM_ALPHA: Float = 0.85f
}
