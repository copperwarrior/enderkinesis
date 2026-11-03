package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.item.ItemStack
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3d
import org.shipwrights.enderkinesis.item.GrandStaffDecay
import org.shipwrights.enderkinesis.item.GrandStaffOfDecayItem
import org.shipwrights.enderkinesis.registry.EKParticles

/** Grand Staff of Decay visuals: fungal tendrils to each poisoned entity, and spores from the gem and the poisoned. */
object GrandStaffDecayClient : StaffGemGlow.Aim {

    private const val DENSITY = 0.6f
    private const val GEM_SPORE_CHANCE = 0.5f
    private const val GEM_SPREAD = 0.25
    private const val TARGET_SPORE_CHANCE = 0.3f
    private const val TARGET_SPREAD = 0.5

    private val GEM_CYAN = floatArrayOf(0.02f, 0.40f, 0.58f)
    private val MOULD = floatArrayOf(0.14f, 0.18f, 0.07f)

    private val links = StaffTendrilLinks(
        GrandStaffOfDecayItem::class.java, GrandStaffDecay::isCasting, GrandStaffDecay::targetsOf,
        TendrilStyle(0.16f, GEM_CYAN, MOULD), TendrilStyle(0.09f, GEM_CYAN, MOULD), DENSITY,
    )
    private val gem = Vector3d()

    fun init() {
        ClientTickEvent.CLIENT_LEVEL_POST.register(ClientTickEvent.ClientLevel { level -> tick(level) })
    }

    fun onDisconnect() = links.clear()

    private fun tick(level: ClientLevel) {
        links.tick(level)
        if (Minecraft.getInstance().isPaused) return
        val random = level.random
        for (caster in level.players()) {
            if (!GrandStaffDecay.isCasting(caster) || random.nextFloat() >= GEM_SPORE_CHANCE) continue
            links.headPosition(caster, 1f, gem)
            level.addParticle(EKParticles.rotSpore(), random.jitter(gem.x, GEM_SPREAD), random.jitter(gem.y, GEM_SPREAD), random.jitter(gem.z, GEM_SPREAD), 0.0, 0.0, 0.0)
        }
        for (target in links.liveTargets) {
            if (random.nextFloat() >= TARGET_SPORE_CHANCE) continue
            val y = target.y + random.nextDouble() * target.bbHeight
            level.addParticle(EKParticles.rotSpore(), random.jitter(target.x, TARGET_SPREAD), y, random.jitter(target.z, TARGET_SPREAD), 0.0, 0.0, 0.0)
        }
    }

    private fun RandomSource.jitter(at: Double, spread: Double): Double = at + (nextDouble() * 2.0 - 1.0) * spread

    // Called with the other world-space volumes, before the fog passes.
    @JvmStatic
    fun render(poseStack: PoseStack, projection: Matrix4f, camera: Camera) = links.render(poseStack, projection, camera)

    /** Records where the gem draws. The gem does not turn. */
    override fun rotation(
        stack: ItemStack, pose: Matrix4f, centerX: Float, centerY: Float, centerZ: Float, spinYaw: Float, out: Quaternionf,
    ): Quaternionf {
        links.record(stack, pose, centerX, centerY, centerZ)
        return out.rotationY(spinYaw)
    }
}
