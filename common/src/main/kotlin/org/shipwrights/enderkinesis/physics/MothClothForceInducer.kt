package org.shipwrights.enderkinesis.physics

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import com.fasterxml.jackson.databind.JsonNode
import org.joml.Vector3d
import org.shipwrights.enderkinesis.dimension.MothClothShipRegistry
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.ships.PhysShip
import org.valkyrienskies.core.api.ships.ShipPhysicsListener
import org.valkyrienskies.core.api.world.PhysLevel
import java.io.DataInput
import java.io.DataOutput
import java.io.IOException
import org.valkyrienskies.core.api.util.PhysTickOnly

/**
 * PD altitude controller pulling any ship carrying Moth Cloth up to
 * [MothClothShipRegistry.TARGET_Y].
 *
 * Control law (accelerations, so behaviour is mass-independent):
 *
 *   a_cmd = KP · (targetY − y) − KD · v_y
 *   F     = clamp(m · (GRAVITY + a_cmd), 0, FORCE_PER_BLOCK · clothCount)
 *
 * The gravity feed-forward makes the hover exact at zero error (no
 * integral term needed), the KD term bleeds vertical speed off as the
 * target approaches instead of blasting past it, and the clamp keeps
 * the cloth's physical capacity meaningful: too little cloth for the
 * hull and the ship rises slowly or not at all; cloth can't push down.
 *
 * The total is split equally per block and applied at each block's
 * shipyard position via `applyWorldForceToModelPos` (world-frame up at a
 * model-space point), so cloth placement determines the ship's balance.
 */
@OptIn(VsBeta::class, PhysTickOnly::class)
class MothClothForceInducer : ShipPhysicsListener {

    /** Packed shipyard positions of the cloth. [MothClothShipRegistry] replaces the whole array
     *  on the server thread, so the physics thread never sees a half-updated set. */
    @Volatile var positions: LongArray = LongArray(0)

    override fun physTick(physShip: PhysShip, physLevel: PhysLevel) {
        if (physShip.isStatic || physShip.mass <= 0.0) return
        val positions = this.positions
        val count = positions.size
        if (count == 0) return

        val mass = physShip.mass
        val y = physShip.transform.position.y()
        val vy = physShip.velocity.y()
        val error = MothClothShipRegistry.TARGET_Y - y

        val accelCmd = KP * error - KD * vy
        val desired = mass * (GRAVITY + accelCmd)
        val maxLift = FORCE_PER_BLOCK * count
        val total = desired.coerceIn(0.0, maxLift)

        // Split equally per block and apply at each block's shipyard
        // position — world-frame ("invariant") up at a model-space point,
        // so the lift never rotates with the ship and asymmetric cloth
        // placement produces real torque (an unbalanced panel tilts the
        // ship) while balanced cloth lifts cleanly.
        forceScratch.set(0.0, total / count, 0.0)
        for (packed in positions) {
            posScratch.set(
                net.minecraft.core.BlockPos.getX(packed) + 0.5,
                net.minecraft.core.BlockPos.getY(packed) + 0.5,
                net.minecraft.core.BlockPos.getZ(packed) + 0.5,
            )
            physShip.applyWorldForceToModelPos(forceScratch, posScratch)
        }

        // Minimal angular damping so pendulum swing from off-axis lift
        // decays instead of ringing forever. τ = −k·(R·I·Rᵀ)·ω —
        // momentOfInertia is model-space, ω and applyWorldTorque are
        // world-frame, so the rotation sandwich bridges the frames.
        // Inertia scaling keeps the decay rate size-independent; k is
        // deliberately small so ships still swing, they just settle.
        val omega = physShip.angularVelocity
        if (omega.lengthSquared() > 1e-6) {
            rotScratch.set(physShip.transform.shipToWorldRotation)
            inertiaWorldScratch.set(rotScratch).mul(physShip.momentOfInertia)
            rotScratch.transpose()
            inertiaWorldScratch.mul(rotScratch)
            inertiaWorldScratch.transform(torqueScratch.set(omega)).mul(-ANGULAR_DAMP)
            physShip.applyWorldTorque(torqueScratch)
        }
    }
    private val forceScratch = Vector3d()
    private val posScratch = Vector3d()
    private val torqueScratch = Vector3d()
    private val rotScratch = org.joml.Matrix3d()
    private val inertiaWorldScratch = org.joml.Matrix3d()


    /** Saves the cloth positions, so the ship lifts from its first physics frame after a load. */
    @JsonValue
    fun saveBytes(): ByteArray = AttachmentIo.encode { Serializer.serialize(this, it) }

    object Serializer {
        private const val FORMAT = 1

        fun serialize(attachment: MothClothForceInducer, output: DataOutput) {
            output.writeInt(FORMAT)
            AttachmentIo.writeLongArray(output, attachment.positions)
        }

        // No positions = inert until the registry rescan.
        fun deserialize(input: DataInput): MothClothForceInducer {
            val inducer = MothClothForceInducer()
            try {
                require(input.readInt() == FORMAT) { "unknown format" }
                inducer.positions = AttachmentIo.readLongArray(input)
            } catch (e: IOException) {
                AttachmentIo.warnDropped(MothClothForceInducer::class.java, e)
            } catch (e: IllegalArgumentException) {
                AttachmentIo.warnDropped(MothClothForceInducer::class.java, e)
            }
            return inducer
        }
    }

    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun fromSave(node: JsonNode): MothClothForceInducer =
            AttachmentIo.decode(node, MothClothForceInducer::class.java, { MothClothForceInducer() }, Serializer::deserialize)

        private const val GRAVITY: Double = 10.0

        /** Proportional gain (1/s²): commanded acceleration per block of
         *  altitude error. ω = √KP = 0.5 rad/s natural frequency. */
        private const val KP: Double = 0.25

        /** Derivative gain (1/s). Critical damping at 2·√KP = 1.0; a bit
         *  above for a soft, overshoot-free approach. During the climb
         *  this sets the equilibrium ascent speed: v ≈ KP·error/KD. */
        private const val KD: Double = 1.2

        /** Lift capacity in Newtons per moth cloth block — clamps the PD
         *  output. VS2 gravity is 10 m/s² and unmapped blocks (including
         *  moth cloth itself) default to ~1000 kg, so anything under
         *  10 kN/block couldn't lift the cloth's own weight. 50 kN = the
         *  block lifts itself plus ~4 t of hull. */
        private const val FORCE_PER_BLOCK: Double = 50_000.0

        /** Angular damping rate (1/s): ω decays with a ~3 s time constant.
         *  Kept small on purpose — unbalanced ships visibly swing before
         *  settling rather than feeling gyro-stabilised. */
        private const val ANGULAR_DAMP: Double = 0.3
    }
}
