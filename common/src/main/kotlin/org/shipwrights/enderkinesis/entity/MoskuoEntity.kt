package org.shipwrights.enderkinesis.entity

import java.util.EnumSet
import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.tags.TagKey
import net.minecraft.util.RandomSource
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.AnimationState
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.EntitySelector
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.control.FlyingMoveControl
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomFlyingGoal
import net.minecraft.world.entity.ai.navigation.PathNavigation
import net.minecraft.world.entity.animal.FlyingAnimal
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.level.Level
import net.minecraft.world.level.ServerLevelAccessor
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.registry.EKEffects

/**
 * Wohlon mosquito. It waits inside leaves and plants, bursts out to sting anything alive, and spreads Trypoemia.
 * A fed Moskuo is fat, slow and easy to hit until it digests in cover and heals.
 */
class MoskuoEntity(type: EntityType<out MoskuoEntity>, level: Level) : PathfinderMob(type, level), FlyingAnimal {

    enum class Action { NONE, STING, DIGEST }

    val stingAnimationState: AnimationState = AnimationState()
    val digestAnimationState: AnimationState = AnimationState()

    private var actionTicks = 0
    private var digestTicks = 0
    private var stingCooldown = 0
    private var settledInCover = false

    val action: Action
        get() = Action.entries.getOrElse(entityData.get(DATA_ACTION).toInt()) { Action.NONE }

    val isFed: Boolean get() = entityData.get(DATA_FED)

    val isPerched: Boolean get() = entityData.get(DATA_PERCHED)

    init {
        moveControl = FlyingMoveControl(this, MAX_TURN, true)
        isNoGravity = true
    }

    override fun defineSynchedData() {
        super.defineSynchedData()
        entityData.define(DATA_ACTION, Action.NONE.ordinal.toByte())
        entityData.define(DATA_FED, false)
        entityData.define(DATA_PERCHED, false)
    }

    private fun setAction(next: Action) {
        actionTicks = 0
        entityData.set(DATA_ACTION, next.ordinal.toByte())
    }

    private fun setFed(value: Boolean) {
        entityData.set(DATA_FED, value)
        val speed = getAttribute(Attributes.FLYING_SPEED) ?: return
        speed.removeModifier(FAT_SLOWDOWN_ID)
        if (value) speed.addTransientModifier(FAT_SLOWDOWN)
    }

    override fun createNavigation(level: Level): PathNavigation =
        wogorLeavesFlyingNavigation(this, level).also { it.setCanFloat(true) }

    override fun isFlying(): Boolean = !onGround()

    override fun causeFallDamage(distance: Float, multiplier: Float, source: DamageSource): Boolean = false

    override fun checkFallDamage(y: Double, onGround: Boolean, state: BlockState, pos: BlockPos) = Unit

    override fun shouldDespawnInPeaceful(): Boolean = true

    override fun getDimensions(pose: Pose): EntityDimensions = if (isFed) FAT_DIMENSIONS else super.getDimensions(pose)

    override fun onSyncedDataUpdated(key: EntityDataAccessor<*>) {
        super.onSyncedDataUpdated(key)
        if (key == DATA_FED) refreshDimensions()
        if (key == DATA_ACTION && level().isClientSide) {
            stingAnimationState.stop()
            digestAnimationState.stop()
            when (action) {
                Action.STING -> stingAnimationState.start(tickCount)
                Action.DIGEST -> digestAnimationState.start(tickCount)
                Action.NONE -> Unit
            }
        }
    }

    override fun registerGoals() {
        goalSelector.addGoal(1, StingGoal())
        goalSelector.addGoal(2, CoverGoal(fed = true))
        goalSelector.addGoal(3, CoverGoal(fed = false))
        goalSelector.addGoal(4, WaterAvoidingRandomFlyingGoal(this, 1.0))
    }

    override fun aiStep() {
        super.aiStep()
        if (level().isClientSide) return
        if (stingCooldown > 0) stingCooldown--
        if (isFed && action == Action.NONE && --digestTicks <= 0) setAction(Action.DIGEST)
        if (action == Action.DIGEST && ++actionTicks >= DIGEST_TICKS) {
            setFed(false)
            heal(maxHealth)
            setAction(Action.NONE)
        }
        entityData.set(DATA_PERCHED, onGround() || settledInCover)
    }

    override fun addAdditionalSaveData(tag: CompoundTag) {
        super.addAdditionalSaveData(tag)
        tag.putBoolean(FED_KEY, isFed)
        tag.putInt(DIGEST_KEY, digestTicks)
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)
        setFed(tag.getBoolean(FED_KEY))
        digestTicks = tag.getInt(DIGEST_KEY)
    }

    private fun isPrey(entity: LivingEntity): Boolean =
        entity !== this && entity.isAlive && entity !is MoskuoEntity && entity !is ArmorStand &&
            EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(entity)

    private fun stingVictim(victim: LivingEntity): Boolean {
        if (!victim.hurt(damageSources().mobAttack(this), STING_DAMAGE)) return false
        val trypoemia = EKEffects.TRYPOEMIA.get()
        val amplifier = victim.getEffect(trypoemia)?.let { (it.amplifier + 1).coerceAtMost(MAX_TRYPOEMIA) } ?: 0
        victim.addEffect(MobEffectInstance(trypoemia, TRYPOEMIA_TICKS, amplifier), this)
        return true
    }

    /** Flies to the nearest prey, then plays the sting; the body swells only if the proboscis lands. */
    private inner class StingGoal : Goal() {
        private var target: LivingEntity? = null
        private var stinging = false
        private var chaseTicks = 0

        init {
            flags = EnumSet.of(Flag.MOVE, Flag.LOOK)
        }

        override fun canUse(): Boolean {
            if (isFed || action != Action.NONE || stingCooldown > 0) return false
            target = level().getEntitiesOfClass(LivingEntity::class.java, boundingBox.inflate(TRIGGER_RANGE)) {
                isPrey(it) && hasLineOfSight(it)
            }.minByOrNull { it.distanceToSqr(this@MoskuoEntity) }
            return target != null
        }

        override fun canContinueToUse(): Boolean {
            val victim = target ?: return false
            if (stinging) return true
            return victim.isAlive && chaseTicks < CHASE_TICKS && distanceToSqr(victim) < CHASE_RANGE * CHASE_RANGE
        }

        override fun requiresUpdateEveryTick(): Boolean = true

        override fun start() {
            stinging = false
            chaseTicks = 0
            settledInCover = false
        }

        override fun tick() {
            val victim = target ?: return
            lookControl.setLookAt(victim, MAX_TURN.toFloat(), MAX_TURN.toFloat())
            if (!stinging) {
                chaseTicks++
                moveControl.setWantedPosition(victim.x, victim.getY(BODY_HEIGHT), victim.z, CHASE_SPEED)
                if (victim.boundingBox.inflate(STING_REACH).contains(position())) {
                    stinging = true
                    navigation.stop()
                    setAction(Action.STING)
                }
                return
            }
            deltaMovement = Vec3.ZERO
            actionTicks++
            // A miss stops the clip before its body swap, so the Moskuo stays skinny.
            if (actionTicks == STING_HIT_TICK &&
                !(victim.boundingBox.inflate(STING_REACH).contains(position()) && stingVictim(victim))
            ) {
                stinging = false
                setAction(Action.NONE)
                target = null
                return
            }
            if (actionTicks >= STING_TICKS) {
                setFed(true)
                digestTicks = DIGEST_DELAY_TICKS
                stinging = false
                setAction(Action.NONE)
                target = null
            }
        }

        override fun stop() {
            if (action == Action.STING) setAction(Action.NONE)
            stinging = false
            target = null
            stingCooldown = STING_COOLDOWN_TICKS
        }
    }

    /**
     * Flies into the nearest leaf or plant block and waits there: to ambush when hungry, to digest when fed.
     * It prefers cover with a solid floor, where it lands; with none in reach it hovers inside the cover.
     */
    private inner class CoverGoal(private val fed: Boolean) : Goal() {
        private var cover: BlockPos? = null
        private var landing = false
        private var searchCooldown = 0

        init {
            flags = EnumSet.of(Flag.MOVE)
        }

        override fun canUse(): Boolean {
            if (isFed != fed || (!fed && action != Action.NONE)) return false
            if (searchCooldown > 0) {
                searchCooldown--
                return false
            }
            searchCooldown = SEARCH_INTERVAL_TICKS
            cover = findCover()
            return cover != null || fed
        }

        override fun canContinueToUse(): Boolean = isFed == fed

        override fun requiresUpdateEveryTick(): Boolean = true

        override fun tick() {
            val spot = cover
            if (action == Action.DIGEST || spot == null) {
                holdStill()
                return
            }
            val x = spot.x + HALF
            val y = spot.y + if (landing) LAND_HEIGHT else COVER_HEIGHT
            val z = spot.z + HALF
            if (distanceToSqr(x, y, z) > ARRIVE_DISTANCE * ARRIVE_DISTANCE && !(landing && settledInCover)) {
                settledInCover = false
                moveControl.setWantedPosition(x, y, z, if (fed) FED_SPEED else 1.0)
                return
            }
            navigation.stop()
            holdStill()
            settledInCover = !landing || onGround()
        }

        // A landed Moskuo keeps gravity on so it rests on the floor; FlyingMoveControl turns it off at takeoff.
        private fun holdStill() {
            if (landing) {
                isNoGravity = false
                deltaMovement = Vec3(0.0, deltaMovement.y, 0.0)
            } else {
                deltaMovement = Vec3.ZERO
            }
        }

        override fun stop() {
            settledInCover = false
            cover = null
            landing = false
        }

        private fun findCover(): BlockPos? {
            val origin = blockPosition()
            val cursor = BlockPos.MutableBlockPos()
            val below = BlockPos.MutableBlockPos()
            var best: BlockPos? = null
            var bestScore = Double.MAX_VALUE
            for (dy in -COVER_REACH_Y..COVER_REACH_Y) {
                for (dx in -COVER_REACH..COVER_REACH) {
                    for (dz in -COVER_REACH..COVER_REACH) {
                        cursor.setWithOffset(origin, dx, dy, dz)
                        if (!level().getBlockState(cursor).`is`(HIDES_IN)) continue
                        below.setWithOffset(cursor, Direction.DOWN)
                        val floor = hasFloor(below)
                        val score = cursor.distSqr(origin) + if (floor) 0.0 else HOVER_PENALTY
                        if (score < bestScore) {
                            bestScore = score
                            best = cursor.immutable()
                            landing = floor
                        }
                    }
                }
            }
            return best
        }

        // Wogor leaves give a Moskuo no collision, so the floor test uses its own collision context.
        private fun hasFloor(pos: BlockPos): Boolean {
            val shape = level().getBlockState(pos).getCollisionShape(level(), pos, CollisionContext.of(this@MoskuoEntity))
            return !shape.isEmpty && shape.max(Direction.Axis.Y) >= FULL_HEIGHT
        }
    }

    companion object {
        const val ID_PATH = "moskuo"
        const val WIDTH = 0.35f
        const val HEIGHT = 0.35f
        private val FAT_DIMENSIONS: EntityDimensions = EntityDimensions.scalable(0.55f, 0.5f)

        /** Blocks a Moskuo hides inside; Wogor leaves let it through, and plants have no collision. */
        val HIDES_IN: TagKey<Block> = TagKey.create(Registries.BLOCK, EnderkinesisMod.id("moskuo_hides_in"))

        private val DATA_ACTION: EntityDataAccessor<Byte> =
            SynchedEntityData.defineId(MoskuoEntity::class.java, EntityDataSerializers.BYTE)
        private val DATA_FED: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(MoskuoEntity::class.java, EntityDataSerializers.BOOLEAN)
        private val DATA_PERCHED: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(MoskuoEntity::class.java, EntityDataSerializers.BOOLEAN)

        private val FAT_SLOWDOWN_ID: UUID = UUID.fromString("3c1f6a2e-8d0b-4a57-9f2e-5b7d2c1e9a40")
        private val FAT_SLOWDOWN = AttributeModifier(
            FAT_SLOWDOWN_ID, "Moskuo engorged", -0.6, AttributeModifier.Operation.MULTIPLY_TOTAL,
        )

        private const val MAX_TURN = 20
        private const val TRIGGER_RANGE = 5.0
        private const val CHASE_RANGE = 12.0
        private const val CHASE_TICKS = 200
        private const val CHASE_SPEED = 1.4
        private const val FED_SPEED = 0.8
        private const val BODY_HEIGHT = 0.6
        private const val STING_REACH = 0.4
        // SSting: the proboscis lands at 0.46 s and the body swaps at 0.54 s.
        private const val STING_HIT_TICK = 9
        private const val STING_TICKS = 20
        private const val STING_COOLDOWN_TICKS = 40
        private const val STING_DAMAGE = 1f
        private const val TRYPOEMIA_TICKS = 2400
        private const val MAX_TRYPOEMIA = 3
        private const val DIGEST_DELAY_TICKS = 600
        // LDigest is 1.17 s.
        private const val DIGEST_TICKS = 23
        private const val SEARCH_INTERVAL_TICKS = 40
        private const val COVER_REACH = 10
        private const val COVER_REACH_Y = 5
        private const val COVER_HEIGHT = 0.3
        private const val LAND_HEIGHT = 0.05
        private const val FULL_HEIGHT = 0.999
        // A floored spot wins over a hover spot up to 8 blocks closer.
        private const val HOVER_PENALTY = 64.0
        private const val ARRIVE_DISTANCE = 0.35
        private const val HALF = 0.5
        private const val FED_KEY = "Fed"
        private const val DIGEST_KEY = "DigestTicks"

        /** Spawns only in or just above cover, so a new Moskuo starts hidden. */
        @Suppress("UNUSED_PARAMETER") // Shape fixed by SpawnPlacements.SpawnPredicate.
        fun canSpawn(
            type: EntityType<Mob>,
            level: ServerLevelAccessor,
            spawnType: MobSpawnType,
            pos: BlockPos,
            random: RandomSource,
        ): Boolean = level.getBlockState(pos).`is`(HIDES_IN) || level.getBlockState(pos.below()).`is`(HIDES_IN)

        fun createAttributes(): AttributeSupplier.Builder = Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 4.0)
            .add(Attributes.FLYING_SPEED, 0.8)
            .add(Attributes.MOVEMENT_SPEED, 0.3)
            .add(Attributes.FOLLOW_RANGE, 16.0)
    }
}
