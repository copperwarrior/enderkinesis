package org.shipwrights.enderkinesis.entity

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtUtils
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.tags.FluidTags
import net.minecraft.util.RandomSource
import net.minecraft.world.DifficultyInstance
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.entity.SpawnGroupData
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ambient.AmbientCreature
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.ServerLevelAccessor
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.registry.EKEffects
import kotlin.math.min
import kotlin.math.sin

/** Passive flame that drifts over the water near where it spawned. */
class WillOTheWispEntity(type: EntityType<out WillOTheWispEntity>, level: Level) : AmbientCreature(type, level) {
    private var home: BlockPos? = null
    private var target: Vec3? = null

    init {
        setNoGravity(true)
    }

    override fun defineSynchedData() {
        super.defineSynchedData()
        entityData.define(BORN, UNSET)
        entityData.define(ENDS, Long.MAX_VALUE)
    }

    // Game time, not tickCount, so a wisp that comes back into view or reloads does not fade in again.
    fun fade(partialTick: Float): Float {
        val born = entityData.get(BORN)
        if (born == UNSET) return 0f
        val now = level().gameTime + partialTick.toDouble()
        val fadeIn = (now - born) / FADE_TICKS
        val fadeOut = (entityData.get(ENDS) - now) / FADE_TICKS
        return min(fadeIn, fadeOut).coerceIn(0.0, 1.0).toFloat()
    }

    fun burst(partialTick: Float): Float =
        if (isDeadOrDying) ((deathTime + partialTick) / BURST_TICKS).coerceIn(0f, 1f) else 0f

    override fun tick() {
        super.tick()
        if (level().isClientSide || isDeadOrDying) return
        val now = level().gameTime
        if (entityData.get(BORN) == UNSET) {
            entityData.set(BORN, now)
            entityData.set(ENDS, now + LIFE_MIN + random.nextInt(LIFE_SPREAD))
        }
        if (isPersistenceRequired || requiresCustomPersistence()) {
            entityData.set(ENDS, Long.MAX_VALUE)
        } else if (now >= entityData.get(ENDS)) {
            discard()
        }
    }

    override fun canBeAffected(effect: MobEffectInstance): Boolean =
        effect.effect != EKEffects.TRYPOEMIA.get() && super.canBeAffected(effect)

    // Vanilla also drops ambient mobs at random within view; the lifetime replaces that, so they fade out instead.
    override fun checkDespawn() {
        noActionTime = 0
        if (isPersistenceRequired || requiresCustomPersistence()) return
        val player = level().getNearestPlayer(this, -1.0) ?: return
        val reach = type.category.despawnDistance
        if (player.distanceToSqr(this) > reach.toDouble() * reach) discard()
    }

    // The flame burst replaces the vanilla poof and the 20-tick death pose.
    override fun tickDeath() {
        ++deathTime
        if (deathTime >= BURST_TICKS && !level().isClientSide && !isRemoved) remove(RemovalReason.KILLED)
    }

    override fun finalizeSpawn(
        level: ServerLevelAccessor,
        difficulty: DifficultyInstance,
        reason: MobSpawnType,
        spawnData: SpawnGroupData?,
        dataTag: CompoundTag?,
    ): SpawnGroupData? {
        home = blockPosition()
        return super.finalizeSpawn(level, difficulty, reason, spawnData, dataTag)
    }

    override fun customServerAiStep() {
        super.customServerAiStep()
        val anchor = home ?: blockPosition().also { home = it }
        val goal = target?.takeUnless { it.distanceToSqr(position()) < ARRIVE_SQ || random.nextInt(RETARGET_CHANCE) == 0 }
            ?: pickTarget(anchor).also { target = it }
        val to = goal.subtract(position())
        val len = to.length()
        val v = deltaMovement
        val want = if (len > 1e-4) to.scale(MAX_SPEED / maxOf(len, 1.0)) else Vec3.ZERO
        val bob = sin((tickCount + id * BOB_PHASE) * BOB_RATE) * BOB_SPEED
        deltaMovement = Vec3(
            v.x + (want.x - v.x) * STEER,
            v.y + (want.y + bob - v.y) * STEER,
            v.z + (want.z - v.z) * STEER,
        )
    }

    // Prefers water columns; falls back to the last column tried, so a dried pond does not strand it.
    private fun pickTarget(anchor: BlockPos): Vec3 {
        val cursor = BlockPos.MutableBlockPos()
        var fallback: Vec3? = null
        repeat(TARGET_TRIES) {
            val x = anchor.x + random.nextInt(WANDER * 2 + 1) - WANDER
            val z = anchor.z + random.nextInt(WANDER * 2 + 1) - WANDER
            val surface = surfaceBelow(level(), cursor.set(x, anchor.y + SCAN_ABOVE, z)) ?: return@repeat
            val hover = surface.y + 1 + HOVER_MIN + random.nextDouble() * (HOVER_MAX - HOVER_MIN)
            val point = Vec3(x + 0.5, hover, z + 0.5)
            if (level().getFluidState(surface).`is`(FluidTags.WATER)) return point
            fallback = point
        }
        return fallback ?: position().add(random.nextGaussian(), 0.0, random.nextGaussian())
    }

    override fun isPushable(): Boolean = false

    override fun doPush(entity: net.minecraft.world.entity.Entity) = Unit

    override fun causeFallDamage(distance: Float, multiplier: Float, source: net.minecraft.world.damagesource.DamageSource) =
        false

    override fun checkFallDamage(y: Double, onGround: Boolean, state: net.minecraft.world.level.block.state.BlockState, pos: BlockPos) =
        Unit

    override fun isIgnoringBlockTriggers(): Boolean = true

    override fun addAdditionalSaveData(tag: CompoundTag) {
        super.addAdditionalSaveData(tag)
        home?.let { tag.put(HOME_TAG, NbtUtils.writeBlockPos(it)) }
        tag.putLong(BORN_TAG, entityData.get(BORN))
        tag.putLong(ENDS_TAG, entityData.get(ENDS))
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)
        if (tag.contains(HOME_TAG)) home = NbtUtils.readBlockPos(tag.getCompound(HOME_TAG))
        if (tag.contains(BORN_TAG)) entityData.set(BORN, tag.getLong(BORN_TAG))
        if (tag.contains(ENDS_TAG)) entityData.set(ENDS, tag.getLong(ENDS_TAG))
    }

    companion object {
        const val ID_PATH = "will_o_the_wisp"
        const val WIDTH = 0.7f
        const val HEIGHT = 1.0f

        private const val HOME_TAG = "Home"
        private const val BORN_TAG = "Born"
        private const val ENDS_TAG = "Ends"
        private const val UNSET = -1L
        private const val FADE_TICKS = 40.0
        private const val BURST_TICKS = 12
        private const val LIFE_MIN = 1200
        private const val LIFE_SPREAD = 2400

        private val BORN: EntityDataAccessor<Long> =
            SynchedEntityData.defineId(WillOTheWispEntity::class.java, EntityDataSerializers.LONG)

        // Game time when the fade-out ends and the wisp leaves.
        private val ENDS: EntityDataAccessor<Long> =
            SynchedEntityData.defineId(WillOTheWispEntity::class.java, EntityDataSerializers.LONG)
        private const val WANDER = 6
        private const val TARGET_TRIES = 4
        // Low start, so a canopy over the pond does not read as the surface.
        private const val SCAN_ABOVE = 3
        private const val SURFACE_SCAN = 10
        private const val HOVER_MIN = 0.6
        private const val HOVER_MAX = 2.2
        private const val ARRIVE_SQ = 0.5
        private const val RETARGET_CHANCE = 160
        private const val MAX_SPEED = 0.06
        private const val STEER = 0.05
        private const val BOB_RATE = 0.07
        private const val BOB_PHASE = 17
        private const val BOB_SPEED = 0.015
        private const val SPAWN_REACH = 2
        private const val SPAWN_DEPTH = 3

        fun createAttributes(): AttributeSupplier.Builder =
            Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 4.0)

        /** Top block of the first non-air run at or below [from], or null when the scan finds none. */
        private fun surfaceBelow(level: LevelAccessor, from: BlockPos.MutableBlockPos): BlockPos? {
            repeat(SURFACE_SCAN) {
                if (!level.isEmptyBlock(from)) return from.immutable()
                from.move(0, -1, 0)
            }
            return null
        }

        /** Air at [pos], with water at most [SPAWN_DEPTH] blocks under it and [SPAWN_REACH] to the side. */
        @Suppress("UNUSED_PARAMETER") // Shape fixed by SpawnPlacements.SpawnPredicate.
        fun canSpawn(
            type: EntityType<Mob>,
            level: ServerLevelAccessor,
            spawnType: MobSpawnType,
            pos: BlockPos,
            random: RandomSource,
        ): Boolean {
            if (!level.isEmptyBlock(pos)) return false
            val cursor = BlockPos.MutableBlockPos()
            for (dy in 1..SPAWN_DEPTH) {
                for (dx in -SPAWN_REACH..SPAWN_REACH) {
                    for (dz in -SPAWN_REACH..SPAWN_REACH) {
                        cursor.setWithOffset(pos, dx, -dy, dz)
                        if (level.getFluidState(cursor).`is`(FluidTags.WATER)) return true
                    }
                }
            }
            return false
        }
    }
}
