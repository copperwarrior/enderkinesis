package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientPlayerEvent
import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.client.Minecraft
import net.minecraft.client.player.Input
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.client.PossessedSteering.applyPitch
import org.shipwrights.enderkinesis.client.PossessedSteering.applyYaw
import org.shipwrights.enderkinesis.client.PossessedSteering.ease
import org.shipwrights.enderkinesis.client.PossessedSteering.lookAtPoint
import org.shipwrights.enderkinesis.client.PossessedSteering.standStill
import org.shipwrights.enderkinesis.client.PossessedSteering.walkForward
import org.shipwrights.enderkinesis.possession.PathFollower
import org.shipwrights.enderkinesis.possession.Possession
import org.shipwrights.enderkinesis.possession.Possession.Kind
import org.shipwrights.enderkinesis.possession.Possession.Mode
import org.shipwrights.enderkinesis.registry.EKSounds
import org.valkyrienskies.mod.common.toWorldCoordinates

/**
 * Walks the local player's body by the server's [Possession] directives, and reports the player's
 * struggle and open screens. The server decides when the body is held and corrects a body that leaves
 * its path. Client thread only. Driven from `LocalPlayerCatalogerWalkMixin`, after `Input.tick`.
 */
object PossessionClient {

    private const val CUE_VOLUME = 0.5f

    /** Cursor travel that counts as one key press, and the most that one tick can add. */
    private const val MOUSE_PIXELS_PER_PRESS = 800.0
    private const val MOUSE_MAX_PER_TICK = 0.5f

    /** Intensity of the tome-summon shaders while the madness holds the body. 1 = the full effect. */
    private const val VIGNETTE_MAX = 0.2f
    private const val VIGNETTE_FADE_TICKS = 40
    private const val VIGNETTE_STEP = VIGNETTE_MAX / VIGNETTE_FADE_TICKS

    /** The vignette follows the struggle faster than the plain fade, so each press shows. */
    private const val VIGNETTE_STRUGGLE_STEP = VIGNETTE_STEP * 4

    private var mode = Mode.NONE
    private var kind = Kind.SSELITH
    private var nodes: List<BlockPos> = emptyList()
    private var index = 0
    private var lookAt: Vec3 = Vec3.ZERO
    private var newPath = false

    private var driving = false
    private var episode = false
    private var struggleFraction = 0f
    private var vignette = 0f
    private var vignetteO = 0f

    private var lastKeys = 0
    private var lastMouseX = 0.0
    private var lastMouseY = 0.0
    private var screenSent: Boolean? = null

    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, Possession.DIRECTIVE) { buf, ctx ->
            val newMode = Mode.entries[buf.readByte().toInt().coerceIn(0, Mode.entries.size - 1)]
            val newKind = Kind.entries[buf.readByte().toInt().coerceIn(0, Kind.entries.size - 1)]
            val path = if (newMode == Mode.WALK) readNodes(buf) else emptyList()
            val at = if (newMode == Mode.STARE) Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()) else Vec3.ZERO
            ctx.queue {
                mode = newMode
                kind = newKind
                nodes = path
                lookAt = at
                newPath = true
            }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, Possession.GRIP) { buf, ctx ->
            val packed = buf.readVarInt()
            ctx.queue { applyGrip(packed) }
        }
        ClientPlayerEvent.CLIENT_PLAYER_JOIN.register {
            screenSent = null
            NetworkManager.sendToServer(Possession.HELLO, FriendlyByteBuf(Unpooled.buffer()))
        }
    }

    private fun readNodes(buf: FriendlyByteBuf): List<BlockPos> {
        val count = buf.readVarInt()
        require(count in 0..Possession.MAX_NODES) { "Possession path too long: $count" }
        return List(count) { BlockPos.of(buf.readLong()) }
    }

    private fun applyGrip(packed: Int) {
        val wasEpisode = episode
        driving = packed and Possession.GRIP_DRIVING != 0
        episode = packed and Possession.GRIP_EPISODE != 0
        struggleFraction = (packed shr Possession.GRIP_FRACTION_SHIFT) / Possession.GRIP_FRACTION_STEPS.toFloat()
        if (episode && !wasEpisode) playFlat(EKSounds.SSELITH_MADNESS_GRIP.get(), CUE_VOLUME)
        if (!episode && wasEpisode) playFlat(EKSounds.SSELITH_MADNESS_RELEASE.get(), CUE_VOLUME)
    }

    fun onDisconnect() {
        mode = Mode.NONE
        nodes = emptyList()
        index = 0
        driving = false
        episode = false
        struggleFraction = 0f
        vignette = 0f
        vignetteO = 0f
        lastKeys = 0
        screenSent = null
    }

    fun onAiStep(player: LocalPlayer) {
        reportStruggle(player.input)
        reportScreen()
        tickVignette()
        // The tome summon drives the camera itself; two drivers make it shake.
        if (PlayerTomeSummonClient.get(player) != null || !player.isAlive || player.isSpectator) return
        when (mode) {
            Mode.NONE -> Unit
            Mode.WALK -> walk(player)
            Mode.STARE -> {
                standStill(player)
                lookAtPoint(player, player.level().toWorldCoordinates(lookAt), kind.ease)
            }
            Mode.STAND -> standStill(player)
        }
    }

    private fun walk(player: LocalPlayer) {
        if (newPath) {
            newPath = false
            index = PathFollower.nearest(player, nodes)
        }
        index = PathFollower.advance(player, nodes, index)
        if (index >= nodes.size) {
            standStill(player)
            return
        }
        val node = PathFollower.point(player, nodes[index])
        applyYaw(player, ease(player.yRot, PathFollower.yawTo(player, node.x, node.z), kind.ease))
        applyPitch(player, ease(player.xRot, 0f, kind.ease))
        walkForward(player, kind.impulse, PathFollower.needsJump(player, node))
    }

    /** New presses since the last tick, read before the walk overwrites the input. A held key does not count. */
    private fun reportStruggle(input: Input) {
        val keys = keyMask(input)
        val effort = Integer.bitCount(keys and lastKeys.inv()) + mouseEffort()
        lastKeys = keys
        if (!episode || effort <= 0f) return
        NetworkManager.sendToServer(Possession.STRUGGLE, FriendlyByteBuf(Unpooled.buffer()).apply { writeFloat(effort) })
    }

    private fun reportScreen() {
        val open = Minecraft.getInstance().screen != null
        if (open == screenSent) return
        screenSent = open
        NetworkManager.sendToServer(Possession.SCREEN, FriendlyByteBuf(Unpooled.buffer()).apply { writeBoolean(open) })
    }

    private fun tickVignette() {
        vignetteO = vignette
        val target = if (driving) VIGNETTE_MAX * (1f - struggleFraction) else 0f
        val step = if (struggleFraction > 0f) VIGNETTE_STRUGGLE_STEP else VIGNETTE_STEP
        vignette += (target - vignette).coerceIn(-step, step)
    }

    fun vignetteIntensity(partialTick: Float): Float = Mth.lerp(partialTick, vignetteO, vignette)

    // The cursor position stays live while the mouse is grabbed, and the walk never writes it.
    private fun mouseEffort(): Float {
        val mouse = Minecraft.getInstance().mouseHandler
        val dx = mouse.xpos() - lastMouseX
        val dy = mouse.ypos() - lastMouseY
        lastMouseX = mouse.xpos()
        lastMouseY = mouse.ypos()
        return (Math.sqrt(dx * dx + dy * dy) / MOUSE_PIXELS_PER_PRESS).toFloat().coerceAtMost(MOUSE_MAX_PER_TICK)
    }

    private fun keyMask(input: Input): Int =
        (if (input.up) 1 else 0) or
            (if (input.down) 2 else 0) or
            (if (input.left) 4 else 0) or
            (if (input.right) 8 else 0) or
            (if (input.jumping) 16 else 0) or
            (if (input.shiftKeyDown) 32 else 0)
}
