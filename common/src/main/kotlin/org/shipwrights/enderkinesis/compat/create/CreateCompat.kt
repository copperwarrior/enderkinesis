package org.shipwrights.enderkinesis.compat.create

import com.simibubi.create.api.behaviour.movement.MovementBehaviour
import com.simibubi.create.content.contraptions.behaviour.MovementContext
import dev.architectury.platform.Platform
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.shipwrights.enderkinesis.block.VoidHarnessBlock
import org.shipwrights.enderkinesis.blockentity.AetherPadBlockEntity
import org.shipwrights.enderkinesis.blockentity.VoidHarnessBlockEntity
import org.shipwrights.enderkinesis.registry.EKBlocks
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.vsCore
import kotlin.math.exp
import kotlin.math.sqrt

object CreateCompat {

    private val LOG = com.mojang.logging.LogUtils.getLogger()

    fun init() {
        if (!Platform.isModLoaded("create")) return
        try {
            vsCore.registerAttachment(
                vsCore.newAttachmentRegistrationBuilder(ContraptionHarnessForceProvider::class.java)
                    .useTransientSerializer()
                    .build()
            )
            vsCore.registerAttachment(
                vsCore.newAttachmentRegistrationBuilder(AetherPadForceProvider::class.java)
                    .useTransientSerializer()
                    .build()
            )
            MovementBehaviour.REGISTRY.register(EKBlocks.VOID_HARNESS.get(), HarnessActor)
            MovementBehaviour.REGISTRY.register(EKBlocks.AETHER_PAD.get(), AetherPadActor)
        } catch (t: Throwable) {
            LOG.warn("[EK] Create interop failed; ship actors will not act on contraptions ({}).", t.message)
        }
    }

    private object HarnessActor : MovementBehaviour {
        override fun tick(context: MovementContext) = applyHarnessPullFromContraption(context)
    }

    private object AetherPadActor : MovementBehaviour {
        override fun tick(context: MovementContext) = applyAetherPadFromContraption(context)
    }

    /** Drive ship pulls from a contraption-mounted harness.
     *
     *  ## The shipyard-vs-world frame trick (was the bug for ages)
     *
     *  When a contraption rides a VS2 ship, VS2's `MixinAbstractContraptionEntity.tickActors`
     *  sets `MovementContext.position` to `toGlobalVector(blockCenter, 1)` — Create's
     *  contraption-relative-to-anchor projection, with NO `shipToWorld` applied. Because the
     *  contraption entity itself lives in the host ship's shipyard chunks, `position` ends up
     *  in the host ship's **shipyard coordinate space**, NOT in true world space. VS2's own
     *  `MixinSawMovementBehaviour` confirms this by calling
     *  `getShipManagingPos(context.world, context.position)` — a lookup that only makes sense
     *  if `context.position` is a shipyard coord.
     *
     *  That single fact disambiguates the two modes for us:
     *  - **`getLoadedShipManagingPos(BlockPos.containing(pos))` returns non-null** → we're on
     *    that host ship and `pos` is shipyard coords. Project through the host's `shipToWorld`
     *    to get the true world position and apply a same-ship steering pull.
     *  - **null** → free-world contraption, `pos` is in real world coords. Apply the world-
     *    placed 3D exp-falloff pull to every nearby loaded ship.
     */
    private fun applyHarnessPullFromContraption(context: MovementContext) {
        val world = context.world as? ServerLevel ?: return
        val state = context.state
        val power = state.getValue(VoidHarnessBlock.POWER)
        if (power <= 0) return
        val pos = context.position

        val peakForce = VoidHarnessBlockEntity.FORCE_MAGNITUDE * (power / 15.0)

        val hostShip = world.getLoadedShipManagingPos(BlockPos.containing(pos.x, pos.y, pos.z))
        if (hostShip != null) {
            applyHostShipSteering(hostShip, pos, peakForce)
            return
        }

        val r2 = VoidHarnessBlockEntity.PULL_RADIUS * VoidHarnessBlockEntity.PULL_RADIUS
        for (ship in world.shipObjectWorld.loadedShips) {
            val com = ship.transform.positionInWorld
            val dx = pos.x - com.x(); val dy = pos.y - com.y(); val dz = pos.z - com.z()
            val d2 = dx * dx + dy * dy + dz * dz
            if (d2 > r2) continue
            val len = sqrt(d2)
            if (len < 1.0e-3) continue
            val u = len / VoidHarnessBlockEntity.SOFT_CORE_RADIUS
            val softCore = 1.0 - exp(-u * u)
            val magnitude = peakForce * exp(-len / VoidHarnessBlockEntity.EXP_FALLOFF_SCALE) * softCore
            val provider = ship.getOrPutAttachment(ContraptionHarnessForceProvider::class.java) {
                ContraptionHarnessForceProvider()
            }
            provider.accumulateForce(
                dx / len * magnitude,
                dy / len * magnitude,
                dz / len * magnitude,
            )
        }
    }

    /**
     * Aether Pad mirror of the harness actor. On every contraption tick we:
     *   1. Read pad `state` + `position` + `world` from the MovementContext.
     *   2. Resolve the host VS2 ship (if any) via the shipyard-frame-aware trick: a non-null
     *      `getLoadedShipManagingPos(BlockPos.containing(pos))` means we're riding that ship
     *      and `pos` is in its shipyard frame; null means free-world contraption with `pos`
     *      already in true world coords.
     *   3. Project the pad's world position + world `FACING` direction.
     *   4. Push entities in the forward cone (shared helper in [AetherPadBlockEntity]).
     *   5. Raycast ground; if within range, stage a lift force on the host ship via
     *      [AetherPadForceProvider]. Free-world contraptions skip the lift (no ship to act on).
     */
    private fun applyAetherPadFromContraption(context: MovementContext) {
        val world = context.world as? Level ?: return
        val state = context.state
        val pos = context.position
        val facing = state.getValue(BlockStateProperties.FACING)
        val power = state.getValue(BlockStateProperties.POWER)
        if (power <= 0) return

        val hostShip = world.getLoadedShipManagingPos(BlockPos.containing(pos.x, pos.y, pos.z))
        val padWorld = Vector3d(pos.x, pos.y, pos.z)

        // CRITICAL: the block's `FACING` is in the block's *local* placement coords. The
        // CONTRAPTION'S rotation transforms local vectors to the contraption's current
        // frame. Without this step, a contraption that's been rotated since assembly
        // emits its beam in the original placement direction — visually obvious as soon
        // as the contraption turns. VS2's `MixinAbstractContraptionEntity` (line 221 of
        // preTickActors) sets `context.rotation` to `v -> applyRotation(v, 1)`, so the
        // operator is always populated.
        //
        // For free-world contraptions: the operator's output is in WORLD frame directly.
        // For VS2-mounted contraptions: the operator's output is in the host's SHIPYARD
        // frame (same as `position`), so we still need the host's `shipToWorldRotation`
        // on top to reach world.
        val rotatedFacing = context.rotation.apply(
            Vec3(facing.stepX.toDouble(), facing.stepY.toDouble(), facing.stepZ.toDouble()),
        )
        val facingWorld = Vector3d(rotatedFacing.x, rotatedFacing.y, rotatedFacing.z)
        if (hostShip != null) {
            hostShip.transform.shipToWorld.transformPosition(padWorld)
            hostShip.transform.shipToWorldRotation.transform(facingWorld)
        }

        AetherPadBlockEntity.pushEntitiesForward(world, padWorld, facingWorld, power)

        if (world !is ServerLevel) return

        AetherPadBlockEntity.spawnBeamParticles(world, padWorld, facingWorld, power)

        val serverHostShip = world.getLoadedShipManagingPos(BlockPos.containing(pos.x, pos.y, pos.z))
            ?: return
        val groundDist = AetherPadBlockEntity.computeGroundDistance(world, padWorld, facingWorld)
        if (groundDist >= AetherPadBlockEntity.GROUND_MAX_RANGE) return

        val proximity = 1.0 - (groundDist / AetherPadBlockEntity.GROUND_MAX_RANGE)
        val mag = AetherPadBlockEntity.springForcePerPad(
            serverHostShip.inertiaData.mass, power,
        ) * proximity
        val fx = -facingWorld.x * mag
        val fy = -facingWorld.y * mag
        val fz = -facingWorld.z * mag
        val provider = serverHostShip.getOrPutAttachment(AetherPadForceProvider::class.java) {
            AetherPadForceProvider()
        }
        val padKey = BlockPos.containing(pos.x, pos.y, pos.z).asLong()
        provider.stage(padKey, fx, fy, fz, pos.x, pos.y, pos.z)
    }

    /** Same-ship steering pull applied to the host VS2 ship a contraption rides. The harness's
     *  position arrives here already in the host's shipyard frame (because that's what VS2
     *  hands MovementContext) — we use it directly as the off-COM application point and
     *  project it through `shipToWorld` to get the world position the phys-tick force vector
     *  aims at. Mirrors [VoidHarnessBlockEntity.applyPullAtHarness] exactly. */
    private fun applyHostShipSteering(ship: LoadedServerShip, harnessShipyardPos: Vec3, peakForce: Double) {
        val harnessWorld = Vector3d(harnessShipyardPos.x, harnessShipyardPos.y, harnessShipyardPos.z)
        ship.transform.shipToWorld.transformPosition(harnessWorld)
        val provider = ship.getOrPutAttachment(ContraptionHarnessForceProvider::class.java) {
            ContraptionHarnessForceProvider()
        }
        provider.setOffComSteering(
            harnessWorld.x, harnessWorld.y, harnessWorld.z,
            harnessShipyardPos.x, harnessShipyardPos.y, harnessShipyardPos.z,
            peakForce,
        )
    }
}
