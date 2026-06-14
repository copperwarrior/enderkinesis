package org.shipwrights.enderkinesis.client

import dev.architectury.networking.NetworkManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Player
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.entity.Cataloger

/**
 * Client-side mirror of the player tome-summon state. The server-side
 * [org.shipwrights.enderkinesis.dimension.SselithMadnessTomeSummon]
 * broadcasts begin/end packets to all players able to see the
 * summoning player; this object caches them so the renderer
 * ([org.shipwrights.enderkinesis.client.CatalogerTomeWorldRenderer])
 * and the local-input gate can both read it.
 *
 *  - **BEGIN packet** (`begin_packet`): playerUUID + sourceShelf +
 *    returnShelf + clientTickAtBegin + totalTicks + returnsToShelf.
 *  - **END packet** (`end_packet`): playerUUID.
 *
 * Active summons are keyed by player UUID (stable across reconnect
 * within a session) so the renderer can look them up while iterating
 * the level's players. Cleared on level change so old state from a
 * previous world doesn't bleed in.
 */
object PlayerTomeSummonClient {

    /** S2C packet: a tome summon has started on the server. */
    val BEGIN_PACKET: ResourceLocation =
        EnderkinesisMod.id("sselith_madness/tome_summon_begin")

    /** S2C packet: a tome summon has ended on the server. */
    val END_PACKET: ResourceLocation =
        EnderkinesisMod.id("sselith_madness/tome_summon_end")

    data class State(
        val sourceBookshelf: BlockPos,
        val returnBookshelf: BlockPos,
        val startTick: Int,
        val totalTicks: Int,
        val returnsToShelf: Boolean,
    )

    private val active = ConcurrentHashMap<UUID, State>()

    /** Returns the active summon state for [player] (by UUID) or null
     *  when no summon is running for them. Read by the renderer and
     *  the local input gate. */
    fun get(player: Player): State? = active[player.uuid]

    /** Called from the `LocalPlayer.aiStep` mixin (same injection
     *  point the menu walk uses). Suppresses movement input AND
     *  eases the camera onto the active phase's look target so the
     *  view smoothly follows the book without the snap-per-tick that
     *  the server's `ClientboundPlayerLookAtPacket` was producing. */
    fun onAiStep(player: net.minecraft.client.player.LocalPlayer) {
        val state = active[player.uuid] ?: return

        // Suppress input — body stays planted.
        val input = player.input
        input.forwardImpulse = 0f
        input.leftImpulse = 0f
        input.up = false
        input.down = false
        input.left = false
        input.right = false
        input.jumping = false
        input.shiftKeyDown = false

        // View easing. OUT → look at the source shelf as the book
        // floats over. DWELL → no forced view; the player's gaze
        // direction already aims wherever they are, and the book
        // renders along that gaze, so the book is naturally in front
        // of them. IN → look at the return shelf as the book floats
        // back.
        val elapsed = player.tickCount - state.startTick
        val dwellStart = Cataloger.TOME_OUTBOUND_TICKS
        val inStart = dwellStart + Cataloger.TOME_DWELL_TICKS
        val target = when {
            elapsed < dwellStart -> state.sourceBookshelf
            elapsed < inStart -> null
            else -> state.returnBookshelf
        } ?: return

        val dx = (target.x + 0.5) - player.x
        val dy = (target.y + 0.5) - player.eyeY
        val dz = (target.z + 0.5) - player.z
        val horiz = Math.sqrt(dx * dx + dz * dz)
        val targetYaw = (Mth.atan2(dz, dx) * (180.0 / Math.PI)).toFloat() - 90f
        val targetPitch = (-(Mth.atan2(dy, horiz) * (180.0 / Math.PI))).toFloat()

        val yawStep = (Mth.wrapDegrees(targetYaw - player.yRot) * EASE_FACTOR)
            .coerceIn(-MAX_TURN_PER_TICK, MAX_TURN_PER_TICK)
        val pitchStep = (Mth.wrapDegrees(targetPitch - player.xRot) * EASE_FACTOR)
            .coerceIn(-MAX_TURN_PER_TICK, MAX_TURN_PER_TICK)

        val newYaw = player.yRot + yawStep
        val newPitch = player.xRot + pitchStep

        // Snapshot *O before writes — vanilla only auto-updates
        // these on mouse input (Entity.turn), which is suppressed
        // while a screen is open. Without this snapshot the
        // `getViewYRot(partialTick) = lerp(*O, *)` reads from a
        // stale value and the rendered camera jitters.
        player.yRotO = player.yRot
        player.yHeadRotO = player.yHeadRot
        player.yBodyRotO = player.yBodyRot
        player.xRotO = player.xRot
        player.yRot = newYaw
        player.yHeadRot = newYaw
        player.yBodyRot = newYaw
        player.xRot = newPitch
    }

    /** Per-tick fraction of the remaining yaw / pitch gap closed
     *  while approaching the look target. With [MAX_TURN_PER_TICK]
     *  as a cap, the bulk of long turns runs at constant velocity
     *  for smooth rendered interpolation; the exponential kicks in
     *  only for the final landing. */
    private const val EASE_FACTOR = 0.2f

    /** Max degrees turned per tick. 12°/tick ≈ 240°/s, fast enough
     *  to keep up with the book's flight, slow enough to read as a
     *  deliberate gaze rather than a snap. */
    private const val MAX_TURN_PER_TICK = 12f

    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, BEGIN_PACKET) { buf, _ ->
            val playerUuid = buf.readUUID()
            val source = buf.readBlockPos()
            val returnTo = buf.readBlockPos()
            val totalTicks = buf.readVarInt()
            val returnsToShelf = buf.readBoolean()
            Minecraft.getInstance().execute {
                val level = Minecraft.getInstance().level ?: return@execute
                // Stamp the entity's own tick counter as start — the
                // renderer reads `entity.tickCount - startTick` for
                // elapsed, so they MUST be in the same units. Using
                // `level.gameTime` would land elapsed wildly out of
                // range (gameTime is persistent world time; tickCount
                // resets to 0 each session) and the render bound
                // check would silently skip the book every frame.
                val target = level.getPlayerByUUID(playerUuid)
                if (target == null) {
                    // Player not loaded on this client yet — we'll
                    // miss the start of this summon, but BEGIN is
                    // rebroadcast at the OUT→DWELL boundary, so the
                    // dwell+IN portion will still render.
                    return@execute
                }
                active[playerUuid] = State(
                    sourceBookshelf = source,
                    returnBookshelf = returnTo,
                    startTick = target.tickCount,
                    totalTicks = totalTicks,
                    returnsToShelf = returnsToShelf,
                )
            }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, END_PACKET) { buf, _ ->
            val playerUuid = buf.readUUID()
            Minecraft.getInstance().execute {
                active.remove(playerUuid)
            }
        }
        // Expire entries whose elapsed has run past total — guards
        // against a missed END packet (cross-dimension teleport, etc).
        // Uses each player's own `tickCount` to stay consistent with
        // the start stamp.
        dev.architectury.event.events.client.ClientTickEvent.CLIENT_LEVEL_POST.register { level ->
            if (active.isEmpty()) return@register
            active.entries.removeIf { (uuid, s) ->
                val p = level.getPlayerByUUID(uuid) ?: return@removeIf true
                p.tickCount - s.startTick > s.totalTicks + 40
            }
        }
    }
}
