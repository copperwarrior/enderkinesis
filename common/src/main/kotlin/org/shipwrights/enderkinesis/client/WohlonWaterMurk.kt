package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.vertex.PoseStack
import java.nio.ByteBuffer
import java.util.function.IntSupplier
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.EffectInstance
import net.minecraft.core.BlockPos
import net.minecraft.tags.FluidTags
import net.minecraft.util.Mth
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.material.FlowingFluid
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.level.material.FogType
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.client.volume.ChunkColumnMask
import org.shipwrights.enderkinesis.client.volume.DepthTextureCopy
import org.shipwrights.enderkinesis.client.volume.VolumeComposite
import org.shipwrights.enderkinesis.client.volume.VolumeDepth
import org.shipwrights.enderkinesis.client.volume.VolumeEffect
import org.shipwrights.enderkinesis.client.volume.VolumeTextures
import org.shipwrights.enderkinesis.client.volume.VolumeView
import org.shipwrights.enderkinesis.dimension.WohlonBiomePresence
import org.shipwrights.enderkinesis.dimension.Wohlonnogondonia

/**
 * Murky Wohlon water seen from above. Main depth is copied before the translucent pass; a screen
 * pass then adds murk by the water depth behind each water-surface pixel. A column mask of the top
 * [MAX_RUNS] water runs keeps glass and ice out. Streaks in the murk flow out from the world centre. Render
 * thread only.
 */
object WohlonWaterMurk {

    /** A chunk plus the widest vanilla blend margin (7) on each side. */
    private const val MAX_BLEND = 7
    private const val BLEND_GRID = 16 + 2 * MAX_BLEND

    // The shader's COLUMN_WRAP is WINDOW_CHUNKS * 16 - 1.
    private const val WINDOW_CHUNKS = 32
    // Past the window the mask wraps to other columns, so the murk fades out over this before its edge.
    private const val EDGE_FADE = 32f
    private const val MAX_RUNS = 2
    // A run top code is y - baseY + 1, so 0 reads as no run, as do unloaded texels.
    private const val MAX_TOP = 255
    private const val MAX_BELOW = 127
    private const val FALLING_BIT = 0x80
    private const val STAGE_DONE = 2 * MAX_RUNS
    // A later run must start this close below the lowest block of the run above it.
    private const val NEXT_RUN_REACH = 32
    // Per mask: a diagonal window crossing (63 slots) takes 4 frames, not one long frame.
    private const val MAX_REBUILDS = 16

    /** Murk for each block of water behind the surface. */
    private const val DENSITY = 0.35f
    // Below 1, so the water texture still shows faintly.
    private const val MAX_MURK = 0.8f
    // The Noise3D uniform in wohlon_water_murk.json holds this unit.
    private const val NOISE_UNIT = 4
    // Keeps float precision in the shader: 64 tiles of the shader's 48-block FLOW_TILE, so no seam.
    private const val NOISE_PERIOD = 3072.0
    private const val TIME_PERIOD_SEC = 4000.0

    private val opaqueDepth = DepthTextureCopy()
    // Per run: top code, then blocks below the top with FALLING_BIT, so falling faces get murk and a thin clamp.
    private val runs = ChunkColumnMask(
        WINDOW_CHUNKS, ::fillRuns, raw = true, unloadedValue = 0, rgba = true, maxRebuilds = MAX_REBUILDS,
    )
    // Per column: even = looking for run stage / 2, odd = inside it.
    private val runStage = IntArray(256)
    // Per column: the lowest Y where the next run may start.
    private val searchFloor = IntArray(256)
    // Wohlon share of each column over the biome blend radius, so the murk fades like vanilla water colour.
    private val weights = ChunkColumnMask(WINDOW_CHUNKS, ::fillWeights, raw = true, unloadedValue = 0, maxRebuilds = MAX_REBUILDS)
    private var blendRadius = -1
    private val blendFlags = BooleanArray(BLEND_GRID * BLEND_GRID)
    private val blendSums = IntArray((BLEND_GRID + 1) * (BLEND_GRID + 1))
    private val view = VolumeView()
    private val effect = VolumeEffect(
        "wohlon_water_murk",
        "OpaqueDepth" to IntSupplier { depthTexture },
        "WeightMask" to IntSupplier { weights.textureId },
        "RunMask" to IntSupplier { runs.textureId },
    )

    private var copied = false
    private var depthTexture = -1
    private var baseY = 0
    private var codeLevel: ClientLevel? = null
    private var camBlockX = 0
    private var camBlockZ = 0
    private val colour = FloatArray(3)
    private val pushUniforms: (EffectInstance) -> Unit = ::pushUniforms

    @JvmStatic
    fun markChunkDirty(chunkX: Int, chunkZ: Int) {
        runs.markDirty(chunkX, chunkZ)
        markBiomesDirty(chunkX, chunkZ)
    }

    // fillRuns reads only water, so a biome resend rebuilds only the weights.
    @JvmStatic
    fun markBiomesDirty(chunkX: Int, chunkZ: Int) {
        // The blend reaches up to 7 blocks into the neighbouring chunks.
        for (dx in -1..1) for (dz in -1..1) weights.markDirty(chunkX + dx, chunkZ + dz)
    }

    // fillRuns reads only water and fillWeights only biomes, so Wohlon growth into air must not rebuild either.
    @JvmStatic
    fun onBlockChanged(pos: BlockPos, oldState: BlockState, newState: BlockState) {
        if (waterKind(oldState.fluidState) != waterKind(newState.fluidState)) runs.markDirty(pos.x shr 4, pos.z shr 4)
    }

    private fun waterKind(fluid: FluidState): Int = when {
        !fluid.`is`(FluidTags.WATER) -> 0
        isFalling(fluid) -> 2
        else -> 1
    }

    private fun isFalling(fluid: FluidState): Boolean =
        !fluid.isSource && fluid.hasProperty(FlowingFluid.FALLING) && fluid.getValue(FlowingFluid.FALLING)

    /** Call just before the translucent chunk layer. */
    @JvmStatic
    fun beforeTranslucent(main: RenderTarget) {
        copied = false
        if (!active()) return
        // VolumeDepth runs first and copies the same depth on a split frame.
        val shared = VolumeDepth.opaqueDepthTextureId()
        depthTexture = if (shared >= 0) {
            shared
        } else {
            opaqueDepth.copyFrom(main)
            opaqueDepth.textureId
        }
        copied = true
    }

    // Fabulous draws water into its own target, so main depth never holds the water surface.
    private fun active(): Boolean {
        val mc = Minecraft.getInstance()
        if (mc.level == null || !WohlonBiomePresence.nearby || Minecraft.useShaderTransparency()) return false
        if (IrisCompat.isShaderPackInUse()) return false
        return mc.gameRenderer.mainCamera.fluidInCamera == FogType.NONE
    }

    /** Call just after the translucent chunk layer. */
    @JvmStatic
    fun afterTranslucent(poseStack: PoseStack, projection: Matrix4f, camera: Camera) {
        if (!copied) return
        copied = false
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val eff = effect.get() ?: return
        if (level !== codeLevel) {
            codeLevel = level
            // The byte holds water heights from 96 below sea level up to 158 above it.
            baseY = level.seaLevel - 96
        }
        val cam = camera.position
        camBlockX = Mth.floor(cam.x)
        camBlockZ = Mth.floor(cam.z)
        val radius = mc.options.biomeBlendRadius().get()
        if (radius != blendRadius) {
            blendRadius = radius
            weights.unbind()
        }
        runs.refresh(level, camBlockX shr 4, camBlockZ shr 4)
        weights.refresh(level, camBlockX shr 4, camBlockZ shr 4)
        view.update(projection, poseStack.last().pose(), cam.x, cam.y, cam.z)
        colour[0] = WohlonAtmosphere.murkRed()
        colour[1] = WohlonAtmosphere.murkGreen()
        colour[2] = WohlonAtmosphere.murkBlue()
        val main = mc.mainRenderTarget
        VolumeTextures.bind3D(NOISE_UNIT, WohlonNoiseTexture.id3D())
        VolumeComposite.drawOverMain(main, eff, pushUniforms)
        VolumeTextures.bind3D(NOISE_UNIT, 0)
        VolumeComposite.restore(main)
    }

    fun onDisconnect() {
        runs.unbind()
        weights.unbind()
        blendRadius = -1
        codeLevel = null
        copied = false
    }

    private fun pushUniforms(eff: EffectInstance) {
        view.pushInvViewProj(eff)
        eff.safeGetUniform("CamBlockWrap").set(runs.wrap(camBlockX), runs.wrap(camBlockZ))
        eff.safeGetUniform("CamFrac").set((view.camX - camBlockX).toFloat(), (view.camZ - camBlockZ).toFloat())
        eff.safeGetUniform("CamY").set(view.camY.toFloat())
        eff.safeGetUniform("BaseY").set(baseY.toFloat())
        eff.safeGetUniform("MurkColor").set(colour[0], colour[1], colour[2])
        eff.safeGetUniform("Density").set(DENSITY)
        eff.safeGetUniform("MaxMurk").set(MAX_MURK)
        eff.safeGetUniform("FogRange").set(WohlonAtmosphere.terrainFogStart, WohlonAtmosphere.terrainFogEnd)
        eff.safeGetUniform("MaskReach").set(runs.maxMarch - EDGE_FADE, runs.maxMarch)
        // The flow runs out from the world centre (0, 0).
        eff.safeGetUniform("CamFromCentre").set(view.camX.toFloat(), view.camZ.toFloat())
        eff.safeGetUniform("CamNoise").set(view.camX.mod(NOISE_PERIOD).toFloat(), view.camZ.mod(NOISE_PERIOD).toFloat())
        val phase = (System.nanoTime() * 1e-9) % (2.0 * TIME_PERIOD_SEC)
        eff.safeGetUniform("Time").set((if (phase <= TIME_PERIOD_SEC) phase else 2.0 * TIME_PERIOD_SEC - phase).toFloat())
    }

    /**
     * Share of Wohlon biome in the `(2r+1)²` columns around each column, as vanilla blends water
     * colour (r = the biome blend option), sampled at the water line. Biome cells are 4×4, so the
     * chunk and its margin read only a few dozen cells; a summed-area table gives every column's box.
     */
    private fun fillWeights(chunk: LevelChunk, out: ByteBuffer) {
        val level = Minecraft.getInstance().level ?: return
        val r = blendRadius.coerceIn(0, MAX_BLEND)
        val side = 16 + 2 * r
        val x0 = chunk.pos.minBlockX - r
        val z0 = chunk.pos.minBlockZ - r
        val qy = (level.seaLevel - 1) shr 2
        for (gz in 0 until side) {
            for (gx in 0 until side) {
                val qx = (x0 + gx) shr 2
                val qz = (z0 + gz) shr 2
                // Reuse the previous column's answer while still inside the same biome cell.
                blendFlags[gz * side + gx] = if (gx > 0 && (x0 + gx - 1) shr 2 == qx) {
                    blendFlags[gz * side + gx - 1]
                } else if (gz > 0 && (z0 + gz - 1) shr 2 == qz) {
                    blendFlags[(gz - 1) * side + gx]
                } else {
                    level.getNoiseBiome(qx, qy, qz).`is`(Wohlonnogondonia.BIOME_KEY)
                }
            }
        }
        val w = side + 1
        java.util.Arrays.fill(blendSums, 0)
        for (gz in 0 until side) {
            var row = 0
            for (gx in 0 until side) {
                if (blendFlags[gz * side + gx]) row++
                blendSums[(gz + 1) * w + gx + 1] = blendSums[gz * w + gx + 1] + row
            }
        }
        val box = (2 * r + 1) * (2 * r + 1)
        for (z in 0 until 16) {
            for (x in 0 until 16) {
                val ax = x; val az = z
                val bx = x + 2 * r + 1; val bz = z + 2 * r + 1
                val count = blendSums[bz * w + bx] - blendSums[az * w + bx] - blendSums[bz * w + ax] + blendSums[az * w + ax]
                out.put(z * 16 + x, (count * 255 / box).toByte())
            }
        }
    }

    /**
     * The top [MAX_RUNS] water runs of each column, top-down. Sections whose palette holds no water
     * end every open run and are skipped whole, so a tall canopy costs nothing.
     */
    private fun fillRuns(chunk: LevelChunk, out: ByteBuffer) {
        for (i in 0 until 256 * 4) out.put(i, 0)
        runStage.fill(0)
        var open = 256
        val sections = chunk.sections
        for (idx in sections.indices.reversed()) {
            val section = sections[idx]
            val sectionY = chunk.getSectionYFromSectionIndex(idx) shl 4
            if (section.hasOnlyAir() || !section.states.maybeHas { it.fluidState.`is`(FluidTags.WATER) }) {
                for (i in 0 until 256) {
                    val stage = runStage[i]
                    if (stage == STAGE_DONE) continue
                    if (stage and 1 == 1) endRun(i, sectionY + 16)
                    // No water here, so the next block to scan is sectionY - 1.
                    if (runStage[i] == STAGE_DONE || (runStage[i] > 0 && sectionY - 1 < searchFloor[i])) {
                        runStage[i] = STAGE_DONE
                        open--
                    }
                }
            } else {
                for (i in 0 until 256) {
                    if (runStage[i] == STAGE_DONE) continue
                    for (ly in 15 downTo 0) {
                        if (!scanBlock(out, i, section.getFluidState(i and 15, ly, i shr 4), sectionY + ly)) continue
                        open--
                        break
                    }
                }
            }
            // Runs that start below baseY cannot get a code, and older runs have hit MAX_BELOW.
            if (open == 0 || sectionY < baseY - MAX_BELOW) return
        }
    }

    private fun endRun(i: Int, lowestWaterY: Int) {
        runStage[i]++
        searchFloor[i] = lowestWaterY - NEXT_RUN_REACH
    }

    /** Steps column [i] one block down; true when the column has just finished. */
    private fun scanBlock(out: ByteBuffer, i: Int, fluid: FluidState, y: Int): Boolean {
        val water = fluid.`is`(FluidTags.WATER)
        val stage = runStage[i]
        val at = i * 4 + (stage shr 1) * 2
        if (stage and 1 == 0) {
            if (stage > 0 && y < searchFloor[i]) {
                runStage[i] = STAGE_DONE
                return true
            }
            if (!water) return false
            val top = y - baseY + 1
            if (top < 1) {
                runStage[i] = STAGE_DONE
                return true
            }
            if (top > MAX_TOP) return false
            out.put(at, top.toByte())
            if (isFalling(fluid)) out.put(at + 1, FALLING_BIT.toByte())
            runStage[i] = stage + 1
            return false
        }
        if (!water) {
            endRun(i, y + 1)
            return runStage[i] == STAGE_DONE
        }
        val info = out.get(at + 1).toInt() and 0xFF
        val below = info and MAX_BELOW
        val falling = if (isFalling(fluid)) FALLING_BIT else info and FALLING_BIT
        out.put(at + 1, ((if (below < MAX_BELOW) below + 1 else below) or falling).toByte())
        return false
    }
}
