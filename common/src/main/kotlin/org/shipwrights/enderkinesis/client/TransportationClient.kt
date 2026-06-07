package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientTickEvent
import dev.architectury.networking.NetworkManager
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.world.item.ItemStack
import org.shipwrights.enderkinesis.item.TransportationNetwork
import java.util.concurrent.atomic.AtomicLong

/**
 * Client-side state for the Tome of Transportation's ghost-item visual. The visual is
 * entirely client-side and has **no entity** at any point — the in-flight item is drawn
 * directly via [TransportationGhostRenderer], which is driven by a
 * [org.shipwrights.enderkinesis.mixin.LevelRendererTransportationGhostsMixin] injection
 * into `LevelRenderer.renderLevel`. This module owns the data (which stacks are in flight,
 * where their endpoints are, when they should despawn) and the trail-particle spawning;
 * the rendering itself reads from [activeFlights].
 *
 * Flow:
 *  1. Server pulls a stack and broadcasts a [TransportationNetwork.DISPATCH] packet.
 *  2. This module receives the packet, allocates a flight id, adds the flight to
 *     [flights] with the receive-time set as its start tick.
 *  3. Each render frame the mixin calls [TransportationGhostRenderer.render], which
 *     iterates [activeFlights], draws each item at the partial-tick-interpolated position
 *     via `ItemRenderer.renderStatic`, AND spawns the purple PORTAL particle trail at that
 *     same render position. Spawning on the render path is the only way to get a trail
 *     that's truly synchronized with the visual — a 20-Hz tick spawn lags the
 *     60+ Hz render. No entity, no hitbox.
 *  4. Each client tick: expire any flight whose elapsed time has reached its travel
 *     duration, or whose endpoints can no longer be resolved.
 *
 * If either endpoint can no longer be resolved (orb broken, chunk unloaded), the flight
 * expires immediately — both the trail particles and the rendered item disappear.
 */
object TransportationClient {

    private val nextFlightId = AtomicLong(1L)

    /** Currently-rendering flights, keyed by id so they can be expired in O(1). */
    private val flights: MutableMap<Long, GhostFlight> = LinkedHashMap()

    /** Per-flight state. The two block positions are resolved each render frame via
     *  [OrbClientGeometry.worldCenter], so the visual tracks live ship motion on both ends.
     *  [tumbleSeed] is `0..1` and drives per-flight rotation offsets + rate jitter in
     *  [TransportationGhostRenderer] so each ghost item tumbles independently — same
     *  textbook tumble math for everyone, just phase- and rate-shifted per stack. */
    class GhostFlight(
        val stack: ItemStack,
        val sendPos: BlockPos,
        val recvPos: BlockPos,
        val startTick: Long,
        val totalTicks: Int,
        val tumbleSeed: Float,
    )

    /** Read-only view of the current flights for the renderer. */
    fun activeFlights(): Collection<GhostFlight> = flights.values

    fun init() {
        NetworkManager.registerReceiver(
            NetworkManager.Side.S2C,
            TransportationNetwork.DISPATCH,
        ) { buf, _ ->
            val sendPos = buf.readBlockPos()
            val recvPos = buf.readBlockPos()
            val stack: ItemStack = buf.readItem()
            val totalTicks = buf.readVarInt()
            Minecraft.getInstance().execute { addFlight(sendPos, recvPos, stack, totalTicks) }
        }
        ClientTickEvent.CLIENT_LEVEL_POST.register(ClientTickEvent.ClientLevel { level -> tick(level) })
    }

    private fun addFlight(sendPos: BlockPos, recvPos: BlockPos, stack: ItemStack, totalTicks: Int) {
        val level = Minecraft.getInstance().level ?: return
        if (stack.isEmpty || totalTicks <= 0) return
        val id = nextFlightId.getAndIncrement()
        flights[id] = GhostFlight(
            stack = stack.copy(),
            sendPos = sendPos,
            recvPos = recvPos,
            startTick = level.gameTime,
            totalTicks = totalTicks,
            tumbleSeed = level.random.nextFloat(),
        )
    }

    /** Per-flight `t` (clamped to [0, 1]) at the given client game time. */
    fun progress(flight: GhostFlight, gameTime: Long, partialTick: Float): Double {
        val elapsedTicks = (gameTime - flight.startTick).toDouble() + partialTick.toDouble()
        return (elapsedTicks / flight.totalTicks).coerceIn(0.0, 1.0)
    }

    /** Render-frame world position for [flight] — `lerp(send, recv, t)` with both endpoints
     *  resolved live. Returns null if either endpoint can't be resolved (orb broken /
     *  unloaded), in which case the renderer should skip the flight. */
    fun renderPos(level: ClientLevel, flight: GhostFlight, partialTick: Float): net.minecraft.world.phys.Vec3? {
        val sendCentre = OrbClientGeometry.worldCenter(level, flight.sendPos) ?: return null
        val recvCentre = OrbClientGeometry.worldCenter(level, flight.recvPos) ?: return null
        val t = progress(flight, level.gameTime, partialTick)
        return net.minecraft.world.phys.Vec3(
            sendCentre.x + (recvCentre.x - sendCentre.x) * t,
            sendCentre.y + (recvCentre.y - sendCentre.y) * t,
            sendCentre.z + (recvCentre.z - sendCentre.z) * t,
        )
    }

    /** Per-tick expiry sweep. The trail is drawn from the render path, so the tick handler
     *  has no per-frame visual work — it only removes flights whose duration has elapsed or
     *  whose endpoints can no longer be resolved (orb broken, chunk unloaded). */
    private fun tick(level: ClientLevel) {
        if (flights.isEmpty()) return
        val now = level.gameTime
        val expired = ArrayList<Long>()
        for ((id, flight) in flights) {
            val elapsed = (now - flight.startTick).toInt()
            if (elapsed >= flight.totalTicks) { expired.add(id); continue }
            if (OrbClientGeometry.worldCenter(level, flight.sendPos) == null ||
                OrbClientGeometry.worldCenter(level, flight.recvPos) == null) {
                expired.add(id)
            }
        }
        for (id in expired) flights.remove(id)
    }
}
