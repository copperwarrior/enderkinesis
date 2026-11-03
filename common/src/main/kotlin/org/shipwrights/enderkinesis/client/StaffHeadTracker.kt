package org.shipwrights.enderkinesis.client

import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3f
import java.util.WeakHashMap

/** Where each held staff of [staff] last drew its head, in world space. Client thread only. */
class StaffHeadTracker(private val staff: Class<out Item>) {

    private class Seen {
        val world = Vector3d()
        var atNanos = 0L
    }

    // Weak keys: held stacks are replaced on every inventory sync.
    private val seen = WeakHashMap<ItemStack, Seen>()
    private val viewRotation = Quaternionf()
    private val scratch = Vector3f()

    /** Call from a [StaffGemGlow.Aim] with the gem's pose and centre. */
    fun record(stack: ItemStack, pose: Matrix4f, centerX: Float, centerY: Float, centerZ: Float) {
        val camera = Minecraft.getInstance().gameRenderer.mainCamera
        viewRotation(camera.xRot, camera.yRot, viewRotation)
        pose.transformPosition(centerX, centerY, centerZ, scratch)
        viewRotation.transformInverse(scratch)
        val cam = camera.position
        seen.getOrPut(stack) { Seen() }.apply {
            world.set(cam.x + scratch.x, cam.y + scratch.y, cam.z + scratch.z)
            atNanos = System.nanoTime()
        }
    }

    fun position(owner: LivingEntity, partialTick: Float, out: Vector3d) {
        val head = staffOf(owner)?.let { seen[it] }
        if (head != null && System.nanoTime() - head.atNanos < FRESH_NANOS) {
            out.set(head.world)
            return
        }
        // Not drawn lately (off screen): above the eyes, about where a held staff's head is.
        val eye = owner.getEyePosition(partialTick)
        out.set(eye.x, eye.y + FALLBACK_LIFT, eye.z)
    }

    private fun staffOf(owner: LivingEntity): ItemStack? = when {
        owner.isUsingItem && staff.isInstance(owner.useItem.item) -> owner.useItem
        staff.isInstance(owner.mainHandItem.item) -> owner.mainHandItem
        staff.isInstance(owner.offhandItem.item) -> owner.offhandItem
        else -> null
    }

    fun clear() = seen.clear()

    companion object {
        private const val FRESH_NANOS = 250_000_000L
        private const val FALLBACK_LIFT = 0.4

        /** Same rotation GameRenderer puts on the level pose stack; first-person hand poses share this camera space. */
        fun viewRotation(xRot: Float, yRot: Float, out: Quaternionf): Quaternionf =
            out.rotationX(xRot * Mth.DEG_TO_RAD).rotateY((yRot + 180f) * Mth.DEG_TO_RAD)
    }
}
