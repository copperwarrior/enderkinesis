package org.shipwrights.enderkinesis.client

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3f
import org.shipwrights.enderkinesis.block.OarscribeBlock
import org.shipwrights.enderkinesis.block.OarscribeFrame
import org.shipwrights.enderkinesis.blockentity.OarscribeBlockEntity
import org.shipwrights.enderkinesis.registry.EKParticles
import org.valkyrienskies.mod.common.getShipManagingPos
import kotlin.math.sqrt

/** Leaves one glyph in the world for each block the pen tip moves while the Oarscribe writes. */
object OarscribeGlyphs {

    /** Client thread only. The particle and the light copy face and line, so reuse is safe. */
    private val turn = Quaternionf()
    private val point = Vector3f()
    private val tip = Vector3d()
    private val south = Vector3f()
    private val normal = Vector3d()
    private val across = Vector3d()
    private val face = Vector3f()
    private val line = Vector3f()

    fun tick(oarscribe: OarscribeBlockEntity, level: Level, pos: BlockPos, state: BlockState) {
        if (!state.getValue(OarscribeBlock.WRITING)) {
            oarscribe.trailLive = false
            return
        }
        turn.set(OarscribeFrame.mount(state)).mul(OarscribeFrame.handTurn(state))
        val penPoint = OarscribeModel.penPoint(state.getValue(OarscribeBlock.MIRRORED))
        turn.transform(point.set(penPoint).div(PIXELS).sub(HALF, HALF, HALF))
        // Shipyard coordinates are too large for float precision, so add the block position as doubles.
        tip.set(point).add(pos.x + HALF_BLOCK, pos.y + HALF_BLOCK, pos.z + HALF_BLOCK)
        // The glyphs lie in the model's south face, which is under the pen point.
        turn.transform(south.set(0f, 0f, 1f))
        normal.set(south.x.toDouble(), south.y.toDouble(), south.z.toDouble())
        across.set(OarscribeFrame.right(state))
        level.getShipManagingPos(pos)?.transform?.let {
            it.shipToWorld.transformPosition(tip)
            it.shipToWorldRotation.transform(normal)
            it.shipToWorldRotation.transform(across)
        }
        face.set(normal.x.toFloat(), normal.y.toFloat(), normal.z.toFloat())
        line.set(across.x.toFloat(), across.y.toFloat(), across.z.toFloat())
        if (!oarscribe.trailLive) {
            oarscribe.trailLive = true
            moveTrail(oarscribe, tip.x, tip.y, tip.z)
            spawn(oarscribe, face, line)
            return
        }
        stepTrail(oarscribe, tip, face, line)
    }

    private fun stepTrail(oarscribe: OarscribeBlockEntity, tip: Vector3d, face: Vector3f, line: Vector3f) {
        val dx = tip.x - oarscribe.trailX
        val dy = tip.y - oarscribe.trailY
        val dz = tip.z - oarscribe.trailZ
        val distance = sqrt(dx * dx + dy * dy + dz * dz)
        val steps = (distance / GLYPH_SPACING).toInt()
        if (steps > MAX_GLYPHS_PER_TICK) {
            // Too fast to fill the gap. Start again at the pen.
            moveTrail(oarscribe, tip.x, tip.y, tip.z)
            spawn(oarscribe, face, line)
            return
        }
        val step = GLYPH_SPACING / distance
        repeat(steps) {
            moveTrail(oarscribe, oarscribe.trailX + dx * step, oarscribe.trailY + dy * step, oarscribe.trailZ + dz * step)
            spawn(oarscribe, face, line)
        }
    }

    private fun moveTrail(oarscribe: OarscribeBlockEntity, x: Double, y: Double, z: Double) {
        oarscribe.trailX = x
        oarscribe.trailY = y
        oarscribe.trailZ = z
    }

    private fun spawn(oarscribe: OarscribeBlockEntity, face: Vector3f, line: Vector3f) {
        val glyph = Minecraft.getInstance().particleEngine.createParticle(
            EKParticles.oarscribeGlyph(), oarscribe.trailX, oarscribe.trailY, oarscribe.trailZ, 0.0, 0.0, 0.0,
        )
        (glyph as? OarscribeGlyphParticle)?.orient(face, line)
        OarscribeGlyphLight.add(oarscribe.trailX, oarscribe.trailY, oarscribe.trailZ, face, line)
    }

    private const val HALF = 0.5f
    private const val HALF_BLOCK = 0.5
    private const val PIXELS = 16f

    /** Each glyph is one block wide, so one block between glyphs makes a solid line. */
    private const val GLYPH_SPACING = 1.0
    private const val MAX_GLYPHS_PER_TICK = 8
}
