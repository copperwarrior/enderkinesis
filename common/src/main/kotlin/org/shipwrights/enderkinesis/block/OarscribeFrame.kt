package org.shipwrights.enderkinesis.block

import net.minecraft.core.Direction
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.AttachFace
import org.joml.Quaternionf
import org.joml.Quaternionfc
import org.joml.Vector3d
import org.joml.Vector3dc
import org.joml.Vector3f

/**
 * Turns from the Oarscribe model to the block. Model +Y is the hand axis. The hand turns about it in
 * [OarscribeBlock.TURNS] steps, and model +X, turned with the hand and mirrored, is the way the ship moves.
 */
object OarscribeFrame {

    private const val HORIZONTALS = 4
    private val FACES = AttachFace.values()

    // Same turn as the blockstate: vanilla BlockModelRotation is rotateYXZ(-y, -x, 0).
    private val MOUNTS: Array<Quaternionf> = Array(FACES.size * HORIZONTALS) { index ->
        val facingYRot = Direction.from2DDataValue(index % HORIZONTALS).toYRot()
        val (x, y) = when (FACES[index / HORIZONTALS]) {
            AttachFace.FLOOR -> 0f to facingYRot + 180f
            AttachFace.CEILING -> 180f to facingYRot
            AttachFace.WALL -> 90f to facingYRot + 180f
        }
        Quaternionf().rotateYXZ(-Math.toRadians(y.toDouble()).toFloat(), -Math.toRadians(x.toDouble()).toFloat(), 0f)
    }

    private val HAND_TURNS: Array<Quaternionf> = Array(OarscribeBlock.TURNS) { turn ->
        Quaternionf().rotateY(turn * (2f * Math.PI.toFloat() / OarscribeBlock.TURNS))
    }

    // Indexed by mount, turn and mirror.
    private val RIGHTS: Array<Vector3dc> = Array(MOUNTS.size * OarscribeBlock.TURNS * 2) { index ->
        val mirrored = index % 2 == 1
        val turn = index / 2 % OarscribeBlock.TURNS
        val mount = index / 2 / OarscribeBlock.TURNS
        val right = Vector3f(if (mirrored) -1f else 1f, 0f, 0f)
        MOUNTS[mount].transform(HAND_TURNS[turn].transform(right))
        Vector3d(right)
    }

    private fun mountIndex(state: BlockState): Int =
        state.getValue(OarscribeBlock.FACE).ordinal * HORIZONTALS + state.getValue(OarscribeBlock.FACING).get2DDataValue()

    /** Turns model axes about the block centre into block axes. */
    fun mount(state: BlockState): Quaternionfc = MOUNTS[mountIndex(state)]

    /** Turns model axes about the hand axis, before [mount]. */
    fun handTurn(state: BlockState): Quaternionfc = HAND_TURNS[state.getValue(OarscribeBlock.TURN)]

    /** A unit vector in block axes. The ship moves this way. */
    fun right(state: BlockState): Vector3dc = RIGHTS[
        (mountIndex(state) * OarscribeBlock.TURNS + state.getValue(OarscribeBlock.TURN)) * 2 +
            if (state.getValue(OarscribeBlock.MIRRORED)) 1 else 0
    ]
}
