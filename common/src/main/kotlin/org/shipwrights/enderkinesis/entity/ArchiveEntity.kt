package org.shipwrights.enderkinesis.entity

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.control.FlyingMoveControl
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation
import net.minecraft.world.entity.ai.navigation.PathNavigation
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.loot.LootParams
import net.minecraft.world.level.storage.loot.LootTable
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets
import net.minecraft.world.level.storage.loot.parameters.LootContextParams
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.registry.EKParticles
import kotlin.math.cos
import kotlin.math.sin

/**
 * **The Archive** — a slow-drifting tornado of papers, books, and scholar
 * fragments that wanders Sselith corridors. Bound by collision; routes via
 * [FlyingPathNavigation] from one cell centre to the next, so a 3-cell hop
 * actually walks the corridor openings rather than phasing through walls.
 * On player contact it collapses, scattering its contained items as drops.
 */
class ArchiveEntity(type: EntityType<out ArchiveEntity>, level: Level) :
    Mob(type, level) {

    /** Items the archive will drop on collapse. Rolled server-side on first
     *  tick after spawn (the constructor runs before [Level.addFreshEntity], so
     *  we can't roll here — the seed is fine but the entity hasn't been
     *  level-anchored yet). Persists across save/load via NBT. */
    private val contents: MutableList<ItemStack> = mutableListOf()
    private var contentsRolled: Boolean = false

    /** Wander target, in world coords. NaN-sentinel via [hasTarget] rather than
     *  Vec3? — JOML/MC Vec3 is value-class-ish, the boolean is cheaper. */
    private var targetX: Double = 0.0
    private var targetY: Double = 0.0
    private var targetZ: Double = 0.0
    private var hasTarget: Boolean = false

    /** Spawn-grace counter — collapse is suppressed for this many ticks after
     *  the entity first comes into existence so spawning the egg right next to
     *  the player doesn't immediately collapse it. Counts down each server
     *  tick; persisted in NBT so a relog inside the grace window doesn't reset. */
    private var spawnGrace: Int = SPAWN_GRACE_TICKS

    /** Path-search cooldown. FlyingPathNavigation with FOLLOW_RANGE 48 is the
     *  single most expensive thing this entity does per tick; gating
     *  resubmission to once per [PATH_COOLDOWN_TICKS] means the archive does
     *  at most one path search every ~5 s, instead of one every tick the
     *  navigator happens to be idle. The visible effect is a brief "settle"
     *  pause at each cell centre before drifting onward — fits the mood. */
    private var pathCooldown: Int = 0

    init {
        // Floating phenomenon — disable gravity so the entity stays at its
        // current Y. Collision IS enabled (noPhysics is left at default false)
        // so the tornado is bound by Sselith's walls — it has to route through
        // corridor openings, not phase through them.
        setNoGravity(true)
        // FlyingMoveControl translates navigation waypoints into deltaMovement;
        // (mob, maxPitchChange, noGravity) — last arg matches our setNoGravity.
        moveControl = FlyingMoveControl(this, 20, true)
    }

    /** Flying pathfinder so the navigator routes through 3-D air, not just
     *  ground tiles. Walks the corridors of Sselith via the openings between
     *  cells; doors/floats default off (the maze has neither). */
    override fun createNavigation(level: Level): PathNavigation {
        val nav = FlyingPathNavigation(this, level)
        nav.setCanOpenDoors(false)
        nav.setCanFloat(false)
        nav.setCanPassDoors(false)
        return nav
    }

    /** Indestructible — players "destroy" an Archive by *touching* it (which
     *  collapses it into loot), not by hitting it. Any other damage source is
     *  ignored entirely so a stray arrow doesn't bypass the collapse-on-contact. */
    override fun isInvulnerableTo(source: DamageSource): Boolean = true

    /** Force-pin onGround to false. The archive is a floating phenomenon, not
     *  a creature standing on a block; vanilla's Entity.checkFallDamage calls
     *  `block.fallOn(...)` whenever onGround becomes true (which fires the
     *  landing sound + particles), and that's exactly what we don't want a
     *  tornado settling on a corridor floor to do. */
    override fun setOnGround(onGround: Boolean) {
        super.setOnGround(false)
    }

    /** Empty — movement is owned by direct navigation.moveTo() calls in [tick]
     *  rather than goal-based AI, so no goals are registered. */
    override fun registerGoals() {
    }

    /** Defines the synced contents field so the client renderer can draw the
     *  real items the archive will drop on collapse, not a stand-in mix. */
    override fun defineSynchedData() {
        super.defineSynchedData()
        entityData.define(DATA_CONTENTS, CompoundTag())
    }

    /** Override checkDespawn so the Archive cleans up when no players are near
     *  (same condition as the in-tick despawn for old chunks). */
    override fun checkDespawn() {
        if (level().isClientSide) return
        if (level().getNearestPlayer(this, DESPAWN_RANGE) == null) discard()
    }

    override fun tick() {
        if (!level().isClientSide) {
            val server = level() as ServerLevel
            if (!contentsRolled) {
                rollContents(server)
                contentsRolled = true
                syncContents()
            }
            val nearest = level().getNearestPlayer(this, DESPAWN_RANGE)
            if (nearest == null) {
                discard()
                return
            }
            if (spawnGrace > 0) {
                spawnGrace--
            } else {
                val touching = level().getEntitiesOfClass(
                    Player::class.java, boundingBox.inflate(COLLAPSE_INFLATE)
                )
                if (touching.isNotEmpty()) {
                    collapse()
                    return
                }
            }
            // Pick a new target only when the navigator is idle AND we're past
            // the path-search cooldown. No forced periodic repick — letting the
            // current path finish naturally is much cheaper than restarting
            // the (expensive) FlyingPathNavigation search every N ticks.
            if (pathCooldown > 0) pathCooldown--
            if (pathCooldown == 0 && (!hasTarget || navigation.isDone)) {
                pickAndSubmitTarget()
                pathCooldown = PATH_COOLDOWN_TICKS
            }
        }

        super.tick()

        if (level().isClientSide) {
            spawnDustSpiral()
            spawnAmbientDust()
        }
    }

    /** Pick a new cell-centre target and submit it to the path navigator. If
     *  the navigator can't find a path (target unreachable from current cell),
     *  the entity will repick next tick — the [navigation.isDone] check in
     *  [tick] catches the failure. */
    private fun pickAndSubmitTarget() {
        pickNewTarget()
        navigation.moveTo(targetX, targetY, targetZ, NAV_SPEED)
    }

    /** Snap to the centre of a random NEIGHBOUR Sselith cell — one of the 8
     *  surrounding cells in the 3×3 XZ window, excluding "stay put". Y stays
     *  at the current height so the archive doesn't try to climb/descend
     *  through battlement caps. Cell constants are duplicated from
     *  `SselithRepertoryChunkGenerator` — kept in sync via shared named
     *  constants; if those change there, change here too. */
    private fun pickNewTarget() {
        val cellX = Math.floorDiv((x.toInt()) - SSELITH_GRID_OFFSET, SSELITH_CELL_X)
        val cellZ = Math.floorDiv((z.toInt()) - SSELITH_GRID_OFFSET, SSELITH_CELL_Z)
        var dirX: Int
        var dirZ: Int
        do {
            dirX = random.nextInt(3) - 1
            dirZ = random.nextInt(3) - 1
        } while (dirX == 0 && dirZ == 0)
        val nextCellX = cellX + dirX
        val nextCellZ = cellZ + dirZ
        targetX = (nextCellX * SSELITH_CELL_X + SSELITH_GRID_OFFSET).toDouble() + SSELITH_CELL_X * 0.5
        targetZ = (nextCellZ * SSELITH_CELL_Z + SSELITH_GRID_OFFSET).toDouble() + SSELITH_CELL_Z * 0.5
        targetY = y
        hasTarget = true
    }

    /** Roll the archive's contents from the entity loot table. Vanilla
     *  `LootTable.getRandomItems(ENTITY ctx)` resolves pool weights, conditions,
     *  and counts — drop the items it returns on collapse. Falls back to an empty
     *  list if the table is missing (datapack removed or not loaded). */
    private fun rollContents(server: ServerLevel) {
        contents.clear()
        val table: LootTable = server.server.lootData.getLootTable(LOOT_TABLE)
        if (table === LootTable.EMPTY) return
        val ctx = LootParams.Builder(server)
            .withParameter(LootContextParams.ORIGIN, position())
            .withParameter(LootContextParams.THIS_ENTITY, this)
            .withParameter(LootContextParams.DAMAGE_SOURCE, server.damageSources().generic())
            .create(LootContextParamSets.ENTITY)
        contents.addAll(table.getRandomItems(ctx))
    }

    /** Server-side: push the rolled contents into the synced data slot so the
     *  client renderer can draw the actual items orbiting the tornado. Called
     *  once after [rollContents] and on NBT load. */
    private fun syncContents() {
        val tag = CompoundTag()
        val list = ListTag()
        for (stack in contents) list.add(stack.save(CompoundTag()))
        tag.put("Items", list)
        entityData.set(DATA_CONTENTS, tag)
    }

    /** Client-callable: decode the synced contents into a list of ItemStacks
     *  for rendering. Returns empty if synced data isn't yet populated. */
    fun clientContents(): List<ItemStack> {
        val tag = entityData.get(DATA_CONTENTS)
        if (!tag.contains("Items", Tag.TAG_LIST.toInt())) return emptyList()
        val list = tag.getList("Items", Tag.TAG_COMPOUND.toInt())
        return List(list.size) { ItemStack.of(list.getCompound(it)) }
    }

    /** Collapse: spawn each stack as an ItemEntity at a random position inside
     *  the column, then *throw* it in a random horizontal direction with an
     *  upward bias — the visual reads as the tornado bursting and flinging its
     *  contents outward, not just dropping them. Audible burst on the way out. */
    private fun collapse() {
        for (stack in contents) {
            val spawnX = x + (random.nextDouble() - 0.5) * COLLAPSE_SPAWN_SPREAD
            val spawnY = y + random.nextDouble() * COLLAPSE_SPAWN_HEIGHT
            val spawnZ = z + (random.nextDouble() - 0.5) * COLLAPSE_SPAWN_SPREAD
            val item = ItemEntity(level(), spawnX, spawnY, spawnZ, stack.copy())

            val theta = random.nextDouble() * (2.0 * Math.PI)
            val horizSpeed = COLLAPSE_HORIZ_MIN +
                random.nextDouble() * (COLLAPSE_HORIZ_MAX - COLLAPSE_HORIZ_MIN)
            val verticalSpeed = COLLAPSE_VERT_MIN +
                random.nextDouble() * (COLLAPSE_VERT_MAX - COLLAPSE_VERT_MIN)
            item.setDeltaMovement(
                cos(theta) * horizSpeed,
                verticalSpeed,
                sin(theta) * horizSpeed,
            )
            level().addFreshEntity(item)
        }
        // Paper-burst — vanilla book-page-turn sound at low pitch reads as a
        // pile of pages erupting; cheap, no custom sound required.
        level().playSound(
            null, x, y, z,
            SoundEvents.BOOK_PAGE_TURN, SoundSource.NEUTRAL,
            1.0f, 0.6f + random.nextFloat() * 0.2f,
        )
        discard()
    }

    /** Light ambient cataloger-style dust scattered around the entity — gives
     *  the archive a settled "library haze" cloud around it on top of the
     *  orbiting tornado column. Spawned at low rate to read as decoration
     *  rather than a second spiral. Uses the cataloger's [SselithDustParticle]
     *  directly so the look matches Sselith's existing dust family. */
    private fun spawnAmbientDust() {
        for (i in 0 until AMBIENT_DUST_PER_TICK) {
            if (random.nextFloat() > AMBIENT_DUST_CHANCE) continue
            val dx = (random.nextDouble() - 0.5) * AMBIENT_DUST_SPREAD
            val dz = (random.nextDouble() - 0.5) * AMBIENT_DUST_SPREAD
            val dy = random.nextDouble() * DUST_HEIGHT
            level().addParticle(
                EKParticles.sselithDust(),
                x + dx, y + dy, z + dz,
                0.0, 0.0, 0.0,
            )
        }
    }

    /** Client-side dust spawn — ring pattern at the original jittered radius and
     *  bottom-biased Y. The actual spiral motion + entity tracking lives in
     *  [ArchiveSpiralDustParticle]: the velocity slot carries this entity's
     *  network ID (`xd = id.toDouble()`); each particle re-fetches the live
     *  entity per tick so the orbit axis follows the archive as it drifts. */
    private fun spawnDustSpiral() {
        for (i in 0 until DUST_PER_TICK) {
            val phase = (tickCount * DUST_SPIN_RATE + i * DUST_PHASE_OFFSET).toDouble()
            val r = DUST_RADIUS + (random.nextDouble() - 0.5) * DUST_RADIUS_JITTER
            val dx = cos(phase) * r
            val dz = sin(phase) * r
            val dy = random.nextDouble() * (DUST_HEIGHT * 0.25)
            level().addParticle(
                EKParticles.archiveSpiralDust(),
                x + dx, y + dy, z + dz,
                id.toDouble(), 0.0, 0.0,
            )
        }
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)
        contents.clear()
        if (tag.contains("Items", Tag.TAG_LIST.toInt())) {
            val list = tag.getList("Items", Tag.TAG_COMPOUND.toInt())
            for (i in 0 until list.size) {
                contents += ItemStack.of(list.getCompound(i))
            }
            contentsRolled = true
            syncContents()
        }
        if (tag.contains("TargetX")) {
            targetX = tag.getDouble("TargetX")
            targetY = tag.getDouble("TargetY")
            targetZ = tag.getDouble("TargetZ")
            hasTarget = true
        }
        if (tag.contains("SpawnGrace")) spawnGrace = tag.getInt("SpawnGrace")
    }

    override fun addAdditionalSaveData(tag: CompoundTag) {
        super.addAdditionalSaveData(tag)
        val list = ListTag()
        for (stack in contents) {
            list.add(stack.save(CompoundTag()))
        }
        tag.put("Items", list)
        if (hasTarget) {
            tag.putDouble("TargetX", targetX)
            tag.putDouble("TargetY", targetY)
            tag.putDouble("TargetZ", targetZ)
        }
        tag.putInt("SpawnGrace", spawnGrace)
    }

    companion object {
        const val ID_PATH = "archive"
        val ID: ResourceLocation = EnderkinesisMod.id(ID_PATH)

        /** Mob-required attributes. Health is irrelevant ([isInvulnerableTo]
         *  returns true). FLYING_SPEED is the one FlyingMoveControl actually
         *  reads — vanilla `createMobAttributes()` doesn't include it, so a
         *  flying mob that omits it crashes with `Can't find attribute
         *  minecraft:generic.flying_speed` on first tick (Phantom/Bee/Vex all
         *  add it explicitly for the same reason). FOLLOW_RANGE bumped to 64
         *  so the navigator can plan across a 49 b cell. */
        fun createAttributes(): AttributeSupplier.Builder = Mob
            .createMobAttributes()
            .add(Attributes.MAX_HEALTH, 1.0)
            .add(Attributes.MOVEMENT_SPEED, 0.4)
            .add(Attributes.FLYING_SPEED, 0.4)
            // 48 is just over a single-cell hop (49 b cells; the entity stands
            // at corridor centre, so target distance is < 49 b). Larger ranges
            // explode FlyingPathNavigation's node-evaluation cost.
            .add(Attributes.FOLLOW_RANGE, 48.0)

        /** Loot table that fills the archive's tornado on first tick. Lives at
         *  `data/enderkinesis/loot_tables/entities/archive.json`. */
        val LOOT_TABLE: ResourceLocation = EnderkinesisMod.id("entities/archive")

        /** Synced contents — server pushes a CompoundTag of stacks here after
         *  rolling the loot table; client renderer reads it to orbit the real
         *  items the player will see drop on collapse. */
        private val DATA_CONTENTS: EntityDataAccessor<CompoundTag> =
            SynchedEntityData.defineId(ArchiveEntity::class.java, EntityDataSerializers.COMPOUND_TAG)

        /** Navigation speed multiplier passed to `navigation.moveTo(..., speed)`.
         *  FlyingMoveControl scales FLYING_SPEED by this each tick — 1.0 ×
         *  FLYING_SPEED 0.6 puts the archive at Phantom-like drift speed. */
        private const val NAV_SPEED = 1.0

        /** Minimum gap between path submissions. One FlyingPathNavigation
         *  search per ~5 s is plenty for a slow tornado and keeps total path
         *  CPU cost negligible. */
        private const val PATH_COOLDOWN_TICKS = 100
        private const val DESPAWN_RANGE = 64.0

        /** Collapse bbox inflation. The tornado bounding box is 1×3×1 and the
         *  player bbox is 0.6×1.8×0.6 — a 0.75 inflate makes the trigger generous
         *  enough that brushing past the edge collapses the archive reliably,
         *  rather than only direct centre-overlap. */
        private const val COLLAPSE_INFLATE = 0.75

        // Collapse scatter — each item spawns at a random point inside the
        // column and is thrown outward with a random horizontal direction +
        // upward bias. Numbers tuned so an archive collapsing in a corridor
        // sprays items 2-3 blocks before they settle.
        private const val COLLAPSE_SPAWN_SPREAD = 0.6
        private const val COLLAPSE_SPAWN_HEIGHT = 2.0
        private const val COLLAPSE_HORIZ_MIN = 0.30
        private const val COLLAPSE_HORIZ_MAX = 0.55
        private const val COLLAPSE_VERT_MIN = 0.25
        private const val COLLAPSE_VERT_MAX = 0.55

        /** Spawn-egg-placed archives sit inside the placing player's bbox for the
         *  first tick — without a grace window they'd collapse on tick 1. 30 ticks
         *  ≈ 1.5 s; enough for the player to step away and see the tornado settle. */
        private const val SPAWN_GRACE_TICKS = 30

        /** Sselith maze cell extents + grid offset — duplicated from
         *  `SselithRepertoryChunkGenerator` so this file doesn't reach into that
         *  one's privates. Keep in sync if the chunkgen constants ever change. */
        private const val SSELITH_CELL_X = 49
        private const val SSELITH_CELL_Z = 49
        private const val SSELITH_GRID_OFFSET = 22

        // Dust spawn-ring tuning. 1/tick × ~75-tick lifetime ≈ 75 live spiral
        // particles per archive — light enough that GPU translucent overdraw
        // stays cheap, dense enough that the spiral curve still reads.
        private const val DUST_PER_TICK = 1
        private const val DUST_SPIN_RATE = 0.35              // rad/tick — rotates spawn phase so successive ticks spawn around the ring
        private const val DUST_PHASE_OFFSET = Math.PI / 2.0  // unused when DUST_PER_TICK = 1; kept in case it's bumped back up
        private const val DUST_RADIUS = 0.55
        private const val DUST_RADIUS_JITTER = 0.25
        private const val DUST_HEIGHT = 2.6

        // Ambient cataloger-style dust scattered around the entity — kept very
        // sparse. With SselithDustParticle's hasPhysics=true (block collision),
        // each live ambient mote pays per-tick terrain-collision cost, so this
        // count is the heaviest dust-particle ms.
        private const val AMBIENT_DUST_PER_TICK = 1
        private const val AMBIENT_DUST_CHANCE = 0.1f
        private const val AMBIENT_DUST_SPREAD = 1.8          // bbox-spreading diameter for ambient placement
    }
}
