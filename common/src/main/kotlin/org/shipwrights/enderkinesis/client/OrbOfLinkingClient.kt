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
 * across multiple tomes; this tick iterates each loaded orb's outgoing links, allocating a
 * separate [BeamPath] in [BeamRegistry] per (sendPos, tomeKind, receiverPos) triple so a pair
 * welded AND signal-wired AND chained renders three concurrent beams in their respective tome
 * colours.
 *
 * Per-tick steps:
 *  1. For each loaded SEND orb in this level, walk `allOutgoing()`.
 *  2. For each `(tomeKind, receiverPos)` pair, find or allocate a beam id keyed by the triple.
 *  3. Update the corresponding [BeamPath]'s endpoints (using render-frame ship transforms so
 *     ships in motion don't cause beam lag), set accent colour from the tome palette, and
 *     spawn particles. Density scales with the SEND-side `POWER` if the link is Signal — the
 *     Signal beam visibly throbs at full power; other-tome beams use the idle rate.
 *  4. GC any beam id that wasn't touched this tick (link removed, orb broken).
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

        for (sendBe in OrbOfLinkingClientRegistry.sendOrbs(level)) {
            val outgoing = sendBe.allOutgoing()
            if (outgoing.isEmpty()) continue
            val sendPos = sendBe.blockPos
            val sendCentre = orbRenderCentre(level, sendPos) ?: continue
            // Signal beams throb with the RECEIVER's aggregate POWER; non-Signal links use a
            // constant idle rate. Looking up POWER once per receiver (rather than per pair)
            // saves work for orbs with many incoming links of mixed tomes.
            for ((linkKey, _) in outgoing) {
                val tomeKind = linkKey.tomeKind
                val recvPos = linkKey.peerPos
                val recvBe = level.getBlockEntity(recvPos) as? OrbOfLinkingBlockEntity ?: continue
                val recvCentre = orbRenderCentre(level, recvPos) ?: continue
                val mid = Vec3(
                    (sendCentre.x + recvCentre.x) * 0.5,
                    (sendCentre.y + recvCentre.y) * 0.5 - BEAM_SAG,
                    (sendCentre.z + recvCentre.z) * 0.5,
                )
                val key = PairKey(sendPos, tomeKind, recvPos)
                val id = pairIds.getOrPut(key) { BeamRegistry.allocate() }
                val path = BeamRegistry.get(id)
                    ?: BeamRegistry.put(id, BeamPath(flowRate = BEAM_FLOW_RATE))
                path.start = sendCentre
                path.control = mid
                path.end = recvCentre
                path.radius = BEAM_RADIUS
                // Orb-to-orb beams use the hourglass profile (wide at both endpoints,
                // pinched in the middle) — both ends are equally significant. They also
                // render less transparent than the Wylland Tome's haze so the link is
                // visible at distance.
                path.profile = BeamProfile.HOURGLASS
                path.alpha = BEAM_ALPHA
                val accent = TomeBeamPalette.colorFor(tomeKind)
                path.accentColor = accent
                path.accentChance = if (accent != null) TomeBeamPalette.ACCENT_CHANCE else 0.0
                seenThisTick.add(id)

                val signalPower = if (tomeKind == SIGNAL_TOME_KIND)
                    recvBe.blockState.getValue(OrbOfLinkingBlock.POWER) else 0
                val count = IDLE_PARTICLES_PER_TICK +
                    ((signalPower.toDouble() / 15.0) * (ACTIVE_PARTICLES_PER_TICK - IDLE_PARTICLES_PER_TICK)).toInt()
                val idAsDouble = id.toDouble()
                repeat(count) {
                    val t = rand.nextDouble()
                    val theta = rand.nextDouble() * Math.PI * 2.0
                    level.addParticle(
                        EKParticles.enchantedBookBeam(),
                        sendCentre.x, sendCentre.y, sendCentre.z,
                        t, theta, idAsDouble,
                    )
                }
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

    /** Render-frame world centre of the orb at [pos]. Delegates to [OrbClientGeometry] —
     *  same helper the Tome of Transportation client uses, so both modules agree on what
     *  "where this orb visually is" means. */
    private fun orbRenderCentre(level: net.minecraft.client.multiplayer.ClientLevel, pos: BlockPos): Vec3? =
        OrbClientGeometry.worldCenter(level, pos)

    private data class PairKey(val send: BlockPos, val tome: ResourceLocation, val recv: BlockPos)

    /** Signal-tome id (constant; matches [org.shipwrights.enderkinesis.item.SignalTomeOrbBehavior.tomeKind]).
     *  Hard-coded as a constant here to avoid pulling in the server-side behavior object on the
     *  client tick's hot path. */
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
