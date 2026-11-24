package org.shipwrights.enderkinesis.client

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import dev.architectury.event.EventResult
import dev.architectury.event.events.client.ClientRawInputEvent
import org.lwjgl.glfw.GLFW
import java.util.Collections
import java.util.WeakHashMap
import org.joml.Quaterniond
import org.joml.Vector3d
import org.shipwrights.enderkinesis.block.QuiescentCradleBlock
import org.shipwrights.enderkinesis.block.QuiescentCradleFrame
import org.shipwrights.enderkinesis.block.QuiescentCradleNetwork
import org.shipwrights.enderkinesis.blockentity.QuiescentCradleBlockEntity
import org.valkyrienskies.mod.common.getShipManagingPos
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Client thread only. The server applies each turn again, so this only predicts the look. The rings have no block shape,
 * so the pick is our own ray test, and a press that grabs a ring is taken before vanilla can place or use anything.
 */
object QuiescentCradleDrag {

    /** Loaded cradles on this client; weak, so an unloaded block entity drops out by itself. */
    private val cradles: MutableSet<QuiescentCradleBlockEntity> = Collections.newSetFromMap(WeakHashMap())

    /** The mouse button that began the drag, or -1 for a drag begun from a keyboard USE. */
    private var mouseButton = -1
    private var pickedAngle = 0.0

    /** Distance along the look ray to the picked ring sample. */
    private var pickedT = 0.0

    /** The dragged ring's angle as the hand moves it, before any sneak snap. */
    private var rawAngle = 0.0
    private var keyWasDown = false

    // The result of [find].
    private var foundCradle: QuiescentCradleBlockEntity? = null
    private var foundRing = -1
    private var foundAngle = 0.0

    /** True when [find] found the ship marker, which resets the gimbal, in place of a ring. */
    private var foundMarker = false

    /** The cradle whose ring is under the crosshair, so its hover can be cleared. */
    private var hovered: QuiescentCradleBlockEntity? = null

    private var pos: BlockPos? = null
    private var ring = -1
    private var edgeOn = false
    private var lastAngle = 0.0
    private var lastAlong = 0.0
    private var pending = 0.0

    // Scratch, in the cradle frame.
    private val eye = Vector3d()
    private val look = Vector3d()
    private val centre = Vector3d()
    private val u = Vector3d()
    private val v = Vector3d()
    private val n = Vector3d()
    private val point = Vector3d()
    private val frame = Quaterniond()
    private val frameAngles = DoubleArray(QuiescentCradleFrame.RINGS)
    private val edgeOut = Vector3d()
    private val edgeTangent = Vector3d()
    private val edgePoint = Vector3d()

    fun init() {
        ClientRawInputEvent.MOUSE_CLICKED_PRE.register(
            ClientRawInputEvent.MouseClicked { mc, button, action, _ -> onMouse(mc, button, action) },
        )
    }

    fun track(cradle: QuiescentCradleBlockEntity) {
        cradles.add(cradle)
    }

    // Fires before vanilla sets the key mapping, on both loaders; Forge cancels only on interruptFalse.
    private fun onMouse(mc: Minecraft, button: Int, action: Int): EventResult {
        if (action == GLFW.GLFW_RELEASE && button == mouseButton) {
            end(mc.level)
            return EventResult.pass()
        }
        if (action != GLFW.GLFW_PRESS || pos != null || mc.screen != null || !mc.options.keyUse.matchesMouse(button)) {
            return EventResult.pass()
        }
        val level = mc.level ?: return EventResult.pass()
        val player = mc.player ?: return EventResult.pass()
        if (!find(level, player)) return EventResult.pass()
        if (!press(level, player)) mouseButton = button
        return EventResult.interruptFalse()
    }

    fun tick(mc: Minecraft) {
        val player = mc.player
        val level = mc.level
        if (player == null || level == null || mc.screen != null) {
            end(level)
            clearHover()
            return
        }
        val held = pos
        if (held == null) {
            updateHover(level, player)
            // USE bound to a key: no raw-input cancel there, so vanilla may also act on the press.
            val keyDown = mc.options.keyUse.isDown && !useIsMouse(mc)
            if (keyDown && !keyWasDown && find(level, player)) press(level, player)
            keyWasDown = keyDown
            return
        }
        val down = if (mouseButton >= 0) {
            GLFW.glfwGetMouseButton(mc.window.window, mouseButton) == GLFW.GLFW_PRESS
        } else {
            mc.options.keyUse.isDown
        }
        if (!down) {
            end(level)
            return
        }
        val cradle = level.getBlockEntity(held) as? QuiescentCradleBlockEntity
        val view = cradle?.view
        if (cradle == null || view == null || !cradle.holding || !inReach(level, player, held)) {
            end(level)
            return
        }
        rawAngle += sweep(level, player, cradle, view)
        // Sneak snaps the angle from the seize turn to 15° steps; the raw angle keeps following the hand.
        val shown = if (player.isShiftKeyDown) Math.round(rawAngle / SNAP_STEP) * SNAP_STEP else rawAngle
        val change = QuiescentCradleFrame.wrap(shown - view.angles[ring])
        if (change != 0.0) {
            view.angles[ring] += change
            pending += change
        }
        if (abs(pending) > MIN_SEND) flush(held)
    }

    private fun useIsMouse(mc: Minecraft): Boolean {
        for (button in GLFW.GLFW_MOUSE_BUTTON_1..GLFW.GLFW_MOUSE_BUTTON_LAST) {
            if (mc.options.keyUse.matchesMouse(button)) return true
        }
        return false
    }

    /**
     * Finds the ring or ship marker nearest along the look ray among the holding cradles in reach, into [foundCradle]
     * and the rest. A ring wins only where the ray meets it before the marker.
     */
    private fun find(level: ClientLevel, player: LocalPlayer): Boolean {
        foundCradle = null
        var bestT = Double.POSITIVE_INFINITY
        for (cradle in cradles) {
            if (cradle.isRemoved || cradle.level !== level || !cradle.holding) continue
            val view = cradle.view ?: continue
            if (!inReach(level, player, cradle.blockPos)) continue
            rayInCradleFrame(level, player, cradle.blockPos)
            val picked = pick(cradle, view)
            if (picked >= 0 && pickedT < bestT) {
                foundCradle = cradle
                foundRing = picked
                foundAngle = pickedAngle
                foundMarker = false
                bestT = pickedT
            }
            val markerT = markerHit(cradle, view)
            if (!markerT.isNaN() && markerT < bestT) {
                foundCradle = cradle
                foundMarker = true
                bestT = markerT
            }
        }
        return foundCradle != null
    }

    /** Acts on what [find] found: a marker press resets the gimbal; returns true then, as no drag starts. */
    private fun press(level: ClientLevel, player: LocalPlayer): Boolean {
        val cradle = foundCradle ?: return true
        if (foundMarker) {
            QuiescentCradleNetwork.sendReset(cradle.blockPos)
            return true
        }
        begin(level, player)
        return false
    }

    /** Distance along the look ray into the ship marker's box, a little enlarged; NaN when the ray misses it. */
    private fun markerHit(cradle: QuiescentCradleBlockEntity, view: QuiescentCradleView): Double {
        if (cradle.blockState.block !is QuiescentCradleBlock) return Double.NaN
        val facing = cradle.blockState.getValue(QuiescentCradleBlock.FACING)
        QuiescentCradleFrame.orreryCentre(facing, centre)
            .add(cradle.blockPos.x.toDouble(), cradle.blockPos.y.toDouble(), cradle.blockPos.z.toDouble())
        QuiescentCradleFrame.ringFrame(QuiescentCradleFrame.ROLL, facing, view.angles, frame).conjugate()
        frame.transform(point.set(eye).sub(centre))
        frame.transform(u.set(look))
        var near = Double.NEGATIVE_INFINITY
        var far = Double.POSITIVE_INFINITY
        for (axis in 0 until QuiescentCradleFrame.RINGS) {
            val half = QuiescentCradleFrame.MARKER_HALF[axis] + MARKER_TOLERANCE
            val origin = point.get(axis)
            val dir = u.get(axis)
            if (abs(dir) < PARALLEL) {
                if (abs(origin) > half) return Double.NaN
                continue
            }
            val t1 = (-half - origin) / dir
            val t2 = (half - origin) / dir
            near = maxOf(near, minOf(t1, t2))
            far = minOf(far, maxOf(t1, t2))
        }
        val entry = maxOf(near, 0.0)
        return if (far < entry) Double.NaN else entry
    }

    private fun clearHover() {
        hovered?.view?.let {
            it.hoverRing = -1
            it.markerHover = false
        }
        hovered = null
    }

    private fun updateHover(level: ClientLevel, player: LocalPlayer) {
        clearHover()
        if (!find(level, player)) return
        val cradle = foundCradle ?: return
        val view = cradle.view ?: return
        if (foundMarker) {
            view.markerHover = true
        } else {
            view.hoverRing = foundRing
            view.knobAngle = foundAngle
        }
        hovered = cradle
    }

    /** Grabs the ring that [find] found. */
    private fun begin(level: ClientLevel, player: LocalPlayer) {
        val cradle = foundCradle ?: return
        val view = cradle.view ?: return
        rayInCradleFrame(level, player, cradle.blockPos)
        pos = cradle.blockPos.immutable()
        ring = foundRing
        view.dragRing = foundRing
        view.knobAngle = foundAngle
        ringPlane(cradle, view, foundRing, own = false)
        edgeOn = abs(n.dot(look)) < EDGE_ON_DOT
        if (edgeOn) {
            nearEdge()
            lastAlong = alongEdge()
        } else {
            lastAngle = planeAngle()
        }
        pending = 0.0
        rawAngle = view.angles[foundRing]
    }

    private fun end(level: ClientLevel?) {
        val held = pos ?: return
        if (pending != 0.0) flush(held)
        (level?.getBlockEntity(held) as? QuiescentCradleBlockEntity)?.view?.dragRing = -1
        pos = null
        ring = -1
        mouseButton = -1
    }

    private fun flush(held: BlockPos) {
        QuiescentCradleNetwork.sendTurn(held, ring, pending.toFloat())
        pending = 0.0
    }

    private fun sweep(level: ClientLevel, player: LocalPlayer, cradle: QuiescentCradleBlockEntity, view: QuiescentCradleView): Double {
        rayInCradleFrame(level, player, cradle.blockPos)
        if (edgeOn) {
            val along = alongEdge()
            if (along.isNaN()) return 0.0
            val turn = (along - lastAlong) / QuiescentCradleFrame.ORRERY_RADII[ring]
            lastAlong = along
            return turn
        }
        // Measured in the parent frame: in the ring's own frame each applied turn would read back as an opposite
        // sweep on the next tick, and the ring would swing back and forth.
        ringPlane(cradle, view, ring, own = false)
        val angle = planeAngle()
        if (angle.isNaN()) return 0.0
        val turn = QuiescentCradleFrame.wrap(angle - lastAngle)
        lastAngle = angle
        return turn
    }

    private fun inReach(level: ClientLevel, player: LocalPlayer, held: BlockPos): Boolean {
        point.set(held.x + 0.5, held.y + 0.5, held.z + 0.5)
        level.getShipManagingPos(held)?.shipToWorld?.transformPosition(point)
        val reach = QuiescentCradleNetwork.MAX_DISTANCE
        return player.eyePosition.distanceToSqr(point.x, point.y, point.z) <= reach * reach
    }

    /** Eye and look direction into [eye] and [look], in the frame of the cradle at [at]. */
    private fun rayInCradleFrame(level: ClientLevel, player: LocalPlayer, at: BlockPos) {
        val eyePos = player.eyePosition
        val view = player.getViewVector(1f)
        eye.set(eyePos.x, eyePos.y, eyePos.z)
        look.set(view.x, view.y, view.z)
        val ship = level.getShipManagingPos(at) ?: return
        ship.worldToShip.transformPosition(eye)
        ship.worldToShip.transformDirection(look)
        look.normalize()
    }

    /**
     * Ring centre into [centre], in-plane axes into [u] and [v], and its axis into [n]. With [own] false, u and v leave
     * out the ring's own turn (its parent frame), so they stay put while the ring turns.
     */
    private fun ringPlane(cradle: QuiescentCradleBlockEntity, view: QuiescentCradleView, ring: Int, own: Boolean = true) {
        val facing = cradle.blockState.getValue(QuiescentCradleBlock.FACING)
        QuiescentCradleFrame.orreryCentre(facing, centre)
            .add(cradle.blockPos.x.toDouble(), cradle.blockPos.y.toDouble(), cradle.blockPos.z.toDouble())
        System.arraycopy(view.angles, 0, frameAngles, 0, frameAngles.size)
        if (!own) frameAngles[ring] = 0.0
        QuiescentCradleFrame.ringFrame(ring, facing, frameAngles, frame)
        frame.transform(QuiescentCradleFrame.axis(QuiescentCradleFrame.planeU(ring), u))
        frame.transform(QuiescentCradleFrame.axis(QuiescentCradleFrame.planeV(ring), v))
        frame.transform(QuiescentCradleFrame.axis(ring, n))
    }

    /**
     * Seen edge-on, the ring's near point moves along [edgeTangent] = n × [edgeOut] for a positive turn. Fixed for the
     * drag: the ring spins about n, so its near point and tangent stay put.
     */
    private fun nearEdge() {
        edgeOut.set(eye).sub(centre)
        edgeOut.fma(-edgeOut.dot(n), n)
        if (edgeOut.lengthSquared() < PARALLEL) edgeOut.set(u) else edgeOut.normalize()
        n.cross(edgeOut, edgeTangent)
        edgePoint.set(edgeOut).mul(QuiescentCradleFrame.ORRERY_RADII[ring]).add(centre)
    }

    /** How far the look ray's hit, on the plane through the near point facing the eye, lies along [edgeTangent]. */
    private fun alongEdge(): Double {
        val facing = look.dot(edgeOut)
        if (abs(facing) < PARALLEL) return Double.NaN
        val t = point.set(edgePoint).sub(eye).dot(edgeOut) / facing
        if (t <= 0.0) return Double.NaN
        return point.set(look).mul(t).add(eye).sub(edgePoint).dot(edgeTangent)
    }

    /** Angle of the look ray's hit on the ring plane, about the centre from u toward v; NaN when the ray misses. */
    private fun planeAngle(): Double {
        val along = look.dot(n)
        if (abs(along) < PARALLEL) return Double.NaN
        val t = point.set(centre).sub(eye).dot(n) / along
        if (t <= 0.0) return Double.NaN
        point.set(look).mul(t).add(eye).sub(centre)
        return atan2(point.dot(v), point.dot(u))
    }

    /** The ring whose circle passes nearest the look ray, within [PICK_TOLERANCE]; -1 when none does. */
    private fun pick(cradle: QuiescentCradleBlockEntity, view: QuiescentCradleView): Int {
        if (cradle.blockState.block !is QuiescentCradleBlock) return -1
        var best = -1
        var bestDistance = PICK_TOLERANCE * PICK_TOLERANCE
        for (candidate in 0 until QuiescentCradleFrame.RINGS) {
            ringPlane(cradle, view, candidate)
            val radius = QuiescentCradleFrame.ORRERY_RADII[candidate]
            for (sample in 0 until PICK_SAMPLES) {
                val a = 2.0 * Math.PI * sample / PICK_SAMPLES
                point.set(centre).fma(radius * Math.cos(a), u).fma(radius * Math.sin(a), v).sub(eye)
                val t = point.dot(look)
                if (t <= 0.0) continue
                val distance = point.fma(-t, look).lengthSquared()
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = candidate
                    pickedAngle = a
                    pickedT = t
                }
            }
        }
        if (best >= 0) {
            // The exact angle on the ring plane, where the ray meets it well; else the nearest sample.
            ringPlane(cradle, view, best)
            val exact = if (abs(n.dot(look)) >= EDGE_ON_DOT) planeAngle() else Double.NaN
            if (!exact.isNaN()) pickedAngle = exact
        }
        return best
    }

    private const val EDGE_ON_DOT = 0.15
    private val SNAP_STEP = Math.toRadians(15.0)

    /** Blocks added round the ship marker's box, so the small marker is easy to click. */
    private const val MARKER_TOLERANCE = 0.01
    private const val PARALLEL = 1e-6

    private const val MIN_SEND = 1e-4
    private const val PICK_SAMPLES = 48

    /** Blocks between the look ray and a small ring that still grabs it. */
    private const val PICK_TOLERANCE = 0.04
}
