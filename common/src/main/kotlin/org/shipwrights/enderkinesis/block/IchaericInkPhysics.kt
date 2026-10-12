package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.registry.EKFluids
import kotlin.math.abs

/**
 * Water-like swimming in Ichaeric Ink, one implementation for both loaders. Ink is not water to any other mod: it
 * carries no water tag, and Forge's own fluid physics for it are off (FluidTypeIchaericInkMixin).
 */
object IchaericInkPhysics {

    // Vanilla water's current strength and minimum push.
    private const val PUSH_SCALE = 0.014
    private const val MIN_PUSH = 0.0045
    private const val STILL_SPEED = 0.003
    private const val SHALLOW = 0.4

    // Vanilla jumpInLiquid's lift.
    private const val SWIM_UP = 0.04

    private const val DRAG_HORIZONTAL = 0.6
    private const val DRAG_VERTICAL = 0.7

    fun inInk(entity: Entity): Boolean = (entity as IchaericInkEntity).enderkinesisInkHeight() > 0.0

    fun eyesInInk(entity: Entity): Boolean = (entity as IchaericInkEntity).enderkinesisEyesInInk()

    /**
     * Push [entity] along the ink's flow and return how deep its feet are (0 when it touches no ink).
     * Ported from vanilla Entity.updateFluidHeightAndDoFluidPushing, which Forge rewrites to ignore custom tags.
     */
    @JvmStatic
    fun updateCurrents(entity: Entity): Double {
        if (entity.touchingUnloadedChunk()) return 0.0
        val level = entity.level()
        val ink = EKFluids.ICHAERIC_INK.get()
        val box = entity.boundingBox.deflate(0.001)
        val pushed = entity.isPushedByFluid
        val pos = BlockPos.MutableBlockPos()
        var depth = 0.0
        var touching = false
        var flow = Vec3.ZERO
        var flows = 0
        // Bounded by the entity's bounding box.
        for (x in Mth.floor(box.minX) until Mth.ceil(box.maxX)) {
            for (y in Mth.floor(box.minY) until Mth.ceil(box.maxY)) {
                for (z in Mth.floor(box.minZ) until Mth.ceil(box.maxZ)) {
                    pos.set(x, y, z)
                    val fluid = level.getFluidState(pos)
                    if (!fluid.type.isSame(ink)) continue
                    val top = y + fluid.getHeight(level, pos).toDouble()
                    if (top < box.minY) continue
                    touching = true
                    depth = maxOf(depth, top - box.minY)
                    if (pushed) {
                        val here = fluid.getFlow(level, pos)
                        flow = flow.add(if (depth < SHALLOW) here.scale(depth) else here)
                        flows++
                    }
                }
            }
        }
        if (flow.length() > 0.0) push(entity, flow.scale(1.0 / flows))
        return if (touching) maxOf(depth, Double.MIN_VALUE) else 0.0
    }

    private fun push(entity: Entity, averageFlow: Vec3) {
        var push = (if (entity is Player) averageFlow else averageFlow.normalize()).scale(PUSH_SCALE)
        val motion = entity.deltaMovement
        if (abs(motion.x) < STILL_SPEED && abs(motion.z) < STILL_SPEED && push.length() < MIN_PUSH) {
            push = push.normalize().scale(MIN_PUSH)
        }
        entity.deltaMovement = motion.add(push)
    }

    @JvmStatic
    fun eyesIn(entity: Entity): Boolean {
        val eyes = BlockPos.containing(entity.x, entity.eyeY, entity.z)
        val fluid = entity.level().getFluidState(eyes)
        return fluid.type.isSame(EKFluids.ICHAERIC_INK.get()) && entity.eyeY < eyes.y + fluid.getHeight(entity.level(), eyes)
    }

    /** Swim up while jumping, as vanilla does in water; true when the lift replaced a jump off the bottom. */
    @JvmStatic
    fun swimUp(entity: LivingEntity, affectedByFluids: Boolean): Boolean {
        if (!affectedByFluids || entity.isInWater || entity.isInLava) return false
        val depth = (entity as IchaericInkEntity).enderkinesisInkHeight()
        if (depth <= 0.0) return false
        val threshold = if (entity.eyeHeight < SHALLOW) 0.0 else SHALLOW
        if (entity.onGround() && depth <= threshold) return false
        entity.deltaMovement = entity.deltaMovement.add(0.0, SWIM_UP, 0.0)
        return entity.onGround()
    }

    /** Extra drag on top of water travel, once per tick. */
    @JvmStatic
    fun drag(entity: LivingEntity) {
        if (inInk(entity)) entity.deltaMovement = entity.deltaMovement.multiply(DRAG_HORIZONTAL, DRAG_VERTICAL, DRAG_HORIZONTAL)
    }

    /** Whether air drains: eyes in ink and nothing lets the entity breathe there, as in vanilla water. */
    @JvmStatic
    fun drowns(entity: LivingEntity): Boolean =
        entity.isAlive && eyesInInk(entity) && !entity.canBreatheUnderwater() &&
            !net.minecraft.world.effect.MobEffectUtil.hasWaterBreathing(entity) &&
            !(entity is Player && entity.abilities.invulnerable)
}
