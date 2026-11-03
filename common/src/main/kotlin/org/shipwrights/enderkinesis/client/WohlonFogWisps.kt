package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.shaders.AbstractUniform
import com.mojang.blaze3d.shaders.Uniform
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.EffectInstance
import net.minecraft.core.BlockPos
import net.minecraft.tags.BlockTags
import net.minecraft.util.Mth
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.material.FogType
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.client.volume.VolumeComposite
import org.shipwrights.enderkinesis.client.volume.VolumeEffect
import org.shipwrights.enderkinesis.client.volume.VolumeProxy
import org.shipwrights.enderkinesis.client.volume.VolumeTextures
import org.shipwrights.enderkinesis.client.volume.VolumeView
import org.shipwrights.enderkinesis.dimension.WohlonBiomePresence
import org.shipwrights.enderkinesis.dimension.Wohlonnogondonia

/**
 * Wispy fog clouds on the ground of Wohlon biome columns, everywhere in view. Each cell of a world
 * grid may hold a wisp; a hash of the cell and its life cycle sets where it is, so each client sees
 * the same fog, and the fog is there before the player arrives. Render thread only.
 */
object WohlonFogWisps {

    private const val CELL = 32
    // Part of the cells that hold a wisp.
    private const val OCCUPANCY = 0.7
    private const val MAX_RANGE = 192.0
    private const val VIEW_FADE = 32.0
    private const val MAX_DRAWS = 160
    private const val MAX_SCAN = 160

    // Seconds. Each cell's wisp lives one cycle, then comes back at a new place in the cell.
    private const val CYCLE_MIN = 30.0
    private const val CYCLE_MAX = 45.0
    private const val FADE = 6.0
    private const val DRIFT_SPEED = 0.25

    // Half extents, in blocks: across and up. The noise, not the shape, makes the wisps.
    private const val WIDE_MIN = 14.0f
    private const val WIDE_MAX = 26.0f
    private const val UP_MIN = 5.0f
    private const val UP_MAX = 9.0f
    // Keeps float precision in the shader. The rotated noise does not repeat, so the field has a seam only at this period.
    private const val NOISE_PERIOD = 65536.0

    private const val DENSITY = 0.025f
    // Only a little lighter than the fog colour: more and the wisps read as light, not fog.
    private const val COLOUR_LIFT = 1.05f
    private const val TIME_PERIOD_SEC = 4000.0

    // The ground cache keeps one entry for each probed column; it starts again past this size.
    private const val GROUND_CACHE_MAX = 8192
    private const val NO_GROUND = Int.MIN_VALUE
    private const val NOT_CACHED = Int.MIN_VALUE + 1

    // The Noise3D uniform in wohlon_fog_wisp.json holds this unit.
    private const val NOISE_UNIT = 4

    private val view = VolumeView()
    private val effect = VolumeEffect("wohlon_fog_wisp")
    private var uniforms: Uniforms? = null
    private val localToView = Matrix4f()
    private val probe = BlockPos.MutableBlockPos()
    private val groundCache = Long2IntOpenHashMap().apply { defaultReturnValue(NOT_CACHED) }
    private var cacheLevel: ClientLevel? = null

    fun onDisconnect() {
        groundCache.clear()
        cacheLevel = null
    }

    // A column in a chunk that is not loaded is not cached, so it is probed again when the chunk loads.
    private fun cachedGround(level: ClientLevel, x: Int, z: Int): Int {
        if (level !== cacheLevel || groundCache.size > GROUND_CACHE_MAX) {
            groundCache.clear()
            cacheLevel = level
        }
        val key = ChunkPos.asLong(x, z)
        val cached = groundCache.get(key)
        if (cached != NOT_CACHED) return cached
        if (level.chunkSource.getChunkNow(x shr 4, z shr 4) == null) return NO_GROUND
        val ground = groundY(level, x, z)
        val value = if (ground != null && isWohlon(level, x, ground, z)) ground else NO_GROUND
        groundCache.put(key, value)
        return value
    }

    private fun isWohlon(level: ClientLevel, x: Int, y: Int, z: Int): Boolean {
        probe.set(x, y, z)
        return level.getBiome(probe).`is`(Wohlonnogondonia.BIOME_KEY)
    }

    // The top terrain block, under any tree part; null when the column has no terrain in reach.
    private fun groundY(level: ClientLevel, x: Int, z: Int): Int? {
        val chunk = level.chunkSource.getChunkNow(x shr 4, z shr 4) ?: return null
        val top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x and 15, z and 15)
        val stop = maxOf(level.minBuildHeight, top - MAX_SCAN)
        var y = top
        while (y > stop) {
            probe.set(x, y, z)
            val state = chunk.getBlockState(probe)
            if (!state.fluidState.isEmpty) return y
            // Terrain, not "solid": wood shapes, buds, vines and roots all stop motion too.
            if (state.`is`(BlockTags.MINEABLE_WITH_SHOVEL) || state.`is`(BlockTags.BASE_STONE_OVERWORLD) ||
                state.`is`(BlockTags.SAND)
            ) return y
            y--
        }
        return null
    }

    /** A value in [0, 1) from a cell, an epoch and a salt. */
    private fun hash(cx: Int, cz: Int, epoch: Long, salt: Int): Double {
        var h = Mth.getSeed(cx, salt, cz) xor (epoch * -0x61c8864680b583ebL)
        h = (h xor (h ushr 31)) * -0x4b47d5b1L
        h = h xor (h ushr 29)
        return (h ushr 11).toDouble() / (1L shl 53).toDouble()
    }

    // Called with the other world-space volumes, before the fog passes.
    @JvmStatic
    fun render(poseStack: PoseStack, projection: Matrix4f, camera: Camera) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        if (!WohlonBiomePresence.nearby || camera.fluidInCamera != FogType.NONE || IrisCompat.isShaderPackInUse()) return
        val eff = effect.get() ?: return
        val u = uniforms ?: Uniforms(eff).also { uniforms = it }
        val main = mc.mainRenderTarget
        val cam = camera.position
        view.update(projection, poseStack.last().pose(), cam.x, cam.y, cam.z)
        u.pushFrame(main.width, main.height, RenderSystem.getShaderFogColor())
        val now = System.nanoTime() * 1e-9
        val phase = now % (2.0 * TIME_PERIOD_SEC)
        u.time.set((if (phase <= TIME_PERIOD_SEC) phase else 2.0 * TIME_PERIOD_SEC - phase).toFloat())

        val range = minOf(MAX_RANGE, mc.options.effectiveRenderDistance * 16.0)
        val minCellX = Mth.floor((cam.x - range) / CELL)
        val maxCellX = Mth.floor((cam.x + range) / CELL)
        val minCellZ = Mth.floor((cam.z - range) / CELL)
        val maxCellZ = Mth.floor((cam.z + range) / CELL)
        var drawn = 0
        for (cz in minCellZ..maxCellZ) {
            for (cx in minCellX..maxCellX) {
                if (drawn >= MAX_DRAWS) break
                if (drawCell(level, u, eff, main, cx, cz, now, range, drawn == 0)) drawn++
            }
        }
        if (drawn > 0) VolumeProxy.end(main, eff)
        VolumeTextures.bind3D(NOISE_UNIT, 0)
    }

    @Suppress("LongParameterList") // One wisp's placement.
    private fun drawCell(
        level: ClientLevel, u: Uniforms, eff: EffectInstance, main: RenderTarget,
        cx: Int, cz: Int, now: Double, range: Double, first: Boolean,
    ): Boolean {
        // Each cell runs its own cycle length and phase, so the wisps do not fade in step.
        val cycle = CYCLE_MIN + hash(cx, cz, 0, 1) * (CYCLE_MAX - CYCLE_MIN)
        val shifted = now + hash(cx, cz, 0, 2) * cycle
        val epoch = Math.floor(shifted / cycle).toLong()
        val age = shifted - epoch * cycle
        if (hash(cx, cz, epoch, 3) > OCCUPANCY) return false

        val x0 = (cx + hash(cx, cz, epoch, 4)) * CELL
        val z0 = (cz + hash(cx, cz, epoch, 5)) * CELL
        val ground = cachedGround(level, Mth.floor(x0), Mth.floor(z0))
        if (ground == NO_GROUND) return false
        val heading = hash(cx, cz, epoch, 6) * Math.PI * 2.0
        val drift = age * DRIFT_SPEED
        val up = Mth.lerp(hash(cx, cz, epoch, 7).toFloat(), UP_MIN, UP_MAX)
        val wideX = Mth.lerp(hash(cx, cz, epoch, 8).toFloat(), WIDE_MIN, WIDE_MAX)
        val wideZ = Mth.lerp(hash(cx, cz, epoch, 9).toFloat(), WIDE_MIN, WIDE_MAX)

        val sx = x0 + Math.cos(heading) * drift - view.camX
        val sy = ground + 1 + up * 0.35 - view.camY
        val sz = z0 + Math.sin(heading) * drift - view.camZ
        val dist = Math.sqrt(sx * sx + sz * sz)
        if (dist > range) return false
        val life = minOf(age / FADE, (cycle - age) / FADE, 1.0).coerceAtLeast(0.0)
        val viewFade = ((range - dist) / VIEW_FADE).coerceIn(0.0, 1.0)
        val fade = (smooth(life.toFloat()) * viewFade).toFloat()
        if (fade <= 0f) return false
        if (!view.boxVisible(
                (sx - wideX).toFloat(), (sy - up).toFloat(), (sz - wideZ).toFloat(),
                (sx + wideX).toFloat(), (sy + up).toFloat(), (sz + wideZ).toFloat(),
            )
        ) return false

        u.boxMin.set(-wideX, -up, -wideZ)
        u.boxMax.set(wideX, up, wideZ)
        localToView.translation(sx.toFloat(), sy.toFloat(), sz.toFloat())
        u.localToView.set(localToView)
        u.camLocal.set(-sx.toFloat(), -sy.toFloat(), -sz.toFloat())
        u.size.set(wideX, up, wideZ)
        u.fade.set(fade)
        u.noiseOrigin.set(
            (sx + view.camX).mod(NOISE_PERIOD).toFloat(), (sy + view.camY).toFloat(), (sz + view.camZ).mod(NOISE_PERIOD).toFloat(),
        )
        if (first) {
            VolumeTextures.bind3D(NOISE_UNIT, WohlonNoiseTexture.id3D())
            VolumeProxy.begin(main, VolumeComposite.Blend.PREMULTIPLIED)
            VolumeProxy.draw(eff)
        } else {
            u.upload()
            VolumeProxy.drawApplied()
        }
        return true
    }

    private fun smooth(t: Float) = t * t * (3f - 2f * t)

    private class Uniforms(private val eff: EffectInstance) {
        val boxMin: AbstractUniform = eff.safeGetUniform("BoxMin")
        val boxMax: AbstractUniform = eff.safeGetUniform("BoxMax")
        val localToView: AbstractUniform = eff.safeGetUniform("LocalToView")
        val camLocal: AbstractUniform = eff.safeGetUniform("CamLocal")
        val size: AbstractUniform = eff.safeGetUniform("Size")
        val fade: AbstractUniform = eff.safeGetUniform("Fade")
        val noiseOrigin: AbstractUniform = eff.safeGetUniform("NoiseOrigin")
        val time: AbstractUniform = eff.safeGetUniform("Time")
        private val screenSize: AbstractUniform = eff.safeGetUniform("ScreenSize")
        private val density: AbstractUniform = eff.safeGetUniform("Density")
        private val fogColour: AbstractUniform = eff.safeGetUniform("FogColor")
        private val perWisp: List<Uniform> =
            listOf(boxMin, boxMax, localToView, camLocal, size, fade, noiseOrigin).filterIsInstance<Uniform>()

        fun upload() {
            for (uniform in perWisp) uniform.upload()
        }

        fun pushFrame(width: Int, height: Int, fog: FloatArray) {
            view.pushViewProj(eff)
            view.pushInvViewProj(eff)
            screenSize.set(width.toFloat(), height.toFloat())
            density.set(DENSITY)
            fogColour.set(
                (fog[0] * COLOUR_LIFT).coerceAtMost(1f),
                (fog[1] * COLOUR_LIFT).coerceAtMost(1f),
                (fog[2] * COLOUR_LIFT).coerceAtMost(1f),
            )
        }
    }
}
