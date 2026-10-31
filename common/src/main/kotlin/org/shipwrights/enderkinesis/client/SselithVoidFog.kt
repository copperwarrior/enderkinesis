package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import java.nio.ByteBuffer
import java.util.function.IntSupplier
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.EffectInstance
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.client.volume.ChunkColumnMask
import org.shipwrights.enderkinesis.client.volume.HalfResTarget
import org.shipwrights.enderkinesis.client.volume.VolumeComposite
import org.shipwrights.enderkinesis.client.volume.VolumeEffect
import org.shipwrights.enderkinesis.client.volume.VolumeView
import org.shipwrights.enderkinesis.dimension.SselithRepertory

/** Volumetric fog in Sselith columns that have no blocks from world bottom to world top.
 *  A half-resolution pass ray-marches a toroidal column mask around the camera; a depth-aware
 *  upsample blends the result over main. All state is on the client render thread. */
object SselithVoidFog {

    // The shader's COLUMN_WRAP is WINDOW_CHUNKS * 16 - 1.
    private const val WINDOW_CHUNKS = 32

    // Blocks past the world top and bottom over which the fog fades to 0.
    private const val EDGE_FADE = 32f

    private val columnMask = ChunkColumnMask(WINDOW_CHUNKS, ::fillColumns)
    private val half = HalfResTarget()
    private val view = VolumeView()
    private val marchEffect = VolumeEffect("sselith_void_fog", "ColumnMask" to IntSupplier { columnMask.textureId })
    private val upsampleEffect = VolumeEffect("sselith_void_fog_upsample", "HalfSampler" to IntSupplier { half.textureId })

    private var camBlockX = 0
    private var camBlockZ = 0
    private var fogDim = 0f
    private var minY = 0f
    private var maxY = 0f
    private var marchDist = 0f

    // Kinks in the density curve, sorted; the shader integrates exactly between them.
    private val densityBreaks = FloatArray(8)

    private val pushMarchUniforms: (EffectInstance) -> Unit = ::pushMarchUniforms
    private val pushUpsampleUniforms: (EffectInstance) -> Unit = ::pushUpsampleUniforms

    /** Chunk load and unload hook. */
    @JvmStatic
    fun markChunkDirty(chunkX: Int, chunkZ: Int) = columnMask.markDirty(chunkX, chunkZ)

    @JvmStatic
    fun onBlockChanged(pos: BlockPos, oldState: BlockState, newState: BlockState) {
        if (oldState.isAir != newState.isAir) markChunkDirty(pos.x shr 4, pos.z shr 4)
    }

    @JvmStatic
    fun render(poseStack: PoseStack, projection: Matrix4f, camera: Camera, partialTick: Float) {
        val mc = Minecraft.getInstance()
        val level = mc.level
        if (level == null || level.dimension() != SselithRepertory.LEVEL_KEY || IrisCompat.isShaderPackInUse()) {
            columnMask.unbind()
            return
        }
        val march = marchEffect.get() ?: return
        val upsample = upsampleEffect.get() ?: return

        val cam = camera.position
        camBlockX = Mth.floor(cam.x)
        camBlockZ = Mth.floor(cam.z)
        columnMask.refresh(level, camBlockX shr 4, camBlockZ shr 4)
        view.update(projection, poseStack.last().pose(), cam.x, cam.y, cam.z)
        fogDim = SselithFog.dim(level, partialTick, level.maxBuildHeight.toDouble())
        minY = level.minBuildHeight.toFloat()
        maxY = level.maxBuildHeight.toFloat()
        fillDensityBreaks()
        // Unloaded chunks read as solid, so steps past the render distance add no fog.
        marchDist = minOf(columnMask.maxMarch, mc.options.effectiveRenderDistance * 16f)

        val main = mc.mainRenderTarget
        half.draw(main, march, pushMarchUniforms)
        VolumeComposite.drawOverMain(main, upsample, pushUpsampleUniforms)
        VolumeComposite.restore(main)
    }

    private fun pushMarchUniforms(eff: EffectInstance) {
        view.pushInvViewProj(eff)
        view.pushDepthParams(eff)
        eff.safeGetUniform("CamBlockWrap").set(columnMask.wrap(camBlockX), columnMask.wrap(camBlockZ))
        eff.safeGetUniform("CamFrac").set((view.camX - camBlockX).toFloat(), (view.camZ - camBlockZ).toFloat())
        eff.safeGetUniform("CamY").set(view.camY.toFloat())
        eff.safeGetUniform("FogRange").set(SselithFog.start(), SselithFog.end())
        eff.safeGetUniform("SlabY").set(minY, maxY)
        eff.safeGetUniform("EdgeFade").set(EDGE_FADE)
        eff.safeGetUniform("BreaksLo").set(densityBreaks[0], densityBreaks[1], densityBreaks[2], densityBreaks[3])
        eff.safeGetUniform("BreaksHi").set(densityBreaks[4], densityBreaks[5], densityBreaks[6], densityBreaks[7])
        eff.safeGetUniform("MaxDist").set(marchDist)
        eff.safeGetUniform("Density").set(SselithRepertory.FOG_FALLOFF_Y.toFloat(), SselithRepertory.FOG_MIN_DENSITY)
    }

    private fun pushUpsampleUniforms(eff: EffectInstance) {
        eff.safeGetUniform("FogColor").set(SselithFog.red() * fogDim, SselithFog.green() * fogDim, SselithFog.blue() * fogDim)
        view.pushDepthParams(eff)
        half.pushHalfSize(eff)
    }

    private fun fillDensityBreaks() {
        val falloff = SselithRepertory.FOG_FALLOFF_Y.toFloat()
        densityBreaks[0] = minY - EDGE_FADE
        densityBreaks[1] = minY
        densityBreaks[2] = -falloff
        densityBreaks[3] = 0f
        densityBreaks[4] = falloff
        densityBreaks[5] = maxY
        densityBreaks[6] = maxY + EDGE_FADE
        densityBreaks[7] = maxY + EDGE_FADE
        densityBreaks.sort()
    }

    /** The flag marks a void column. */
    private fun fillColumns(chunk: LevelChunk, out: ByteBuffer) {
        scanColumns(chunk, out)
        openAxialBand(chunk, out)
    }

    private fun scanColumns(chunk: LevelChunk, out: ByteBuffer) {
        for (i in 0 until 256) out.put(i, ChunkColumnMask.FLAG_BIT.toByte())
        for (section in chunk.sections) {
            if (section.hasOnlyAir()) continue
            for (z in 0 until 16) {
                for (x in 0 until 16) {
                    val i = z * 16 + x
                    if (out.get(i).toInt() == 0) continue
                    for (y in 0 until 16) {
                        if (!section.getBlockState(x, y, z).isAir) {
                            out.put(i, 0)
                            break
                        }
                    }
                }
            }
        }
    }

    // The path floor, arches and gap pillars put blocks in the axial band. Scene depth still clips the fog there.
    private fun openAxialBand(chunk: LevelChunk, out: ByteBuffer) {
        val minX = chunk.pos.minBlockX
        val minZ = chunk.pos.minBlockZ
        for (z in 0 until 16) {
            for (x in 0 until 16) {
                if (SselithRepertory.isAxialBand(minX + x, minZ + z)) out.put(z * 16 + x, ChunkColumnMask.FLAG_BIT.toByte())
            }
        }
    }

    fun onDisconnect() = columnMask.unbind()
}
