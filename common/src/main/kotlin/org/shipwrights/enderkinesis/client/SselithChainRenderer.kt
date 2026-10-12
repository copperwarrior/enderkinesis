package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexBuffer
import com.mojang.blaze3d.vertex.VertexFormat
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import java.lang.ref.WeakReference
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.dimension.SselithRepertory

/**
 * Per-cell chain rendering for Sselith's Repertory, baked into a static VBO.
 *
 * The chain geometry — one column's worth of crossed 45° planes, one quad
 * pair per Y, the same shape the Planar Anchor uses for its tether — never
 * changes; only the camera moves. So we bake a single column from
 * `-BAKE_HALF_HEIGHT` to `+BAKE_HALF_HEIGHT` once into a static VBO using
 * the block-atlas `block/chain` sprite, and per frame we iterate visible
 * library-cell froglight positions and emit one `drawWithShader` per chain
 * with a per-chain model-view (camera translation + chain XZ offset).
 *
 * This collapses what was ~50 000 CPU-side vertex emissions per frame down
 * to draw calls of pre-uploaded GPU buffers, killing the per-frame CPU cost.
 *
 * The shared bake holds only the links out of the build height. Each column
 * has its own VBO for the links in the build height, lit from the level. One
 * column samples its light again each frame.
 */
object SselithChainRenderer {

    /** Atlas-relative sprite path (no `textures/`, no `.png`). */
    private val CHAIN_SPRITE = ResourceLocation("minecraft", "block/chain")

    // ---- Geometry mirrored from SselithRepertoryChunkGenerator ----
    private const val MAZE_CELL_X = 49
    private const val MAZE_CELL_Z = 49
    private const val LIBRARY_QUADRANT_SHIFT = 5
    private const val POSITIVE_Z_EXTRA_SHIFT = 0
    private const val LIBRARY_FROGLIGHT_INSET = 4

    /** Half-width (block units) of one chain plane. Plane is rotated 45° in
     *  XZ — diagonal corner-to-corner span equals vanilla chain's 3-px
     *  width. Same constant the Planar Anchor uses for its tether. */
    private const val CHAIN_HALF_WIDTH = 0.066291265f  // 1.5 / (16 * sqrt(2))

    /** Bake range (blocks). Each Y in [-BAKE_HALF_HEIGHT, +BAKE_HALF_HEIGHT)
     *  produces one chain-link quad pair, baked once at world (0, y, 0).
     *  Per-frame XZ-only translation moves it under each chain column;
     *  the chain only "exists" inside this Y window. ±1024 sits well past
     *  Sselith's ±512 wrap zone so the chain stays visible at any camera Y
     *  inside (and a bit beyond) the playable band. */
    private const val BAKE_HALF_HEIGHT = 1024

    /** XZ distance (blocks) from camera within which a chain column is
     *  bound + drawn. Beyond this the per-chain draw call (already cheap
     *  thanks to the VBO) is wasted on geometry the player can't see. */
    private const val VIEW_DISTANCE = 96
    private const val VIEW_DISTANCE_SQ = VIEW_DISTANCE.toDouble() * VIEW_DISTANCE.toDouble()

    /** Fog start/end set on the shader for the chain pass to suppress fog —
     *  any value past the camera's frustum keeps `vertexDistance <= fogStart`
     *  for every fragment, so the shader's linear-fog mix never engages. */
    private const val NO_FOG_DISTANCE = 1.0e6f

    /** Lazily-baked VBO; reset to null if the sprite UVs change (atlas
     *  rebuilt by a resource reload) so we can rebake against the new UVs. */
    private var vbo: VertexBuffer? = null
    private var bakedU0: Float = Float.NaN
    private var bakedV0: Float = Float.NaN
    private var bakedMinY = 0
    private var bakedMaxY = 0

    /** Frames between the sweeps that free the columns out of view. */
    private const val EVICT_INTERVAL_FRAMES = 256L

    private class LitColumn(val lights: IntArray) {
        val vbo = VertexBuffer(VertexBuffer.Usage.DYNAMIC)
        var baked = false
        var lastDrawnFrame = 0L
    }

    // The build-height part of each column, lit from the level. Render thread only.
    private val litColumns = Long2ObjectOpenHashMap<LitColumn>()
    private var litLevel = WeakReference<ClientLevel>(null)
    private var frame = 0L
    private var visibleColumns = 0
    private val scratchPos = BlockPos.MutableBlockPos()
    private val scratchMatrix = Matrix4f()

    /** Called after the world's opaque blocks have been drawn by
     *  [org.shipwrights.enderkinesis.mixin.LevelRendererSselithChainsMixin].
     *  The pose stack still has the camera transform applied, so
     *  `poseStack.last().pose()` is the world → clip matrix from the
     *  camera; per-chain we multiply in a translation to the chain's
     *  world XZ (and `-camY` so the bake's Y=0 lines up with world Y=0). */
    fun render(poseStack: PoseStack, projection: Matrix4f, camera: Camera, partialTick: Float) {
        val level = Minecraft.getInstance().level ?: return
        if (level.dimension() != SselithRepertory.LEVEL_KEY) {
            clearLitColumns()
            return
        }
        if (litLevel.get() !== level) {
            clearLitColumns()
            litLevel = WeakReference(level)
        }

        val sprite = Minecraft.getInstance()
            .getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
            .apply(CHAIN_SPRITE)
        val v = ensureVbo(sprite, level.minBuildHeight, level.maxBuildHeight)

        // entityCutoutNoCull-equivalent state. Set up once; one draw call
        // per chain reuses it.
        //  - disable blend     (NO_TRANSPARENCY)
        //  - depth test on, mask on
        //  - cull off          (NO_CULL — single-winding quads visible both
        //    sides)
        //  - shader: entity cutout no-cull
        //  - sampler 0: blocks atlas (chain sprite)
        //  - sampler 1: overlay texture (set up via EntityRenderDispatcher
        //    so `drawWithShader`'s sampler iteration finds it — at the
        //    "translucent" injection point the overlay isn't otherwise
        //    guaranteed to be bound, and the shader samples it for every
        //    fragment).
        //  - sampler 2: light texture, already turned on for the world pass
        //    by `LevelRenderer.renderLevel` and still active here.
        // Apply vanilla's full composite render state for `cutoutMipped` —
        // sampler bindings, lightmap, fog, blend, depth, cull, shader,
        // texture filter, the works. Doing this through the mixin invoker
        // instead of replicating every shard's effect by hand is the only
        // reliable way to get the chain texture to sample correctly:
        // missing any shard (sampler 2 not bound, ColorModulator left as a
        // tint from an earlier renderer, atlas filter wrong, etc.) produces
        // a black / white / shapeless result with no clear single cause.
        val chunkRenderType = RenderType.cutoutMipped()
        // Fabulous: `translucentTarget.copyDepthFrom` runs before this hook and leaves framebuffer 0
        // bound. The cutoutMipped output shard is a no-op, so bind the main target here.
        if (Minecraft.useShaderTransparency()) Minecraft.getInstance().mainRenderTarget.bindWrite(false)
        chunkRenderType.setupRenderState()
        // cutoutMipped enables face culling (chunks emit double-sided
        // geometry where needed). Our crossed chain planes are single-
        // winding quads — without disabling cull we'd only ever see the
        // front face of each plane. Re-enabled in the teardown to match
        // what `clearRenderState` would have left.
        RenderSystem.disableCull()
        // Reset ColorModulator after setup — cutoutMipped doesn't include a
        // shard that touches it, so whatever tint the previous renderer
        // left in place would otherwise multiply through and zero out the
        // chain colour.
        val savedShaderColor = RenderSystem.getShaderColor().copyOf()
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
        val shader = RenderSystem.getShader() ?: run {
            RenderSystem.setShaderColor(
                savedShaderColor[0], savedShaderColor[1],
                savedShaderColor[2], savedShaderColor[3],
            )
            chunkRenderType.clearRenderState()
            return
        }
        // Push fog start/end out past any reasonable vertex distance for the
        // chain pass. The entity-cutout shader runs `linear_fog(...)` per
        // fragment using the current `FogStart`/`FogEnd` uniforms — at our
        // injection point those are Sselith's atmospheric values (low
        // distance, yellow tint), which fades the chains into the fog and
        // makes them invisible past a few cells. Restored at the end so the
        // subsequent translucent pass still uses the correct fog.
        val savedFogStart = RenderSystem.getShaderFogStart()
        val savedFogEnd = RenderSystem.getShaderFogEnd()
        RenderSystem.setShaderFogStart(NO_FOG_DISTANCE)
        RenderSystem.setShaderFogEnd(NO_FOG_DISTANCE)

        val baseMatrix = poseStack.last().pose()
        val camPos: Vec3 = camera.position

        // Out of the build height there is no light data. The bake uses FULL_BRIGHT there, and the
        // Sselith lightmap pins that cell to white, so the eclipse must dim that part here.
        val skyScale = 1f - SselithRepertoryLighting.currentEclipseIntensity(partialTick)
        RenderSystem.setShaderColor(skyScale, skyScale, skyScale, 1f)
        v.bind()
        forEachVisibleColumn(camPos) { wx, wz ->
            drawColumn(v, baseMatrix, projection, shader, camPos, wx, wz)
        }

        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
        frame++
        val refreshSlot = (frame % maxOf(visibleColumns, 1)).toInt()
        var slot = 0
        forEachVisibleColumn(camPos) { wx, wz ->
            val lit = litColumn(level, sprite, wx, wz, slot == refreshSlot)
            slot++
            drawColumn(lit, baseMatrix, projection, shader, camPos, wx, wz)
        }
        visibleColumns = slot
        VertexBuffer.unbind()
        if (frame % EVICT_INTERVAL_FRAMES == 0L) evictStaleColumns()
        RenderSystem.setShaderFogStart(savedFogStart)
        RenderSystem.setShaderFogEnd(savedFogEnd)
        RenderSystem.setShaderColor(
            savedShaderColor[0], savedShaderColor[1],
            savedShaderColor[2], savedShaderColor[3],
        )
        RenderSystem.enableCull()
        chunkRenderType.clearRenderState()
    }

    private inline fun forEachVisibleColumn(camPos: Vec3, action: (wx: Int, wz: Int) -> Unit) {
        val pad = VIEW_DISTANCE + MAZE_CELL_X
        val baseCellX = Math.floorDiv(Math.floor(camPos.x).toInt(), MAZE_CELL_X)
        val baseCellZ = Math.floorDiv(Math.floor(camPos.z).toInt(), MAZE_CELL_Z)
        val rangeX = (pad / MAZE_CELL_X) + 1
        val rangeZ = (pad / MAZE_CELL_Z) + 1
        val insetHi = MAZE_CELL_X - 1 - LIBRARY_FROGLIGHT_INSET

        for (dx in -rangeX..rangeX) {
            for (dz in -rangeZ..rangeZ) {
                for (corner in 0 until 4) {
                    val tileX = if (corner and 1 == 0) LIBRARY_FROGLIGHT_INSET else insetHi
                    val tileZ = if (corner and 2 == 0) LIBRARY_FROGLIGHT_INSET else insetHi
                    val wx = chainWorldX(baseCellX + dx, tileX)
                    val wz = chainWorldZ(baseCellZ + dz, tileZ)
                    val ddx = (wx + 0.5) - camPos.x
                    val ddz = (wz + 0.5) - camPos.z
                    if (ddx * ddx + ddz * ddz <= VIEW_DISTANCE_SQ) action(wx, wz)
                }
            }
        }
    }

    /** The bake is at world Y=0, so `-camY` puts the bake's Y=0 at world Y=0. [v] must be bound. */
    private fun drawColumn(
        v: VertexBuffer, baseMatrix: Matrix4f, projection: Matrix4f, shader: ShaderInstance,
        camPos: Vec3, wx: Int, wz: Int,
    ) {
        val mv = scratchMatrix.set(baseMatrix).translate(
            (wx + 0.5 - camPos.x).toFloat(),
            (-camPos.y).toFloat(),
            (wz + 0.5 - camPos.z).toFloat(),
        )
        v.drawWithShader(mv, projection, shader)
    }

    /** Returns the bound VBO of the build-height part of one column. A [refresh] samples the light again. */
    private fun litColumn(
        level: ClientLevel, sprite: TextureAtlasSprite, wx: Int, wz: Int, refresh: Boolean,
    ): VertexBuffer {
        val key = ChunkPos.asLong(wx, wz)
        val column = litColumns.get(key)
            ?: LitColumn(IntArray(bakedMaxY - bakedMinY)).also { litColumns.put(key, it) }
        column.lastDrawnFrame = frame
        val changed = (refresh || !column.baked) && sampleLights(level, wx, wz, column.lights)
        column.vbo.bind()
        if (changed || !column.baked) {
            val builder = Tesselator.getInstance().builder
            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK)
            bakeLinks(builder, sprite, bakedMinY, bakedMaxY, column.lights)
            column.vbo.upload(builder.end())
            column.baked = true
        }
        return column.vbo
    }

    /** Writes the packed light of each block of the column into [lights]. Returns true if a value changed. */
    private fun sampleLights(level: ClientLevel, wx: Int, wz: Int, lights: IntArray): Boolean {
        var changed = false
        for (i in lights.indices) {
            val light = LevelRenderer.getLightColor(level, scratchPos.set(wx, bakedMinY + i, wz))
            if (lights[i] != light) {
                lights[i] = light
                changed = true
            }
        }
        return changed
    }

    private fun evictStaleColumns() {
        val it = litColumns.values.iterator()
        while (it.hasNext()) {
            val column = it.next()
            if (frame - column.lastDrawnFrame < EVICT_INTERVAL_FRAMES) continue
            column.vbo.close()
            it.remove()
        }
    }

    private fun clearLitColumns() {
        if (litColumns.isEmpty()) return
        litColumns.values.forEach { it.vbo.close() }
        litColumns.clear()
    }

    /** Returns the cached VBO of the out-of-bounds links. Rebakes if the sprite UVs change
     *  (a resource reload rebuilds the atlas) or the build height changes. Must run on the
     *  render thread (GL upload). */
    private fun ensureVbo(sprite: TextureAtlasSprite, minY: Int, maxY: Int): VertexBuffer {
        val existing = vbo
        val current = bakedU0 == sprite.u0 && bakedV0 == sprite.v0 && bakedMinY == minY && bakedMaxY == maxY
        if (existing != null && current) return existing
        existing?.close()
        clearLitColumns()

        val tess = Tesselator.getInstance()
        val builder: BufferBuilder = tess.builder
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK)
        bakeLinks(builder, sprite, -BAKE_HALF_HEIGHT, minY, null)
        bakeLinks(builder, sprite, maxY, BAKE_HALF_HEIGHT, null)

        val newVbo = VertexBuffer(VertexBuffer.Usage.STATIC)
        newVbo.bind()
        newVbo.upload(builder.end())
        VertexBuffer.unbind()
        vbo = newVbo
        bakedU0 = sprite.u0
        bakedV0 = sprite.v0
        bakedMinY = minY
        bakedMaxY = maxY
        return newVbo
    }

    /** 2 crossed 45° planes, one quad pair per Y in [yFrom, yUntil). Atlas-sprite UVs match the
     *  vanilla chain block. Single-winding quads — caller renders with `disableCull` so both
     *  sides are visible from one quad. [lights] holds the packed light per Y from [yFrom];
     *  null gives FULL_BRIGHT. */
    private fun bakeLinks(
        builder: BufferBuilder, sprite: TextureAtlasSprite, yFrom: Int, yUntil: Int, lights: IntArray?,
    ) {
        val w = CHAIN_HALF_WIDTH
        val uSpan = sprite.u1 - sprite.u0
        val uA0 = sprite.u0
        val uA1 = sprite.u0 + uSpan * 3f / 16f
        val uB0 = uA1
        val uB1 = sprite.u0 + uSpan * 6f / 16f
        val v0 = sprite.v0
        val v1 = sprite.v1

        for (y in yFrom until yUntil) {
            val y0 = y.toFloat()
            val y1 = (y + 1).toFloat()
            val light = lights?.get(y - yFrom) ?: LightTexture.FULL_BRIGHT
            // Plane A — diagonal (-w, -w) → (+w, +w), cols 0–2.
            quad(builder, -w, y0, -w,  w, y0,  w,  w, y1,  w, -w, y1, -w,
                uA0, uA1, v1, v0, light)
            // Plane B — perpendicular diagonal, cols 3–5.
            quad(builder, -w, y0,  w,  w, y0, -w,  w, y1, -w, -w, y1,  w,
                uB0, uB1, v1, v0, light)
        }
    }

    /** BLOCK vertex format: pos + color + uv + uv2 + normal. Used so the
     *  bake matches the chunk renderer's cutoutMipped expected layout
     *  (the same layer vanilla chain blocks are drawn in). */
    private fun quad(
        builder: BufferBuilder,
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        x3: Float, y3: Float, z3: Float,
        uLeft: Float, uRight: Float, vBottom: Float, vTop: Float,
        light: Int,
    ) {
        builder.vertex(x0.toDouble(), y0.toDouble(), z0.toDouble())
            .color(255, 255, 255, 255).uv(uLeft, vBottom)
            .uv2(light).normal(0f, 1f, 0f).endVertex()
        builder.vertex(x1.toDouble(), y1.toDouble(), z1.toDouble())
            .color(255, 255, 255, 255).uv(uRight, vBottom)
            .uv2(light).normal(0f, 1f, 0f).endVertex()
        builder.vertex(x2.toDouble(), y2.toDouble(), z2.toDouble())
            .color(255, 255, 255, 255).uv(uRight, vTop)
            .uv2(light).normal(0f, 1f, 0f).endVertex()
        builder.vertex(x3.toDouble(), y3.toDouble(), z3.toDouble())
            .color(255, 255, 255, 255).uv(uLeft, vTop)
            .uv2(light).normal(0f, 1f, 0f).endVertex()
    }

    /** Inverse of the chunk generator's `effX → worldX` quadrant-shift
     *  mapping. Must stay in lock-step with `SselithRepertoryChunkGenerator`. */
    private fun chainWorldX(libCellX: Int, tileX: Int): Int {
        val effX = libCellX * MAZE_CELL_X + tileX
        return if (effX >= 0) effX + LIBRARY_QUADRANT_SHIFT + 1
        else effX - LIBRARY_QUADRANT_SHIFT
    }

    private fun chainWorldZ(libCellZ: Int, tileZ: Int): Int {
        val effZ = libCellZ * MAZE_CELL_Z + tileZ
        return if (effZ >= 0) effZ + LIBRARY_QUADRANT_SHIFT + POSITIVE_Z_EXTRA_SHIFT + 1
        else effZ - LIBRARY_QUADRANT_SHIFT
    }
}
