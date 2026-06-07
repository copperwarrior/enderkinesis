package org.shipwrights.enderkinesis.client

import com.mojang.logging.LogUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.block.model.BlockModel
import net.minecraft.client.renderer.block.model.BakedQuad
import net.minecraft.client.renderer.block.model.FaceBakery
import net.minecraft.client.resources.model.BlockModelRotation
import net.minecraft.client.resources.model.Material
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.inventory.InventoryMenu

/**
 * Builds (lazily, once at first render) the astrolabe's geometry split into four movable
 * sub-groups, matching the Blockbench group hierarchy in `models/block/ender_astrolabe.json`:
 *
 *  - [BakedGroups.base]: element 0 (the "base" element) — never moves.
 *  - [BakedGroups.shaft]: element 1 ("shaft") — moves with the `body` yaw.
 *  - [BakedGroups.pitchTop]: elements 2 + 3 ("pitch", "yaw" rings) — body yaw + `pitch_rot`
 *    pitch.
 *  - [BakedGroups.center]: elements 4 + 5 + 6 ("spyglass_a", "spyglass_b", "sphere") — body
 *    yaw + pitch_rot pitch + `center` roll.
 *
 *  Vanilla bakes a model's elements into a single flat list of [BakedQuad]s and discards the
 *  group hierarchy, so the standard `ModelRenderer` can't render sub-groups independently. We
 *  load the model JSON via [BlockModel.fromString] (which still gives us the per-element
 *  [BlockElement] records), bake each face manually with [FaceBakery], and group the resulting
 *  quads by element index — then the renderer can `pushPose` a transform between groups.
 *
 *  Bake is deferred to first use ([ensureBaked]) because the texture atlas isn't available
 *  until resource reload completes.
 */
object EnderAstrolabeGeometry {

    /** Resource location of the block-model JSON, AND of the sprite in the block atlas. They
     *  share a name; the model's `"1": "enderkinesis:block/ender_astrolabe"` texture entry
     *  points at the same atlas sprite. */
    private val MODEL_LOC = ResourceLocation("enderkinesis", "block/ender_astrolabe")
    private val MODEL_JSON = ResourceLocation("enderkinesis", "models/block/ender_astrolabe.json")

    /** Indices into the model's `elements` array for each animation group. */
    private val BASE_INDICES = intArrayOf(0)
    private val SHAFT_INDICES = intArrayOf(1)
    private val PITCH_TOP_INDICES = intArrayOf(2, 3)             // pitch ring + yaw ring
    private val CENTER_INDICES = intArrayOf(4, 5, 6)             // spyglass_a, spyglass_b, sphere

    /** `body` group's rotation pivot in block units (16 px = 1 block), per the Blockbench JSON.
     *  Converted to render units (1 px = 1/16 block) when applied to a [PoseStack]. */
    const val BODY_ORIGIN_PX_X: Float = 8f
    const val BODY_ORIGIN_PX_Y: Float = 1f
    const val BODY_ORIGIN_PX_Z: Float = 8f

    /** `pitch_rot` group's rotation pivot. The Blockbench JSON ships with `(0, 0, 0)` (likely
     *  an unset default) which placed the pivot at the model's corner — every pitch rotation
     *  swung the whole top of the astrolabe out into space. The natural pivot is the ring
     *  centre, which every individual ring element uses as its own `rotation.origin`. */
    const val PITCH_ORIGIN_PX_X: Float = 8f
    const val PITCH_ORIGIN_PX_Y: Float = 11f
    const val PITCH_ORIGIN_PX_Z: Float = 8f

    /** `center` group's rotation pivot. The Blockbench JSON had this at `(8, 12, 7)` but
     *  that point sits off-centre from the actual geometric mass of the group — the sphere
     *  element occupies `(5..11, 8..14, 5..11)` (centre `(8, 11, 8)`) and spyglass_b uses
     *  `(8, 11, 8)` as its own rotation origin. Using `(8, 11, 8)` makes the roll spin the
     *  globe around its true centre and the spyglasses around the ring axis they share. */
    const val CENTER_ORIGIN_PX_X: Float = 8f
    const val CENTER_ORIGIN_PX_Y: Float = 11f
    const val CENTER_ORIGIN_PX_Z: Float = 8f

    private val LOG = LogUtils.getLogger()

    @Volatile private var baked: BakedGroups? = null

    data class BakedGroups(
        val base: List<BakedQuad>,
        val shaft: List<BakedQuad>,
        val pitchTop: List<BakedQuad>,
        val center: List<BakedQuad>,
    )

    /** Returns the baked groups, or null if not yet ready (texture atlas missing / model
     *  load failed). Safe to call every frame — bakes once and caches. */
    fun ensureBaked(mc: Minecraft): BakedGroups? {
        baked?.let { return it }
        return try {
            val res = mc.resourceManager.getResource(MODEL_JSON).orElse(null) ?: return null
            val text = res.openAsReader().use { it.readText() }
            val model = BlockModel.fromString(text)
            val elements = model.elements
            if (elements.size < 7) {
                LOG.warn("[EK] astrolabe model has {} elements (expected 7); animation disabled",
                    elements.size)
                return null
            }
            val sprite = mc.modelManager
                .getAtlas(InventoryMenu.BLOCK_ATLAS)
                .getSprite(MODEL_LOC)
            // Resolved a real texture (not the missing-no sprite) means the atlas is loaded.
            val faceBakery = FaceBakery()

            fun bakeIndices(indices: IntArray): List<BakedQuad> {
                val out = ArrayList<BakedQuad>(indices.size * 6)
                for (i in indices) {
                    val el = elements[i]
                    for ((dir, face) in el.faces) {
                        out.add(faceBakery.bakeQuad(
                            el.from, el.to, face, sprite, dir,
                            BlockModelRotation.X0_Y0, el.rotation, el.shade,
                            MODEL_LOC,
                        ))
                    }
                }
                return out
            }

            val result = BakedGroups(
                base = bakeIndices(BASE_INDICES),
                shaft = bakeIndices(SHAFT_INDICES),
                pitchTop = bakeIndices(PITCH_TOP_INDICES),
                center = bakeIndices(CENTER_INDICES),
            )
            baked = result
            LOG.info("[EK] astrolabe geometry baked: base={} shaft={} pitchTop={} center={} quads",
                result.base.size, result.shaft.size, result.pitchTop.size, result.center.size)
            result
        } catch (t: Throwable) {
            LOG.error("[EK] failed to bake astrolabe geometry", t)
            null
        }
    }
}
