package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.authlib.GameProfile
import java.util.UUID
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.PlayerInfo
import net.minecraft.world.level.block.entity.SkullBlockEntity
import net.minecraft.client.model.HumanoidModel
import net.minecraft.client.model.PlayerModel
import net.minecraft.client.model.AnimationUtils
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.geom.builders.CubeDeformation
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.HumanoidMobRenderer
import net.minecraft.client.renderer.entity.LivingEntityRenderer
import net.minecraft.client.renderer.entity.RenderLayerParent
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer
import net.minecraft.client.renderer.entity.layers.RenderLayer
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.client.renderer.texture.HttpTexture
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.item.BowItem
import net.minecraft.world.item.CrossbowItem
import net.minecraft.world.item.TridentItem
import org.lwjgl.opengl.GL11
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.entity.RaisedSkeletonEntity
import org.shipwrights.enderkinesis.mixin.HttpTextureAccessor

class RaisedSkeletonRenderer(ctx: EntityRendererProvider.Context) :
    HumanoidMobRenderer<RaisedSkeletonEntity, RaisedSkeletonRenderer.BonesModel>(
        ctx, BonesModel(ctx.bakeLayer(ModelLayers.SKELETON)), SHADOW_RADIUS,
    ) {

    private val boneBody: BonesModel = model
    private val zombieBody = BonesModel(ctx.bakeLayer(ModelLayers.ZOMBIE), thinArms = false)

    init {
        addLayer(SkinLayer(this))
        addLayer(
            HumanoidArmorLayer(
                this,
                HumanoidModel(ctx.bakeLayer(ModelLayers.SKELETON_INNER_ARMOR)),
                HumanoidModel(ctx.bakeLayer(ModelLayers.SKELETON_OUTER_ARMOR)),
                ctx.modelManager,
            ),
        )
    }

    override fun render(
        entity: RaisedSkeletonEntity,
        entityYaw: Float,
        partialTick: Float,
        poseStack: PoseStack,
        buffer: MultiBufferSource,
        packedLight: Int,
    ) {
        // One renderer, two bodies: the layers read the body through getParentModel, so they follow the swap.
        model = if (entity.isZombie) zombieBody else boneBody
        poseStack.pushPose()
        // HumanoidModel's riding legs reach forward at hip height; drop the body so they rest on the ground.
        if (entity.isInSittingPose) poseStack.translate(0.0, -RaisedSkeletonEntity.SIT_DROP, 0.0)
        val buried = 1f - entity.riseProgress(partialTick)
        // Ease out: the skeleton breaks the surface fast, then slows as it stands clear.
        if (buried > 0f) poseStack.translate(0.0, -(buried * buried * entity.bbHeight).toDouble(), 0.0)
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight)
        poseStack.popPose()
    }

    override fun getTextureLocation(entity: RaisedSkeletonEntity): ResourceLocation =
        if (entity.isZombie) ZOMBIE_TEXTURE else SKELETON_TEXTURE

    // Stands in for SkeletonModel, whose setupAnim forces zombie arms over any non-bow pose (crossbows included).
    // Translucent, not vanilla's cutout: the hat front has soft alpha that cutout snaps to opaque or nothing.
    class BonesModel(root: ModelPart, private val thinArms: Boolean = true) :
        HumanoidModel<RaisedSkeletonEntity>(root, RenderType::entityTranslucent) {
        override fun prepareMobModel(
            entity: RaisedSkeletonEntity, limbSwing: Float, limbSwingAmount: Float, partialTick: Float,
        ) {
            val main = entity.mainHandItem
            val pose = when {
                main.item is CrossbowItem && entity.isChargingCrossbow -> ArmPose.CROSSBOW_CHARGE
                main.item is CrossbowItem && (entity.isAggressive || CrossbowItem.isCharged(main)) -> ArmPose.CROSSBOW_HOLD
                main.item is BowItem && entity.isAggressive -> ArmPose.BOW_AND_ARROW
                main.item is TridentItem && entity.isAggressive -> ArmPose.THROW_SPEAR
                else -> ArmPose.EMPTY
            }
            val right = entity.mainArm == HumanoidArm.RIGHT
            rightArmPose = if (right) pose else ArmPose.EMPTY
            leftArmPose = if (right) ArmPose.EMPTY else pose
            super.prepareMobModel(entity, limbSwing, limbSwingAmount, partialTick)
            riding = riding || entity.isInSittingPose
        }

        override fun setupAnim(
            entity: RaisedSkeletonEntity,
            limbSwing: Float,
            limbSwingAmount: Float,
            ageInTicks: Float,
            netHeadYaw: Float,
            headPitch: Float,
        ) {
            super.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch)
            if (entity.isAggressive && entity.rangedWeaponHand() == null) {
                // SkeletonModel.setupAnim's melee swing.
                val swing = Mth.sin(attackTime * Mth.PI)
                val ease = Mth.sin((1.0f - (1.0f - attackTime) * (1.0f - attackTime)) * Mth.PI)
                rightArm.zRot = 0.0f
                leftArm.zRot = 0.0f
                rightArm.yRot = -(0.1f - swing * 0.6f)
                leftArm.yRot = 0.1f - swing * 0.6f
                rightArm.xRot = -Mth.HALF_PI - (swing * 1.2f - ease * 0.4f)
                leftArm.xRot = -Mth.HALF_PI - (swing * 1.2f - ease * 0.4f)
                AnimationUtils.bobArms(rightArm, leftArm, ageInTicks)
            }
        }

        // SkeletonModel's offset: the held item sits at the 2-wide bone arm, not a 4-wide player arm.
        override fun translateToHand(side: HumanoidArm, poseStack: PoseStack) {
            if (!thinArms) {
                super.translateToHand(side, poseStack)
                return
            }
            val shift = if (side == HumanoidArm.RIGHT) 1.0f else -1.0f
            val arm = getArm(side)
            arm.x += shift
            arm.translateAndRotate(poseStack)
            arm.x -= shift
        }
    }

    /** The raised player's skin, cut by the wide/slim mask, on a player mesh just outside the bones. */
    private class SkinLayer(
        parent: RenderLayerParent<RaisedSkeletonEntity, BonesModel>,
    ) : RenderLayer<RaisedSkeletonEntity, BonesModel>(parent) {

        private val wide = inflatedPlayerModel(slim = false)
        private val slim = inflatedPlayerModel(slim = true)
        private val masked = MaskedSkins()
        private val profiles = SkinProfiles()

        override fun render(
            poseStack: PoseStack,
            buffer: MultiBufferSource,
            packedLight: Int,
            entity: RaisedSkeletonEntity,
            limbSwing: Float,
            limbSwingAmount: Float,
            partialTick: Float,
            ageInTicks: Float,
            netHeadYaw: Float,
            headPitch: Float,
        ) {
            val uuid = entity.skinUuid ?: return
            if (entity.isInvisible) return
            val info = profiles.infoFor(uuid, entity.skinName)
            val isSlim = (info?.modelName ?: DefaultPlayerSkin.getSkinModelName(uuid)) == SLIM_MODEL
            val skin = info?.skinLocation ?: DefaultPlayerSkin.getDefaultSkin(uuid)
            val texture = masked.get(skin, isSlim) ?: return
            val model = if (isSlim) slim else wide
            parentModel.copyPropertiesTo(model)
            // copyPropertiesTo covers HumanoidModel parts only; PlayerModel.setupAnim normally syncs the outer layer.
            model.leftSleeve.copyFrom(model.leftArm)
            model.rightSleeve.copyFrom(model.rightArm)
            model.leftPants.copyFrom(model.leftLeg)
            model.rightPants.copyFrom(model.rightLeg)
            model.jacket.copyFrom(model.body)
            model.renderToBuffer(
                poseStack,
                buffer.getBuffer(RenderType.entityCutoutNoCull(texture)),
                packedLight,
                LivingEntityRenderer.getOverlayCoords(entity, 0f),
                1f, 1f, 1f, 1f,
            )
        }

        private fun inflatedPlayerModel(slim: Boolean): PlayerModel<RaisedSkeletonEntity> {
            val mesh = PlayerModel.createMesh(CubeDeformation(SKIN_INFLATE), slim)
            return PlayerModel(LayerDefinition.create(mesh, SKIN_SIZE, SKIN_SIZE).bakeRoot(), slim)
        }
    }

    /** Like the Wand of Ga'Mylan: a tab-list entry only loads a signed skin, so a dev or offline login shows a default
     *  one. Without a loaded tab skin, fill the profile from Mojang by name. Render thread only. */
    private class SkinProfiles {
        private val filled = HashMap<UUID, PlayerInfo>()
        private val requested = HashSet<UUID>()

        fun infoFor(uuid: UUID, name: String): PlayerInfo? {
            val tab = Minecraft.getInstance().connection?.getPlayerInfo(uuid)
            if (tab != null && tab.isSkinLoaded) return tab
            filled[uuid]?.let { return it }
            if (name.isNotEmpty() && requested.add(uuid)) {
                SkullBlockEntity.updateGameprofile(GameProfile(uuid, name)) { profile ->
                    Minecraft.getInstance().execute {
                        if (profile != null) filled[uuid] = PlayerInfo(profile, false)
                    }
                }
            }
            return tab
        }
    }

    /** Per-skin copies of player skins with the mask's luminance multiplied into alpha. Render thread only. */
    private class MaskedSkins {
        private val wide = HashMap<ResourceLocation, ResourceLocation>()
        private val slim = HashMap<ResourceLocation, ResourceLocation>()
        private var wideMask: NativeImage? = null
        private var slimMask: NativeImage? = null

        fun get(skin: ResourceLocation, isSlim: Boolean): ResourceLocation? {
            val cache = if (isSlim) slim else wide
            cache[skin]?.let { return it }
            val built = build(skin, isSlim) ?: return null
            cache[skin] = built
            return built
        }

        private fun build(skin: ResourceLocation, isSlim: Boolean): ResourceLocation? {
            val textures = Minecraft.getInstance().textureManager
            val source = textures.getTexture(skin)
            // Until the download lands, an HttpTexture holds the fallback Steve skin.
            if (source is HttpTexture && !(source as HttpTextureAccessor).`enderkinesis$isUploaded`()) return null
            val mask = mask(isSlim) ?: return null
            RenderSystem.bindTexture(source.id)
            val width = GlStateManager._getTexLevelParameter(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH)
            val height = GlStateManager._getTexLevelParameter(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT)
            if (width <= 0 || height <= 0) return null
            val out = NativeImage(width, height, true)
            NativeImage(width, height, false).use { pixels ->
                pixels.downloadTexture(0, false)
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val keep = coverage(mask.getPixelRGBA(x * mask.width / width, y * mask.height / height))
                        val c = pixels.getPixelRGBA(x, y)
                        val alpha = (c ushr ALPHA_SHIFT) * keep / CHANNEL_MAX
                        out.setPixelRGBA(x, y, (c and RGB_BITS) or (alpha shl ALPHA_SHIFT))
                    }
                }
            }
            val id = EnderkinesisMod.id(
                "raised_skeleton_skin/${if (isSlim) "slim" else "wide"}/${skin.namespace}/${skin.path}",
            )
            textures.register(id, DynamicTexture(out))
            return id
        }

        private fun mask(isSlim: Boolean): NativeImage? {
            val loaded = if (isSlim) slimMask else wideMask
            if (loaded != null) return loaded
            val resource = Minecraft.getInstance().resourceManager
                .getResource(if (isSlim) SLIM_MASK else WIDE_MASK).orElse(null) ?: return null
            val image = resource.open().use(NativeImage::read)
            if (isSlim) slimMask = image else wideMask = image
            return image
        }

        /** Mask luminance (red channel) times mask alpha; NativeImage pixels are ABGR. */
        private fun coverage(abgr: Int): Int = (abgr and CHANNEL_MAX) * (abgr ushr ALPHA_SHIFT) / CHANNEL_MAX
    }

    companion object {
        private const val SHADOW_RADIUS = 0.5f
        private const val SKIN_INFLATE = 0.02f
        private const val SKIN_SIZE = 64
        private const val SLIM_MODEL = "slim"
        private const val ALPHA_SHIFT = 24
        private const val CHANNEL_MAX = 0xFF
        private const val RGB_BITS = 0x00FFFFFF
        private val SKELETON_TEXTURE = EnderkinesisMod.id("textures/entity/raised_skeleton.png")
        private val ZOMBIE_TEXTURE = EnderkinesisMod.id("textures/entity/raised_zombie.png")
        private val WIDE_MASK = EnderkinesisMod.id("textures/entity/raised_skeleton_alpha_wide.png")
        private val SLIM_MASK = EnderkinesisMod.id("textures/entity/raised_skeleton_alpha_slim.png")
    }
}
