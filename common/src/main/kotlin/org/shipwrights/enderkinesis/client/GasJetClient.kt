// Client gas jets run only on the client game thread.
package org.shipwrights.enderkinesis.client

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.client.multiplayer.ClientLevel
import org.shipwrights.enderkinesis.spell.GasJetEmitter
import org.shipwrights.enderkinesis.spell.GasJetSim
import org.shipwrights.enderkinesis.spell.GasJetTuning
import org.shipwrights.enderkinesis.spell.GasKind
import org.shipwrights.enderkinesis.spell.JET_CHANNELS
import org.shipwrights.enderkinesis.spell.jetChannel

object GasJetClient {
    // Client thread only. The list gives a no-allocation walk for the per-frame line build.
    private val byId = Array(JET_CHANNELS) { Long2ObjectOpenHashMap<GasJetEmitter>() }
    internal val emitters = ArrayList<GasJetEmitter>()
    private var now = 0L
    private const val EXPIRE_TICKS = 3 * GasJetTuning.RESEND_EVERY

    @Suppress("LongParameterList") // The wire state of an emitter.
    fun received(
        id: Long, kind: GasKind, hand: Boolean, stop: Boolean, frame: Long, px: Double, py: Double, pz: Double,
        dx: Double, dy: Double, dz: Double, strength: Float, parcelMass: Float, start: Long, seed: Long,
    ) {
        val map = byId[jetChannel(kind, hand)]
        var e = map.get(id)
        if (stop) { e?.active = false; return }
        if (e == null) {
            e = GasJetEmitter(id, kind, seed, hand)
            map.put(id, e)
            emitters.add(e)
        }
        if (!e.active || e.startTick != start) { e.active = true; e.carry = 0f }
        e.startTick = start
        e.frame = frame; e.px = px; e.py = py; e.pz = pz; e.dx = dx; e.dy = dy; e.dz = dz
        e.strength = strength
        e.parcelMass = parcelMass
        e.lastHeld = now
    }

    fun reset() {
        for (map in byId) map.clear()
        emitters.clear()
    }

    fun isEmpty(kind: GasKind): Boolean {
        for (i in emitters.indices) if (emitters[i].kind == kind) return false
        return true
    }

    fun tick(level: ClientLevel) {
        now++
        var i = emitters.size - 1
        while (i >= 0) {
            val e = emitters[i]
            if (e.active && now - e.lastHeld > EXPIRE_TICKS) e.active = false
            GasJetSim.tick(level, e, null)
            if (!e.active && e.parcels.n == 0) {
                byId[jetChannel(e.kind, e.hand)].remove(e.id)
                emitters.removeAt(i)
            }
            i--
        }
    }
}
