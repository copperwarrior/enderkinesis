package org.shipwrights.enderkinesis.entity

import java.util.EnumSet
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.tags.FluidTags
import net.minecraft.tags.TagKey
import net.minecraft.util.Mth
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.AgeableMob
import net.minecraft.world.entity.AnimationState
import net.minecraft.world.entity.EntityEvent
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.FloatGoal
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal
import net.minecraft.world.entity.ai.navigation.PathNavigation
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.pathfinder.BlockPathTypes
import kotlin.math.sqrt
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.block.scanForBlock
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.shipwrights.enderkinesis.registry.EKSounds

/**
 * Wohlon frog. It lazes on land beside water, hops along the shore, swims for fun and eats Moskuo and swarm flies.
 * Adults lay Horned Frogspawn now and then; it hatches into babies, which grow up on the vanilla AgeableMob clock.
 */
class HornedFrogEntity(type: EntityType<out HornedFrogEntity>, level: Level) : AgeableMob(type, level) {

    val tongueAnimationState: AnimationState = AnimationState()
    val jumpAnimationState: AnimationState = AnimationState()
    val fallAnimationState: AnimationState = AnimationState()

    // Client only: the prey point the tongue aims at, read each tick while the Tongue clip plays.
    var hasTongueAim = false
        private set
    var tongueAimX = 0.0
        private set
    var tongueAimY = 0.0
        private set
    var tongueAimZ = 0.0
        private set

    private var mealCooldown = random.nextInt(FLY_MEAL_MAX_TICKS)
    private var spawnCooldown = random.nextInt(LAY_MIN_TICKS)
    private var lastMealTick = NEVER_FED
    private var isPregnant = false
    private var layChecks = 0
    private var leapTicks = 0
    private var leapElapsed = 0
    private var unreachablePrey = NO_TARGET
    private var unreachableUntil = 0

    /** True from launch until the frog lands, comes down in water, or the planned flight time runs out. */
    private val isLeaping: Boolean get() = leapTicks > 0

    init {
        installWogorLeavesControls(this) { move, jump ->
            moveControl = move
            jumpControl = jump
        }
    }

    override fun createNavigation(level: Level): PathNavigation =
        wogorLeavesNavigation(this, level).also { it.setCanFloat(true) }

    override fun registerGoals() {
        goalSelector.addGoal(0, FloatGoal(this))
        goalSelector.addGoal(2, TongueGoal())
        goalSelector.addGoal(3, SwimForFunGoal())
        goalSelector.addGoal(4, WaterlineGoal())
        goalSelector.addGoal(5, HopGoal())
        goalSelector.addGoal(6, LookAtPlayerGoal(this, Player::class.java, LOOK_RANGE))
        goalSelector.addGoal(7, RandomLookAroundGoal(this))
    }

    // Faster than a land mob's 0.8, so a swim looks like a frog's kick.
    override fun getWaterSlowDown(): Float = WATER_SLOWDOWN

    override fun removeWhenFarAway(distanceToClosestPlayer: Double): Boolean = false

    // No breeding: new frogs come only from frogspawn.
    override fun getBreedOffspring(level: ServerLevel, partner: AgeableMob): AgeableMob? = null

    override fun shouldDropLoot(): Boolean = !isBaby && super.shouldDropLoot()

    override fun causeFallDamage(distance: Float, multiplier: Float, source: DamageSource): Boolean = false

    override fun aiStep() {
        super.aiStep()
        if (level().isClientSide) return
        if (mealCooldown > 0) mealCooldown--
        if (leapTicks > 0) {
            leapTicks--
            if (++leapElapsed >= LEAP_MIN_AIR_TICKS && (onGround() || isInWater)) leapTicks = 0
        }
        if (spawnCooldown > 0) spawnCooldown--
        if ((tickCount + id) % LAY_CHECK_TICKS == 0) {
            if (isPregnant) pregnantLayCheck() else tryBreed()
        }
    }

    override fun tick() {
        super.tick()
        if (level().isClientSide) {
            updateAirAnimations()
            updateTongueAim()
        }
    }

    override fun defineSynchedData() {
        super.defineSynchedData()
        entityData.define(DATA_TONGUE_TARGET, NO_TARGET)
    }

    // The prey keeps its last point after it is eaten, so the tongue retracts from where it struck.
    private fun updateTongueAim() {
        if (!tongueAnimationState.isStarted) {
            hasTongueAim = false
            return
        }
        val prey = level().getEntity(entityData.get(DATA_TONGUE_TARGET)) ?: return
        tongueAimX = prey.x
        tongueAimY = prey.getY(HALF)
        tongueAimZ = prey.z
        hasTongueAim = true
    }

    // Jump plays while the frog rises and Fall while it drops; both clips hold their last frame.
    private fun updateAirAnimations() {
        val rise = y - yo
        when {
            onGround() || isInWater -> {
                jumpAnimationState.stop()
                fallAnimationState.stop()
            }
            rise > AIR_RISE -> {
                fallAnimationState.stop()
                jumpAnimationState.startIfStopped(tickCount)
            }
            rise < -AIR_RISE -> {
                jumpAnimationState.stop()
                fallAnimationState.startIfStopped(tickCount)
            }
        }
    }

    override fun handleEntityEvent(id: Byte) {
        if (id == EntityEvent.START_ATTACKING) tongueAnimationState.start(tickCount) else super.handleEntityEvent(id)
    }

    override fun addAdditionalSaveData(tag: CompoundTag) {
        super.addAdditionalSaveData(tag)
        tag.putInt(MEAL_KEY, mealCooldown)
        tag.putInt(SPAWN_KEY, spawnCooldown)
        tag.putLong(LAST_MEAL_KEY, lastMealTick)
        tag.putBoolean(PREGNANT_KEY, isPregnant)
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)
        mealCooldown = tag.getInt(MEAL_KEY)
        spawnCooldown = tag.getInt(SPAWN_KEY)
        lastMealTick = if (tag.contains(LAST_MEAL_KEY)) tag.getLong(LAST_MEAL_KEY) else NEVER_FED
        isPregnant = tag.getBoolean(PREGNANT_KEY)
    }

    override fun getAmbientSound(): SoundEvent = EKSounds.HORNED_FROG_AMBIENT.get()

    override fun getHurtSound(source: DamageSource): SoundEvent = EKSounds.HORNED_FROG_HURT.get()

    override fun getDeathSound(): SoundEvent = EKSounds.HORNED_FROG_DEATH.get()

    override fun playStepSound(pos: BlockPos, state: BlockState) = playSound(EKSounds.HORNED_FROG_STEP.get(), STEP_VOLUME, 1f)

    private fun wetSides(pos: BlockPos, scratch: BlockPos.MutableBlockPos): Int {
        var wet = 0
        for (side in HORIZONTALS) {
            scratch.setWithOffset(pos, side)
            val atFeet = level().getFluidState(scratch).`is`(FluidTags.WATER)
            scratch.move(Direction.DOWN)
            if (atFeet || level().getFluidState(scratch).`is`(FluidTags.WATER)) wet++
        }
        return wet
    }

    /** The cell the feet are in; on mud (14 px tall) the frog stands inside the mud cell, so lift the feet first. */
    private fun standingCell(): BlockPos = BlockPos.containing(x, y + FEET_LIFT, z)

    /** Dry cell with a floor: a low block in it (carpet, layer, slab) or a sturdy top under it, such as mud. */
    private fun isStandable(pos: BlockPos, scratch: BlockPos.MutableBlockPos): Boolean {
        if (!level().getFluidState(pos).isEmpty) return false
        val shape = level().getBlockState(pos).getCollisionShape(level(), pos)
        if (!shape.isEmpty) return shape.max(Direction.Axis.Y) <= LOW_FLOOR
        scratch.setWithOffset(pos, Direction.DOWN)
        return level().getBlockState(scratch).isFaceSturdy(level(), scratch, Direction.UP)
    }

    /** Height the feet rest at in a standable [pos]. */
    private fun floorHeight(pos: BlockPos, scratch: BlockPos.MutableBlockPos): Double {
        val shape = level().getBlockState(pos).getCollisionShape(level(), pos)
        if (!shape.isEmpty) return pos.y + shape.max(Direction.Axis.Y)
        scratch.setWithOffset(pos, Direction.DOWN)
        val below = level().getBlockState(scratch).getCollisionShape(level(), scratch)
        return if (below.isEmpty) pos.y.toDouble() else scratch.y + below.max(Direction.Axis.Y)
    }

    private fun isWaterline(pos: BlockPos, scratch: BlockPos.MutableBlockPos): Boolean =
        isStandable(pos, scratch) && wetSides(pos, scratch) > 0

    /** Best-scored shore cell in reach, or null; at most (2·reach+1)²·(2·[SHORE_REACH_Y]+1) cells. */
    private fun findWaterline(reach: Int, minDistanceSq: Int): BlockPos? {
        val origin = standingCell()
        val cursor = BlockPos.MutableBlockPos()
        val scratch = BlockPos.MutableBlockPos()
        var best: BlockPos? = null
        var bestScore = NOT_WATERLINE
        for (dy in -SHORE_REACH_Y..SHORE_REACH_Y) {
            for (dx in -reach..reach) {
                for (dz in -reach..reach) {
                    val distance = dx * dx + dy * dy + dz * dz
                    if (distance < minDistanceSq) continue
                    cursor.setWithOffset(origin, dx, dy, dz)
                    if (!isStandable(cursor, scratch)) continue
                    // A little noise, so two frogs on one shore do not pick the same cell.
                    val score = waterlineScore(wetSides(cursor, scratch), distance) + random.nextInt(SCORE_NOISE)
                    if (score > bestScore) {
                        bestScore = score
                        best = cursor.immutable()
                    }
                }
            }
        }
        return best
    }

    /** A random water-surface cell in reach; bounded random tries, not a full scan. */
    private fun findSwimSpot(): BlockPos? {
        val cursor = BlockPos.MutableBlockPos()
        repeat(SWIM_SPOT_TRIES) {
            cursor.set(
                blockPosition().x + random.nextInt(SWIM_REACH * 2 + 1) - SWIM_REACH,
                blockPosition().y + random.nextInt(SWIM_REACH_Y * 2 + 1) - SWIM_REACH_Y,
                blockPosition().z + random.nextInt(SWIM_REACH * 2 + 1) - SWIM_REACH,
            )
            if (isSwimSurface(level(), cursor)) return cursor.immutable()
        }
        return null
    }

    private fun isFed(): Boolean = isRecentlyFed(lastMealTick, level().gameTime)

    private fun canBreedNow(): Boolean = isAlive && !isBaby && !isPregnant && spawnCooldown <= 0 && isFed()

    /** Three fed, ready adults within [GROUP_RADIUS] breed: all lose their fed state and wait; one becomes pregnant. */
    private fun tryBreed() {
        val level = level() as? ServerLevel ?: return
        if (!canBreedNow()) return
        val partners = level.getEntitiesOfClass(HornedFrogEntity::class.java, boundingBox.inflate(GROUP_RADIUS)) {
            it !== this && it.canBreedNow()
        }.sortedBy { it.distanceToSqr(this) }
        val trio = frogBreedingTrio(this, partners) { random.nextInt(it) } ?: return
        val cooldown = LAY_MIN_TICKS + random.nextInt(LAY_MAX_TICKS - LAY_MIN_TICKS)
        for (frog in trio.members) {
            frog.lastMealTick = NEVER_FED
            frog.spawnCooldown = cooldown
        }
        trio.mother.isPregnant = true
        trio.mother.layChecks = 0
    }

    // Every check for the first EAGER_LAY_CHECKS, then once per RETRY_LAY_CHECKS, until the clutch is laid.
    private fun pregnantLayCheck() {
        layChecks++
        if (layChecks <= EAGER_LAY_CHECKS || layChecks % RETRY_LAY_CHECKS == 0) tryLaySpawn()
    }

    /**
     * A pregnant frog on the shore lays its one frogspawn on still water beside it, unless the area holds enough
     * frogs or spawn. The WaterlineGoal brings a frog that is off the shore back to it.
     */
    private fun tryLaySpawn() {
        val level = level() as? ServerLevel ?: return
        if (isInWater || !onGround()) return
        val scratch = BlockPos.MutableBlockPos()
        if (!isWaterline(standingCell(), scratch)) return
        if (level.getEntitiesOfClass(HornedFrogEntity::class.java, boundingBox.inflate(CROWD_RADIUS)).size >= MAX_CROWD) return
        val block = EKBlocks.HORNED_FROGSPAWN.get()
        var spawnNear = 0
        val crowded = level.scanForBlock(block, blockPosition(), SPAWN_CAP_RADIUS, SPAWN_CAP_RADIUS_Y, scratch) {
            ++spawnNear >= MAX_SPAWN_NEAR
        }
        if (crowded != null) return
        val at = findLaySpot(level, block.defaultBlockState()) ?: return
        if (!level.setBlock(at, block.defaultBlockState(), Block.UPDATE_ALL)) return
        playSound(EKSounds.HORNED_FROG_LAY_SPAWN.get(), 1f, 1f)
        isPregnant = false
    }

    /** Air over a still water source within [LAY_REACH] blocks, where the spawn can survive. */
    @Suppress("DEPRECATION") // BlockState.canSurvive is the vanilla placement check.
    private fun findLaySpot(level: ServerLevel, spawn: BlockState): BlockPos? {
        val water = BlockPos.MutableBlockPos()
        val above = BlockPos.MutableBlockPos()
        val origin = standingCell()
        for (dy in -1..0) {
            for (dx in -LAY_REACH..LAY_REACH) {
                for (dz in -LAY_REACH..LAY_REACH) {
                    water.setWithOffset(origin, dx, dy, dz)
                    val fluid = level.getFluidState(water)
                    if (!fluid.`is`(FluidTags.WATER) || !fluid.isSource || fluid.getFlow(level, water).lengthSqr() != 0.0) continue
                    above.setWithOffset(water, Direction.UP)
                    if (level.getBlockState(above).isAir && spawn.canSurvive(level, above)) return above.immutable()
                }
            }
        }
        return null
    }

    private fun isPrey(entity: LivingEntity): Boolean =
        entity.isAlive && entity.type.`is`(PREY) && (entity !is SwarmFlyEntity || entity.flyCount > 0)

    private fun preyReach(prey: LivingEntity): Double =
        TONGUE_REACH + if (prey is SwarmFlyEntity) prey.cloudRadius.toDouble() else prey.bbWidth / 2.0

    private fun inTongueReach(prey: LivingEntity, slack: Double = 0.0): Boolean {
        val dx = prey.x - x
        val dz = prey.z - z
        val rise = prey.getY(HALF) - (y + MOUTH_HEIGHT * scale)
        return tongueCanReach(sqrt(dx * dx + dz * dz), rise, preyReach(prey) + slack)
    }

    /**
     * Leaps onto [landing], a standable cell, from the ground or the water surface. False when the cell is too near
     * or too far, no leap fits, or the arc hits a block; the frog only lands on dry floor, never in water.
     */
    private fun tryLeap(landing: BlockPos): Boolean {
        val fromWater = isInWater && !isUnderWater
        if (isLeaping || !(onGround() || fromWater)) return false
        val scratch = BlockPos.MutableBlockPos()
        if (!isStandable(landing, scratch)) return false
        val tx = landing.x + HALF
        val tz = landing.z + HALF
        val dx = tx - x
        val dz = tz - z
        val distance = sqrt(dx * dx + dz * dz)
        if (distance < LEAP_MIN_DISTANCE || distance > LEAP_MAX_DISTANCE) return false
        val plan = planLeap(distance, floorHeight(landing, scratch) - y, fromWater) ?: return false
        val path = leapPath(plan.speed, plan.lift, fromWater, plan.ticks)
        val ux = dx / distance
        val uz = dz / distance
        val size = getDimensions(pose)
        // The last tick meets the floor, so only the flight before it must be clear.
        for (t in 0 until plan.ticks - 1) {
            val box = size.makeBoundingBox(x + ux * path[t * 2], y + path[t * 2 + 1], z + uz * path[t * 2])
            if (!level().noCollision(this, box)) return false
        }
        navigation.stop()
        faceToward(tx, tz)
        setDeltaMovement(ux * plan.speed, plan.lift, uz * plan.speed)
        // Ground friction would cut the first tick of the leap; the plan counts air drag only.
        setOnGround(false)
        hasImpulse = true
        leapTicks = plan.ticks + LEAP_GRACE_TICKS
        leapElapsed = 0
        playSound(EKSounds.HORNED_FROG_JUMP.get(), 1f, voicePitch)
        return true
    }

    /** Leaps to the standable cell nearest a point [LEAP_SHORT] short of the prey, so the tongue reaches it after. */
    private fun leapToward(prey: LivingEntity, horizontal: Double): Boolean {
        val back = LEAP_SHORT / horizontal
        val aimX = prey.x + (x - prey.x) * back
        val aimZ = prey.z + (z - prey.z) * back
        val origin = standingCell()
        val cursor = BlockPos.MutableBlockPos()
        val scratch = BlockPos.MutableBlockPos()
        var best: BlockPos? = null
        var bestDistance = Double.MAX_VALUE
        for (dy in -LEAP_SEARCH_Y..LEAP_SEARCH_Y) {
            for (dx in -1..1) {
                for (dz in -1..1) {
                    cursor.set(Mth.floor(aimX) + dx, origin.y + dy, Mth.floor(aimZ) + dz)
                    if (!isStandable(cursor, scratch)) continue
                    val ox = cursor.x + HALF - aimX
                    val oz = cursor.z + HALF - aimZ
                    val distance = ox * ox + oz * oz + dy * dy
                    if (distance < bestDistance) {
                        bestDistance = distance
                        best = cursor.immutable()
                    }
                }
            }
        }
        return best != null && tryLeap(best)
    }

    private fun faceToward(tx: Double, tz: Double) {
        val yaw = (Mth.atan2(tz - z, tx - x) * Mth.RAD_TO_DEG).toFloat() - QUARTER_TURN
        yRot = yaw
        yBodyRot = yaw
        yHeadRot = yaw
    }

    /** Walks to prey in reach of the tongue, then strikes; the prey is gone when the tongue is fully out. */
    private inner class TongueGoal : Goal() {
        private var prey: LivingEntity? = null
        private var searchCooldown = 0
        private var repathTicks = 0
        private var chaseTicks = 0
        private var strikeTicks = NOT_STRIKING
        private var bestDistance = 0.0
        private var stuckTicks = 0
        private var nextLeapTick = 0

        init {
            flags = EnumSet.of(Flag.MOVE, Flag.LOOK)
        }

        override fun canUse(): Boolean {
            if (mealCooldown > 0 || --searchCooldown > 0) return false
            searchCooldown = SEARCH_INTERVAL_TICKS
            prey = level().getEntitiesOfClass(LivingEntity::class.java, boundingBox.inflate(HUNT_RANGE, HUNT_HEIGHT, HUNT_RANGE)) {
                isPrey(it) && (it.id != unreachablePrey || tickCount >= unreachableUntil) && hasLineOfSight(it)
            }.minByOrNull { it.distanceToSqr(this@HornedFrogEntity) }
            return prey != null
        }

        override fun canContinueToUse(): Boolean {
            if (strikeTicks != NOT_STRIKING) return true
            val target = prey ?: return false
            return isPrey(target) && chaseTicks < CHASE_TICKS && stuckTicks < STUCK_TICKS &&
                target.distanceToSqr(this@HornedFrogEntity) < HUNT_RANGE * HUNT_RANGE
        }

        override fun requiresUpdateEveryTick(): Boolean = true

        override fun start() {
            repathTicks = 0
            chaseTicks = 0
            stuckTicks = 0
            bestDistance = Double.MAX_VALUE
            strikeTicks = NOT_STRIKING
            // A chase may swim; prey over water is common.
            setPathfindingMalus(BlockPathTypes.WATER, 0f)
        }

        // A chase that ends with the prey still set never struck: skip that prey for a while; the meal timer is untouched.
        override fun stop() {
            prey?.let {
                unreachablePrey = it.id
                unreachableUntil = tickCount + UNREACHABLE_TICKS
            }
            prey = null
            strikeTicks = NOT_STRIKING
            entityData.set(DATA_TONGUE_TARGET, NO_TARGET)
            setPathfindingMalus(BlockPathTypes.WATER, BlockPathTypes.WATER.malus)
            navigation.stop()
        }

        override fun tick() {
            val target = prey ?: return
            lookControl.setLookAt(target, LOOK_TURN, LOOK_TURN)
            if (strikeTicks != NOT_STRIKING) {
                navigation.stop()
                if (++strikeTicks == TONGUE_HIT_TICK) swallow(target)
                if (strikeTicks >= TONGUE_TICKS) {
                    prey = null
                    strikeTicks = NOT_STRIKING
                }
                return
            }
            if (isLeaping) return
            chaseTicks++
            val dx = target.x - x
            val dz = target.z - z
            val horizontal = sqrt(dx * dx + dz * dz)
            if (horizontal < bestDistance - PROGRESS_STEP) {
                bestDistance = horizontal
                stuckTicks = 0
            } else {
                stuckTicks++
            }
            if (inTongueReach(target)) {
                navigation.stop()
                faceToward(target.x, target.z)
                strikeTicks = 0
                entityData.set(DATA_TONGUE_TARGET, target.id)
                level().broadcastEntityEvent(this@HornedFrogEntity, EntityEvent.START_ATTACKING)
                playSound(EKSounds.HORNED_FROG_TONGUE.get(), 1f, voicePitch)
            } else if (horizontal in PREY_LEAP_MIN..PREY_LEAP_MAX && tickCount >= nextLeapTick &&
                leapTowardAndRecord(target, horizontal)
            ) {
                stuckTicks = 0
            } else if (--repathTicks <= 0) {
                repathTicks = REPATH_TICKS
                // Return value ignored: with no path the chase times out after CHASE_TICKS.
                navigation.moveTo(target, CHASE_SPEED)
            }
        }

        private fun leapTowardAndRecord(target: LivingEntity, horizontal: Double): Boolean {
            nextLeapTick = tickCount + PREY_LEAP_RETRY_TICKS
            return leapToward(target, horizontal)
        }

        private fun swallow(target: LivingEntity) {
            if (!target.isAlive || !inTongueReach(target, TONGUE_SLACK)) return
            val ate = if (target is SwarmFlyEntity) target.eatFly() else target.eatWhole(this@HornedFrogEntity)
            if (!ate) return
            playSound(EKSounds.HORNED_FROG_EAT.get(), 1f, voicePitch)
            lastMealTick = level().gameTime
            mealCooldown = if (target is SwarmFlyEntity) {
                FLY_MEAL_MIN_TICKS + random.nextInt(FLY_MEAL_MAX_TICKS - FLY_MEAL_MIN_TICKS)
            } else {
                MOSKUO_MEAL_MIN_TICKS + random.nextInt(MOSKUO_MEAL_MAX_TICKS - MOSKUO_MEAL_MIN_TICKS)
            }
        }
    }

    /** Now and then a frog on the shore swims out and paddles between a few surface spots. */
    private inner class SwimForFunGoal : Goal() {
        private var swimTicks = 0
        private var legs = 0

        init {
            flags = EnumSet.of(Flag.MOVE)
        }

        override fun canUse(): Boolean {
            if (isInWater || !onGround() || random.nextInt(SWIM_CHANCE) != 0) return false
            return findSwimSpot()?.let { moveToSpot(it) } == true
        }

        override fun canContinueToUse(): Boolean = swimTicks > 0 && legs <= SWIM_MAX_LEGS

        override fun start() {
            // The default WATER malus keeps land paths dry; this swim wades straight in.
            setPathfindingMalus(BlockPathTypes.WATER, 0f)
            swimTicks = SWIM_MIN_TICKS + random.nextInt(SWIM_MAX_TICKS - SWIM_MIN_TICKS)
            legs = 0
        }

        override fun tick() {
            swimTicks--
            if (!navigation.isDone) return
            legs++
            val next = findSwimSpot()
            if (next == null || !moveToSpot(next)) swimTicks = 0
        }

        override fun stop() {
            setPathfindingMalus(BlockPathTypes.WATER, BlockPathTypes.WATER.malus)
            navigation.stop()
        }

        private fun moveToSpot(spot: BlockPos): Boolean =
            navigation.moveTo(spot.x + HALF, spot.y.toDouble(), spot.z + HALF, SWIM_SPEED)
    }

    /** Walks back to the best shore cell nearby whenever the frog is not on one, such as after a swim. */
    private inner class WaterlineGoal : Goal() {
        private var checkCooldown = 0
        private var walkTicks = 0

        init {
            flags = EnumSet.of(Flag.MOVE)
        }

        override fun canUse(): Boolean {
            if (--checkCooldown > 0) return false
            checkCooldown = SHORE_CHECK_TICKS
            val scratch = BlockPos.MutableBlockPos()
            if (onGround() && isWaterline(standingCell(), scratch)) return false
            val shore = findWaterline(SHORE_REACH, 0) ?: return false
            // From the water, a bank in leaping range is reached in one leap.
            if (isInWater && tryLeap(shore)) return true
            return navigation.moveTo(shore.x + HALF, shore.y.toDouble(), shore.z + HALF, WALK_SPEED)
        }

        override fun canContinueToUse(): Boolean = isLeaping || (!navigation.isDone && walkTicks < SHORE_WALK_TICKS)

        override fun start() {
            walkTicks = 0
        }

        override fun tick() {
            walkTicks++
        }
    }

    /** The frog's lazy move: now and then one leap to another shore cell a few blocks away. */
    private inner class HopGoal : Goal() {
        init {
            flags = EnumSet.of(Flag.MOVE, Flag.JUMP)
        }

        override fun canUse(): Boolean {
            if (!onGround() || isInWater || random.nextInt(HOP_CHANCE) != 0) return false
            val scratch = BlockPos.MutableBlockPos()
            if (!isWaterline(standingCell(), scratch)) return false
            val landing = findWaterline(HOP_REACH, HOP_MIN_DISTANCE_SQ) ?: return false
            return tryLeap(landing)
        }

        override fun canContinueToUse(): Boolean = isLeaping
    }

    companion object {
        const val ID_PATH = "horned_frog"
        const val WIDTH = 0.6f
        const val HEIGHT = 0.6f

        /** Prey the tongue takes: the Moskuo and swarm flies. */
        val PREY: TagKey<EntityType<*>> = TagKey.create(Registries.ENTITY_TYPE, EnderkinesisMod.id("horned_frog_prey"))

        private const val MEAL_KEY = "MealCooldown"
        private const val NO_TARGET = -1
        private val DATA_TONGUE_TARGET: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(HornedFrogEntity::class.java, EntityDataSerializers.INT)
        private const val SPAWN_KEY = "SpawnCooldown"
        private const val LAST_MEAL_KEY = "LastMeal"
        private const val PREGNANT_KEY = "Pregnant"
        // 2 minutes of checks every 5 s, then one a minute.
        private const val EAGER_LAY_CHECKS = 24
        private const val RETRY_LAY_CHECKS = 12

        /** A frog lays no spawn, and spawn hatches no frogs, past [MAX_CROWD] frogs in this radius. */
        const val CROWD_RADIUS = 16.0
        const val MAX_CROWD = 8
        private const val SPAWN_CAP_RADIUS = 12
        private const val SPAWN_CAP_RADIUS_Y = 3
        private const val MAX_SPAWN_NEAR = 2
        private const val GROUP_RADIUS = 8.0
        private const val LAY_CHECK_TICKS = 100
        private const val LAY_MIN_TICKS = 12000
        private const val LAY_MAX_TICKS = 24000
        private const val LAY_REACH = 2
        private const val WATER_SLOWDOWN = 0.9f
        private const val AIR_RISE = 0.02
        private const val STEP_VOLUME = 0.15f
        private const val HALF = 0.5
        private const val QUARTER_TURN = 90f
        private const val LOOK_TURN = 30f
        private const val LOOK_RANGE = 6f

        private const val SEARCH_INTERVAL_TICKS = 20
        private const val HUNT_RANGE = 8.0
        private const val HUNT_HEIGHT = 4.0
        private const val CHASE_TICKS = 160
        private const val CHASE_SPEED = 1.2
        private const val REPATH_TICKS = 10
        // The Tongue bone is 10 px long and scales to 4.2×, so it reaches about 2.5 blocks.
        private const val TONGUE_REACH = 2.5
        private const val TONGUE_SLACK = 0.5
        // Tongue pivot height: 5 px above the feet.
        private const val MOUTH_HEIGHT = 0.3
        private const val STUCK_TICKS = 60
        private const val PROGRESS_STEP = 0.25
        private const val UNREACHABLE_TICKS = 400
        private const val PREY_LEAP_MIN = 3.0
        private const val PREY_LEAP_MAX = 6.0
        private const val PREY_LEAP_RETRY_TICKS = 20
        private const val LEAP_SHORT = 1.5
        private const val LEAP_SEARCH_Y = 2
        // Tongue: the tip reaches the prey at 0.125 s (tick 2.5), so the prey goes at tick 3; the clip is 0.71 s.
        private const val TONGUE_HIT_TICK = 3
        private const val TONGUE_TICKS = 14
        private const val NOT_STRIKING = -1
        private const val FLY_MEAL_MIN_TICKS = 200
        private const val FLY_MEAL_MAX_TICKS = 400
        private const val MOSKUO_MEAL_MIN_TICKS = 1200
        private const val MOSKUO_MEAL_MAX_TICKS = 2400

        private const val SWIM_CHANCE = 600
        private const val SWIM_REACH = 6
        private const val SWIM_REACH_Y = 2
        private const val SWIM_SPOT_TRIES = 16
        private const val SWIM_MIN_TICKS = 300
        private const val SWIM_MAX_TICKS = 600
        private const val SWIM_MAX_LEGS = 4
        private const val SWIM_SPEED = 1.0

        private const val SHORE_CHECK_TICKS = 40
        private const val SHORE_REACH = 6
        private const val SHORE_REACH_Y = 2
        private const val SHORE_WALK_TICKS = 300
        private const val SCORE_NOISE = 4
        private const val WALK_SPEED = 1.0

        private const val HOP_CHANCE = 300
        private const val HOP_REACH = 4
        private const val HOP_MIN_DISTANCE_SQ = 4
        private const val LEAP_MIN_DISTANCE = 1.5
        private const val LEAP_MAX_DISTANCE = 6.0
        private const val LEAP_GRACE_TICKS = 10
        private const val LEAP_MIN_AIR_TICKS = 2
        private const val FEET_LIFT = 0.5
        private const val LOW_FLOOR = 0.5

        private const val NEAR_WATER_REACH = 4
        private val HORIZONTALS = arrayOf(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)

        /** Water with no water above it: the frog paddles on the surface. */
        fun isSwimSurface(level: BlockGetter, pos: BlockPos): Boolean =
            level.getFluidState(pos).`is`(FluidTags.WATER) && !level.getFluidState(pos.above()).`is`(FluidTags.WATER) &&
                level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty

        /** Spawn check: water within [NEAR_WATER_REACH] blocks at the frog's level or one below. */
        fun isNearWater(level: BlockGetter, pos: BlockPos): Boolean {
            val cursor = BlockPos.MutableBlockPos()
            for (dy in -1..0) {
                for (dx in -NEAR_WATER_REACH..NEAR_WATER_REACH) {
                    for (dz in -NEAR_WATER_REACH..NEAR_WATER_REACH) {
                        cursor.setWithOffset(pos, dx, dy, dz)
                        if (level.getFluidState(cursor).`is`(FluidTags.WATER)) return true
                    }
                }
            }
            return false
        }

        fun createAttributes(): AttributeSupplier.Builder = Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 8.0)
            .add(Attributes.MOVEMENT_SPEED, 0.2)
            .add(Attributes.FOLLOW_RANGE, 16.0)
    }
}
