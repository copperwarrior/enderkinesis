package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.model.HumanoidModel
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.entity.EntityRenderDispatcher
import net.minecraft.client.renderer.entity.LivingEntityRenderer
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.TamableAnimal
import net.minecraft.world.entity.monster.Zombie
import net.minecraft.world.entity.player.Player
import org.shipwrights.enderkinesis.mixin.EntityVehicleAccessor
import org.shipwrights.enderkinesis.mixin.LivingEntitySwimAmountAccessor
import org.shipwrights.enderkinesis.registry.EKEffects
import java.util.WeakHashMap

/**
 * Discord (Chaos) on the local player: their allies, other players and their own tamed
 * animals, are drawn as zombies. Each ally gets a client-only zombie that copies its
 * position, facing and walk, and its pose: crouching, swimming, sleeping or riding, as a player
 * is drawn so. Anything you couldn't see anyway (invisible to you, or a spectator) stays unseen
 * rather than turning into a zombie you can.
 */
object DiscordDisguise {

    private class StandIn(val zombie: Zombie, var lastTick: Int = -1)

    private val standIns = WeakHashMap<Entity, StandIn>()

    /** The zombie to draw instead of [entity], or null to draw it as it is. */
    @JvmStatic
    fun standIn(entity: Entity): Entity? {
        val player = Minecraft.getInstance().player ?: return null
        if (entity === player || !player.hasEffect(EKEffects.DISCORD.get())) return null
        val ally = entity is Player || (entity is TamableAnimal && entity.isOwnedBy(player))
        if (!ally || entity.isSpectator || entity.isInvisibleTo(player)) return null
        val standIn = standIns.getOrPut(entity) { StandIn(Zombie(EntityType.ZOMBIE, entity.level())) }
        copyPose(entity, standIn)
        return standIn.zombie
    }

    /**
     * Draws [stand] in [entity]'s place as `EntityRenderDispatcher.render` would draw [entity],
     * with what a zombie's renderer doesn't do itself for a player's pose: the crouch (the model's
     * own flag, and the player's lowered drawing) and the swimming body laid flat.
     */
    @JvmStatic
    fun render(dispatcher: EntityRenderDispatcher, entity: Entity, stand: Entity, x: Double, y: Double, z: Double, yaw: Float, partialTick: Float, pose: PoseStack, buffers: MultiBufferSource, light: Int) {
        val zombie = stand as? Zombie ?: return dispatcher.render(stand, x, y, z, yaw, partialTick, pose, buffers, light)
        val model = (dispatcher.getRenderer(zombie) as? LivingEntityRenderer<*, *>)?.model as? HumanoidModel<*>
        val crouching = entity.isCrouching
        val swim = zombie.getSwimAmount(partialTick)
        pose.pushPose()
        if (swim > 0f && entity is LivingEntity) {
            // As `PlayerRenderer.setupRotations`: about the body's own facing, tipped forward.
            val body = 180f - Mth.rotLerp(partialTick, zombie.yBodyRotO, zombie.yBodyRot)
            val tip = Mth.lerp(swim, 0f, if (entity.isInWater) -90f - entity.getViewXRot(partialTick) else -90f)
            pose.translate(x, y, z)
            pose.mulPose(Axis.YP.rotationDegrees(body))
            pose.mulPose(Axis.XP.rotationDegrees(tip))
            if (entity.isVisuallySwimming) pose.translate(0f, -1f, 0.3f)
            pose.mulPose(Axis.YP.rotationDegrees(-body))
            pose.translate(-x, -y, -z)
        }
        model?.crouching = crouching
        try {
            // A player crouching is drawn an eighth of a block lower (`PlayerRenderer.getRenderOffset`).
            dispatcher.render(zombie, x, if (crouching) y - 0.125 else y, z, yaw, partialTick, pose, buffers, light)
        } finally {
            model?.crouching = false
            pose.popPose()
        }
    }

    private fun copyPose(entity: Entity, standIn: StandIn) {
        val zombie = standIn.zombie
        zombie.setPos(entity.x, entity.y, entity.z)
        zombie.xo = entity.xo; zombie.yo = entity.yo; zombie.zo = entity.zo
        zombie.yRot = entity.yRot; zombie.yRotO = entity.yRotO
        zombie.xRot = entity.xRot; zombie.xRotO = entity.xRotO
        zombie.tickCount = entity.tickCount
        zombie.setOnGround(entity.onGround())
        zombie.pose = entity.pose
        // Sitting on what the ally rides, for the riding pose, without joining its passengers.
        (zombie as EntityVehicleAccessor).`enderkinesis$setVehicle`(entity.vehicle)
        if (entity is LivingEntity) {
            zombie.yBodyRot = entity.yBodyRot; zombie.yBodyRotO = entity.yBodyRotO
            zombie.yHeadRot = entity.yHeadRot; zombie.yHeadRotO = entity.yHeadRotO
            val swim = entity as LivingEntitySwimAmountAccessor
            (zombie as LivingEntitySwimAmountAccessor).apply {
                `enderkinesis$setSwimAmount`(swim.`enderkinesis$getSwimAmount`())
                `enderkinesis$setSwimAmountO`(swim.`enderkinesis$getSwimAmountO`())
            }
            entity.sleepingPos.ifPresentOrElse({ zombie.setSleepingPos(it) }, { zombie.clearSleepingPos() })
            // Advance the zombie's walk once per tick at the ally's walking speed.
            if (standIn.lastTick != entity.tickCount) {
                standIn.lastTick = entity.tickCount
                zombie.walkAnimation.update(entity.walkAnimation.speed(), 1f)
            }
        }
    }

    fun reset() = standIns.clear()
}
