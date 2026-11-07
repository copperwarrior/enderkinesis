package org.shipwrights.enderkinesis.entity

import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.DamageTypeTags
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.MoverType
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.dimension.WeheloPocket
import java.util.UUID

/** Dashes in one straight line out of the Wehelo pocket fog, through walls, takes a dropped blade on the way, and fades. */
class WeheloStoneCreature(type: EntityType<out WeheloStoneCreature>, level: Level) : PathfinderMob(type, level) {

    /** The blade item on this creature's line, until it holds the blade or the blade is gone. */
    var target: UUID? = null
        private set
    private var heading: Vec3 = Vec3.ZERO
    private var fadeOutTicks = 0

    init {
        // The blade is lost with the creature, also to /kill.
        setDropChance(EquipmentSlot.MAINHAND, 0f)
        noPhysics = true
        isNoGravity = true
    }

    /** Sets the one heading for its life: from here through [item]. */
    internal fun fetch(item: ItemEntity) {
        target = item.uuid
        val line = item.position().subtract(position())
        heading = line.normalize().scale(getAttributeValue(Attributes.MOVEMENT_SPEED))
        yRot = (Mth.atan2(-line.x, line.z) * Mth.RAD_TO_DEG).toFloat()
        yBodyRot = yRot
        yHeadRot = yRot
    }

    override fun defineSynchedData() {
        super.defineSynchedData()
        entityData.define(VANISHING, false)
    }

    override fun tick() {
        super.tick()
        if (!entityData.get(VANISHING)) return
        fadeOutTicks++
        if (!level().isClientSide && fadeOutTicks >= FADE_TICKS) discard()
    }

    /** 0 is gone, 1 is solid: it fades in from the fog when it comes, and out where it vanishes. */
    fun fade(partialTick: Float): Float {
        val fadeIn = (tickCount + partialTick) / FADE_TICKS
        val fadeOut = if (entityData.get(VANISHING)) 1f - (fadeOutTicks + partialTick) / FADE_TICKS else 1f
        return Mth.clamp(minOf(fadeIn, fadeOut), 0f, 1f)
    }

    override fun customServerAiStep() {
        super.customServerAiStep()
        if (heading == Vec3.ZERO) {
            // No line to follow, e.g. a /summon.
            discard()
            return
        }
        if (entityData.get(VANISHING)) return
        val outOfTime = tickCount >= LIFETIME_TICKS - FADE_TICKS
        target?.let { uuid ->
            val item = (level() as ServerLevel).getEntity(uuid) as? ItemEntity
            if (item == null || !item.isAlive || !WeheloPocket.isCreatureTarget(item.item)) {
                discard()
                return
            }
            if (distanceToSqr(item) <= REACH * REACH) {
                setItemSlot(EquipmentSlot.MAINHAND, item.item.copy())
                swing(InteractionHand.MAIN_HAND)
                take(item, item.item.count)
                item.discard()
                target = null
            } else if (!lineReaches(item)) {
                discard()
                return
            } else if (outOfTime) {
                // A blade it cannot reach stays where it is; no new creature comes for it.
                WeheloPocket.markUnreachable(item)
            }
        }
        if (outOfTime || !WeheloPocket.inside(this) || (target == null && hiddenInFog())) {
            target = null
            entityData.set(VANISHING, true)
        }
    }

    /** True while the rest of its line still passes within reach of [item]. */
    private fun lineReaches(item: ItemEntity): Boolean {
        val dx = item.x - x; val dy = item.y - y; val dz = item.z - z
        val along = (dx * heading.x + dy * heading.y + dz * heading.z) / heading.length()
        if (along < 0.0) return false
        return dx * dx + dy * dy + dz * dz - along * along <= REACH * REACH
    }

    private fun hiddenInFog(): Boolean {
        val nearest = level().getNearestPlayer(this, -1.0) ?: return true
        return distanceTo(nearest) >= WeheloPocket.HIDDEN_DISTANCE
    }

    // A fixed velocity: no friction, gravity, fluid flow or push can turn or slow it.
    override fun travel(input: Vec3) {
        if (isControlledByLocalInstance) {
            deltaMovement = heading
            move(MoverType.SELF, heading)
        }
        // Vanilla runs this on both sides; the client needs it for the run animation.
        calculateEntityAnimation(false)
    }

    override fun isPushable(): Boolean = false

    override fun push(entity: Entity) = Unit

    override fun isInvulnerableTo(source: DamageSource): Boolean =
        !source.`is`(DamageTypeTags.BYPASSES_INVULNERABILITY) || super.isInvulnerableTo(source)

    // A tear must not carry it, or the blade, out of the pocket.
    override fun canChangeDimensions(): Boolean = false

    override fun shouldDropExperience(): Boolean = false

    // Its line and its minute do not survive a reload.
    override fun shouldBeSaved(): Boolean = false

    companion object {
        const val ID_PATH = "wehelo_stone_creature"
        private val VANISHING: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(WeheloStoneCreature::class.java, EntityDataSerializers.BOOLEAN)
        private const val REACH = 1.5
        private const val LIFETIME_TICKS = 1200
        private const val FADE_TICKS = 20
        private const val MAX_HEALTH = 40.0
        /** Blocks per tick along its line. */
        private const val MOVEMENT_SPEED = 0.57
        private const val FOLLOW_RANGE = 96.0

        fun createAttributes(): AttributeSupplier.Builder = createMobAttributes()
            .add(Attributes.MAX_HEALTH, MAX_HEALTH)
            .add(Attributes.MOVEMENT_SPEED, MOVEMENT_SPEED)
            .add(Attributes.FOLLOW_RANGE, FOLLOW_RANGE)
            .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
    }
}
