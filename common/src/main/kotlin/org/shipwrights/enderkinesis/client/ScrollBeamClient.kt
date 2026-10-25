package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import it.unimi.dsi.fastutil.ints.Int2DoubleOpenHashMap
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.item.AegisBox
import org.shipwrights.enderkinesis.item.BeamSegment
import org.shipwrights.enderkinesis.item.BeamTrace
import org.shipwrights.enderkinesis.item.MysticScrollItem
import org.shipwrights.enderkinesis.item.SunderingBeamTrace
import org.shipwrights.enderkinesis.spell.Shape
import org.shipwrights.enderkinesis.spell.SpellCasting

/**
 * Draws Mystic Scroll beams in the Staff of Sundering's style, coloured by their spell.
 *
 * Each player holding a Beam cast is traced here on the client, with the same Aegis
 * reflections and self-loops as the server. Merged beams exist only on the server, which
 * sends each one's path every tick (see [onMerged]); a parent feeding a merged beam is drawn
 * only up to the crossing, Staff of Sundering beams included (see [clip]).
 */
object ScrollBeamClient {

    private class Merged(val segments: List<BeamSegment>, val rgb: Int, val tick: Long)

    // Render thread only.
    private var merged: List<Merged> = emptyList()
    private val cuts = Int2DoubleOpenHashMap()
    private var cutsTick = 0L

    /** Merged-beam data older than this many ticks is stale: the merge ended. */
    private const val STALE_TICKS = 3

    @JvmStatic
    fun renderAll(pose: PoseStack, consumers: MultiBufferSource, camX: Double, camY: Double, camZ: Double, tickDelta: Float) {
        val level = Minecraft.getInstance().level ?: return
        val gameTime = level.gameTime + tickDelta.toDouble()
        for (player in level.players()) {
            val spell = beamSpell(player) ?: continue
            val trace = trace(level, player, tickDelta, SpellCasting.beamReach(spell.magnitude(spell.shapeElement!!)))
            SunderingClient.renderColouredBeam(pose, consumers, camX, camY, camZ, clip(player.id, trace.segments), spell.tint(), gameTime)
        }
        val now = level.gameTime
        for (beam in merged) {
            if (now - beam.tick > STALE_TICKS) continue
            SunderingClient.renderColouredBeam(pose, consumers, camX, camY, camZ, beam.segments, beam.rgb, gameTime)
        }
    }

    private fun beamSpell(player: Player) =
        if (!player.isUsingItem) null
        else MysticScrollItem.spellOf(player.useItem)?.takeIf { it.shape == Shape.BEAM }

    private fun trace(level: ClientLevel, player: Player, tickDelta: Float, reach: Double): BeamTrace {
        val shields = ArrayList<AegisBox.Frame>()
        shields.addAll(SunderingBeamTrace.collectWieldedShields(level, player))
        shields.addAll(AegisClient.snapshotDebugBoxes())
        val look = player.getViewVector(tickDelta).normalize()
        return SunderingBeamTrace.trace(level, player, player.getEyePosition(tickDelta), look, reach, shields)
    }

    /** [segments] of the beam held by the entity with [entityId], ending where it feeds a
     *  merged beam, if it does. */
    @JvmStatic
    fun clip(entityId: Int, segments: List<BeamSegment>): List<BeamSegment> {
        val level = Minecraft.getInstance().level ?: return segments
        if (level.gameTime - cutsTick > STALE_TICKS || !cuts.containsKey(entityId)) return segments
        val cut = cuts.get(entityId)
        val out = ArrayList<BeamSegment>()
        var travelled = 0.0
        for (seg in segments) {
            if (travelled + seg.length >= cut) {
                val keep = (cut - travelled).coerceAtLeast(0.0)
                out += BeamSegment(seg.start, seg.direction, seg.start.add(seg.direction.scale(keep)), keep)
                break
            }
            out += seg
            travelled += seg.length
        }
        return out
    }

    /** One tick's merged beams from the server: each one's path and colour, and how far
     *  along its path each parent (by entity id) still reaches. */
    fun onMerged(beams: List<Pair<List<Pair<Vec3, Vec3>>, Int>>, parentCuts: Map<Int, Double>) {
        val tick = Minecraft.getInstance().level?.gameTime ?: return
        merged = beams.map { (points, rgb) ->
            Merged(points.map { (from, to) -> BeamSegment(from, to.subtract(from).normalize(), to, from.distanceTo(to)) }, rgb, tick)
        }
        cuts.clear()
        for ((id, cut) in parentCuts) cuts.put(id, cut)
        cutsTick = tick
    }

    fun reset() {
        merged = emptyList()
        cuts.clear()
    }
}
