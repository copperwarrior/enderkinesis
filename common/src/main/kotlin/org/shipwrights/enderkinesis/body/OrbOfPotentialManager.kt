package org.shipwrights.enderkinesis.body

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import org.joml.Matrix3d
import org.joml.Quaterniond
import org.joml.Vector3d
import org.valkyrienskies.core.api.bodies.VsBodyCreateData
import org.valkyrienskies.core.api.world.ServerShipWorld
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.vsCore

/**
 * Server-side spawn helper for Orb of Potential VS Bodies.
 *
 *  Called from [org.shipwrights.enderkinesis.blockentity.FractalProjectorBlockEntity]'s
 *  one-shot server tick: when the BE first ticks (chunk just loaded, or
 *  the structure was just painted), the BE calls [spawnAt] with its
 *  block-pos and current `fractal_type` value, then deletes itself by
 *  clearing the block to air. The body persists across saves via VS2's
 *  body store; the marker block exists only as long as it takes the
 *  server to consume it.
 *
 *  No periodic scanning, no dedup table — atomic spawn-then-clear in
 *  the same server tick avoids both. If a crash happens between
 *  `spawnAt` and the block clear, the next load will tick the BE again
 *  and produce a duplicate orb at that pos; we accept that as the
 *  cost of keeping the spawn path simple.
 */
object OrbOfPotentialManager {

    private const val SPHERE_RADIUS: Double = 1.5
    /** Light body mass — orbs are decorative; we still need a non-zero
     *  mass so any future force-applying attachment has something to
     *  multiply. 100 kg with a 1.5 m sphere is roughly a small boulder. */
    private const val MASS: Double = 100.0

    /** Create a non-ship [VsBody] with sphere collision centred on
     *  [pos] (block-centre offset). Returns the new body id, or null
     *  if VS2's ship world wasn't available (very early load). */
    fun spawnAt(level: ServerLevel, pos: BlockPos): Long? {
        val shipWorld = level.shipObjectWorld as? ServerShipWorld ?: return null
        val centre = Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        val body = shipWorld.createBody(
            VsBodyCreateData(
                // VS2's `Level.dimensionId` is `registry:namespace:path` —
                // "minecraft:dimension:enderkinesis:sselith_repertory" — not
                // the plain `namespace:path` you get from
                // `level.dimension().location().toString()`. Using the
                // bare ResourceLocation registered the body under a phantom
                // dimension Krunch never simulated, so the sphere had no
                // collision against ships or terrain.
                dimensionId = level.dimensionId,
                inertiaData = vsCore.newShipInertiaData(
                    Vector3d(),                       // centre-of-mass at body origin
                    MASS,
                    Matrix3d().identity().scale(MASS) // diagonal MOI
                ),
                kinematics = vsCore.newBodyKinematics(
                    Vector3d(),                       // velocity
                    Vector3d(),                       // angular velocity
                    centre,                           // world position
                    Quaterniond(),                    // identity rotation
                    Vector3d(1.0),                    // unit scale
                    Vector3d(),                       // positionInModel
                ),
                collisionShape = vsCore.newSphereBodyShape(SPHERE_RADIUS),
                // Dynamic body. Gravity gets cancelled per phys tick by
                // [OrbGravityCanceller], which subscribes to
                // `vsApi.physTickEvent` and applies an upward force of
                // `mass * gravity` to every body in [OrbBodyRegistry].
                // The body still responds to other forces (collisions,
                // future interactions) — only gravity is suppressed.
                isStatic = false,
                collisionMask = -1,                   // collide with everything
                staticFrictionCoefficient = 0.3,
                dynamicFrictionCoefficient = 0.3,
                restitutionCoefficient = 0.1,
            )
        )
        // Non-ship VsBodies don't expose AttachmentHolder the way
        // LoadedServerShip does, so identity ("this body is an orb")
        // is tracked off-body — the marker BlockEntity holds the
        // bodyId, and that's the identity test the renderer and the
        // future entity-collision pass will use.
        return body.id
    }
}
