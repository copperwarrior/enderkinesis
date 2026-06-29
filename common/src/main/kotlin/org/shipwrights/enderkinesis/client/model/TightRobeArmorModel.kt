package org.shipwrights.enderkinesis.client.model

import net.minecraft.client.model.HumanoidModel
import net.minecraft.client.model.geom.ModelLayerLocation
import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.PartDefinition
import net.minecraft.client.model.geom.builders.CubeDeformation
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition
import org.shipwrights.enderkinesis.EnderkinesisMod

/**
 * Tight-fit replacement bake for vanilla armor's [HumanoidModel]. Vanilla armor uses
 * [CubeDeformation] `0.5f` on the body layer (and `1.0f` on the helmet) to puff the mesh
 * out beyond the player skin — that's the ~1 px gap visible around the wearer. We bake
 * with [TIGHT_DEFORMATION] = `0.15f` so the robe sits nearly flush with the player model.
 *
 * Two layers because vanilla's armor renderer historically splits chest+helmet+boots
 * (which use the same humanoid mesh) from leggings (which use a half-mesh variant in
 * vanilla's [HumanoidArmorLayer] thanks to different deformation values). We keep the
 * split so a future leggings-specific tweak (e.g. dropping the head/arms cubes for the
 * leggings model) can land without touching every call site, but for v1 both layers use
 * the same tight humanoid mesh — `layer_1` and `layer_2` differ only in the texture path
 * the Fabric renderer points them at.
 */
object TightRobeArmorModel {

    /** Inset of the body/arms/legs armor mesh outside the player skin, in pixel-units of
     *  model space. Reference points: vanilla player inner skin is `0.0`, the outer
     *  "jacket" overlay layer is `+0.25`, vanilla armor body is `+0.5`. `0.3f` clears the
     *  jacket overlay by a thin gap while still being 40% tighter than vanilla armor. */
    private const val BODY_DEFORMATION = 0.3f

    /** Leggings-layer body dilation. Sits **inside** [BODY_DEFORMATION] so the chestplate
     *  body cube occludes the leggings body cube when both are worn — same trick vanilla
     *  uses with `1.0` outer / `0.5` inner. The 0.15 gap is half of [BODY_DEFORMATION]
     *  (proportionally identical to vanilla's `0.5` gap on `1.0`), large enough to clear
     *  any floating-point z-fight at any reasonable camera distance. */
    private const val LEGGINGS_BODY_DEFORMATION = 0.15f

    /** Head + hat deformation. Vanilla armor's helmet uses `1.0f` on the head cube and
     *  `1.5f` on the hat overlay (`CubeDeformation(1.0).extend(0.5)`); we keep both at
     *  the vanilla values so the helmet sits at standard size — only the body is tight. */
    private const val HEAD_DEFORMATION = 1.0f

    /** Layer for the helmet + chestplate + boots mesh (vanilla armor `layer_1`). */
    val INNER_LAYER: ModelLayerLocation =
        ModelLayerLocation(EnderkinesisMod.id("tight_robe_inner"), "main")

    /** Layer for the leggings mesh (vanilla armor `layer_2`). */
    val OUTER_LAYER: ModelLayerLocation =
        ModelLayerLocation(EnderkinesisMod.id("tight_robe_outer"), "main")

    /** Standalone witch-hat layer — only the head + (brim, hat_1, hat_2, hat_3)
     *  children. Baked at 128×128 because the artist's Blockbench export
     *  uses that atlas size; the body armor's 64×64 can't accommodate the
     *  hat's larger UV regions. Rendered in a second pass on top of the
     *  Blue-Witch helmet by [TightRobeArmorRenderer]. */
    val WITCH_HAT_LAYER: ModelLayerLocation =
        ModelLayerLocation(EnderkinesisMod.id("blue_witch_hat"), "main")

    /** Inner layer (helmet/chest/boots). 64×64 so it shares the same texture-size
     *  normalization as the outer layer — the materials we ship use a single 64×64 PNG
     *  for both `_layer_1` and `_layer_2`, and the mesh's `texOffs` calls were authored
     *  against vanilla's 64×32 coordinates. With texture size 64×32 the UVs would divide
     *  the y-offset by 32 and sample twice the intended row on a 64×64 PNG (e.g. body's
     *  texOffs(16,16) lands at pixel y=32 — the coat-tail region — instead of y=16). */
    fun createInnerLayer(): LayerDefinition =
        LayerDefinition.create(meshWithVanillaHelmet(), 64, 64)

    /** Leggings layer — 64×64 because coat-tail UVs occupy rows 32-50ish (offsets ported
     *  from `CatalogerModel`). The `_layer_2.png` files need to be extended to 64×64 so
     *  rows 32+ contain coat-tail texture data; rows 0-31 are the standard vanilla armor
     *  UV layout. See `dev/blockbench/tight_robe_armor_outer.bbmodel` for the editable
     *  Blockbench source — open it, edit the texture (or generate one via Texturing →
     *  Create Blank Texture at 64×64), then export the PNG to overwrite `_layer_2.png`. */
    fun createOuterLayer(): LayerDefinition =
        LayerDefinition.create(meshWithVanillaHelmetAndCoatTails(), 64, 64)

    /** Witch-hat-only mesh. The bake's root part acts as the head pivot —
     *  hat_1 and brim are direct children so the renderer can access them
     *  via `root.getChild("hat_1")` / `root.getChild("brim")`. Copying the
     *  helmet head's xRot/yRot/zRot onto this root makes the whole hat
     *  rotate with the player's head. UVs and offsets are ported from the
     *  artist's Blockbench export (`wohlonnogondonia_hat.java` — the
     *  updated version with hat_3_r1 x-offset = -2.5); the only conversion
     *  is BB world Y → MC head-local Y. */
    fun createWitchHatLayer(): LayerDefinition {
        val mesh = MeshDefinition()
        addWitchHat(mesh.root)
        return LayerDefinition.create(mesh, 128, 128)
    }

    /** Attach the brim + hat cone stack as direct children of [parent].
     *  When [parent] is the mesh root, the bake's root ModelPart will
     *  expose `"hat_1"` and `"brim"` directly via `root.getChild(name)` —
     *  which is what [TightRobeArmorRenderer]'s jiggle pass relies on.
     *  UVs reference the 128×128 `blue_witch_hat.png` atlas, so the
     *  layer above bakes at 128×128.
     *
     *  Coordinate conversion: head-local Y points DOWN (vanilla head cube
     *  goes from y=-8 at the top to y=0 at the neck pivot), so a hat that
     *  should *sit higher* in BB world needs a *more negative* head-local
     *  Y. Both `hat_1` and `brim` are placed at head-local `y = -5.5`
     *  (BB world `Y ≈ 29.5`) — the wearer-tested seating point. The
     *  inner-nested parts (`hat_2`, `hat_3`) keep their BB-relative
     *  offsets verbatim because they're children of `hat_1` and inherit
     *  its lift. */
    private fun addWitchHat(parent: PartDefinition) {
        // hat_1 — base of the witch hat cone. Sits at BB world Y ≈ 29.5
        // (head-local y = 24 - 29.5 = -5.5). Nudged forward 0.25 (head-local
        // Z is back-positive, so -0.25 = forward) — wearer-tested seating.
        val hat1 = parent.addOrReplaceChild(
            "hat_1",
            CubeListBuilder.create(),
            PartPose.offset(0f, -5.5f, -0.25f),
        )
        hat1.addOrReplaceChild(
            "hat_1_r1",
            CubeListBuilder.create()
                .texOffs(0, 26)
                .addBox(-6.0f, -5.625f, -6.0f, 12.0f, 7.5f, 12.0f, CubeDeformation(0.0f)),
            PartPose.offsetAndRotation(0.0f, 0.0f, 0.0f, -0.3927f, 0.0f, 0.0f),
        )

        // hat_2 — middle cone segment, child of hat_1. Nudged slightly
        // forward (Z = 1.75 vs the original 2.0) per the BB update so the
        // taller cube doesn't poke off the back of the brim.
        val hat2 = hat1.addOrReplaceChild(
            "hat_2",
            CubeListBuilder.create(),
            PartPose.offset(0.0f, -5.0f, 1.75f),
        )
        hat2.addOrReplaceChild(
            "hat_2_r1",
            CubeListBuilder.create()
                .texOffs(0, 45)
                .addBox(-4.5f, -6.2402f, -4.4343f, 9.0f, 8.0f, 9.0f, CubeDeformation(0.0f)),
            PartPose.offsetAndRotation(0.0f, 0.0f, 0.25f, -0.3927f, 0.0f, 0.0f),
        )

        // hat_3 — tip of the cone, child of hat_2.
        val hat3 = hat2.addOrReplaceChild(
            "hat_3",
            CubeListBuilder.create(),
            PartPose.offset(0.0f, -5.6938f, 2.3097f),
        )
        hat3.addOrReplaceChild(
            "hat_3_r1",
            CubeListBuilder.create()
                .texOffs(36, 48)
                .addBox(-2.5f, -5.9142f, -2.75f, 5.0f, 9.0f, 5.0f, CubeDeformation(0.0f)),
            PartPose.offsetAndRotation(0.0f, 0.0f, 0.25f, -0.7854f, 0.0f, 0.0f),
        )

        // brim — flat 24×2×24 slab, symmetric about the pivot. The BB
        // export had an asymmetric cube (-20…4 X, -4…20 Z) with the
        // pivot offset by (+8, -8) to land it on the head; we move the
        // cube to (-12…12, -12…12) and zero out the X/Z pivot so the
        // pivot sits at the centre of the slab. Texture UVs come from the
        // cube's *dimensions* (still 24×2×24) and texOffs, so moving the
        // cube's *position* doesn't change what pixels each face samples.
        parent.addOrReplaceChild(
            "brim",
            CubeListBuilder.create()
                .texOffs(0, 0)
                .addBox(-12.0f, -2.0f, -12.0f, 24.0f, 2.0f, 24.0f, CubeDeformation(0.0f)),
            PartPose.offset(0.0f, -5.5f, 0.0f),
        )
    }

    /** Build a humanoid mesh where body/arms/legs use [BODY_DEFORMATION] (tight) but the
     *  head + hat are overridden back to [HEAD_DEFORMATION] so the helmet sits at
     *  standard vanilla-armor size. The hat extends `+0.5` exactly like vanilla armor.
     *
     *  Also attaches the cataloger hood pieces (`hood_flop_2` + `hood_flop_1`) as children
     *  of `head`. The hood is in the mesh for every material, but the renderer toggles its
     *  visibility per material — only the End Cult set shows it. The flap rotations are
     *  animated cape-style at runtime by `TightRobeArmorRenderer.applyHoodFlap`. */
    private fun meshWithVanillaHelmet(): MeshDefinition {
        val mesh = HumanoidModel.createMesh(CubeDeformation(BODY_DEFORMATION), 0.0f)
        val root = mesh.root
        val headDef = CubeDeformation(HEAD_DEFORMATION)
        val head = root.addOrReplaceChild(
            "head",
            CubeListBuilder.create().texOffs(0, 0).addBox(-4.0f, -8.0f, -4.0f, 8.0f, 8.0f, 8.0f, headDef),
            PartPose.offset(0.0f, 0.0f, 0.0f),
        )
        // `extend(0.5f)` is vanilla armor's gap between head cube (1.0) and hat overlay
        // (1.5) — that 0.5 px halo is where the back-side of the hat overlay shows through
        // the translucent head cube. Tighten to 0.15 so the hat sits almost flush with
        // the head (1.0 → 1.15), reducing the visible halo to ~0.15 px while still
        // clearing the head cube without z-fighting.
        root.addOrReplaceChild(
            "hat",
            CubeListBuilder.create().texOffs(32, 0).addBox(-4.0f, -8.0f, -4.0f, 8.0f, 8.0f, 8.0f, headDef.extend(0.15f)),
            PartPose.offset(0.0f, 0.0f, 0.0f),
        )
        // Re-declare body so we get a handle to attach pearl children to. Cube identical
        // to vanilla HumanoidModel's body at our tight `BODY_DEFORMATION`.
        val body = root.addOrReplaceChild(
            "body",
            CubeListBuilder.create()
                .texOffs(16, 16)
                .addBox(-4.0f, 0.0f, -2.0f, 8.0f, 12.0f, 4.0f, CubeDeformation(BODY_DEFORMATION)),
            PartPose.offset(0.0f, 0.0f, 0.0f),
        )
        addHood(head)
        addPearls(body)
        return mesh
    }

    /** Three End Cult chestplate pearls (front-of-chest necklace beads). Artist added them
     *  as additional cubes on the body in `robe_with_hood.java`; we split each into its
     *  own [PartDefinition] with a pivot at the top-centre of the cube so the renderer
     *  can swing each independently around its chain-attachment point.
     *
     *  All three share texOffs `(0, 44)` — a 2×2×2 UV region that all three pearls sample
     *  from, so a single pixel-region on the texture serves every bead. */
    private fun addPearls(body: PartDefinition) {
        val pearlCube = CubeDeformation(0.0f)
        // Centre pearl — hangs lower (y=1.5 vs 0.25 for the side ones), dips below the
        // line of the necklace as the chain droops in the middle.
        // Z pivots shifted from artist's -3.0 to -2.5 — half a pixel closer to the body
        // front, so the pearls sit just slightly recessed against the chest.
        body.addOrReplaceChild(
            "pearl_center",
            CubeListBuilder.create()
                .texOffs(0, 44)
                .addBox(-1.0f, 0.0f, -1.0f, 2.0f, 2.0f, 2.0f, pearlCube),
            PartPose.offset(0.0f, 1.5f, -2.5f),
        )
        body.addOrReplaceChild(
            "pearl_left",
            CubeListBuilder.create()
                .texOffs(0, 44)
                .addBox(-1.0f, 0.0f, -1.0f, 2.0f, 2.0f, 2.0f, pearlCube),
            PartPose.offset(-3.75f, 0.25f, -2.5f),
        )
        body.addOrReplaceChild(
            "pearl_right",
            CubeListBuilder.create()
                .texOffs(0, 44)
                .addBox(-1.0f, 0.0f, -1.0f, 2.0f, 2.0f, 2.0f, pearlCube),
            PartPose.offset(3.75f, 0.25f, -2.5f),
        )
    }

    /** Append the End Cult hood pieces to [head]. The artist's Blockbench export
     *  (`dev/blockbench/robe_with_hood.java`) used a shifted head pose `(0, 18, 11)`; we
     *  convert hood_flop_2's offset back to the vanilla head pivot at `(0, 0, 0)`:
     *
     *      world = head.offset + hood.offset
     *      world = (0, 18, 11) + (0, -26.2001, -5.5128) = (0, -8.2001, 5.4872)
     *
     *  Children of hood_flop_2 keep their artist-export poses unchanged. The rest pose
     *  here is "hood at rest" — the renderer rotates these xRot values toward the artist's
     *  -32°/-35° caps based on movement. */
    private fun addHood(head: PartDefinition) {
        val hoodFlop2 = head.addOrReplaceChild(
            "hood_flop_2",
            CubeListBuilder.create(),
            PartPose.offset(0.0f, -8.2001f, 5.4872f),
        )
        hoodFlop2.addOrReplaceChild(
            "hood_flop_2_r1",
            CubeListBuilder.create()
                .texOffs(30, 32)
                .addBox(-4.0f, -4.182f, -1.9896f, 8.0f, 7.0f, 9.0f, CubeDeformation(0.0f)),
            PartPose.offsetAndRotation(0.0f, 3.75f, -2.0f, -0.7854f, 0.0f, 0.0f),
        )
        hoodFlop2.addOrReplaceChild(
            "hood_flop_1",
            CubeListBuilder.create()
                .texOffs(0, 32)
                .addBox(-2.0f, 0.0f, -3.0f, 4.0f, 9.0f, 3.0f, CubeDeformation(0.0f)),
            PartPose.offset(0.0f, 5.75f, 5.9f),
        )
    }

    /** Coat-tail pieces ported from the artist's re-export at
     *  `dev/blockbench/cataloger_robes.java`. The exported model uses a wrapper-group
     *  pattern (Blockbench's standard output for rotated cubes): each named part is an
     *  empty group whose only job is to carry an offset+rotation, with a `_r1` child
     *  holding the actual cube and its own Z=π flip. Pasted as-is here for fidelity —
     *  changing UVs / shapes in Blockbench and re-exporting should land verbatim. The
     *  robe group is reparented from root (artist's export) to `body` so it follows the
     *  body's animation through `copyPropertiesTo`. */
    private fun meshWithVanillaHelmetAndCoatTails(): MeshDefinition {
        val mesh = meshWithVanillaHelmet()
        val legDef = CubeDeformation(LEGGINGS_BODY_DEFORMATION)
        // Re-declare body using LEGGINGS_BODY_DEFORMATION so this layer's body cube sits
        // inside the chestplate's body cube — vanilla armor's outer/inner split, but
        // proportionally scaled to our tight-fit base. Both layers continue to draw
        // their own body texture region (layer_1 vs layer_2), but with the gap, the
        // chestplate's outer surface occludes the leggings' outer surface from view.
        val body = mesh.root.addOrReplaceChild(
            "body",
            CubeListBuilder.create()
                .texOffs(16, 16)
                .addBox(-4.0f, 0.0f, -2.0f, 8.0f, 12.0f, 4.0f, legDef),
            PartPose.offset(0.0f, 0.0f, 0.0f),
        )
        // Same trick on the leg cubes — boots draw the same leg cubes at BODY_DEFORMATION
        // (outer), so legs need to sit inside them too or boots + leggings z-fight on
        // the lower-leg overlap. Positions/UVs are copied verbatim from
        // [HumanoidModel.createMesh]; only the deformation differs.
        mesh.root.addOrReplaceChild(
            "right_leg",
            CubeListBuilder.create()
                .texOffs(0, 16)
                .addBox(-2.0f, 0.0f, -2.0f, 4.0f, 12.0f, 4.0f, legDef),
            PartPose.offset(-1.9f, 12.0f, 0.0f),
        )
        mesh.root.addOrReplaceChild(
            "left_leg",
            CubeListBuilder.create()
                .texOffs(0, 16)
                .mirror()
                .addBox(-2.0f, 0.0f, -2.0f, 4.0f, 12.0f, 4.0f, legDef),
            PartPose.offset(1.9f, 12.0f, 0.0f),
        )

        // Robe wrapper. Empty cube list — just a transform that orients the coat-tail
        // group at the bottom-front of the body, with the Z=π flip the artist set in
        // Blockbench. The cape-flap renderer rotates THIS part's xRot.
        val robe = body.addOrReplaceChild(
            "robe",
            CubeListBuilder.create(),
            PartPose.offsetAndRotation(0.0f, 12.188f, 1.3866f, -0.2618f, 0.0f, 3.1416f),
        )

        // robe_r1 — the actual front cube. Z=π flip undoes robe's Z=π so the texture
        // reads upright. texOffs(0, 32) is the front coat-tail UV.
        robe.addOrReplaceChild(
            "robe_r1",
            CubeListBuilder.create()
                .texOffs(0, 32)
                .addBox(-3.0f, -6.0f, -2.0f, 6.0f, 12.0f, 4.0f, CubeDeformation(0.0f)),
            PartPose.offsetAndRotation(0.0f, -6.0132f, -0.2394f, 0.0f, 0.0f, 3.1416f),
        )

        // body_r1 wrapper (left side flap).
        val bodyR1 = robe.addOrReplaceChild(
            "body_r1",
            CubeListBuilder.create(),
            PartPose.offsetAndRotation(7.0f, -5.7632f, -1.2394f, 0.0088f, 0.2527f, 0.0692f),
        )
        bodyR1.addOrReplaceChild(
            "body_r1_r1",
            CubeListBuilder.create()
                .texOffs(21, 33)
                .addBox(-1.5f, -6.0f, -1.5f, 3.0f, 12.0f, 3.0f, CubeDeformation(0.0f)),
            PartPose.offsetAndRotation(-3.5f, 0.0f, 0.5f, 0.0f, 0.0f, 3.1416f),
        )

        // body_r2 wrapper (right side flap). Both side flaps share UV with `body_r1_r1`'s
        // `texOffs(21, 33)` — symmetric pieces drawn from the same texels.
        val bodyR2 = robe.addOrReplaceChild(
            "body_r2",
            CubeListBuilder.create(),
            PartPose.offsetAndRotation(-7.0f, -5.7632f, -1.2394f, 0.0088f, -0.2527f, -0.0692f),
        )
        bodyR2.addOrReplaceChild(
            "body_r2_r1",
            CubeListBuilder.create()
                .texOffs(21, 33)
                .addBox(-1.5f, -6.0f, -1.5f, 3.0f, 12.0f, 3.0f, CubeDeformation(0.0f)),
            PartPose.offsetAndRotation(3.5f, 0.0f, 0.5f, 0.0f, 0.0f, -3.1416f),
        )

        return mesh
    }
}
