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
import org.shipwrights.enderkinesis.item.MysticScrollItem
import org.shipwrights.enderkinesis.spell.Shape

/**
 * A Mystic Scroll in a hand out in the world (anyone else's, or yours in third person): drawn as
 * the spell it carries, as your own first-person hand shows it ([SpellHand]), not as the scroll.
 * Carried, its cubes drift loosely with a few glyphs; charging a Bolt or holding a Beam, Spray or
 * Chain, it's the held spell, its glyphs drawn into a ring as it charges. It sits just ahead of
 * the fist along the arm, the same size and pulse as in the first-person hand. The arm itself
 * moves with what they're doing ([SpellArmPoses]).
 *
 * Drawn from vanilla's held-item layer, in place of the item ([draw]). Where each caster's spell
 * showed is kept for a moment ([heldAt]), so what's drawn into a gathering Bolt goes to it.
 */
object SpellInHand {
    /** How far ahead of the fist, along the arm, the spell sits (as in [SpellHand]). */
    private const val AHEAD = 0.35
    /** The held-item layer's item sits this far past the fist, down the arm and out in front. */
    private const val ITEM_PAST_FIST = 0.125
    private const val ITEM_OUT = 0.125
    private const val FULL_CHARGE_TICKS = 30f
    /** How long, in ticks, where a spell showed still counts. */
    private const val STALE_TICKS = 2.0

    private class Seen(val at: Vec3, val tick: Double)

    // Render thread only.
    private val seen = HashMap<Int, Seen>()

    /**
     * In place of vanilla drawing [stack] in [entity]'s hand on [arm]'s side, with [pose] where
     * the held-item layer puts an item: the spell, if [stack] is a scroll carrying one. Returns
     * whether it took the item's place (nothing is drawn for an entity this viewer can't see).
     */
    @JvmStatic
    fun draw(entity: LivingEntity, stack: ItemStack, arm: HumanoidArm, pose: PoseStack, buffers: MultiBufferSource, light: Int): Boolean {
        if (stack.item !is MysticScrollItem) return false
        val spell = MysticScrollItem.spellOf(stack) ?: return false
        val mc = Minecraft.getInstance()
        // Vanilla's rule: an entity invisible to you shows you nothing of the spell either.
        if (mc.player?.let { entity.isInvisibleTo(it) } == true) return true
        val level = entity.level()
        val time = SpellClock.time(level, mc.frameTime)
        // In the layer's space the arm runs down -z, and +y is out in front of the palm.
        val axis = Vec3(0.0, 0.0, -1.0)
        val centre = Vec3(0.0, -ITEM_OUT, -(AHEAD - ITEM_PAST_FIST))
        val draw = SpellCore.Draw(pose.last(), buffers, SpellCore.viewIn(pose.last()), light, time)
        val usedArm = if (entity.usedItemHand == InteractionHand.MAIN_HAND) entity.mainArm else entity.mainArm.opposite
        val casting = entity.isUsingItem && entity.useItem.item is MysticScrollItem && usedArm == arm && spell.castShape != null
        if (casting) {
            val charge = if (spell.castShape == Shape.BOLT) ((entity.ticksUsingItem + mc.frameTime) / FULL_CHARGE_TICKS).coerceIn(0f, 1f) else 1f
            SpellCore.held(draw, centre, SpellCore.heldSize(charge, time), spell, charge, axis)
            remember(entity, pose, centre)
        } else {
            SpellCore.carried(draw, centre, spell, axis)
        }
        return true
    }

    /** Notes where in the world [entity]'s held spell showed: [centre] in [pose]'s space, which
     *  goes from there to the camera's space, turned as the world is. */
    private fun remember(entity: LivingEntity, pose: PoseStack, centre: Vec3) {
        // Only from the world itself: not the inventory's picture of the player, say.
        if (!SpellCubeFx.drawingLevel) return
        val view = pose.last().pose().transformPosition(Vector3f(centre.x.toFloat(), centre.y.toFloat(), centre.z.toFloat()))
        val world = Matrix3f(RenderSystem.getInverseViewRotationMatrix()).transform(view)
        val camera = Minecraft.getInstance().gameRenderer.mainCamera.position
        seen[entity.id] = Seen(camera.add(world.x.toDouble(), world.y.toDouble(), world.z.toDouble()), SpellClock.hand(0f))
    }

    /** Where [entity]'s held spell showed in the world lately, if it did. */
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
