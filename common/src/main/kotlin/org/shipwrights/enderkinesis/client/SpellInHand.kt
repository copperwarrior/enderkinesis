package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import org.joml.Matrix3f
import org.joml.Vector3f
import org.shipwrights.enderkinesis.item.UlderScrollItem
import org.shipwrights.enderkinesis.spell.Shape

object SpellInHand {
    /** Keep equal to the value in [SpellHand]. */
    private const val AHEAD = 0.35
    /** The vanilla held-item layer puts the item this far past the fist, down the arm and out in front. */
    private const val ITEM_PAST_FIST = 0.125
    private const val ITEM_OUT = 0.125
    private const val FULL_CHARGE_TICKS = 30f
    private const val STALE_TICKS = 2.0

    private class Seen(val at: Vec3, val tick: Double)

    // Render thread only.
    private val seen = HashMap<Int, Seen>()

    /** Returns true when the spell takes the place of the item, also when the entity is invisible to the viewer. */
    @JvmStatic
    fun draw(entity: LivingEntity, stack: ItemStack, arm: HumanoidArm, pose: PoseStack, buffers: MultiBufferSource, light: Int): Boolean {
        if (stack.item !is UlderScrollItem) return false
        val spell = UlderScrollItem.spellOf(stack) ?: return false
        val mc = Minecraft.getInstance()
        // As in vanilla, an entity that is invisible to the viewer shows no spell.
        if (mc.player?.let { entity.isInvisibleTo(it) } == true) return true
        val level = entity.level()
        val time = SpellClock.time(level, mc.frameTime)
        // In the layer space, the arm goes down -z, and +y is out in front of the palm.
        val axis = Vec3(0.0, 0.0, -1.0)
        val centre = Vec3(0.0, -ITEM_OUT, -(AHEAD - ITEM_PAST_FIST))
        val draw = SpellCore.Draw(pose.last(), buffers, SpellCore.viewIn(pose.last()), light, time)
        val usedArm = if (entity.usedItemHand == InteractionHand.MAIN_HAND) entity.mainArm else entity.mainArm.opposite
        val heldShape = if (entity.isUsingItem && entity.useItem.item is UlderScrollItem && usedArm == arm) UlderScrollItem.heldShape(entity.useItem) else null
        if (heldShape != null) {
            val charge = if (heldShape == Shape.BOLT) ((entity.ticksUsingItem + mc.frameTime) / FULL_CHARGE_TICKS).coerceIn(0f, 1f) else 1f
            SpellCore.held(draw, centre, SpellCore.heldSize(charge, time), spell, charge, axis)
            remember(entity, pose, centre)
        } else {
            SpellCore.carried(draw, centre, spell, axis)
        }
        return true
    }

    /** [pose] maps [centre] to view space. The inverse view rotation then gives world axes. */
    private fun remember(entity: LivingEntity, pose: PoseStack, centre: Vec3) {
        // Ignore pictures outside the level, for example the inventory player.
        if (!SpellCubeFx.drawingLevel) return
        val view = pose.last().pose().transformPosition(Vector3f(centre.x.toFloat(), centre.y.toFloat(), centre.z.toFloat()))
        val world = Matrix3f(RenderSystem.getInverseViewRotationMatrix()).transform(view)
        val camera = Minecraft.getInstance().gameRenderer.mainCamera.position
        seen[entity.id] = Seen(camera.add(world.x.toDouble(), world.y.toDouble(), world.z.toDouble()), SpellClock.hand(0f))
    }

    fun heldAt(entity: LivingEntity): Vec3? {
        val s = seen[entity.id] ?: return null
        if (SpellClock.hand(0f) - s.tick > STALE_TICKS) {
            seen.remove(entity.id)
            return null
        }
        return s.at
    }

    fun reset() = seen.clear()
}
