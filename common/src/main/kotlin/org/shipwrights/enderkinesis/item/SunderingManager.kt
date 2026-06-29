package org.shipwrights.enderkinesis.item

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.MobType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.BaseFireBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.UUID

/** Server-side per-tick logic for the Staff of Sundering.
 *
 *  Four ramp stages over [STAGE_TICKS] × 4 = [MAX_RAMP_TICKS] ticks. All
 *  physical effects (damage, fire, block-break enable, block-break speed)
 *  ramp **continuously** off [rampFactor] rather than stepping at stage
 *  boundaries — the integer stage value is here only as a convenience for
 *  the client renderer's transition timing.
 *
 *  Concretely:
 *   - damage smoothly grows 0 → [DAMAGE_MIN_PER_TICK] over the first 25 % of
 *     the ramp (so the beam isn't a one-shot when fully ramped from zero),
 *     then continues 25 → 100 % up to [DAMAGE_MAX_PER_TICK];
 *   - fire-on-hit seconds ramp 0 → [FIRE_SECONDS_MAX] over the same window;
 *   - block breaking enables at [MINING_ENABLE_RAMP] (0.5 = the visual
 *     "stage 2 → stage 3" transition midpoint), speed multiplier scales 1× →
 *     [EFFICIENCY_5_MULT] across the remainder. */
object SunderingManager {

    const val RANGE: Double = 128.0

    /** Ticks per visual ramp stage. 40 = 2 s. Total ramp = 4 stages × 2 s = 8 s. */
    const val STAGE_TICKS: Int = 40

    /** Number of visual ramp stages. The client uses this for the sub-stage
     *  fractions `t01 / t12 / t23 / t34` that drive transition fades. */
    const val STAGE_COUNT: Int = 4

    /** Total ramp length in ticks — past this, all physical effects hold at
     *  their stage-4 cap. */
    const val MAX_RAMP_TICKS: Int = STAGE_TICKS * STAGE_COUNT

    /** Per-tick damage at the start of the ramp's second segment (rampF = 0.25).
     *  Sharpness-I iron sword = 7 hp/swing, spread across 20 ticks. */
    const val DAMAGE_MIN_PER_TICK: Float = 7f / 20f

    /** Per-tick damage at full ramp (rampF = 1.0). Sharpness-V diamond sword
     *  = 10 hp/swing, spread across 20 ticks. */
    const val DAMAGE_MAX_PER_TICK: Float = 10f / 20f

    /** Cap on `setSecondsOnFire` applied each damage tick. Scales linearly
     *  with [rampFactor]: 0 sec at start, [FIRE_SECONDS_MAX] at full ramp. */
    private const val FIRE_SECONDS_MAX: Int = 4

    /** Beam-line inflate radius for the entity-hit broad phase. */
    private const val ENTITY_DAMAGE_RADIUS: Double = 0.5

    /** Vanilla `LivingEntity.invulnerableTime` cooldown after a successful
     *  hit — 20 ticks total, with the "may take another fresh hit" cutoff
     *  at 10 ticks. Calling `hurt` every game tick falls into the
     *  `damageAmount <= this.lastHurt` rejection path for 9 out of every
     *  10 ticks; only the first hit and every 10th hit thereafter
     *  actually deals damage. Grouping our per-tick rate into a
     *  10-tick **chunk** and landing it on ticks 1, 11, 21, ... lines up
     *  with the cooldown so every hurt call falls into the fresh-hit
     *  branch and the intended per-second value lands intact. */
    private const val DAMAGE_PERIOD_TICKS: Int = 10

    /** Quick Charge — ramp speedup multiplier per level. */
    private const val QUICK_CHARGE_PER_LEVEL: Float = 0.25f

    /** Channeling — base interval (ticks) between lightning bolts at full
     *  ramp; actual interval is `BASE / level`. 400 ticks = 20 s. */
    private const val CHANNELING_BASE_INTERVAL: Int = 400

    /** Per-level bonus damage to `MobType.WATER` mobs per chunk. */
    private const val IMPALING_DAMAGE_PER_LEVEL: Float = 2.5f

    /** Per-level bonus damage to `MobType.ARTHROPOD` mobs per chunk. */
    private const val BANE_DAMAGE_PER_LEVEL: Float = 2.5f

    /** Per-level bonus damage to `MobType.UNDEAD` mobs per chunk. */
    private const val SMITE_DAMAGE_PER_LEVEL: Float = 2.5f

    /** Power — flat per-tick damage bonus per level (so per-chunk =
     *  `level × 0.025 × DAMAGE_PERIOD_TICKS`, ≈ `level × 0.5 hp/s`). */
    private const val POWER_PER_TICK_PER_LEVEL: Float = 0.025f

    /** Combined Punch + Knockback strength contribution per level. */
    private const val KNOCKBACK_PER_LEVEL: Double = 0.5

    /** Fire Aspect seconds added on top of the ramp's fire-seconds value. */
    private const val FIRE_ASPECT_SEC_PER_LEVEL: Int = 4

    /** Sweeping Edge — extra radius beyond the base. */
    private const val SWEEPING_BASE_RADIUS: Double = 1.0
    private const val SWEEPING_RADIUS_PER_LEVEL: Double = 0.5

    /** Sweeping Edge damage as a fraction of the main chunk per level. */
    private const val SWEEPING_DAMAGE_FRACTION_PER_LEVEL: Float = 0.3f

    /** Flame — per-tick chance **per candidate cell** to ignite. Multiple
     *  candidates are sampled per tick (one per level), and each rolls
     *  independently against this — so total fire rate grows roughly
     *  quadratically with level. */
    private const val FLAME_TICK_CHANCE_PER_LEVEL: Double = 0.10

    /** Efficiency — per-level mining speed multiplier addition. */
    private const val EFFICIENCY_BOOST_PER_LEVEL: Float = 0.1f

    /** Infinity — per-level range multiplier addition (range × `(1 + level)`)
     *  and per-level damage divisor (damage ÷ `(1 + level)`). */
    private const val INFINITY_PER_LEVEL: Int = 1

    /** Baseline ticks-per-hardness for the slow drill — same as the
     *  Disintegration tome's no-tool mining baseline so stage-2 / 3 transition
     *  mining feels like it. */
    private const val MINING_TICKS_PER_HARDNESS: Float = 30f

    /** Efficiency-V vanilla multiplier (`level² + 1 = 26 + 1`). Stage-4 cap
     *  for the linear mining-speed ramp. */
    private const val EFFICIENCY_5_MULT: Float = 27f

    /** Ramp fraction at which block-breaking turns on. 0.5 lines up with the
     *  client's beacon-beam fade-in midpoint, so blocks visibly start
     *  evaporating just as the beacon takes over from the particle beam. */
    private const val MINING_ENABLE_RAMP: Float = 0.5f

    private val VIRTUAL_TOOL: ItemStack = ItemStack(Items.NETHERITE_PICKAXE)

    private val miningProgress: MutableMap<UUID, BlockMining> = HashMap()

    /** Per-player game-tick counter that advances every [tick] call. Used
     *  to drive the [DAMAGE_PERIOD_TICKS]-tick damage cadence. Cleared in
     *  [release] so the next wielding session restarts from tick 1 (i.e.
     *  the first damage chunk lands immediately on engage, not 10 ticks
     *  later). */
    private val damageTickCounter: MutableMap<UUID, Int> = HashMap()

    /** Per-player counter of consecutive at-full-ramp ticks for Channeling.
     *  Bumped only while `rampF >= 1.0`, reset whenever the staff drops
     *  below full or is released. Lightning fires when this hits the
     *  per-level interval. */
    private val channelingTickCounter: MutableMap<UUID, Int> = HashMap()

    private data class BlockMining(val pos: BlockPos, val state: BlockState, val ticks: Float)

    /** Integer stage 1..[STAGE_COUNT] — used by the client to label which
     *  transition window we're currently in. Server logic should prefer
     *  [rampFactor]. */
    fun stageOf(elapsed: Int): Int = (elapsed / STAGE_TICKS + 1).coerceIn(1, STAGE_COUNT)

    /** Continuous 0..1 fraction across the whole ramp. Everything else here
     *  is a function of this, so the curve has no stage-boundary jumps. */
    fun rampFactor(elapsed: Int): Float =
        (elapsed.toFloat() / MAX_RAMP_TICKS).coerceIn(0f, 1f)

    /** Two-segment ramp: 0 → MIN over the first quarter, then MIN → MAX over
     *  the remainder. Keeps the "stage 1 ≈ iron Sharp-I, stage 4 ≈ diamond
     *  Sharp-V" anchor points intact while smoothing the leading edge so the
     *  beam doesn't deal full Sharp-I instantly at t = 0. */
    fun damagePerTick(elapsed: Int): Float {
        val rampF = rampFactor(elapsed)
        return if (rampF < 0.25f) {
            DAMAGE_MIN_PER_TICK * (rampF / 0.25f)
        } else {
            DAMAGE_MIN_PER_TICK + (DAMAGE_MAX_PER_TICK - DAMAGE_MIN_PER_TICK) *
                ((rampF - 0.25f) / 0.75f)
        }
    }

    /** Whole-second cap for `setSecondsOnFire`. 0 at the very start (so
     *  pre-ignition particles don't light mobs), `FIRE_SECONDS_MAX` at full
     *  ramp. */
    private fun fireSecondsApplied(elapsed: Int): Int =
        (FIRE_SECONDS_MAX * rampFactor(elapsed)).toInt().coerceIn(0, FIRE_SECONDS_MAX)

    /** Block-break speed multiplier. 0 (disabled) below [MINING_ENABLE_RAMP];
     *  above it, linearly interpolated 1× → [EFFICIENCY_5_MULT] across the
     *  remaining ramp range. */
    private fun miningMultiplier(elapsed: Int): Float {
        val rampF = rampFactor(elapsed)
        if (rampF < MINING_ENABLE_RAMP) return 0f
        val t = (rampF - MINING_ENABLE_RAMP) / (1f - MINING_ENABLE_RAMP)
        return 1f + (EFFICIENCY_5_MULT - 1f) * t
    }

    fun elapsed(player: Player, stack: ItemStack): Int {
        val total = stack.item.getUseDuration(stack)
        return (total - player.useItemRemainingTicks).coerceAtLeast(0)
    }

    /** Same as [elapsed] but multiplied by Quick Charge's ramp-speedup
     *  factor. Public so the client renderer can read it directly when
     *  computing visual stage progression — keeps the wireframe / glow
     *  fade / etc. in sync with the server's accelerated ramp. */
    @JvmStatic
    fun effectiveElapsed(player: Player, stack: ItemStack): Int {
        val raw = elapsed(player, stack)
        val quick = SunderingEnchants.fromOffhand(player).quickCharge
        if (quick == 0) return raw
        return (raw * (1f + quick * QUICK_CHARGE_PER_LEVEL)).toInt()
    }

    /** Range with Infinity scaling applied — base [RANGE] × `(1 + level)`.
     *  Public so the client renderer can trace the visual beam at the
     *  same distance the server actually checks; without this the visual
     *  beam would stop at the base range and Infinity would appear to
     *  do nothing in-game. */
    @JvmStatic
    fun effectiveRange(player: Player): Double {
        val infinity = SunderingEnchants.fromOffhand(player).infinity
        if (infinity == 0) return RANGE
        return RANGE * (1.0 + infinity * INFINITY_PER_LEVEL)
    }

    fun tick(level: ServerLevel, player: Player) {
        val stack = player.useItem
        val rawElapsed = elapsed(player, stack)
        val enchants = SunderingEnchants.fromOffhand(player)

        // Quick Charge accelerates the ramp; everything downstream that
        // reads "where are we on the curve" works off this effective
        // elapsed instead of the raw use-ticks.
        val ench = enchants
        val effectiveElapsed = if (ench.quickCharge > 0) {
            (rawElapsed * (1f + ench.quickCharge * QUICK_CHARGE_PER_LEVEL)).toInt()
        } else rawElapsed
        val rampF = rampFactor(effectiveElapsed)

        val origin = player.getEyePosition(1f)
        val viewVec = player.getViewVector(1f)

        // Infinity multiplies effective range by `1 + level`.
        val effectiveRange = RANGE * (1.0 + ench.infinity * INFINITY_PER_LEVEL)

        // Collect every shield the beam might bounce off — actively
        // wielded Aegis frames plus any debug boxes spawned via
        // `/aegisbox`. The trace handles the polyline / reflection math.
        val shields = ArrayList<AegisBox.Frame>()
        shields.addAll(SunderingBeamTrace.collectWieldedShields(level, player))
        shields.addAll(
            org.shipwrights.enderkinesis.command.AegisDebugCommands.allDebugBoxes()
        )
        val trace = SunderingBeamTrace.trace(level, player, origin, viewVec, effectiveRange, shields)

        // Advance the i-frame-matched damage cadence. Counter starts at 1
        // on the first tick of a wielding session and increments on each
        // subsequent tick. `landsDamage` is true on ticks 1, 11, 21, ...
        // — every DAMAGE_PERIOD_TICKS ticks — so each hurt call falls into
        // vanilla's fresh-hit branch (invulnerableTime ≤ 10) and the full
        // chunk lands instead of getting cooldown-rejected.
        val counter = (damageTickCounter[player.uuid] ?: 0) + 1
        damageTickCounter[player.uuid] = counter
        val landsDamage = (counter % DAMAGE_PERIOD_TICKS) == 1

        // Damage chunk for this tick: per-tick rate × period, plus the
        // Power flat bonus, divided by Infinity's `(1 + level)`. Per-
        // mob-type bonuses (Impaling / Bane / Smite) are applied inside
        // [damageEntitiesAlong] so they only land on matching targets.
        val dmgChunk: Float = if (landsDamage) {
            val base = damagePerTick(effectiveElapsed) * DAMAGE_PERIOD_TICKS
            val withPower = base + ench.power * POWER_PER_TICK_PER_LEVEL * DAMAGE_PERIOD_TICKS
            withPower / (1f + ench.infinity * INFINITY_PER_LEVEL)
        } else 0f

        val fireSecs = fireSecondsApplied(effectiveElapsed) + ench.fireAspect * FIRE_ASPECT_SEC_PER_LEVEL

        for (segment in trace.segments) {
            damageEntitiesAlong(
                level, player, segment.start, segment.direction, segment.end,
                dmgChunk, fireSecs, ench,
            )
        }

        // Sweeping Edge — AoE damage around the polyline terminus,
        // scaled by level.
        if (landsDamage && ench.sweepingEdge > 0 && dmgChunk > 0f && trace.segments.isNotEmpty()) {
            val tipPos = trace.segments.last().end
            val sweepRadius = SWEEPING_BASE_RADIUS + SWEEPING_RADIUS_PER_LEVEL * ench.sweepingEdge
            val sweepDamage = dmgChunk * SWEEPING_DAMAGE_FRACTION_PER_LEVEL * ench.sweepingEdge
            applySweepingEdge(level, player, tipPos, sweepRadius, sweepDamage, ench)
        }

        // Channeling — spawn a lightning bolt at the beam terminus every
        // `CHANNELING_BASE_INTERVAL / level` ticks at full ramp. Counter
        // is bumped only while ramp is full; resets when it isn't.
        handleChanneling(level, player, rampF, ench, trace)

        // Flame — random chance to set fire to a block within the stage-
        // sized cubic radius of the beam terminus.
        handleFlameFire(level, rampF, ench, trace)

        // Mining only targets the polyline's terminating block (if any).
        // Silk Touch disables mining outright.
        val mult = miningMultiplier(effectiveElapsed)
        val finalBlock = trace.finalBlockPos
        if (ench.silkTouch == 0 && mult > 0f && finalBlock != null) {
            mineBlock(level, player, finalBlock, mult, ench)
        } else {
            miningProgress.remove(player.uuid)
        }
    }

    private fun damageEntitiesAlong(
        level: ServerLevel, player: Player,
        from: Vec3, direction: Vec3, to: Vec3,
        damage: Float, fireSeconds: Int, ench: SunderingEnchants,
    ) {
        if (damage <= 0f && fireSeconds <= 0) return
        val box = AABB(from, to).inflate(ENTITY_DAMAGE_RADIUS)
        val source = level.damageSources().indirectMagic(player, player)
        // Don't filter on `is LivingEntity` here — End Crystals, item
        // frames, and other non-living `Entity` subclasses still have a
        // meaningful `hurt(...)` (crystals explode, frames drop, etc.)
        // and were silently immune under the old filter.
        val entities = level.getEntities(player, box) { e: Entity ->
            e !== player && e.isAlive
        }
        val knockbackStrength = (ench.punch + ench.knockback) * KNOCKBACK_PER_LEVEL
        for (entity in entities) {
            if (!hitsBeamLine(entity.boundingBox, from, to, ENTITY_DAMAGE_RADIUS)) continue
            if (damage > 0f) {
                // Mob-type damage bonuses and knockback only make sense
                // for living entities; the base chunk still lands on
                // anything else.
                val finalDamage = if (entity is LivingEntity) damage + perMobTypeBonus(entity, ench) else damage
                DisintegrationLootingOverride.set(ench.looting)
                val landed: Boolean = try {
                    entity.hurt(source, finalDamage)
                } finally {
                    DisintegrationLootingOverride.clear()
                }
                if (landed && knockbackStrength > 0.0 && entity is LivingEntity) {
                    applyKnockback(entity, from, direction, knockbackStrength)
                }
            }
            // Fire applies every tick the entity is in the beam, even on
            // the 9-out-of-10 ticks the damage chunk skips — keeps mobs
            // continuously alight while caught without depending on the
            // hurt cadence. `setSecondsOnFire` is a `max(current, requested)`
            // so calling it every tick can't extend the burn past
            // `fireSeconds` after the beam moves off.
            if (fireSeconds > 0) {
                entity.setSecondsOnFire(fireSeconds)
            }
        }
    }

    /** Per-mob-type damage bonus from Impaling / Bane of Arthropods /
     *  Smite. Vanilla `MobType` and the wetness check both contribute to
     *  the Impaling target set. */
    private fun perMobTypeBonus(entity: LivingEntity, ench: SunderingEnchants): Float {
        var bonus = 0f
        val mobType = entity.mobType
        if (ench.impaling > 0 && (mobType == MobType.WATER || entity.isInWaterOrRain)) {
            bonus += ench.impaling * IMPALING_DAMAGE_PER_LEVEL
        }
        if (ench.smite > 0 && mobType == MobType.UNDEAD) {
            bonus += ench.smite * SMITE_DAMAGE_PER_LEVEL
        }
        if (ench.baneOfArthropods > 0 && mobType == MobType.ARTHROPOD) {
            bonus += ench.baneOfArthropods * BANE_DAMAGE_PER_LEVEL
        }
        return bonus
    }

    /** Push the entity in the beam direction (horizontal component only,
     *  matching vanilla `LivingEntity.knockback` which ignores Y). The
     *  knockback API negates its (x, z) args internally, so we feed it
     *  the **opposite** of the push direction to get the right sign. */
    private fun applyKnockback(entity: LivingEntity, from: Vec3, direction: Vec3, strength: Double) {
        val flatLen = Math.sqrt(direction.x * direction.x + direction.z * direction.z)
        val (kx, kz) = if (flatLen > 1e-4) {
            // Push along the beam direction.
            (-direction.x / flatLen) to (-direction.z / flatLen)
        } else {
            // Beam near-vertical — fall back to away-from-source.
            val dx = entity.x - from.x
            val dz = entity.z - from.z
            val l = Math.sqrt(dx * dx + dz * dz)
            if (l < 1e-4) return
            (-dx / l) to (-dz / l)
        }
        entity.knockback(strength, kx, kz)
    }

    /** Sweeping Edge — damage every living entity within [radius] of
     *  [tipPos] (excluding the wielder and other shield-projecting
     *  players' shields) for [damage] hp. Uses the same indirect-magic
     *  source as the main beam so on-hit effects (fire aspect ticks,
     *  etc.) chain correctly. */
    private fun applySweepingEdge(
        level: ServerLevel, player: Player, tipPos: Vec3,
        radius: Double, damage: Float, ench: SunderingEnchants,
    ) {
        if (damage <= 0f || radius <= 0.0) return
        val box = AABB.ofSize(tipPos, radius * 2.0, radius * 2.0, radius * 2.0)
        val source = level.damageSources().indirectMagic(player, player)
        val rSq = radius * radius
        // Same End-Crystal-compatible filter as [damageEntitiesAlong] —
        // don't restrict to LivingEntity here either.
        val entities = level.getEntities(player, box) { e: Entity ->
            e !== player && e.isAlive
        }
        for (entity in entities) {
            if (entity.position().distanceToSqr(tipPos) > rSq) continue
            val finalDamage = if (entity is LivingEntity) damage + perMobTypeBonus(entity, ench) else damage
            DisintegrationLootingOverride.set(ench.looting)
            try {
                entity.hurt(source, finalDamage)
            } finally {
                DisintegrationLootingOverride.clear()
            }
        }
    }

    /** Channeling — fires a lightning bolt on the **first** tick the staff
     *  reaches full ramp (immediate visual feedback that the enchantment
     *  is working), then again every `CHANNELING_BASE_INTERVAL / level`
     *  ticks for as long as it stays at full ramp. The counter is reset
     *  whenever the ramp drops below full or no block terminus is
     *  available — so dropping out of full charge and back up again
     *  re-triggers the immediate bolt. */
    private fun handleChanneling(
        level: ServerLevel, player: Player, rampF: Float,
        ench: SunderingEnchants, trace: BeamTrace,
    ) {
        if (ench.channeling <= 0 || rampF < 1f || trace.finalBlockPos == null) {
            channelingTickCounter.remove(player.uuid)
            return
        }
        val prev = channelingTickCounter[player.uuid]
        val interval = (CHANNELING_BASE_INTERVAL / ench.channeling).coerceAtLeast(1)
        val shouldFire = (prev == null) || (prev + 1 >= interval)
        if (!shouldFire) {
            channelingTickCounter[player.uuid] = (prev ?: 0) + 1
            return
        }
        // Reset interval counter (next fire is one full interval from now).
        channelingTickCounter[player.uuid] = 0
        val pos = trace.finalBlockPos!!
        val lightning = EntityType.LIGHTNING_BOLT.create(level) ?: return
        lightning.moveTo(pos.x + 0.5, pos.y + 1.0, pos.z + 0.5)
        lightning.setCause(player as? net.minecraft.server.level.ServerPlayer)
        level.addFreshEntity(lightning)
    }

    /** Flame — samples `ench.flame` candidate cells per tick inside the
     *  `±stage` cubic radius around the beam terminus, each with an
     *  independent `FLAME_TICK_CHANCE_PER_LEVEL` chance of igniting.
     *  Filters for air cells with at least one non-air neighbour (vanilla
     *  fire-placement rule). With multiple candidates per tick, the
     *  effective rate grows fast enough that low levels still produce
     *  visible fire within seconds instead of feeling broken. */
    private fun handleFlameFire(
        level: ServerLevel, rampF: Float, ench: SunderingEnchants, trace: BeamTrace,
    ) {
        if (ench.flame <= 0 || trace.finalBlockPos == null) return
        val stage = (rampF * STAGE_COUNT).toInt().coerceIn(1, STAGE_COUNT)
        val centre = trace.finalBlockPos!!
        val candidates = ench.flame
        for (i in 0 until candidates) {
            if (level.random.nextDouble() >= FLAME_TICK_CHANCE_PER_LEVEL) continue
            val dx = level.random.nextInt(stage * 2 + 1) - stage
            val dy = level.random.nextInt(stage * 2 + 1) - stage
            val dz = level.random.nextInt(stage * 2 + 1) - stage
            val target = centre.offset(dx, dy, dz)
            if (!level.getBlockState(target).isAir) continue
            // Vanilla flame placement rule — at least one neighboring face
            // must be solid / flammable for fire to take hold.
            val hasNeighbour = Direction.values().any {
                !level.getBlockState(target.relative(it)).isAir
            }
            if (!hasNeighbour) continue
            level.setBlock(target, BaseFireBlock.getState(level, target), 11)
        }
    }

    private fun hitsBeamLine(box: AABB, from: Vec3, to: Vec3, radius: Double): Boolean =
        box.inflate(radius).clip(from, to).isPresent

    private fun mineBlock(
        level: ServerLevel, player: Player, pos: BlockPos,
        miningMult: Float, ench: SunderingEnchants,
    ) {
        val state = level.getBlockState(pos)
        if (state.isAir) {
            miningProgress.remove(player.uuid)
            return
        }
        val hardness = state.getDestroySpeed(level, pos)
        if (hardness < 0f) {                                    // bedrock-class — unbreakable
            miningProgress.remove(player.uuid)
            return
        }
        // Efficiency multiplies the mining-speed scalar on top of the
        // ramp's own multiplier — `1 + level × 0.1`, applied here as a
        // divisor on the required tick count.
        val effMult = 1f + ench.efficiency * EFFICIENCY_BOOST_PER_LEVEL
        val ticksRequired = (hardness * MINING_TICKS_PER_HARDNESS) / (miningMult * effMult)

        val current = miningProgress[player.uuid]
        val progress = if (current != null && current.pos == pos) current.ticks + 1f else 1f

        if (progress >= ticksRequired) {
            // Stamp Fortune / Silk Touch onto the virtual mining tool so
            // the loot table sees them when rolling drops. Silk Touch's
            // disable-mining branch is upstream in [tick]; reaching here
            // means we ARE mining, so silkTouch only affects the drop set.
            val tool = buildMiningTool(ench)
            val drops = Block.getDrops(
                state, level, pos, level.getBlockEntity(pos), player, tool,
            )
            miningProgress.remove(player.uuid)
            level.destroyBlock(pos, false)
            for (drop in drops) Block.popResource(level, pos, drop)
        } else {
            miningProgress[player.uuid] = BlockMining(pos, state, progress)
        }
    }

    /** Virtual netherite pickaxe stamped with whatever subset of Fortune
     *  / Silk Touch the offhand book carries. The Disintegration tome
     *  uses the same trick to make the loot table produce tooled drops;
     *  re-using its shape here keeps the behaviour symmetric across
     *  beam-style block breakers. */
    private fun buildMiningTool(ench: SunderingEnchants): ItemStack {
        if (ench.fortune == 0 && ench.silkTouch == 0) return VIRTUAL_TOOL
        val tool = VIRTUAL_TOOL.copy()
        val tag = tool.orCreateTag
        val list = ListTag()
        if (ench.fortune > 0) {
            val entry = CompoundTag()
            entry.putString("id", "minecraft:fortune")
            entry.putInt("lvl", ench.fortune)
            list.add(entry)
        }
        if (ench.silkTouch > 0) {
            val entry = CompoundTag()
            entry.putString("id", "minecraft:silk_touch")
            entry.putInt("lvl", ench.silkTouch)
            list.add(entry)
        }
        tag.put("Enchantments", list)
        return tool
    }

    fun release(player: Player) {
        miningProgress.remove(player.uuid)
        damageTickCounter.remove(player.uuid)
        channelingTickCounter.remove(player.uuid)
    }
}
