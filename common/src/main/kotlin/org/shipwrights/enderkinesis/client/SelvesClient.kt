package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import dev.architectury.event.events.client.ClientTickEvent
import dev.architectury.networking.NetworkManager
import kotlin.math.abs
import net.minecraft.client.Minecraft
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.resources.ResourceLocation
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import org.shipwrights.enderkinesis.mixin.WalkAnimationStateAccessor
import org.shipwrights.enderkinesis.ritual.Form
import org.shipwrights.enderkinesis.ritual.Selves
import org.shipwrights.enderkinesis.ritual.SelvesBodies

/** Draws players in a creature form as that creature, and players in another player's body with that player's skin. */
object SelvesClient {

    private const val SIZE_EPSILON = 1.0e-4f

    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, Selves.S2C_FORMS) { buf, _ ->
            val state = Selves.read(buf)
            Minecraft.getInstance().execute { Selves.receive(state) }
        }
        // An entity gets its UUID after its size is set, and a form can change at any time.
        ClientTickEvent.CLIENT_LEVEL_POST.register { level ->
            for (player in level.players()) {
                val size = player.getDimensions(player.pose)
                if (abs(player.bbWidth - size.width) > SIZE_EPSILON || abs(player.bbHeight - size.height) > SIZE_EPSILON) {
                    player.refreshDimensions()
                }
            }
            if (Selves.clientBodies.isEmpty()) return@register
            for (entity in level.entitiesForRendering()) {
                if (!SelvesBodies.isBody(entity)) continue
                val size = Player.STANDING_DIMENSIONS
                if (abs(entity.bbWidth - size.width) > SIZE_EPSILON || abs(entity.bbHeight - size.height) > SIZE_EPSILON) {
                    entity.refreshDimensions()
                }
            }
        }
    }

    // Render thread only; baked again on each resource reload.
    private var wideBody: PlayerBodyRenderer? = null
    private var slimBody: PlayerBodyRenderer? = null

    @JvmStatic
    fun bakeBodyRenderers(context: EntityRendererProvider.Context) {
        wideBody = PlayerBodyRenderer(context, false)
        slimBody = PlayerBodyRenderer(context, true)
    }

    @JvmStatic
    fun bodyRenderer(entity: Entity): EntityRenderer<*>? {
        val owner = SelvesBodies.ownerOf(entity) ?: return null
        val slim = Minecraft.getInstance().connection?.getPlayerInfo(owner)?.modelName == "slim"
        return if (slim) slimBody else wideBody
    }

    /** False when [player] is in no creature form, and vanilla draws it. */
    @JvmStatic
    fun render(
        player: AbstractClientPlayer, yaw: Float, partialTick: Float, pose: PoseStack, buffers: MultiBufferSource, light: Int,
    ): Boolean {
        if (player.isSpectator) return false
        val dummy = Selves.dummyOf(player) ?: return false
        mirror(player, dummy)
        Minecraft.getInstance().entityRenderDispatcher.getRenderer(dummy).render(dummy, yaw, partialTick, pose, buffers, light)
        return true
    }

    @JvmStatic
    fun skin(player: AbstractClientPlayer, vanilla: ResourceLocation): ResourceLocation =
        wornInfo(player)?.skinLocation ?: vanilla

    @JvmStatic
    fun modelName(player: AbstractClientPlayer, vanilla: String): String = wornInfo(player)?.modelName ?: vanilla

    private fun wornInfo(player: AbstractClientPlayer) =
        (Selves.formOf(player) as? Form.Of)?.let { Minecraft.getInstance().connection?.getPlayerInfo(it.owner) }

    private fun mirror(player: AbstractClientPlayer, dummy: LivingEntity) {
        dummy.setPosRaw(player.x, player.y, player.z)
        dummy.xo = player.xo
        dummy.yo = player.yo
        dummy.zo = player.zo
        dummy.yRot = player.yRot
        dummy.yRotO = player.yRotO
        dummy.xRot = player.xRot
        dummy.xRotO = player.xRotO
        dummy.yBodyRot = player.yBodyRot
        dummy.yBodyRotO = player.yBodyRotO
        dummy.yHeadRot = player.yHeadRot
        dummy.yHeadRotO = player.yHeadRotO
        dummy.tickCount = player.tickCount
        dummy.setOnGround(player.onGround())
        dummy.hurtTime = player.hurtTime
        dummy.deathTime = player.deathTime
        dummy.isInvisible = player.isInvisible
        dummy.swinging = player.swinging
        dummy.swingTime = player.swingTime
        dummy.attackAnim = player.attackAnim
        dummy.oAttackAnim = player.oAttackAnim
        val from = player.walkAnimation as WalkAnimationStateAccessor
        val to = dummy.walkAnimation as WalkAnimationStateAccessor
        to.`enderkinesis$setPosition`(from.`enderkinesis$getPosition`())
        to.`enderkinesis$setSpeed`(from.`enderkinesis$getSpeed`())
        to.`enderkinesis$setSpeedOld`(from.`enderkinesis$getSpeedOld`())
    }
}
