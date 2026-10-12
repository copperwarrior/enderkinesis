package org.shipwrights.enderkinesis.item

import dev.architectury.event.events.common.TickEvent
import dev.architectury.networking.NetworkManager
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.registry.EKItems
import org.shipwrights.enderkinesis.body.GamylanCageRegistry
import org.valkyrienskies.core.api.bodies.ServerBaseVsBody
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.dimensionId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap


/** Network packets for the Staff of Scales.
 *
 *  Server-side raycast pattern: client trusts its own pick for HUD purposes,
 *  but the server runs an independent 64-block raycast (identical to
 *  [StaffOfConcealmentItem]'s) on BEGIN to authoritatively resolve the target
 *  ship. The picked ship id is cached per-player and used for every COMMIT —
 *  we no longer trust a client-supplied ship id, which side-steps the
 *  server-side `allShips.getById` lookup miss we were seeing.
 *
 *  - [BEGIN] (C2S): no payload (server raycasts itself).
 *  - [STATE] (S2C): server's reply — picked ship id + current scale.
 *  - [COMMIT] (C2S): scale only; server uses the cached ship from BEGIN. */
object ScalesNetwork {

    val BEGIN: ResourceLocation = EnderkinesisMod.id("staff_of_scales/begin")
    val STATE: ResourceLocation = EnderkinesisMod.id("staff_of_scales/state")
    val COMMIT: ResourceLocation = EnderkinesisMod.id("staff_of_scales/commit")
    val RESET: ResourceLocation = EnderkinesisMod.id("staff_of_scales/reset")

    /** Per-player cache of the picked ship *reference* (not just id). Set on
     *  BEGIN, read on COMMIT, cleared on release / disconnect. We hold the
     *  `LoadedServerShip` directly because — as observed in testing —
     *  `level.shipObjectWorld.allShips.getById(id)` can return null for a
     *  ship that `level.getLoadedShipManagingPos(...)` resolves fine. The
     *  two VS2 lookup APIs aren't equivalent for our use; holding the ref
     *  side-steps that. */
    private val activeByPlayer: MutableMap<UUID, LoadedServerShip> = ConcurrentHashMap()

    /** Parallel cache for non-ship VS bodies (Ga'Mylan cages, today —
     *  any future VS body would join here). Server BEGIN raycast picks
     *  whichever target is closer; COMMIT routes by which cache the
     *  player's BEGIN populated. */
    private val activeBodyByPlayer: MutableMap<UUID, ServerBaseVsBody> = ConcurrentHashMap()

    /** Reach matches [StaffOfConcealmentItem.REACH] / [StaffOfScalesItem.REACH]. */
    private const val REACH: Double = 64.0

    fun activeShipFor(player: ServerPlayer): LoadedServerShip? = activeByPlayer[player.uuid]

    private fun holdsStaff(player: ServerPlayer): Boolean =
        player.mainHandItem.item === EKItems.STAFF_OF_SCALES.get()

    fun clearActive(player: ServerPlayer) {
        activeByPlayer.remove(player.uuid)
        activeBodyByPlayer.remove(player.uuid)
    }

    private fun serverRaycastShip(player: ServerPlayer): LoadedServerShip? {
        val level = player.level()
        val eye = player.getEyePosition(1f)
        val end = eye.add(player.getViewVector(1f).scale(REACH))
        val ctx = ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player)
        val hit = level.clip(ctx)
        if (hit.type != HitResult.Type.BLOCK) return null
        return level.getLoadedShipManagingPos(hit.blockPos) as? LoadedServerShip
    }

    /** Server-side cage-body picker — ray-sphere test against every cage
     *  body in the player's dimension, returning the closest (or null).
     *  Mirrors [org.shipwrights.enderkinesis.client.GamylanCageRaytrace]
     *  on the client side. */
    private fun serverRaycastCageBody(player: ServerPlayer): ServerBaseVsBody? {
        val level = player.level()
        val eye = player.getEyePosition(1f)
        val look = player.getViewVector(1f)
        val world = level.shipObjectWorld
        val vsDim = level.dimensionId
        var best: ServerBaseVsBody? = null
        var bestT = REACH
        GamylanCageRegistry.forEachInVsDim(vsDim) { data ->
            val body = world.allBodies.getById(data.bodyId) as? ServerBaseVsBody ?: return@forEachInVsDim
            val pos = body.kinematics.position
            // Scale the bounding radius by the body's current scaling
            // so an already-Scales-enlarged cage still fits inside the
            // sphere the ray tests against; without this multiplier
            // the Staff of Scales can't re-target a cage it previously
            // grew (or gets phantom hits around a shrunken one).
            val bodyScale = body.kinematics.transform.scaling.x()
            val r = org.shipwrights.enderkinesis.body.GamylanCageShape.boundingRadius(data.shapeDims) * bodyScale
            // Same ray-sphere math as the client's pickOrb / cage trace.
            val ocX = pos.x() - eye.x
            val ocY = pos.y() - eye.y
            val ocZ = pos.z() - eye.z
            val t = ocX * look.x + ocY * look.y + ocZ * look.z
            if (t <= 0.0 || t > bestT) return@forEachInVsDim
            val perpX = ocX - look.x * t
            val perpY = ocY - look.y * t
            val perpZ = ocZ - look.z * t
            val perp2 = perpX * perpX + perpY * perpY + perpZ * perpZ
            if (perp2 > r * r) return@forEachInVsDim
            val hitT = t - kotlin.math.sqrt(r * r - perp2)
            if (hitT in 0.0..bestT) {
                bestT = hitT
                best = body
            }
        }
        return best
    }

    fun init() {
        // The maps hold ship and body references of the session.
        dev.architectury.event.events.common.PlayerEvent.PLAYER_QUIT.register(::clearActive)
        dev.architectury.event.events.common.LifecycleEvent.SERVER_STOPPED.register { _ ->
            activeByPlayer.clear()
            activeBodyByPlayer.clear()
        }
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, BEGIN) { _, ctx ->
            val player = ctx.player as? ServerPlayer ?: return@registerReceiver
            ctx.queue {
                if (!holdsStaff(player)) {
                    clearActive(player)
                    return@queue
                }
                if (ScalesManager.isDimensionBlacklisted(player)) {
                    player.displayClientMessage(
                        net.minecraft.network.chat.Component.translatable(
                            "item.enderkinesis.staff_of_scales.blacklisted_dimension"
                        ), true,
                    )
                    return@queue
                }
                // Cage body picker runs FIRST — a cage in front of a
                // ship hull should win, same precedence rule the Wylland
                // Tome uses. The two cache maps are mutually exclusive
                // per player; clear the other on every BEGIN.
                val body = serverRaycastCageBody(player)
                if (body != null) {
                    activeByPlayer.remove(player.uuid)
                    activeBodyByPlayer[player.uuid] = body
                    val current = ScalesManager.getCurrentScale(body)
                    val reply = FriendlyByteBuf(io.netty.buffer.Unpooled.buffer())
                    reply.writeLong(body.id)
                    reply.writeDouble(current)
                    NetworkManager.sendToPlayer(player, STATE, reply)
                    return@queue
                }
                val ship = serverRaycastShip(player)
                if (ship == null) {
                    activeByPlayer.remove(player.uuid)
                    activeBodyByPlayer.remove(player.uuid)
                    return@queue
                }
                activeBodyByPlayer.remove(player.uuid)
                activeByPlayer[player.uuid] = ship
                val current = ScalesManager.getCurrentScale(ship)
                val reply = FriendlyByteBuf(io.netty.buffer.Unpooled.buffer())
                reply.writeLong(ship.id)
                reply.writeDouble(current)
                NetworkManager.sendToPlayer(player, STATE, reply)
            }
        }

        NetworkManager.registerReceiver(NetworkManager.Side.C2S, COMMIT) { buf, ctx ->
            val scale = buf.readDouble()
            val player = ctx.player as? ServerPlayer ?: return@registerReceiver
            ctx.queue {
                if (!holdsStaff(player) || !player.isAlive) {
                    clearActive(player)
                    return@queue
                }
                val body = activeBodyByPlayer[player.uuid]
                if (body != null) {
                    val applied = ScalesManager.commitScaleOnBody(player, body, scale)
                    return@queue
                }
                val ship = activeByPlayer[player.uuid]
                if (ship == null) {
                    return@queue
                }
                val applied = ScalesManager.commitScaleOnShip(player, ship, scale)
            }
        }

        NetworkManager.registerReceiver(NetworkManager.Side.C2S, RESET) { _, ctx ->
            val player = ctx.player as? ServerPlayer ?: return@registerReceiver
            ctx.queue {
                if (player.mainHandItem.item !== EKItems.STAFF_OF_SCALES.get()) {
                    return@queue
                }
                val ship = serverRaycastShip(player)
                if (ship == null) {
                    return@queue
                }
                ScalesManager.startResetTween(player, ship)
            }
        }

        // Advance any active reset tweens once per server-level tick. Runs
        // before COMMIT packets are processed in the same tick, so a held
        // right-click drag still wins last-write while the user is dragging.
        TickEvent.SERVER_LEVEL_POST.register(
            TickEvent.ServerLevelTick { level -> ScalesManager.tickResetTweens(level) }
        )
    }
}
