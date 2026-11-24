package org.shipwrights.enderkinesis.entity

import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.tags.DamageTypeTags
import net.minecraft.tags.FluidTags
import net.minecraft.util.Mth
import net.minecraft.util.RandomSource
import net.minecraft.world.DifficultyInstance
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySelector
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.entity.SpawnGroupData
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.navigation.PathNavigation
import net.minecraft.world.entity.ambient.AmbientCreature
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.level.Level
import net.minecraft.world.level.ServerLevelAccessor
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.AABB
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One entity is a whole swarm: the server moves only the swarm centre and its fly count,
 * and the client draws each fly as a sprite on a seeded path around that centre.
 */
class SwarmFlyEntity(type: EntityType<out SwarmFlyEntity>, level: Level) : AmbientCreature(type, level) {

    enum class Mode { WANDER, PESTER, REST }

    val flyCount: Int get() = entityData.get(DATA_COUNT)

    val mode: Mode get() = Mode.entries.getOrElse(entityData.get(DATA_MODE).toInt()) { Mode.WANDER }

    /** Cloud radius for the current count and mode; the frog's tongue reach adds this. */
    val cloudRadius: Float get() = swarmRadius(flyCount, mode == Mode.REST)

    private var goalX = 0.0
    private var goalY = 0.0
    private var goalZ = 0.0
    private var hasGoal = false
    private var goalSpeed = WANDER_SPEED
    private var shunned: UUID? = null
    private var shunTicks = 0
    private var modeTicks = 0
    private var pesterTarget: LivingEntity? = null
    private var restPos: BlockPos? = null
    private var settled = false
    private var arriveTicks = 0
    private var splitCooldown = SPLIT_COOLDOWN_TICKS
    private var mergeCooldown = 0
    private var pesterCooldown = PESTER_COOLDOWN_MIN_TICKS
    private var restCooldown = REST_COOLDOWN_MIN_TICKS

    init {
        isNoGravity = true
    }

    override fun defineSynchedData() {
        super.defineSynchedData()
        entityData.define(DATA_COUNT, 1)
        entityData.define(DATA_MODE, Mode.WANDER.ordinal.toByte())
    }

    private fun setCount(value: Int) = entityData.set(DATA_COUNT, value.coerceIn(0, SWARM_MAX_FLIES))

    private fun setMode(next: Mode, ticks: Int) {
        entityData.set(DATA_MODE, next.ordinal.toByte())
        modeTicks = ticks
        hasGoal = false
        settled = false
        arriveTicks = 0
        if (next != Mode.PESTER) pesterTarget = null
        if (next != Mode.REST) restPos = null
    }

    // Steering moves the swarm; the navigation is here only for the Wogor-leaves rule shared by all fauna.
    override fun createNavigation(level: Level): PathNavigation = wogorLeavesFlyingNavigation(this, level)

    // Pickable so a melee swing can swat it; projectiles still pass through.
    override fun isPickable(): Boolean = !isRemoved

    override fun canBeHitByProjectile(): Boolean = false

    override fun isPushable(): Boolean = false

    override fun isAttackable(): Boolean = true

    override fun doPush(entity: Entity) = Unit

    override fun pushEntities() = Unit

    override fun isIgnoringBlockTriggers(): Boolean = true

    override fun isInvulnerableTo(source: DamageSource): Boolean = !source.`is`(DamageTypeTags.BYPASSES_INVULNERABILITY)

    /** A melee hit disperses the swarm and deals no damage, so no fly dies and no kill counts. */
    override fun hurt(source: DamageSource, amount: Float): Boolean {
        if (source.`is`(DamageTypeTags.BYPASSES_INVULNERABILITY)) return super.hurt(source, amount)
        val attacker = source.entity
        if (!level().isClientSide && !isRemoved && attacker is LivingEntity && source.directEntity === attacker) {
            disperse(attacker)
        }
        return false
    }

    private fun disperse(attacker: LivingEntity) {
        val pieces = swarmDispersePieces(flyCount) { SWARM_DISPERSE_MIN + random.nextInt(SWARM_DISPERSE_MAX - SWARM_DISPERSE_MIN + 1) }
        if (pieces.isEmpty()) return
        for (index in 1 until pieces.size) {
            val child = type.create(level()) as? SwarmFlyEntity ?: continue
            child.moveTo(x, y, z, random.nextFloat() * FULL_TURN, 0f)
            child.setCount(pieces[index])
            child.fleeFrom(attacker)
            // A piece that cannot spawn keeps its flies in this swarm.
            if (level().addFreshEntity(child)) setCount(flyCount - pieces[index])
        }
        fleeFrom(attacker)
    }

    private fun fleeFrom(attacker: LivingEntity) {
        setMode(Mode.WANDER, FLEE_TICKS)
        shunned = attacker.uuid
        shunTicks = SHUN_TICKS
        splitCooldown = SPLIT_COOLDOWN_TICKS
        mergeCooldown = SPLIT_COOLDOWN_TICKS
        val angle = Mth.atan2(z - attacker.z, x - attacker.x).toFloat() + (random.nextFloat() * 2 - 1) * FLEE_SPREAD
        val distance = FLEE_MIN_DISTANCE + random.nextDouble() * FLEE_EXTRA_DISTANCE
        goalX = x + Mth.cos(angle) * distance
        goalY = y + random.nextDouble() * FLEE_RISE
        goalZ = z + Mth.sin(angle) * distance
        goalSpeed = FLEE_SPEED
        hasGoal = true
    }

    override fun causeFallDamage(distance: Float, multiplier: Float, source: DamageSource): Boolean = false

    override fun checkFallDamage(y: Double, onGround: Boolean, state: BlockState, pos: BlockPos) = Unit

    override fun getBoundingBoxForCulling(): AABB = boundingBox.inflate(CULL_MARGIN)

    /** The frog's meal: one fly less; an empty swarm goes away with no drops. */
    fun eatFly(): Boolean {
        val count = flyCount
        if (count <= 0 || isRemoved) return false
        setCount(count - 1)
        if (count == 1) discard()
        return true
    }

    override fun customServerAiStep() {
        super.customServerAiStep()
        if (splitCooldown > 0) splitCooldown--
        if (mergeCooldown > 0) mergeCooldown--
        if (pesterCooldown > 0) pesterCooldown--
        if (restCooldown > 0) restCooldown--
        if (shunTicks > 0 && --shunTicks == 0) shunned = null
        modeTicks--
        when (mode) {
            Mode.WANDER -> wander()
            Mode.PESTER -> pester()
            Mode.REST -> rest()
        }
    }

    private fun wander() {
        if ((tickCount + id) % SOCIAL_CHECK_TICKS == 0 && socialCheck()) return
        if (!hasGoal || modeTicks <= 0 || distanceToSqr(goalX, goalY, goalZ) < GOAL_REACHED_SQ) pickWanderGoal()
        if (hasGoal) steerTo(goalX, goalY, goalZ, goalSpeed)
    }

    /** Merge, split, pester or rest; true when the mode changed. */
    private fun socialCheck(): Boolean {
        if (mergeCooldown == 0) tryMerge()
        if (splitCooldown == 0 && random.nextFloat() < SPLIT_CHANCE) trySplit()
        if (pesterCooldown == 0 && random.nextFloat() < PESTER_CHANCE) {
            val target = findPesterTarget()
            if (target != null) {
                startPester(target)
                return true
            }
        }
        if (restCooldown == 0 && random.nextFloat() < REST_CHANCE) {
            val cover = findCover()
            if (cover != null) {
                setMode(Mode.REST, REST_MIN_TICKS + random.nextInt(REST_MAX_TICKS - REST_MIN_TICKS))
                restPos = cover
                return true
            }
        }
        return false
    }

    private fun tryMerge() {
        val others = level().getEntitiesOfClass(SwarmFlyEntity::class.java, boundingBox.inflate(MERGE_RANGE)) {
            it !== this && it.isAlive && it.mode == Mode.WANDER && it.mergeCooldown == 0
        }
        val count = flyCount
        val other = others.firstOrNull { swarmCanMerge(count, it.flyCount) && swarmAbsorbs(count, id, it.flyCount, it.id) }
            ?: return
        setCount(count + other.flyCount)
        other.discard()
    }

    private fun trySplit() {
        val count = flyCount
        val leaving = swarmSplitSize(count)
        if (leaving == 0) return
        val child = type.create(level()) as? SwarmFlyEntity ?: return
        child.moveTo(x, y, z, random.nextFloat() * FULL_TURN, 0f)
        child.setCount(leaving)
        child.splitCooldown = SPLIT_COOLDOWN_TICKS
        child.mergeCooldown = SPLIT_COOLDOWN_TICKS
        if (!level().addFreshEntity(child)) return
        setCount(count - leaving)
        splitCooldown = SPLIT_COOLDOWN_TICKS
        mergeCooldown = SPLIT_COOLDOWN_TICKS
        hasGoal = false
    }

    private fun pickWanderGoal() {
        modeTicks = GOAL_MIN_TICKS + random.nextInt(GOAL_MAX_TICKS - GOAL_MIN_TICKS)
        goalSpeed = WANDER_SPEED
        if (mergeCooldown == 0 && random.nextFloat() < MERGE_SEEK_CHANCE && seekMergePartner()) return
        repeat(GOAL_TRIES) {
            val gx = x + (random.nextDouble() * 2 - 1) * WANDER_RANGE
            val gz = z + (random.nextDouble() * 2 - 1) * WANDER_RANGE
            val bx = Mth.floor(gx)
            val bz = Mth.floor(gz)
            if (!level().hasChunk(bx shr CHUNK_SHIFT, bz shr CHUNK_SHIFT)) return@repeat
            // NO_LEAVES keeps the swarm under the canopy; the height map counts water as a floor.
            val ground = level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz)
            val gy = ground + HOVER_MIN + random.nextDouble() * HOVER_SPREAD
            if (abs(gy - y) > MAX_CLIMB) return@repeat
            goalX = gx
            goalY = gy
            goalZ = gz
            hasGoal = true
            return
        }
    }

    private fun seekMergePartner(): Boolean {
        val count = flyCount
        val partner = level().getEntitiesOfClass(SwarmFlyEntity::class.java, boundingBox.inflate(MERGE_SEEK_RANGE)) {
            it !== this && it.isAlive && it.mode == Mode.WANDER && swarmCanMerge(count, it.flyCount)
        }.minByOrNull { it.distanceToSqr(this) } ?: return false
        goalX = partner.x
        goalY = partner.y
        goalZ = partner.z
        hasGoal = true
        return true
    }

    private fun isPesterable(entity: LivingEntity): Boolean =
        entity !is SwarmFlyEntity && entity !is ArmorStand && entity.isAlive && !entity.isUnderWater &&
            EntitySelector.NO_SPECTATORS.test(entity) && entity.uuid != shunned

    private fun findPesterTarget(): LivingEntity? =
        level().getEntitiesOfClass(LivingEntity::class.java, boundingBox.inflate(PESTER_RANGE)) {
            isPesterable(it) && hasLineOfSight(it)
        }.minByOrNull { it.distanceToSqr(this) }

    private fun startPester(target: LivingEntity) {
        setMode(Mode.PESTER, PESTER_MIN_TICKS + random.nextInt(PESTER_MAX_TICKS - PESTER_MIN_TICKS))
        pesterTarget = target
    }

    /** Hangs around the target's head; a target that outruns the leash loses the swarm. */
    private fun pester() {
        val target = pesterTarget
        if (target == null || modeTicks <= 0 || !isPesterable(target) ||
            target.level() !== level() || distanceToSqr(target) > PESTER_LEASH * PESTER_LEASH
        ) {
            pesterCooldown = PESTER_COOLDOWN_MIN_TICKS + random.nextInt(PESTER_COOLDOWN_MIN_TICKS)
            setMode(Mode.WANDER, 0)
            return
        }
        // Centre the cloud just above the eyes, so the flies cross the face.
        steerTo(target.x, target.eyeY + HEAD_LIFT - bbHeight / 2, target.z, PESTER_SPEED)
    }

    /** Flies into cover and stays there until the time runs out or something comes too close. */
    private fun rest() {
        val cover = restPos
        if (cover == null || modeTicks <= 0 || !level().getBlockState(cover).`is`(MoskuoEntity.HIDES_IN)) {
            endRest(null)
            return
        }
        val cx = cover.x + HALF
        val cy = cover.y + HALF - bbHeight / 2
        val cz = cover.z + HALF
        if (!settled) {
            steerTo(cx, cy, cz, WANDER_SPEED)
            settled = distanceToSqr(cx, cy, cz) < SETTLE_DISTANCE_SQ
            // Too long on the way in: the cover is out of reach.
            if (!settled && ++arriveTicks > REST_ARRIVE_TICKS) endRest(null)
            return
        }
        deltaMovement = deltaMovement.scale(REST_DAMPING)
        if ((tickCount + id) % DISTURB_CHECK_TICKS != 0) return
        val disturber = level().getEntitiesOfClass(LivingEntity::class.java, boundingBox.inflate(DISTURB_RANGE)) {
            isPesterable(it)
        }.minByOrNull { it.distanceToSqr(this) }
        if (disturber != null) endRest(disturber)
    }

    private fun endRest(disturber: LivingEntity?) {
        restCooldown = REST_COOLDOWN_MIN_TICKS + random.nextInt(REST_COOLDOWN_MIN_TICKS)
        if (disturber != null) startPester(disturber) else setMode(Mode.WANDER, 0)
    }

    // Bat-style steering: ease the velocity toward the wanted one, and let travel() move and drag it.
    private fun steerTo(tx: Double, ty: Double, tz: Double, speed: Double) {
        val dx = tx - x
        val dy = ty - y
        val dz = tz - z
        val distance = sqrt(dx * dx + dy * dy + dz * dz)
        val velocity = deltaMovement
        if (distance < MIN_STEER_DISTANCE) {
            deltaMovement = velocity.scale(REST_DAMPING)
            return
        }
        val scale = minOf(speed, distance * ARRIVE_SLOWDOWN) / distance
        setDeltaMovement(
            velocity.x + (dx * scale - velocity.x) * STEER,
            velocity.y + (dy * scale - velocity.y) * STEER,
            velocity.z + (dz * scale - velocity.z) * STEER,
        )
    }

    private fun findCover(): BlockPos? {
        val origin = blockPosition()
        val cursor = BlockPos.MutableBlockPos()
        var best: BlockPos? = null
        var bestDistance = Int.MAX_VALUE
        for (dy in -COVER_REACH_Y..COVER_REACH_Y) {
            for (dx in -COVER_REACH..COVER_REACH) {
                for (dz in -COVER_REACH..COVER_REACH) {
                    val distance = dx * dx + dy * dy + dz * dz
                    if (distance >= bestDistance) continue
                    cursor.setWithOffset(origin, dx, dy, dz)
                    if (!level().getBlockState(cursor).`is`(MoskuoEntity.HIDES_IN)) continue
                    bestDistance = distance
                    best = cursor.immutable()
                }
            }
        }
        return best
    }

    override fun finalizeSpawn(
        level: ServerLevelAccessor, difficulty: DifficultyInstance, spawnType: MobSpawnType,
        spawnData: SpawnGroupData?, tag: CompoundTag?,
    ): SpawnGroupData? {
        setCount(SPAWN_MIN_FLIES + random.nextInt(SPAWN_MAX_FLIES - SPAWN_MIN_FLIES + 1))
        return super.finalizeSpawn(level, difficulty, spawnType, spawnData, tag)
    }

    override fun addAdditionalSaveData(tag: CompoundTag) {
        super.addAdditionalSaveData(tag)
        tag.putInt(COUNT_KEY, flyCount)
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)
        setCount(tag.getInt(COUNT_KEY).coerceAtLeast(1))
    }

    companion object {
        const val ID_PATH = "swarm_fly"
        const val WIDTH = 0.5f
        const val HEIGHT = 0.5f

        private val DATA_COUNT: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(SwarmFlyEntity::class.java, EntityDataSerializers.INT)
        private val DATA_MODE: EntityDataAccessor<Byte> =
            SynchedEntityData.defineId(SwarmFlyEntity::class.java, EntityDataSerializers.BYTE)

        private const val COUNT_KEY = "Flies"
        private const val SPAWN_MIN_FLIES = 8
        private const val SPAWN_MAX_FLIES = 16
        private const val CULL_MARGIN = 1.5

        private const val SOCIAL_CHECK_TICKS = 20
        private const val SPLIT_CHANCE = 0.04f
        private const val SPLIT_COOLDOWN_TICKS = 400
        private const val MERGE_RANGE = 1.5
        private const val MERGE_SEEK_CHANCE = 0.3f
        private const val MERGE_SEEK_RANGE = 12.0

        private const val WANDER_SPEED = 0.12
        private const val WANDER_RANGE = 8.0
        private const val GOAL_TRIES = 4
        private const val GOAL_MIN_TICKS = 60
        private const val GOAL_MAX_TICKS = 160
        private const val GOAL_REACHED_SQ = 0.5
        private const val HOVER_MIN = 0.8
        private const val HOVER_SPREAD = 1.7
        private const val MAX_CLIMB = 6.0
        private const val FULL_TURN = 360f
        private const val CHUNK_SHIFT = 4

        private const val PESTER_CHANCE = 0.05f
        private const val PESTER_RANGE = 8.0
        private const val PESTER_LEASH = 12.0
        private const val PESTER_SPEED = 0.3
        private const val PESTER_MIN_TICKS = 200
        private const val PESTER_MAX_TICKS = 400
        private const val PESTER_COOLDOWN_MIN_TICKS = 600
        private const val HEAD_LIFT = 0.25

        private const val FLEE_TICKS = 60
        private const val FLEE_SPEED = 0.3
        private const val FLEE_SPREAD = 1.2f
        private const val FLEE_MIN_DISTANCE = 3.0
        private const val FLEE_EXTRA_DISTANCE = 3.0
        private const val FLEE_RISE = 1.5
        private const val SHUN_TICKS = 1200

        private const val REST_CHANCE = 0.03f
        private const val REST_MIN_TICKS = 600
        private const val REST_MAX_TICKS = 1800
        private const val REST_ARRIVE_TICKS = 200
        private const val REST_COOLDOWN_MIN_TICKS = 1200
        private const val REST_DAMPING = 0.5
        private const val SETTLE_DISTANCE_SQ = 0.09
        private const val DISTURB_CHECK_TICKS = 10
        private const val DISTURB_RANGE = 2.0
        private const val COVER_REACH = 8
        private const val COVER_REACH_Y = 4
        private const val HALF = 0.5

        private const val STEER = 0.1
        private const val ARRIVE_SLOWDOWN = 0.2
        private const val MIN_STEER_DISTANCE = 0.05

        /** Open air or cover, with ground, water or cover at most [SPAWN_FLOOR_REACH] blocks below. */
        @Suppress("UNUSED_PARAMETER") // Shape fixed by SpawnPlacements.SpawnPredicate.
        fun canSpawn(
            type: EntityType<Mob>,
            level: ServerLevelAccessor,
            spawnType: MobSpawnType,
            pos: BlockPos,
            random: RandomSource,
        ): Boolean {
            val here = level.getBlockState(pos)
            if (!here.isAir && !here.`is`(MoskuoEntity.HIDES_IN)) return false
            val cursor = pos.mutable()
            repeat(SPAWN_FLOOR_REACH) {
                cursor.move(0, -1, 0)
                val state = level.getBlockState(cursor)
                if (!state.isAir) {
                    return state.`is`(MoskuoEntity.HIDES_IN) || level.getFluidState(cursor).`is`(FluidTags.WATER) ||
                        state.isFaceSturdy(level, cursor, Direction.UP)
                }
            }
            return false
        }

        private const val SPAWN_FLOOR_REACH = 3

        fun createAttributes(): AttributeSupplier.Builder = Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 1.0)
            .add(Attributes.MOVEMENT_SPEED, 0.0)
    }
}
