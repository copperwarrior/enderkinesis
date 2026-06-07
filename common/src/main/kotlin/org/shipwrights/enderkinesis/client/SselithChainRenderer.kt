package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexBuffer
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.resources.ResourceLocation
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
 * to a constant ~16 draw calls of pre-uploaded GPU buffers, killing the
 * per-frame CPU cost.
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

    /** Called after the world's opaque blocks have been drawn by
     *  [org.shipwrights.enderkinesis.mixin.LevelRendererSselithChainsMixin].
     *  The pose stack still has the camera transform applied, so
     *  `poseStack.last().pose()` is the world → clip matrix from the
     *  camera; per-chain we multiply in a translation to the chain's
     *  world XZ (and `-camY` so the bake's Y=0 lines up with world Y=0). */
    fun render(poseStack: PoseStack, projection: Matrix4f, camera: Camera) {
        val level = Minecraft.getInstance().level ?: return
        if (level.dimension() != SselithRepertory.LEVEL_KEY) return

        val sprite = Minecraft.getInstance()
            .getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
            .apply(CHAIN_SPRITE)
        val v = ensureVbo(sprite) ?: return

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
        val camX = camPos.x
        val camY = camPos.y
        val camZ = camPos.z

        val pad = VIEW_DISTANCE + MAZE_CELL_X
        val baseCellX = Math.floorDiv(Math.floor(camX).toInt(), MAZE_CELL_X)
        val baseCellZ = Math.floorDiv(Math.floor(camZ).toInt(), MAZE_CELL_Z)
        val rangeX = (pad / MAZE_CELL_X) + 1
        val rangeZ = (pad / MAZE_CELL_Z) + 1
        val insetLo = LIBRARY_FROGLIGHT_INSET
        val insetHi = MAZE_CELL_X - 1 - LIBRARY_FROGLIGHT_INSET

        v.bind()
        for (dx in -rangeX..rangeX) {
            val libCellX = baseCellX + dx
            for (dz in -rangeZ..rangeZ) {
                val libCellZ = baseCellZ + dz
                for (tileX in intArrayOf(insetLo, insetHi)) {
                    val wx = chainWorldX(libCellX, tileX)
                    for (tileZ in intArrayOf(insetLo, insetHi)) {
                        val wz = chainWorldZ(libCellZ, tileZ)
                        val ddx = (wx + 0.5) - camX
                        val ddz = (wz + 0.5) - camZ
                        if (ddx * ddx + ddz * ddz > VIEW_DISTANCE_SQ) continue

                        // Camera-relative translation: bake is at world Y=0,
                        // so subtracting camY puts the bake's Y=0 at the
                        // camera's Y=0 view origin — chain spans world
                        // Y = [-BAKE_HALF_HEIGHT, +BAKE_HALF_HEIGHT] for
                        // all cameras inside the dimension's wrap zone.
                        val mv = Matrix4f(baseMatrix).translate(
                            (wx + 0.5 - camX).toFloat(),
                            (-camY).toFloat(),
                            (wz + 0.5 - camZ).toFloat(),
                        )
                        v.drawWithShader(mv, projection, shader)
                    }
                }
            }
        }
        VertexBuffer.unbind()
        RenderSystem.setShaderFogStart(savedFogStart)
        RenderSystem.setShaderFogEnd(savedFogEnd)
        RenderSystem.setShaderColor(
            savedShaderColor[0], savedShaderColor[1],
            savedShaderColor[2], savedShaderColor[3],
        )
        RenderSystem.enableCull()
        chunkRenderType.clearRenderState()
    }

    /** Returns the cached VBO, rebaking if the sprite UVs have changed
     *  (e.g., the block atlas was rebuilt by a resource reload). Must run
     *  on the render thread (GL upload). */
    private fun ensureVbo(sprite: TextureAtlasSprite): VertexBuffer? {
        val existing = vbo
        if (existing != null && bakedU0 == sprite.u0 && bakedV0 == sprite.v0) return existing
        existing?.close()

        val tess = Tesselator.getInstance()
        val builder: BufferBuilder = tess.builder
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK)
        bakeColumn(builder, sprite)

        val newVbo = VertexBuffer(VertexBuffer.Usage.STATIC)
        newVbo.bind()
        newVbo.upload(builder.end())
        VertexBuffer.unbind()
        vbo = newVbo
        bakedU0 = sprite.u0
        bakedV0 = sprite.v0
        return newVbo
    }

    /** One chain column: 2 crossed 45° planes, one quad pair per Y in
     *  [-BAKE_HALF_HEIGHT, +BAKE_HALF_HEIGHT). Atlas-sprite UVs match the
     *  vanilla chain block. Single-winding quads — caller renders with
     *  `disableCull` so both sides are visible from one quad. */
    private fun bakeColumn(builder: BufferBuilder, sprite: TextureAtlasSprite) {
        val w = CHAIN_HALF_WIDTH
        val uSpan = sprite.u1 - sprite.u0
        val uA0 = sprite.u0
        val uA1 = sprite.u0 + uSpan * 3f / 16f
        val uB0 = uA1
        val uB1 = sprite.u0 + uSpan * 6f / 16f
        val v0 = sprite.v0
        val v1 = sprite.v1

        for (y in -BAKE_HALF_HEIGHT until BAKE_HALF_HEIGHT) {
            val y0 = y.toFloat()
            val y1 = (y + 1).toFloat()
            // Plane A — diagonal (-w, -w) → (+w, +w), cols 0–2.
            quad(builder, -w, y0, -w,  w, y0,  w,  w, y1,  w, -w, y1, -w,
                uA0, uA1, v1, v0)
            // Plane B — perpendicular diagonal, cols 3–5.
            quad(builder, -w, y0,  w,  w, y0, -w,  w, y1, -w, -w, y1,  w,
                uB0, uB1, v1, v0)
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
    ) {
        val light = LightTexture.FULL_BRIGHT
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
