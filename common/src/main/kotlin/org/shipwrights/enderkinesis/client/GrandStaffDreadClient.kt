package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.shipwrights.enderkinesis.item.GrandStaffDread
import org.shipwrights.enderkinesis.item.GrandStaffOfDreadItem
import org.shipwrights.enderkinesis.registry.EKParticles
import org.shipwrights.enderkinesis.spell.SpellMote

/** Grand Staff of Dread visuals: dark tendrils from the orb to each afflicted entity, and abyssal eyes. Client thread only. */
object GrandStaffDreadClient : StaffGemGlow.Aim {

    private const val DENSITY = 0.6f
    private const val EYE_CHANCE = 0.25f
    private const val EYE_SPREAD = 0.6
    // Eyes around the camera entity stay out of its face.
    private const val SELF_EYE_MIN = 2.0
    private const val SELF_EYE_MAX = 4.0

    private val ORB_TEAL = floatArrayOf(0.05f, 0.20f, 0.17f)
    private val ABYSS = floatArrayOf(0.03f, 0.01f, 0.05f)

    private val links = StaffTendrilLinks(
        GrandStaffOfDreadItem::class.java, GrandStaffDread::isCasting, GrandStaffDread::targetsOf,
        TendrilStyle(0.16f, ORB_TEAL, ABYSS), TendrilStyle(0.09f, ORB_TEAL, ABYSS), DENSITY,
    )

    fun init() {
        ClientTickEvent.CLIENT_LEVEL_POST.register(ClientTickEvent.ClientLevel { level -> tick(level) })
    }

    fun onDisconnect() = links.clear()

    private fun tick(level: ClientLevel) {
        links.tick(level)
        for (target in links.liveTargets) spawnEye(level, target)
    }

    private fun spawnEye(level: ClientLevel, target: LivingEntity) {
        val random = level.random
        if (random.nextFloat() >= EYE_CHANCE) return
        val x: Double
        val z: Double
        if (target === Minecraft.getInstance().cameraEntity) {
            val angle = random.nextDouble() * Math.PI * 2.0
            val dist = SELF_EYE_MIN + random.nextDouble() * (SELF_EYE_MAX - SELF_EYE_MIN)
            x = target.x + Math.cos(angle) * dist
            z = target.z + Math.sin(angle) * dist
        } else {
            x = target.x + (random.nextDouble() * 2.0 - 1.0) * EYE_SPREAD
            z = target.z + (random.nextDouble() * 2.0 - 1.0) * EYE_SPREAD
        }
        val y = target.eyeY + (random.nextDouble() * 2.0 - 1.0) * EYE_SPREAD
        level.addParticle(EKParticles.mote(SpellMote.YGANN_EYE), x, y, z, 0.0, 0.0, 0.0)
    }

    // Called with the other world-space volumes, before the fog passes.
    @JvmStatic
    fun render(poseStack: PoseStack, projection: Matrix4f, camera: Camera) = links.render(poseStack, projection, camera)

    /** Records where the orb draws. The orb does not turn. */
    override fun rotation(
        stack: ItemStack, pose: Matrix4f, centerX: Float, centerY: Float, centerZ: Float, spinYaw: Float, out: Quaternionf,
    ): Quaternionf {
        links.record(stack, pose, centerX, centerY, centerZ)
        return out.rotationY(spinYaw)
    }
}
