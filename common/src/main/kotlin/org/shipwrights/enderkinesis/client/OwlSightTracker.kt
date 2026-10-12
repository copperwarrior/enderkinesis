package org.shipwrights.enderkinesis.client

import dev.architectury.networking.NetworkManager
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.sounds.SoundSource
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.joml.Vector3d
import org.joml.Vector3f
import org.shipwrights.enderkinesis.block.OwlStatueEscape
import org.shipwrights.enderkinesis.client.model.StatueOwlModel
import org.shipwrights.enderkinesis.registry.EKSounds
import org.valkyrienskies.mod.common.getShipManagingPos
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.exp

/** Per-owl head pose and line-of-sight history. [OwlSightPasses] measures sight on the GPU
 *  and feeds results back here. All state is render-thread only. */
object OwlSightTracker {
    const val MAX_TRACKED: Int = 32

    private const val HEAD_PIVOT_Y: Float = -3f / 16f
    private const val PRUNE_INTERVAL: Long = 1024L
    private const val MIN_LOOK_DISTANCE: Float = 1.0e-4f
    private const val MEAN_UNSEEN_MILLIS_PER_TURN: Double = 10_000.0
    private const val REVEAL_VOLUME: Float = 0.35f
    private const val DRONING_HORN_INDEX: Int = 6
    private const val MAX_PRIMED: Int = 64

    private class OwlState {
        var xRot = 0f
        var yRot = 0f
        var lastSeenFrame = -1L
        var heldThrough = -1L
        var lastRenderedFrame = 0L
        var lastRollMillis = 0L
        var tracking = false
        var turned = false
    }

    private val states = Long2ObjectOpenHashMap<OwlState>()

    // Not an OwlState: the prune removes a state that has no render for PRUNE_INTERVAL frames.
    private val primed = LongOpenHashSet()
    private var levelIdentity = 0
    private var frameCounter = 0L
    private var resolvedFrame = 0L

    internal var candidateCount = 0
        private set
    internal val candidateKeys = LongArray(MAX_TRACKED)
    internal val candidateMatrices = Array(MAX_TRACKED) { Matrix4f() }
    internal val candidateXRot = FloatArray(MAX_TRACKED)
    internal val candidateYRot = FloatArray(MAX_TRACKED)

    private val scratchLinear = Matrix3f()
    private val scratchDir = Vector3f()

    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, OwlStatueEscape.ARRIVED_PACKET) { buf, ctx ->
            val key = buf.readLong()
            ctx.queue { prime(key) }
        }
    }

    /** The owl at [key] looks at the camera from its first frame, so the first sight of it is a reveal. */
    private fun prime(key: Long) {
        resetIfLevelChanged()
        if (primed.size >= MAX_PRIMED) primed.clear()
        states.remove(key)
        primed.add(key)
    }

    /** [modelView] is the BER pose after the statue transform, so the camera sits at its origin. */
    fun poseOwl(key: Long, modelView: Matrix4f, model: StatueOwlModel) {
        resetIfLevelChanged()
        val frame = frameCounter + 1
        // A new owl has no measured frames yet; hold it facing forward until it has one.
        val now = Util.getMillis()
        val state = states.get(key) ?: OwlState().also {
            if (primed.remove(key)) it.tracking = true else it.heldThrough = frame
            it.lastRollMillis = now
            states.put(key, it)
        }
        state.lastRenderedFrame = frame
        when {
            state.lastSeenFrame == resolvedFrame -> {
                if (state.turned) reveal(key)
                state.turned = false
                state.tracking = false
                state.lastRollMillis = now
            }
            state.heldThrough >= resolvedFrame -> state.lastRollMillis = now
            else -> {
                if (!state.tracking) state.tracking = rollTurn(state, now)
                if (state.tracking) {
                    lookAtCamera(state, modelView)
                    state.turned = true
                }
            }
        }
        model.setBone2Rotation(state.xRot, state.yRot)

        if (candidateCount < MAX_TRACKED && !IrisCompat.isShaderPackInUse()) {
            val i = candidateCount++
            candidateKeys[i] = key
            candidateMatrices[i].set(modelView)
            candidateXRot[i] = state.xRot
            candidateYRot[i] = state.yRot
        } else {
            // No measurement this frame, so hold the pose rather than risk a visible turn.
            state.heldThrough = frame
        }
    }

    internal fun startFrame(): Long {
        frameCounter++
        if (frameCounter % PRUNE_INTERVAL == 0L) {
            val cutoff = frameCounter - PRUNE_INTERVAL
            states.values.removeIf { it.lastRenderedFrame < cutoff }
        }
        return frameCounter
    }

    internal fun holdCandidates(frame: Long) {
        for (i in 0 until candidateCount) states.get(candidateKeys[i])?.heldThrough = frame
    }

    internal fun markSeen(key: Long, frame: Long) {
        states.get(key)?.lastSeenFrame = frame
    }

    internal fun markResolved(frame: Long) {
        if (frame > resolvedFrame) resolvedFrame = frame
    }

    internal fun clearCandidates() {
        candidateCount = 0
    }

    /** Off-screen time counts too: the roll covers all time since the owl was last seen. */
    private fun rollTurn(state: OwlState, now: Long): Boolean {
        val elapsed = (now - state.lastRollMillis).coerceAtLeast(0L)
        state.lastRollMillis = now
        val chance = 1.0 - exp(-elapsed / MEAN_UNSEEN_MILLIS_PER_TURN)
        return ThreadLocalRandom.current().nextDouble() < chance
    }

    private fun reveal(key: Long) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val pos = BlockPos.of(key)
        val world = Vector3d(pos.x + 0.5, pos.y + 1.5, pos.z + 0.5)
        level.getShipManagingPos(pos)?.transform?.shipToWorld?.transformPosition(world)
        level.playLocalSound(
            world.x, world.y, world.z, EKSounds.PRISMATIC_HORN_CAVES[DRONING_HORN_INDEX].get(),
            SoundSource.AMBIENT, REVEAL_VOLUME, 1f, false,
        )
        OwlsMarkClient.apply()
    }

    private fun lookAtCamera(state: OwlState, modelView: Matrix4f) {
        val dir = modelView.transformPosition(0f, HEAD_PIVOT_Y, 0f, scratchDir).negate()
        scratchLinear.set(modelView).invert().transform(dir)
        val length = dir.length()
        if (length < MIN_LOOK_DISTANCE) return
        // Inverts ModelPart's rotationZYX applied to the beak direction, model -Z.
        state.yRot = atan2(-dir.x, -dir.z)
        state.xRot = asin((dir.y / length).coerceIn(-1f, 1f))
    }

    private fun resetIfLevelChanged() {
        val identity = System.identityHashCode(Minecraft.getInstance().level)
        if (identity == levelIdentity) return
        levelIdentity = identity
        states.clear()
        primed.clear()
        candidateCount = 0
    }
}
