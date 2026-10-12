package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.resources.model.BakedModel
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.DirectionalBlock
import org.shipwrights.enderkinesis.blockentity.EchoCannonBlockEntity
import kotlin.math.sin

/**
 * Draws the Echo Cannon's **barrel** as a dynamic overlay on top of the
 * static chunk-mesh base — the base geometry (everything except the barrel)
 * lives in `models/block/echo_cannon.json` and renders via the normal
 * chunk-section bake; the barrel lives in `models/block/echo_cannon_barrel.json`
 * and is drawn here so it can slide backwards on fire.
 *
 * Animation shape: on the fire tick, [EchoCannonBlockEntity] stamps the
 * game-tick into its synced `recoilStartTick`. This renderer reads that
 * value and, for `recoilElapsed ∈ [0, RECOIL_DURATION_TICKS]`, translates
 * the barrel back along its own facing axis by
 * `RECOIL_MAX_UNITS × cos(t · π/2)`. At `t = 0` the barrel is fully back;
 * at `t = 1` it's flush against rest. Cosine gives a soft, non-linear ease
 * — snappy departure, gentle settle — with no discontinuity at either end.
 *
 * Facing handling mirrors the blockstate's variant rotations (x-90 for
 * down, x-270 for up, y-180/270/90 for south/west/east; north = identity).
 * That way the BER-drawn barrel lines up with the chunk-mesh base at any
 * facing without hand-picking translation vectors per direction.
 */
class EchoCannonRenderer(@Suppress("UNUSED_PARAMETER") ctx: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<EchoCannonBlockEntity> {

    override fun render(
        be: EchoCannonBlockEntity,
        partialTick: Float,
        ps: PoseStack,
        bufferSource: net.minecraft.client.renderer.MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        val barrelModel = capturedBarrelModel ?: return
        val level = be.level ?: return
        val state = be.blockState
        val facing = state.getValue(DirectionalBlock.FACING)

        // Charge-forward (barrel eases forward while charge is winding up)
        // and recoil-back (barrel snaps back on fire, eases forward to rest)
        // combine into a single signed local-Z displacement.
        val chargeForward = computeChargeOffset(
            level.gameTime, partialTick, be.clientChargeStartTick,
        )
        val recoilBack = computeRecoilOffset(
            level.gameTime, partialTick, be.clientRecoilStartTick,
        )
        val barrelZUnits = recoilBack - chargeForward
        // Shiver: as the charge builds the barrel oscillates rotationally
        // around its own axis (post-facing local Z), amplitude ramping with
        // charge progress. Reads as the cannon straining against the winding
        // sonic charge.
        val shiverDeg = computeShiverAngle(
            level.gameTime, partialTick, be.clientChargeStartTick,
        )

        ps.pushPose()
        // Rotate around block center so the barrel orientation matches the
        // static base for this FACING variant.
        ps.translate(0.5, 0.5, 0.5)
        applyFacingRotation(ps, facing)
        // +Z = deeper into the block (recoil back); -Z = out of the block
        // (charge forward). Divide by 16 since offsets are in model units.
        ps.translate(0.0, 0.0, barrelZUnits.toDouble() / 16.0)
        // Roll around the local Z (= barrel axis in world after facing) so
        // the barrel shivers around its own centerline, not the block cube.
        if (shiverDeg != 0f) ps.mulPose(Axis.ZP.rotationDegrees(shiverDeg))
        ps.translate(-0.5, -0.5, -0.5)

        val br = Minecraft.getInstance().blockRenderer
        val vc = bufferSource.getBuffer(RenderType.solid())
        // BlockState-agnostic render: state param is used only for tint
        // lookups (which we don't use) and neighbour occlusion (we pass the
        // cannon's own state — the barrel model has no tinted faces).
        br.modelRenderer.renderModel(
            ps.last(), vc, state, barrelModel,
            1f, 1f, 1f, packedLight, packedOverlay,
        )
        ps.popPose()
    }

    /** Recoil ease-out (smoothstep-reverse). MAX × (1 − smoothstep(t)),
     *  where `smoothstep(t) = t²(3 − 2t)` has zero slope at both endpoints.
     *  Gives a soft peel-off from full recoil and a gentle settle at rest —
     *  reads heavier than a plain cosine curve. */
    private fun computeRecoilOffset(
        gameTime: Long, partialTick: Float, recoilStartTick: Long,
    ): Float {
        if (recoilStartTick == NO_RECOIL) return 0f
        val elapsed = (gameTime - recoilStartTick).toFloat() + partialTick
        if (elapsed < 0f || elapsed >= RECOIL_DURATION_TICKS) return 0f
        val t = elapsed / RECOIL_DURATION_TICKS
        return RECOIL_MAX_UNITS * (1f - smoothstep(t))
    }

    /** Charge ease-in-out (smoothstep). MAX × smoothstep(t). Zero slope at
     *  both endpoints, so the barrel eases both AWAY from rest at charge
     *  start and INTO the forward position just before the fire tick —
     *  matches the settle-in feel of the recoil return. */
    private fun computeChargeOffset(
        gameTime: Long, partialTick: Float, chargeStartTick: Long,
    ): Float {
        if (chargeStartTick == NO_CHARGE) return 0f
        val elapsed = (gameTime - chargeStartTick).toFloat() + partialTick
        if (elapsed < 0f || elapsed >= CHARGE_DURATION_TICKS) return 0f
        val t = elapsed / CHARGE_DURATION_TICKS
        return CHARGE_MAX_UNITS * smoothstep(t)
    }

    /** Classic smoothstep: `3t² − 2t³` = `t²(3 − 2t)`. Zero at t=0 and 1
     *  at t=1, with zero slope at both endpoints. */
    private fun smoothstep(t: Float): Float = t * t * (3f - 2f * t)

    /** Shiver oscillation (roll around the barrel's own axis) during
     *  charge-up. Angle = MAX × envelope(t) × sin(elapsed × freq), where
     *  `envelope(t) = sin(t · π)` — starts at 0, peaks at t=0.5, returns to
     *  0 at t=1. Reads as the cannon straining mid-wind-up and then
     *  "settling into aim" as the shot is about to fire. */
    private fun computeShiverAngle(
        gameTime: Long, partialTick: Float, chargeStartTick: Long,
    ): Float {
        if (chargeStartTick == NO_CHARGE) return 0f
        val elapsed = (gameTime - chargeStartTick).toFloat() + partialTick
        if (elapsed < 0f || elapsed >= CHARGE_DURATION_TICKS) return 0f
        val t = elapsed / CHARGE_DURATION_TICKS
        val envelope = sin(t * Math.PI.toFloat())
        return SHIVER_MAX_DEG * envelope * sin(elapsed * SHIVER_FREQ_RAD_PER_TICK)
    }

    /** Match the blockstate JSON's variant rotations so the BER-drawn
     *  barrel lines up with the chunk-mesh base at every facing.
     *
     *  Mojang blockstate `"y": N` is CW-from-above; the right-hand-convention
     *  Y rotation used by `Axis.YP` is CCW-from-above, so horizontal facings
     *  are negated. Same pattern MagicMissileLauncherRenderer uses. */
    private fun applyFacingRotation(ps: PoseStack, facing: Direction) {
        when (facing) {
            Direction.NORTH -> {}
            Direction.EAST -> ps.mulPose(Axis.YP.rotationDegrees(-90f))
            Direction.SOUTH -> ps.mulPose(Axis.YP.rotationDegrees(-180f))
            Direction.WEST -> ps.mulPose(Axis.YP.rotationDegrees(-270f))
            Direction.DOWN -> ps.mulPose(Axis.XP.rotationDegrees(-90f))
            Direction.UP -> ps.mulPose(Axis.XP.rotationDegrees(90f))
        }
    }

    companion object {
        /** Sentinel matching [EchoCannonBlockEntity.NO_RECOIL]. */
        private const val NO_RECOIL: Long = Long.MIN_VALUE
        /** Sentinel matching [EchoCannonBlockEntity.NO_CHARGE]. */
        private const val NO_CHARGE: Long = Long.MIN_VALUE

        /** Full recoil distance in model units (16 per block; converted to
         *  block units at the translate site). Matches the "3 units" spec. */
        private const val RECOIL_MAX_UNITS: Float = 3f

        /** Return-to-rest duration in game ticks (20 tps → 20 ticks = 1 s). */
        private const val RECOIL_DURATION_TICKS: Float = 20f

        /** Charge-forward distance in model units. The barrel eases forward
         *   1 unit as it winds up, so at the fire tick there's a full
         *  4-unit visual snap into the -3-unit recoil position. */
        private const val CHARGE_MAX_UNITS: Float = 1f

        /** Charge duration in game ticks — matches
         *  [EchoCannonBlockEntity.CHARGE_TICKS] so the barrel finishes its
         *  forward push at the exact moment the beam fires. */
        private const val CHARGE_DURATION_TICKS: Float = 50f

        /** Peak shiver amplitude in degrees at full charge. 6° reads as a
         *  visible strain without looking wobbly. */
        private const val SHIVER_MAX_DEG: Float = 6f

        /** Shiver frequency in rad/tick. 3.0 rad/tick = 3·20/(2π) ≈ 9.5 Hz —
         *  fast enough to read as a shake, not a slow wobble. */
        private const val SHIVER_FREQ_RAD_PER_TICK: Float = 3f

        /** Resource location of the barrel model. Registered as an extra
         *  model on both loaders so it gets baked; the platform-specific
         *  bake hook stamps [capturedBarrelModel] as soon as baking
         *  completes. */
        @JvmStatic
        val BARREL_MODEL_LOCATION: ResourceLocation =
            ResourceLocation("enderkinesis", "block/echo_cannon_barrel")

        /** Populated by the platform bake hook (Fabric
         *  `ModelLoadingPlugin.modifyModelAfterBake`, Forge
         *  `ModelEvent.ModifyBakingResult`) as soon as the barrel model
         *  finishes baking. Null before that first bake; the renderer
         *  no-ops in that window. */
        @Volatile
        @JvmStatic
        var capturedBarrelModel: BakedModel? = null

        /** Shared no-alloc random for `getQuads()` calls if we ever need
         *  them from this renderer's helpers. Vanilla model renderers use
         *  a random per invocation; a fixed-seed shared source is fine
         *  here since the barrel model has no random-variant quads. */
        @Suppress("unused")
        private val quadRandom: RandomSource = RandomSource.create(0L)
    }
}
