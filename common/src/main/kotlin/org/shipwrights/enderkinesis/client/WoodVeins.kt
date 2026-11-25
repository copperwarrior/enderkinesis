package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.Minecraft
import net.minecraft.client.model.PlayerModel
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeDeformation
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.client.renderer.entity.LivingEntityRenderer
import net.minecraft.client.renderer.entity.RenderLayerParent
import net.minecraft.client.renderer.entity.layers.RenderLayer
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.inventory.InventoryMenu
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.duck.AntleredCrownHolder
import java.util.UUID

/**
 * Wogor log veins that grow from the crown band over a player as the wood fraction rises. They draw on their own
 * shell, between the skin overlay and armor, so they touch no other layer.
 */
object WoodVeins {

    @Volatile
    @JvmField
    var shader: ShaderInstance? = null

    private const val MAX_PLAYERS = 64
    private const val BUFFER_SIZE = 256

    /** Growth units per tick: one half-heart step (0.05 at 20 max health) grows in half a second. */
    private const val GROWTH_PER_TICK = 0.005f

    private class Veins(val slim: Boolean, val location: ResourceLocation) {
        var shown = 0f
        lateinit var renderType: RenderType
    }

    // Access order, so the least recently drawn player leaves first. Render thread only.
    private val cache = object : LinkedHashMap<UUID, Veins>(MAX_PLAYERS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UUID, Veins>): Boolean {
            if (size <= MAX_PLAYERS) return false
            Minecraft.getInstance().textureManager.release(eldest.value.location)
            return true
        }
    }

    /** The vein type for [player] this frame, or null when no vein shows. */
    private fun veinsFor(player: AbstractClientPlayer): Veins? {
        val target = (player as AntleredCrownHolder).`enderkinesis$veinGrowth`()
        val slim = player.modelName == SLIM
        var veins = cache[player.uuid]
        if (veins == null || veins.slim != slim) {
            if (target <= 0f) return null
            veins = build(player.uuid, slim)
        }
        veins.shown = approach(veins.shown, target, GROWTH_PER_TICK * Minecraft.getInstance().deltaFrameTime)
        return if (veins.shown > 0f) veins else null
    }

    private fun approach(from: Float, to: Float, step: Float): Float =
        if (from < to) minOf(from + step, to) else maxOf(from - step, to)

    private fun build(uuid: UUID, slim: Boolean): Veins {
        val mask = buildWoodVeinMask(uuid.mostSignificantBits xor uuid.leastSignificantBits, slim)
        val image = NativeImage(VEIN_SKIN_SIZE, VEIN_SKIN_SIZE, true)
        for (y in 0 until VEIN_SKIN_SIZE) {
            for (x in 0 until VEIN_SKIN_SIZE) {
                val order = mask[y * VEIN_SKIN_SIZE + x].toInt() and BYTE_MASK
                image.setPixelRGBA(x, y, OPAQUE or order)
            }
        }
        val location = EnderkinesisMod.id("wood_veins/$uuid")
        Minecraft.getInstance().textureManager.register(location, DynamicTexture(image))
        val veins = Veins(slim, location)
        veins.renderType = renderType(veins)
        cache[uuid] = veins
        return veins
    }

    private fun renderType(veins: Veins): RenderType = RenderType.create(
        "${EnderkinesisMod.MOD_ID}_wood_veins", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, BUFFER_SIZE,
        true, false,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.ShaderStateShard { shader?.also { it.setWogorLogRect() } })
            .setTextureState(RenderStateShard.TextureStateShard(veins.location, false, false))
            // Units 1 and 2 hold the overlay and the lightmap, so the atlas goes to unit 3.
            .setTexturingState(
                RenderStateShard.TexturingStateShard("${EnderkinesisMod.MOD_ID}_wood_veins", {
                    RenderSystem.setShaderTexture(ATLAS_UNIT, InventoryMenu.BLOCK_ATLAS)
                    shader?.let { it.safeGetUniform("Growth").set(veins.shown) }
                }) {},
            )
            .setTransparencyState(RenderStateShard.NO_TRANSPARENCY)
            .setCullState(RenderStateShard.NO_CULL)
            .setLightmapState(RenderStateShard.LIGHTMAP)
            .setOverlayState(RenderStateShard.OVERLAY)
            .createCompositeState(true),
    )

    fun renderBody(
        poseStack: PoseStack, buffers: MultiBufferSource, light: Int, player: AbstractClientPlayer,
        model: PlayerModel<AbstractClientPlayer>,
    ) {
        if (player.isInvisible) return
        val veins = veinsFor(player) ?: return
        val consumer = buffers.getBuffer(veins.renderType)
        val overlay = LivingEntityRenderer.getOverlayCoords(player, 0f)
        val shell = if (veins.slim) slimShell else wideShell
        renderPart(shell.head, model.head, poseStack, consumer, light, overlay)
        renderPart(shell.body, model.body, poseStack, consumer, light, overlay)
        renderPart(shell.rightArm, model.rightArm, poseStack, consumer, light, overlay)
        renderPart(shell.leftArm, model.leftArm, poseStack, consumer, light, overlay)
        renderPart(shell.rightLeg, model.rightLeg, poseStack, consumer, light, overlay)
        renderPart(shell.leftLeg, model.leftLeg, poseStack, consumer, light, overlay)
    }

    /** The first-person arm; vanilla draws the hand outside the render layers. */
    @JvmStatic
    fun renderHand(
        poseStack: PoseStack, buffers: MultiBufferSource, light: Int, player: AbstractClientPlayer,
        arm: ModelPart, rightArm: Boolean,
    ) {
        val veins = veinsFor(player) ?: return
        val shell = if (veins.slim) slimShell else wideShell
        val part = if (rightArm) shell.rightArm else shell.leftArm
        renderPart(part, arm, poseStack, buffers.getBuffer(veins.renderType), light, OverlayTexture.NO_OVERLAY)
    }

    private fun renderPart(
        part: ModelPart, posedLike: ModelPart, poseStack: PoseStack, consumer: VertexConsumer, light: Int, overlay: Int,
    ) {
        part.copyFrom(posedLike)
        part.render(poseStack, consumer, light, overlay)
    }

    private class Shell(root: ModelPart) {
        val head: ModelPart = root.getChild("head")
        val body: ModelPart = root.getChild("body")
        val rightArm: ModelPart = root.getChild("right_arm")
        val leftArm: ModelPart = root.getChild("left_arm")
        val rightLeg: ModelPart = root.getChild("right_leg")
        val leftLeg: ModelPart = root.getChild("left_leg")
    }

    private val wideShell: Shell by lazy { Shell(bakeShell(slim = false)) }
    private val slimShell: Shell by lazy { Shell(bakeShell(slim = true)) }

    // The base boxes and UVs of PlayerModel, inflated past the skin overlay (hat 0.5, others 0.25) and the crown
    // wreath (0.5), but inside armor (leggings 0.5, the rest 1.0). Poses come from the parent model each draw.
    private fun bakeShell(slim: Boolean): ModelPart {
        val mesh = MeshDefinition()
        val root = mesh.root
        val limb = CubeDeformation(LIMB_INFLATION)
        val arm = if (slim) 3f else 4f
        root.addOrReplaceChild(
            "head",
            CubeListBuilder.create().texOffs(0, 0).addBox(-4f, -8f, -4f, 8f, 8f, 8f, CubeDeformation(HEAD_INFLATION)),
            PartPose.ZERO,
        )
        root.addOrReplaceChild(
            "body", CubeListBuilder.create().texOffs(16, 16).addBox(-4f, 0f, -2f, 8f, 12f, 4f, limb), PartPose.ZERO,
        )
        root.addOrReplaceChild(
            "right_arm",
            CubeListBuilder.create().texOffs(40, 16).addBox(if (slim) -2f else -3f, -2f, -2f, arm, 12f, 4f, limb),
            PartPose.ZERO,
        )
        root.addOrReplaceChild(
            "left_arm", CubeListBuilder.create().texOffs(32, 48).addBox(-1f, -2f, -2f, arm, 12f, 4f, limb), PartPose.ZERO,
        )
        root.addOrReplaceChild(
            "right_leg", CubeListBuilder.create().texOffs(0, 16).addBox(-2f, 0f, -2f, 4f, 12f, 4f, limb), PartPose.ZERO,
        )
        root.addOrReplaceChild(
            "left_leg", CubeListBuilder.create().texOffs(16, 48).addBox(-2f, 0f, -2f, 4f, 12f, 4f, limb), PartPose.ZERO,
        )
        return LayerDefinition.create(mesh, VEIN_SKIN_SIZE, VEIN_SKIN_SIZE).bakeRoot()
    }

    private const val HEAD_INFLATION = 0.75f
    private const val LIMB_INFLATION = 0.375f
    private const val SLIM = "slim"
    private const val BYTE_MASK = 0xFF
    private const val OPAQUE = -0x1000000
    private const val ATLAS_UNIT = 3
}

class WoodVeinLayer(
    parent: RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>>,
) : RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>>(parent) {

    override fun render(
        poseStack: PoseStack, buffers: MultiBufferSource, light: Int, player: AbstractClientPlayer,
        limbSwing: Float, limbSwingAmount: Float, partialTick: Float, ageInTicks: Float, netHeadYaw: Float, headPitch: Float,
    ) = WoodVeins.renderBody(poseStack, buffers, light, player, parentModel)
}
