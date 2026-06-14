package org.shipwrights.enderkinesis.item

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.BlockTags
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.monster.Skeleton
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BoneMealItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.item.AxeItem
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.BushBlock
import net.minecraft.world.level.block.CandleBlock
import net.minecraft.world.level.block.CandleCakeBlock
import net.minecraft.world.level.block.HugeMushroomBlock
import net.minecraft.world.level.block.VineBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.material.Fluids
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f

/**
 * Mystic Gem — 7-block-diameter burst on right-click, 10-second per-stack cooldown, not
 * consumed. Subclasses override [applyBurst] to do their thing; the base handles cooldown
 * gating, side-checks, and the AABB/blockpos iteration helpers each gem uses.
 *
 * Particles are emitted from the server via [ServerLevel.sendParticles] (auto-broadcast to
 * watching clients) using vanilla particle types tinted to fit each gem — no custom
 * particle providers needed.
 */
abstract class MysticGemItem(properties: Properties) : Item(properties) {

    override fun use(
        level: Level, player: Player, hand: InteractionHand,
    ): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        if (player.cooldowns.isOnCooldown(this)) return InteractionResultHolder.fail(stack)
        if (level is ServerLevel) {
            applyBurst(level, player, player.position())
        }
        player.cooldowns.addCooldown(this, COOLDOWN_TICKS)
        player.awardStat(net.minecraft.stats.Stats.ITEM_USED.get(this))
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide)
    }

    protected abstract fun applyBurst(level: ServerLevel, player: Player, center: Vec3)

    /** Iterate every [LivingEntity] whose origin lies inside the gem's burst sphere. The
     *  user is skipped unless [includeUser] is true — most gems don't want to hurt the
     *  person triggering them. */
    protected fun forEachLiving(
        level: ServerLevel, center: Vec3, user: Player, includeUser: Boolean = false,
        action: (LivingEntity) -> Unit,
    ) {
        val aabb = AABB.ofSize(center, DIAMETER, DIAMETER, DIAMETER)
        val r2 = RADIUS * RADIUS
        for (entity in level.getEntitiesOfClass(LivingEntity::class.java, aabb)) {
            if (!entity.isAlive) continue
            if (!includeUser && entity === user) continue
            if (entity.position().distanceToSqr(center) > r2) continue
            action(entity)
        }
    }

    /** Sphere-clipped block iteration. The radius-squared test culls the ~50% of AABB
     *  corner positions that aren't actually inside the sphere — keeps gems from lighting
     *  candles at the very edge of a 7×7×7 cube that the visual sphere wouldn't reach. */
    protected inline fun forEachBlock(center: Vec3, action: (BlockPos) -> Unit) {
        val r = Math.ceil(RADIUS).toInt()
        val cx = Mth.floor(center.x); val cy = Mth.floor(center.y); val cz = Mth.floor(center.z)
        val r2 = RADIUS * RADIUS
        for (dx in -r..r) for (dy in -r..r) for (dz in -r..r) {
            if (dx * dx + dy * dy + dz * dz > r2) continue
            action(BlockPos(cx + dx, cy + dy, cz + dz))
        }
    }

    /** Sphere-distributed particle burst. Single `sendParticles` call with the radius as
     *  the offset; MC samples [count] positions inside that AABB-volume for us, which is
     *  good enough at this radius and avoids hammering the network with one packet per
     *  particle. */
    protected fun emitBurst(level: ServerLevel, particle: ParticleOptions, center: Vec3, count: Int) {
        level.sendParticles(particle, center.x, center.y + 1.0, center.z, count, RADIUS, RADIUS, RADIUS, 0.05)
    }

    /** [count] particles emitted from `center` with outward-radial motion. Each particle
     *  is its own one-shot packet (vanilla's batched [emitBurst] can't direct motion
     *  per-particle), so keep [count] modest. */
    protected fun emitOutwardBlast(
        level: ServerLevel, particle: ParticleOptions, center: Vec3, count: Int, speed: Double,
    ) {
        for (i in 0 until count) {
            val theta = level.random.nextDouble() * 2.0 * Math.PI
            val phi = Math.acos(2.0 * level.random.nextDouble() - 1.0)
            val dx = Math.sin(phi) * Math.cos(theta)
            val dy = Math.cos(phi)
            val dz = Math.sin(phi) * Math.sin(theta)
            level.sendParticles(particle, center.x, center.y + 1.0, center.z, 0, dx * speed, dy * speed, dz * speed, 1.0)
        }
    }

    /** [count] particles spawned ON the burst perimeter with motion vectors pointing
     *  inward. Visual feel: "pulled toward the player." Same per-particle cost as
     *  [emitOutwardBlast]. */
    protected fun emitInwardCollapse(
        level: ServerLevel, particle: ParticleOptions, center: Vec3, count: Int, speed: Double,
    ) {
        for (i in 0 until count) {
            val theta = level.random.nextDouble() * 2.0 * Math.PI
            val phi = Math.acos(2.0 * level.random.nextDouble() - 1.0)
            val dx = Math.sin(phi) * Math.cos(theta)
            val dy = Math.cos(phi)
            val dz = Math.sin(phi) * Math.sin(theta)
            val px = center.x + dx * RADIUS
            val py = center.y + 1.0 + dy * RADIUS
            val pz = center.z + dz * RADIUS
            level.sendParticles(particle, px, py, pz, 0, -dx * speed, -dy * speed, -dz * speed, 1.0)
        }
    }

    /** Tinted colour cloud using `ENTITY_EFFECT` (vanilla's status-effect swirl). When
     *  count=0 is sent for this particle, the client's `MobEffectParticle` reads the
     *  xSpeed/ySpeed/zSpeed packet fields as the RGB colour (0..1 each) — same trick
     *  vanilla uses for lingering potion clouds. */
    protected fun emitColoredEffectCloud(
        level: ServerLevel, center: Vec3, count: Int, r: Double, g: Double, b: Double,
    ) {
        for (i in 0 until count) {
            val rx = center.x + (level.random.nextDouble() - 0.5) * 2.0 * RADIUS
            val ry = center.y + 1.0 + (level.random.nextDouble() - 0.5) * 2.0 * RADIUS
            val rz = center.z + (level.random.nextDouble() - 0.5) * 2.0 * RADIUS
            level.sendParticles(ParticleTypes.ENTITY_EFFECT, rx, ry, rz, 0, r, g, b, 1.0)
        }
    }

    /** Particles laid out in a horizontal band around `center`, each with tangential
     *  velocity perpendicular to its position vector. Each particle moves in a straight
     *  line (vanilla doesn't curve free particles), but the initial tangential motion
     *  reads as a swirl over the particle's lifetime. The vertical band is intentionally
     *  thin (~3 m) so the swirl isn't diluted across the full burst sphere. */
    protected fun emitSwirl(
        level: ServerLevel, particle: ParticleOptions, center: Vec3, count: Int, tangentSpeed: Double,
    ) {
        for (i in 0 until count) {
            val angle = level.random.nextDouble() * 2.0 * Math.PI
            // r ∈ [1, RADIUS] — avoid r=0 where tangent direction collapses to a point.
            val r = 1.0 + level.random.nextDouble() * (RADIUS - 1.0)
            val px = center.x + r * Math.cos(angle)
            val py = center.y + 0.5 + level.random.nextDouble() * 3.0
            val pz = center.z + r * Math.sin(angle)
            val vx = -Math.sin(angle) * tangentSpeed
            val vy = 0.02
            val vz = Math.cos(angle) * tangentSpeed
            level.sendParticles(particle, px, py, pz, 0, vx, vy, vz, 1.0)
        }
    }

    /** A few upward-drifting particles tagged to a specific affected block — used by
     *  Moonstone to mark every block it transforms or destroys. One-shot per particle
     *  with explicit upward motion so the rise is deterministic rather than depending on
     *  vanilla's distribution behaviour. Sparse on purpose; this is per-block. */
    protected fun emitBlockMark(
        level: ServerLevel, particle: ParticleOptions, pos: BlockPos, count: Int = 2,
    ) {
        for (i in 0 until count) {
            val ox = (level.random.nextDouble() - 0.5) * 0.6
            val oz = (level.random.nextDouble() - 0.5) * 0.6
            val vy = 0.08 + level.random.nextDouble() * 0.10
            level.sendParticles(
                particle,
                pos.x + 0.5 + ox, pos.y + 1.0, pos.z + 0.5 + oz,
                0, 0.0, vy, 0.0, 1.0,
            )
        }
    }

    protected companion object {
        /** Burst radius (m). Half the visible diameter. */
        const val RADIUS: Double = 5.5
        const val DIAMETER: Double = 11.0
        /** 10 s @ 20 TPS. Long enough to feel deliberate, short enough that a player who
         *  wants to chain effects can. */
        const val COOLDOWN_TICKS: Int = 200
    }
}

// ---------------------------------------------------------------------------
// Citrine — circle of flame damage, lights candles
// ---------------------------------------------------------------------------
class MysticCitrineItem(properties: Properties) : MysticGemItem(properties) {
    override fun applyBurst(level: ServerLevel, player: Player, center: Vec3) {
        forEachLiving(level, center, player) {
            it.setSecondsOnFire(4)
            it.hurt(level.damageSources().inFire(), 4f)
        }
        forEachBlock(center) { pos ->
            val state = level.getBlockState(pos)
            when {
                state.hasProperty(CandleBlock.LIT) && !state.getValue(CandleBlock.LIT) ->
                    level.setBlock(pos, state.setValue(CandleBlock.LIT, true), 3)
                state.hasProperty(CandleCakeBlock.LIT) && !state.getValue(CandleCakeBlock.LIT) ->
                    level.setBlock(pos, state.setValue(CandleCakeBlock.LIT, true), 3)
                // Sparse surface fire — 1-in-12 air block whose neighbour below has a sturdy
                // top face gets a fire block. Sturdy-face check is what BaseFireBlock uses
                // for "can rest here," so anything we place will survive at least one tick.
                state.isAir && level.random.nextInt(SURFACE_FIRE_DENOMINATOR) == 0 -> {
                    val below = pos.below()
                    if (level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
                        level.setBlock(pos, Blocks.FIRE.defaultBlockState(), 3)
                    }
                }
            }
        }
        // Radial blast from the player outward. Single sphere-shell of FLAME with high
        // outward speed feels like an actual detonation; SMALL_FLAME provides the slower
        // trailing embers from the sphere-volume burst.
        emitOutwardBlast(level, ParticleTypes.FLAME, center, 100, 0.6)
        emitBurst(level, ParticleTypes.SMALL_FLAME, center, 60)
        level.playSound(null, BlockPos.containing(center), SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 1f, 1f)
    }

    private companion object {
        /** 1-in-N chance per air-above-sturdy block. 12 ≈ a couple of fires per burst on
         *  flat ground, sparse enough not to feel like a flamethrower. */
        const val SURFACE_FIRE_DENOMINATOR = 12
    }
}

// ---------------------------------------------------------------------------
// Onyx — pull nearby entities toward the player
// ---------------------------------------------------------------------------
class MysticOnyxItem(properties: Properties) : MysticGemItem(properties) {
    override fun applyBurst(level: ServerLevel, player: Player, center: Vec3) {
        // Pull EVERY entity in the radius, not just LivingEntities — dropped items, XP
        // orbs, projectiles, boats, minecarts all get yanked in too. Skip the user (passed
        // as exclude) so the gem doesn't yank its own holder around.
        val aabb = AABB.ofSize(center, DIAMETER, DIAMETER, DIAMETER)
        val r2 = RADIUS * RADIUS
        for (entity in level.getEntities(player, aabb) { it.isAlive }) {
            if (entity.position().distanceToSqr(center) > r2) continue
            val toCentre = center.subtract(entity.position())
            val dist = toCentre.length().coerceAtLeast(0.1)
            val factor = 0.6 * (dist / RADIUS)
            entity.deltaMovement = entity.deltaMovement.add(toCentre.normalize().scale(factor))
            entity.hurtMarked = true
        }
        // Particles converge inward from the perimeter — matches the entity-pull motion
        // (everything getting yanked toward the holder). REVERSE_PORTAL reads as the
        // collapse focal point at the centre. Speed 2.0 is fast enough to read as a
        // suction tow, not a drift.
        emitInwardCollapse(level, ParticleTypes.PORTAL, center, 160, 2.0)
        emitBurst(level, ParticleTypes.REVERSE_PORTAL, center, 30)
        level.playSound(null, BlockPos.containing(center), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1f, 0.6f)
    }
}

// ---------------------------------------------------------------------------
// Jade — bone meal plants, poison living mobs
// ---------------------------------------------------------------------------
class MysticJadeItem(properties: Properties) : MysticGemItem(properties) {
    override fun applyBurst(level: ServerLevel, player: Player, center: Vec3) {
        forEachLiving(level, center, player) {
            it.addEffect(MobEffectInstance(MobEffects.POISON, 200, 1))
        }
        val bonemeal = ItemStack(Items.BONE_MEAL)
        forEachBlock(center) { pos ->
            // Scatter the bonemeal — 1-in-N chance per block. Without this gate growCrop
            // hits every bonemeal-able block in the burst, which on a grass field reads
            // as "instant overgrowth" rather than a sprinkling. Failed-roll blocks just
            // don't get touched.
            if (level.random.nextInt(BONEMEAL_DENOMINATOR) != 0) return@forEachBlock
            BoneMealItem.growCrop(bonemeal, level, pos)
        }
        emitBurst(level, ParticleTypes.HAPPY_VILLAGER, center, 80)
        emitBurst(level, ParticleTypes.COMPOSTER, center, 50)
        // Poison status-swirl, tinted to vanilla poison green (0x4E9331). Same particle
        // MC renders around an entity with the POISON effect — `sendParticles` with
        // count=0 on ENTITY_EFFECT lets us pass an explicit RGB through the offset.
        emitColoredEffectCloud(level, center, 120, POISON_R, POISON_G, POISON_B)
        level.playSound(null, BlockPos.containing(center), SoundEvents.SLIME_SQUISH, SoundSource.PLAYERS, 1f, 1.5f)
    }

    private companion object {
        // Vanilla MobEffects.POISON colour 0x4E9331 → R=78, G=147, B=49 as 0..1 floats.
        const val POISON_R: Double = 78.0 / 255.0
        const val POISON_G: Double = 147.0 / 255.0
        const val POISON_B: Double = 49.0 / 255.0
        /** 1-in-N chance per block. 3 → ~33 % coverage, sprinkled across the radius. */
        const val BONEMEAL_DENOMINATOR: Int = 3
    }
}

// ---------------------------------------------------------------------------
// Aquamarine — freeze water, cold damage to living mobs, skeletons → strays
// ---------------------------------------------------------------------------
class MysticAquamarineItem(properties: Properties) : MysticGemItem(properties) {
    override fun applyBurst(level: ServerLevel, player: Player, center: Vec3) {
        forEachLiving(level, center, player) {
            // Skeleton → Stray conversion has to happen before the damage hit; convertTo
            // discards the old entity and returns a fresh stray with the same equipment.
            // Apply the freeze damage to the stray instead so the result isn't a
            // skeleton-corpse plus a fresh full-HP stray standing next to it.
            val target = if (it is Skeleton && it.javaClass == Skeleton::class.java) {
                it.convertTo(EntityType.STRAY, true) ?: it
            } else it
            target.ticksFrozen += 200
            target.hurt(level.damageSources().freeze(), 3f)
        }
        forEachBlock(center) { pos ->
            val state = level.getBlockState(pos)
            if (state.fluidState.`is`(Fluids.WATER) && state.fluidState.isSource) {
                level.setBlock(pos, Blocks.ICE.defaultBlockState(), 3)
            }
        }
        // Two-layer swirl: SNOWFLAKE carries the visible motion (long-lived white flakes
        // are easy to track around the centre), TINTED_SNOW dust carries the cyan-blue
        // tint over the same band. Tangent speed 1.0 ⇒ ~20 m of arc per particle
        // lifetime — fast enough that the swirl reads even on a single tick capture.
        emitSwirl(level, ParticleTypes.SNOWFLAKE, center, 100, 1.0)
        emitSwirl(level, TINTED_SNOW, center, 140, 1.0)
        level.playSound(null, BlockPos.containing(center), SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1f, 0.7f)
        level.playSound(null, BlockPos.containing(center), SoundEvents.POWDER_SNOW_STEP, SoundSource.PLAYERS, 1f, 0.5f)
    }

    private companion object {
        val TINTED_SNOW: DustParticleOptions = DustParticleOptions(Vector3f(0.78f, 0.88f, 1.0f), 1.5f)
    }
}

// ---------------------------------------------------------------------------
// Moonstone — cleanse organic blocks, damage living mobs
// ---------------------------------------------------------------------------
class MysticMoonstoneItem(properties: Properties) : MysticGemItem(properties) {
    override fun applyBurst(level: ServerLevel, player: Player, center: Vec3) {
        forEachLiving(level, center, player) {
            // Wither II for 10 s — slow drain that distinguishes Moonstone from
            // Bloodstone's instant-kill at the same radius.
            it.addEffect(MobEffectInstance(MobEffects.WITHER, 200, 1))
        }
        forEachBlock(center) { pos ->
            val state = level.getBlockState(pos)
            val transformed = moonstoneTransform(state)
            when {
                transformed != null -> {
                    level.setBlock(pos, transformed, 3)
                    emitBlockMark(level, ParticleTypes.END_ROD, pos)
                }
                moonstoneShouldDestroy(state) -> {
                    // destroyBlock with dropBlock=false gives break particles + sound.
                    level.destroyBlock(pos, false)
                    emitBlockMark(level, ParticleTypes.END_ROD, pos)
                }
            }
        }
        emitOutwardBlast(level, ParticleTypes.END_ROD, center, 80, 0.5)
        emitBurst(level, ParticleTypes.GLOW, center, 60)
        level.playSound(null, BlockPos.containing(center), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1f, 1.2f)
    }

    /** Surface- and biome-block conversions. Returns the new state to swap in, or null
     *  when no transform applies (the destroy path handles those). Preserves AXIS for
     *  stripped logs, and WATERLOGGED + HORIZONTAL_FACING for dead coral so a wall fan
     *  stays a wall fan on the same face. */
    private fun moonstoneTransform(state: BlockState): BlockState? {
        val block = state.block
        // Surface soils → plain dirt. Mycelium thrown in alongside the user's list of
        // grass/podzol/path — same "living surface decomposing" category.
        if (block == Blocks.GRASS_BLOCK || block == Blocks.PODZOL ||
            block == Blocks.MYCELIUM || block == Blocks.DIRT_PATH
        ) {
            return Blocks.DIRT.defaultBlockState()
        }
        // Nylium → netherrack. Nether equivalent of grass→dirt.
        if (block == Blocks.CRIMSON_NYLIUM || block == Blocks.WARPED_NYLIUM) {
            return Blocks.NETHERRACK.defaultBlockState()
        }
        // Logs / wood / stems / hyphae → stripped variants. AxeItem.STRIPPABLES is the
        // canonical mapping vanilla uses for axe-on-log; reusing it picks up every
        // future mod-added log automatically.
        AxeItem.STRIPPABLES[block]?.let { stripped ->
            var newState = stripped.defaultBlockState()
            if (state.hasProperty(BlockStateProperties.AXIS) && newState.hasProperty(BlockStateProperties.AXIS)) {
                newState = newState.setValue(BlockStateProperties.AXIS, state.getValue(BlockStateProperties.AXIS))
            }
            return newState
        }
        // Live coral → dead coral (all 5 species × 4 forms: block, plant, fan, wall fan).
        CORAL_LIVE_TO_DEAD[block]?.let { dead ->
            var newState = dead.defaultBlockState()
            if (state.hasProperty(BlockStateProperties.WATERLOGGED) && newState.hasProperty(BlockStateProperties.WATERLOGGED)) {
                newState = newState.setValue(BlockStateProperties.WATERLOGGED, state.getValue(BlockStateProperties.WATERLOGGED))
            }
            if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING) && newState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                newState = newState.setValue(BlockStateProperties.HORIZONTAL_FACING, state.getValue(BlockStateProperties.HORIZONTAL_FACING))
            }
            return newState
        }
        return null
    }

    /** Outright-destroy targets — anything that should just go away rather than
     *  decompose to a less-living variant. */
    private fun moonstoneShouldDestroy(state: BlockState): Boolean {
        val block = state.block
        if (state.`is`(BlockTags.LEAVES) ||
            block is BushBlock ||                           // grass, flowers, saplings, crops, ferns, small mushrooms, sweet berries…
            block is VineBlock ||
            block is HugeMushroomBlock ||                   // red/brown mushroom blocks, mushroom stem
            state.`is`(BlockTags.WART_BLOCKS) ||
            state.`is`(BlockTags.UNDERWATER_BONEMEALS)      // kelp, sea pickle
        ) return true
        return block == Blocks.MOSS_BLOCK ||
            block == Blocks.MOSS_CARPET ||
            block == Blocks.MELON ||
            block == Blocks.PUMPKIN ||
            block == Blocks.CARVED_PUMPKIN ||
            block == Blocks.JACK_O_LANTERN
    }

    private companion object {
        val CORAL_LIVE_TO_DEAD: Map<Block, Block> = mapOf(
            Blocks.TUBE_CORAL_BLOCK to Blocks.DEAD_TUBE_CORAL_BLOCK,
            Blocks.BRAIN_CORAL_BLOCK to Blocks.DEAD_BRAIN_CORAL_BLOCK,
            Blocks.BUBBLE_CORAL_BLOCK to Blocks.DEAD_BUBBLE_CORAL_BLOCK,
            Blocks.FIRE_CORAL_BLOCK to Blocks.DEAD_FIRE_CORAL_BLOCK,
            Blocks.HORN_CORAL_BLOCK to Blocks.DEAD_HORN_CORAL_BLOCK,
            Blocks.TUBE_CORAL to Blocks.DEAD_TUBE_CORAL,
            Blocks.BRAIN_CORAL to Blocks.DEAD_BRAIN_CORAL,
            Blocks.BUBBLE_CORAL to Blocks.DEAD_BUBBLE_CORAL,
            Blocks.FIRE_CORAL to Blocks.DEAD_FIRE_CORAL,
            Blocks.HORN_CORAL to Blocks.DEAD_HORN_CORAL,
            Blocks.TUBE_CORAL_FAN to Blocks.DEAD_TUBE_CORAL_FAN,
            Blocks.BRAIN_CORAL_FAN to Blocks.DEAD_BRAIN_CORAL_FAN,
            Blocks.BUBBLE_CORAL_FAN to Blocks.DEAD_BUBBLE_CORAL_FAN,
            Blocks.FIRE_CORAL_FAN to Blocks.DEAD_FIRE_CORAL_FAN,
            Blocks.HORN_CORAL_FAN to Blocks.DEAD_HORN_CORAL_FAN,
            Blocks.TUBE_CORAL_WALL_FAN to Blocks.DEAD_TUBE_CORAL_WALL_FAN,
            Blocks.BRAIN_CORAL_WALL_FAN to Blocks.DEAD_BRAIN_CORAL_WALL_FAN,
            Blocks.BUBBLE_CORAL_WALL_FAN to Blocks.DEAD_BUBBLE_CORAL_WALL_FAN,
            Blocks.FIRE_CORAL_WALL_FAN to Blocks.DEAD_FIRE_CORAL_WALL_FAN,
            Blocks.HORN_CORAL_WALL_FAN to Blocks.DEAD_HORN_CORAL_WALL_FAN,
        )
    }
}

// ---------------------------------------------------------------------------
// Spinel — chaos swap; cycle living-mob positions
// ---------------------------------------------------------------------------
class MysticSpinelItem(properties: Properties) : MysticGemItem(properties) {
    override fun applyBurst(level: ServerLevel, player: Player, center: Vec3) {
        val entities = mutableListOf<LivingEntity>()
        // Include the user — chaos swap explicitly puts the wielder into the cycle, so
        // triggering this with no other mobs nearby is a no-op (they'd just swap with
        // themselves) but triggering with one mob nearby teleports both.
        forEachLiving(level, center, player, includeUser = true) { entities += it }
        if (entities.size < 2) {
            emitBurst(level, ParticleTypes.REVERSE_PORTAL, center, 50)
            level.playSound(null, BlockPos.containing(center), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1f, 1.4f)
            return
        }
        // One-cycle permutation: entity i goes to the slot of entity i+1, last wraps to
        // first. Captures positions up-front so we don't observe an entity at a slot
        // another entity has already moved into. Simpler than a Fisher-Yates shuffle and
        // guarantees no fixed points for n ≥ 2.
        val positions = entities.map { it.position() }
        for (i in entities.indices) {
            val target = positions[(i + 1) % positions.size]
            entities[i].teleportTo(target.x, target.y, target.z)
            emitBurst(level, ParticleTypes.PORTAL, target, 30)
        }
        emitBurst(level, ParticleTypes.REVERSE_PORTAL, center, 60)
        level.playSound(null, BlockPos.containing(center), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1f, 0.7f)
    }
}

// ---------------------------------------------------------------------------
// Bloodstone — sacrifice passive mobs for health boost + regen scaling with kill count
// ---------------------------------------------------------------------------
class MysticBloodstoneItem(properties: Properties) : MysticGemItem(properties) {
    override fun applyBurst(level: ServerLevel, player: Player, center: Vec3) {
        var sacrificed = 0
        forEachLiving(level, center, player) {
            // Anything living except the wielder and undead. Undead are skipped because
            // they have no life-force to drain — taking blood from a skeleton is a
            // category error. Villagers, animals, hostile non-undead, and other players
            // all count.
            if (it.mobType == net.minecraft.world.entity.MobType.UNDEAD) return@forEachLiving
            // Fixed [DRAIN_DAMAGE] per drained entity — enough to bleed a target without
            // one-shotting things that aren't already low-HP. Each successful drain (where
            // damage actually landed) counts as one unit toward the wielder's buff scaling.
            // `hurt` returns false on invulnerable / i-framed / already-dead / magic-immune
            // targets; we only count the unit when it returns true so failed hits can't
            // farm buffs.
            if (it.hurt(level.damageSources().magic(), DRAIN_DAMAGE)) sacrificed++
        }
        if (sacrificed == 0) {
            emitBurst(level, ParticleTypes.SMOKE, center, 40)
            level.playSound(null, BlockPos.containing(center), SoundEvents.WITHER_HURT, SoundSource.PLAYERS, 1f, 1.4f)
            return
        }
        // Duration carries the scale — 15 s per sacrifice, additive — while the amp
        // climbs slowly and caps out at HEALTH_BOOST III (amp 2). Killing one chicken →
        // 15 s @ III? no, amp 0; a whole flock of 16 → 4 min @ amp 1; past that, longer
        // duration, no further amp gain.
        val durTicks = DURATION_TICKS_PER_SACRIFICE * sacrificed
        val amp = minOf(MAX_AMP, (sacrificed - 1) / AMP_STEP_SACRIFICES)
        player.addEffect(MobEffectInstance(MobEffects.HEALTH_BOOST, durTicks, amp))
        player.addEffect(MobEffectInstance(MobEffects.REGENERATION, durTicks, amp))
        emitBurst(level, ParticleTypes.DAMAGE_INDICATOR, center, 80)
        // Blood-red dust replaces HEART — same role (signals the life transfer) without
        // the cute heart silhouette.
        emitBurst(level, BLOOD_DUST, center, 100)
        level.playSound(null, BlockPos.containing(center), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 1f, 0.6f)
    }

    private companion object {
        /** Per-sacrifice duration. 15 s × kill count — a single chicken gives a quarter-
         *  minute window, a full flock of 16 gets you four minutes of regen. */
        const val DURATION_TICKS_PER_SACRIFICE = 300
        /** Highest HEALTH_BOOST / REGENERATION amp ever applied. 2 ⇒ "III" in MC's
         *  1-indexed display, matching the user-facing cap. */
        const val MAX_AMP = 2
        /** Kills needed per amp tier. 8 ⇒ first tier at 1 kill (amp 0), second at 9
         *  (amp 1), capped at 17 (amp 2). Slow scaling so a player isn't incentivised to
         *  routinely massacre whole pastures for a fleeting tier bump. */
        const val AMP_STEP_SACRIFICES = 8

        /** Damage per drained entity. Each successful drain at this damage counts as one
         *  unit toward [DURATION_TICKS_PER_SACRIFICE] / [AMP_STEP_SACRIFICES]. */
        const val DRAIN_DAMAGE: Float = 10f

        val BLOOD_DUST: DustParticleOptions = DustParticleOptions(Vector3f(0.85f, 0.10f, 0.10f), 1.4f)
    }
}
