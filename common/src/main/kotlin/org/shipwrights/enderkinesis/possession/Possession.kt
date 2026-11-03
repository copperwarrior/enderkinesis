package org.shipwrights.enderkinesis.possession

import dev.architectury.event.events.common.PlayerEvent
import dev.architectury.event.events.common.TickEvent
import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.TagKey
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.dimension.SselithMadness
import org.shipwrights.enderkinesis.dimension.SselithMadnessTomeSummon
import org.shipwrights.enderkinesis.item.GrandStaffDread
import org.shipwrights.enderkinesis.registry.EKEffects
import org.shipwrights.enderkinesis.registry.EKEntities
import org.valkyrienskies.mod.common.toWorldCoordinates
import java.util.UUID

/**
 * Server control of a body that Sselith Madness or Ulder Paranoia walks. A ghost pathfinder on the player's
 * server position finds the path. The client walks the nodes it gets; the server moves a player back onto
 * the path when they leave it or stall. A player whose client never says hello (a fake player) gets its
 * movement input from the server. Server thread only.
 */
object Possession {

    /** S2C: [Mode], [Kind], then the nodes (WALK) or the look point (STARE). */
    val DIRECTIVE: ResourceLocation = EnderkinesisMod.id("possession/directive")
    /** S2C: grip flags and the struggle fraction, for the cue sounds and the vignette. */
    val GRIP: ResourceLocation = EnderkinesisMod.id("possession/grip")
    /** C2S: this client walks the directives itself. */
    val HELLO: ResourceLocation = EnderkinesisMod.id("possession/hello")
    /** C2S: struggle effort of one client tick. */
    val STRUGGLE: ResourceLocation = EnderkinesisMod.id("possession/struggle")
    /** C2S: a screen opened or closed. */
    val SCREEN: ResourceLocation = EnderkinesisMod.id("possession/screen")

    const val MAX_NODES = 256
    const val GRIP_DRIVING = 1
    const val GRIP_EPISODE = 2
    const val GRIP_FRACTION_SHIFT = 2
    const val GRIP_FRACTION_STEPS = 63

    enum class Kind(val impulse: Float, val ease: Float) { SSELITH(0.5f, 0.2f), PARANOIA(1f, 0.3f) }
    enum class Mode { NONE, WALK, STARE, STAND }

    // A walking body reaches a node about every 5 ticks.
    private const val STALL_TICKS = 40L
    private const val STRAY_DIST = 3.0
    private const val STRAY_TICKS = 10
    private const val ANCHOR_RADIUS = 1.5
    private const val DUST_INTERVAL_TICKS = 4
    private const val PATH_RETRY_TICKS = 10
    private const val PARANOIA_SPEED = 1.0
    private const val OPENED_POI_REACH = 5.0

    private val CATALOGER_TARGETS: TagKey<Block> =
        TagKey.create(Registries.BLOCK, EnderkinesisMod.id("cataloger_targets"))

    private class Body {
        val grip = SselithGrip()
        var clientDriven = false
        var kind: Kind? = null
        var ghost: PathfinderMob? = null
        var sselith: SselithWalk? = null
        var mode = Mode.NONE
        var path: Path? = null
        var stareAt: Vec3? = null
        var anchor: Vec3 = Vec3.ZERO
        var progressIndex = 0
        var progressTick = 0L
        var strayTicks = 0
        var paranoiaTo: BlockPos? = null
        var pathRetry = 0
        var gripSent = -1
    }

    private val bodies = HashMap<UUID, Body>()

    fun init() {
        TickEvent.SERVER_POST.register(::tickServer)
        TickEvent.PLAYER_PRE.register(::steerFakePlayer)
        PlayerEvent.PLAYER_QUIT.register { bodies.remove(it.uuid) }
        receive(HELLO) { body, _ -> body.clientDriven = true }
        receive(STRUGGLE) { body, buf -> body.grip.addEffort(buf.readFloat()) }
        receive(SCREEN) { body, buf -> body.grip.screenOpen = buf.readBoolean() }
    }

    private fun receive(id: ResourceLocation, apply: (Body, FriendlyByteBuf) -> Unit) {
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, id) { buf, ctx ->
            val player = ctx.player as? ServerPlayer ?: return@registerReceiver
            val copy = FriendlyByteBuf(buf.copy())
            ctx.queue { apply(bodies.getOrPut(player.uuid) { Body() }, copy) }
        }
    }

    private fun tickServer(server: MinecraftServer) {
        for (player in server.playerList.players) tickBody(player, bodies.getOrPut(player.uuid) { Body() })
    }

    private fun tickBody(player: ServerPlayer, body: Body) {
        val free = !player.isAlive || player.isSpectator || SselithMadnessTomeSummon.isActive(player)
        val sselith = !free && body.grip.tick(player)
        sendGrip(player, body, sselith)
        val kind = when {
            free -> null
            sselith -> Kind.SSELITH
            player.hasEffect(EKEffects.ULDER_PARANOIA.get()) -> Kind.PARANOIA
            else -> null
        }
        if (kind != body.kind || (kind != null && body.ghost?.level() !== player.level())) switchTo(player, body, kind)
        when (kind) {
            Kind.SSELITH -> planSselith(player, body)
            Kind.PARANOIA -> planParanoia(player, body)
            null -> setMode(player, body, Mode.NONE)
        }
        if (kind != null) enforce(player, body)
    }

    private fun switchTo(player: ServerPlayer, body: Body, kind: Kind?) {
        body.sselith?.stop()
        body.ghost?.navigation?.stop()
        body.sselith = null
        body.ghost = null
        body.paranoiaTo = null
        body.pathRetry = 0
        setMode(player, body, Mode.NONE)
        body.kind = kind
        val level = player.level()
        when (kind) {
            Kind.SSELITH -> {
                val ghost = EKEntities.CATALOGER.get().create(level) ?: return
                PathFollower.syncGhost(ghost, player)
                val walk = SselithWalk(ghost)
                openedPoi(player, body)?.let(walk::beginAt)
                body.ghost = ghost
                body.sselith = walk
            }
            Kind.PARANOIA -> body.ghost = EntityType.ZOMBIE.create(level)
            null -> Unit
        }
    }

    private fun openedPoi(player: ServerPlayer, body: Body): BlockPos? {
        if (!body.grip.screenOpen) return null
        val hit = player.pick(OPENED_POI_REACH, 1f, false) as? BlockHitResult ?: return null
        return hit.blockPos.takeIf { player.level().getBlockState(it).`is`(CATALOGER_TARGETS) }
    }

    private fun planSselith(player: ServerPlayer, body: Body) {
        val walk = body.sselith ?: return
        val ghost = walk.ghost
        PathFollower.syncGhost(ghost, player)
        walk.tick()
        ghost.navigation.tick()
        val stare = walk.staringAt
        val path = ghost.navigation.path
        when {
            stare != null -> setStare(player, body, stare)
            path != null && !ghost.navigation.isDone -> setWalk(player, body, path)
            else -> setMode(player, body, Mode.STAND)
        }
        if (body.mode == Mode.WALK && player.tickCount % DUST_INTERVAL_TICKS == 0) SselithMadness.spawnMenuWalkDust(player)
    }

    private fun planParanoia(player: ServerPlayer, body: Body) {
        val ghost = body.ghost ?: return
        val dest = GrandStaffDread.destinationOf(player)
        if (dest == null) {
            setMode(player, body, Mode.NONE)
            return
        }
        if (GrandStaffDread.arrived(player, dest)) {
            ghost.navigation.stop()
            setMode(player, body, Mode.STAND)
            return
        }
        PathFollower.syncGhost(ghost, player)
        if (dest != body.paranoiaTo || ghost.navigation.isDone) {
            if (body.pathRetry > 0) {
                body.pathRetry--
            } else {
                body.pathRetry = PATH_RETRY_TICKS
                body.paranoiaTo = dest
                ghost.navigation.moveTo(dest.x + 0.5, dest.y.toDouble(), dest.z + 0.5, PARANOIA_SPEED)
            }
        }
        ghost.navigation.tick()
        val path = ghost.navigation.path
        if (path != null && !ghost.navigation.isDone) setWalk(player, body, path) else setMode(player, body, Mode.STAND)
    }

    private fun setWalk(player: ServerPlayer, body: Body, path: Path) {
        if (body.mode == Mode.WALK && body.path === path) return
        body.mode = Mode.WALK
        body.path = path
        body.progressIndex = path.nextNodeIndex
        body.progressTick = player.serverLevel().gameTime
        body.strayTicks = 0
        send(player, body)
    }

    private fun setStare(player: ServerPlayer, body: Body, at: Vec3) {
        if (body.mode == Mode.STARE && body.stareAt == at) return
        body.mode = Mode.STARE
        body.stareAt = at
        body.anchor = player.position()
        body.strayTicks = 0
        send(player, body)
    }

    private fun setMode(player: ServerPlayer, body: Body, mode: Mode) {
        if (body.mode == mode) return
        body.mode = mode
        body.path = null
        body.stareAt = null
        body.anchor = player.position()
        body.strayTicks = 0
        send(player, body)
    }

    private fun send(player: ServerPlayer, body: Body) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeByte(body.mode.ordinal)
        buf.writeByte((body.kind ?: Kind.SSELITH).ordinal)
        when (body.mode) {
            Mode.WALK -> {
                val path = checkNotNull(body.path)
                val count = minOf(path.nodeCount, MAX_NODES)
                buf.writeVarInt(count)
                for (i in 0 until count) buf.writeLong(path.getNodePos(i).asLong())
            }
            Mode.STARE -> {
                val at = checkNotNull(body.stareAt)
                buf.writeDouble(at.x).writeDouble(at.y).writeDouble(at.z)
            }
            Mode.NONE, Mode.STAND -> Unit
        }
        NetworkManager.sendToPlayer(player, DIRECTIVE, buf)
    }

    private fun sendGrip(player: ServerPlayer, body: Body, driving: Boolean) {
        val grip = body.grip
        val steps = (grip.struggleFraction.coerceIn(0f, 1f) * GRIP_FRACTION_STEPS).toInt()
        val packed = (if (driving) GRIP_DRIVING else 0) or
            (if (driving && grip.episode) GRIP_EPISODE else 0) or
            (steps shl GRIP_FRACTION_SHIFT)
        if (packed == body.gripSent) return
        body.gripSent = packed
        NetworkManager.sendToPlayer(player, GRIP, FriendlyByteBuf(Unpooled.buffer()).writeVarInt(packed))
    }

    /** Moves the player back when they leave the path, stall on it, or leave the place they must stand. */
    private fun enforce(player: ServerPlayer, body: Body) {
        when (body.mode) {
            Mode.WALK -> enforceWalk(player, body)
            Mode.STARE, Mode.STAND -> {
                val away = player.distanceToSqr(body.anchor) > ANCHOR_RADIUS * ANCHOR_RADIUS
                body.strayTicks = if (away) body.strayTicks + 1 else 0
                if (body.strayTicks > STRAY_TICKS) {
                    teleport(player, body.anchor)
                    body.strayTicks = 0
                }
            }
            Mode.NONE -> Unit
        }
    }

    private fun enforceWalk(player: ServerPlayer, body: Body) {
        val path = body.path ?: return
        if (path.isDone) return
        val now = player.serverLevel().gameTime
        val next = path.nextNodeIndex
        if (next > body.progressIndex) {
            body.progressIndex = next
            body.progressTick = now
        }
        val to = PathFollower.point(player, path.getNodePos(next))
        val from = if (next > 0) PathFollower.point(player, path.getNodePos(next - 1)) else to
        val stray = distanceToSegment(player, from, to) > STRAY_DIST
        body.strayTicks = if (stray) body.strayTicks + 1 else 0
        if (body.strayTicks <= STRAY_TICKS && now - body.progressTick <= STALL_TICKS) return
        teleport(player, if (stray) from else to)
        body.strayTicks = 0
        body.progressTick = now
    }

    private fun distanceToSegment(player: Player, a: Vec3, b: Vec3): Double {
        val ab = b.subtract(a)
        val lengthSq = ab.lengthSqr()
        val t = if (lengthSq < 1.0e-9) 0.0 else (player.position().subtract(a).dot(ab) / lengthSq).coerceIn(0.0, 1.0)
        return player.position().distanceTo(a.add(ab.scale(t)))
    }

    private fun teleport(player: ServerPlayer, at: Vec3) {
        player.connection.teleport(at.x, at.y, at.z, player.yRot, player.xRot)
    }

    /** Fake players have no client to walk them, so their own movement input carries out the directive. */
    private fun steerFakePlayer(player: Player) {
        if (player !is ServerPlayer) return
        val body = bodies[player.uuid] ?: return
        if (body.clientDriven) return
        val kind = body.kind ?: return
        when (body.mode) {
            Mode.WALK -> {
                val path = body.path ?: return
                if (path.isDone) return stand(player)
                val node = PathFollower.point(player, path.getNodePos(path.nextNodeIndex))
                face(player, PathFollower.yawTo(player, node.x, node.z), 0f)
                player.zza = kind.impulse
                player.xxa = 0f
                player.setJumping(PathFollower.needsJump(player, node))
            }
            Mode.STARE -> {
                val at = player.level().toWorldCoordinates(body.stareAt ?: return)
                face(player, PathFollower.yawTo(player, at.x, at.z), PathFollower.pitchTo(player, at))
                stand(player)
            }
            Mode.STAND -> stand(player)
            Mode.NONE -> Unit
        }
    }

    private fun face(player: Player, yaw: Float, pitch: Float) {
        player.yRot = yaw
        player.yHeadRot = yaw
        player.xRot = pitch
    }

    private fun stand(player: Player) {
        player.zza = 0f
        player.xxa = 0f
        player.setJumping(false)
    }
}
