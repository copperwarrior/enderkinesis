package org.shipwrights.enderkinesis.item

import com.mojang.logging.LogUtils
import dev.architectury.event.events.common.TickEvent
import dev.architectury.networking.NetworkManager
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.registry.EKItems
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val LOG = LogUtils.getLogger()

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

    /** Reach matches [StaffOfConcealmentItem.REACH] / [StaffOfScalesItem.REACH]. */
    private const val REACH: Double = 64.0

    fun activeShipFor(player: ServerPlayer): LoadedServerShip? = activeByPlayer[player.uuid]

    fun clearActive(player: ServerPlayer) {
        activeByPlayer.remove(player.uuid)
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

    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, BEGIN) { _, ctx ->
            val player = ctx.player as? ServerPlayer ?: return@registerReceiver
            LOG.info("[Scales] SERVER got BEGIN player={}", player.name.string)
            ctx.queue {
                if (ScalesManager.isDimensionBlacklisted(player)) {
                    LOG.info("[Scales] SERVER BEGIN refused: dimension blacklisted")
                    player.displayClientMessage(
                        net.minecraft.network.chat.Component.translatable(
                            "item.enderkinesis.staff_of_scales.blacklisted_dimension"
                        ), true,
                    )
                    return@queue
                }
                val ship = serverRaycastShip(player)
                if (ship == null) {
                    LOG.info("[Scales] SERVER BEGIN: server raycast missed (no ship in 64-block cone)")
                    activeByPlayer.remove(player.uuid)
                    return@queue
                }
                activeByPlayer[player.uuid] = ship
                val current = ScalesManager.getCurrentScale(ship)
                LOG.info("[Scales] SERVER cached shipId={} (ref) for player={}, replying STATE scale={}",
                    ship.id, player.name.string, current)
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
                val ship = activeByPlayer[player.uuid]
                if (ship == null) {
                    LOG.info("[Scales] SERVER COMMIT ignored: no cached active ship for player={}",
                        player.name.string)
                    return@queue
                }
                val applied = ScalesManager.commitScaleOnShip(player, ship, scale)
                LOG.info("[Scales] SERVER COMMIT shipId={} scale={} applied={}",
                    ship.id, scale, applied)
            }
        }

        NetworkManager.registerReceiver(NetworkManager.Side.C2S, RESET) { _, ctx ->
            val player = ctx.player as? ServerPlayer ?: return@registerReceiver
            LOG.info("[Scales] SERVER got RESET player={}", player.name.string)
            ctx.queue {
                if (player.mainHandItem.item !== EKItems.STAFF_OF_SCALES.get()) {
                    LOG.info("[Scales] SERVER RESET refused: player not holding Scales staff")
                    return@queue
                }
                val ship = serverRaycastShip(player)
                if (ship == null) {
                    LOG.info("[Scales] SERVER RESET: server raycast missed (no ship in 64-block cone)")
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
