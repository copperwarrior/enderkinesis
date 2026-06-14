package org.shipwrights.enderkinesis.entity

import com.mojang.logging.LogUtils
import java.util.EnumSet
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.registry.EKBlocks

/**
 * Rare flourish goal: while idle, occasionally pick a nearby Sselith
 * bookshelf within line of sight, turn to face it, and let the
 * [Cataloger.beginTomeSummon] / [Cataloger.endTomeSummon] hooks drive
 * the client-side floating-tome render for [Cataloger.TOME_TOTAL_TICKS].
 *
 * The cataloger does NOT path to the bookshelf — it stays put, gaze
 * locked, while the tome travels through the air on its own. Movement
 * goals (wander, POI) are suppressed via [Flag.MOVE]; head/body turn is
 * owned here via [Flag.LOOK].
 *
 * **Cadence.** Two layers of rarity:
 *  - A random-gate roll on every [canUse] tick at 1/[TRIGGER_DENOMINATOR]
 *    — expected time to fire is ~20 minutes of consecutive canUse polls.
 *  - A post-summon cooldown ([COOLDOWN_AFTER_TICKS]) so two summons can't
 *    bunch back-to-back on the same cataloger.
 *
 * **Bookshelf pick.** Scans the (2·R+1)³ box around the cataloger for
 * Sselith bookshelves, filtered by line of sight from the cataloger's
 * eyes to the block's centre. Random pick from the eligible set. The
 * scan is bounded by [SEARCH_RADIUS] and only runs when the random
 * gate fires, so cost is negligible.
 */
class SummonTomeFromBookshelfGoal(
    private val mob: Cataloger,
) : Goal() {

    private var source: BlockPos? = null
    private var returnTo: BlockPos? = null
    private var summonStartTick = 0L
    private var nextEarliestTrigger = 0L

    init {
        flags = EnumSet.of(Flag.MOVE, Flag.LOOK)
    }

    override fun canUse(): Boolean {
        val level = mob.level()
        if (level.isClientSide) return false
        if (mob.isVehicle) return false
        val now = level.gameTime
        if (now < nextEarliestTrigger) return false
        if (mob.random.nextInt(TRIGGER_DENOMINATOR) != 0) return false
        val pair = pickBookshelfPair() ?: run {
            // Nothing in range right now — short scan cooldown so we
            // don't re-scan every tick at full rate after a miss.
            nextEarliestTrigger = now + SCAN_MISS_COOLDOWN_TICKS
            return false
        }
        source = pair.first
        returnTo = pair.second
        return true
    }

    override fun canContinueToUse(): Boolean {
        if (source == null) return false
        return mob.level().gameTime - summonStartTick < Cataloger.TOME_TOTAL_TICKS
    }

    override fun start() {
        val src = source ?: return
        val ret = returnTo ?: src
        summonStartTick = mob.level().gameTime
        mob.navigation.stop()
        mob.beginTomeSummon(src, ret)
        LOG.info(
            "Cataloger tome-summon triggered: entity={} uuid={} at=({},{},{}) source=({},{},{}) returnTo=({},{},{})",
            mob.id, mob.uuid,
            mob.blockX, mob.blockY, mob.blockZ,
            src.x, src.y, src.z,
            ret.x, ret.y, ret.z,
        )
    }

    override fun stop() {
        mob.endTomeSummon()
        nextEarliestTrigger = mob.level().gameTime + COOLDOWN_AFTER_TICKS
        source = null
        returnTo = null
    }

    override fun tick() {
        val src = source ?: return
        val ret = returnTo ?: src
        val elapsedTicks = mob.level().gameTime - summonStartTick
        // Watch the active endpoint of the current phase: source during
        // outbound, return during inbound, source through dwell so the
        // head doesn't snap mid-summon.
        val watching = if (elapsedTicks >= Cataloger.TOME_OUTBOUND_TICKS + Cataloger.TOME_DWELL_TICKS) ret else src
        mob.lookControl.setLookAt(watching.x + 0.5, watching.y + 0.5, watching.z + 0.5)
        // Keep the cataloger anchored — any in-flight nav from a
        // pre-empted goal would slide it off the mark.
        if (!mob.navigation.isDone) mob.navigation.stop()
        if (elapsedTicks % TRAIL_INTERVAL_TICKS == 0L) {
            spawnTrailParticle(elapsedTicks.toFloat(), src, ret)
        }
    }

    /** Emit a single vanilla enchant glyph at the tome's current world
     *  position during flight phases. Dwell skips the trail — the open
     *  book at the hold-point is busy enough visually without an
     *  additional glyph stream. [ParticleTypes.ENCHANT] is the same
     *  faint white-tinted sga glyph the enchanting table emits;
     *  scales down and fades quickly so it reads as a wake, not as a
     *  column of dressing. Server-side so all observers see the same
     *  trail. */
    private fun spawnTrailParticle(elapsed: Float, src: BlockPos, ret: BlockPos) {
        val level = mob.level() as? ServerLevel ?: return
        val phase = CatalogerTomePath.phaseOf(elapsed)
        if (phase == CatalogerTomePath.Phase.DWELL) return
        val progress = CatalogerTomePath.progressInPhase(phase, elapsed)
        val (holdX, holdY, holdZ) = CatalogerTomePath.holdPoint(
            mob.x, mob.eyeY, mob.z, mob.yHeadRot, mob.xRot,
        )
        val (px, py, pz) = CatalogerTomePath.computePosition(
            phase, progress,
            src.x + 0.5, src.y + 0.5, src.z + 0.5,
            ret.x + 0.5, ret.y + 0.5, ret.z + 0.5,
            holdX, holdY, holdZ,
            mob.x, mob.eyeY, mob.z,
        )
        val jx = (mob.random.nextDouble() - 0.5) * TRAIL_JITTER
        val jy = (mob.random.nextDouble() - 0.5) * TRAIL_JITTER
        val jz = (mob.random.nextDouble() - 0.5) * TRAIL_JITTER
        // For ParticleTypes.ENCHANT the (x, y, z) passed to sendParticles
        // is the particle's DESTINATION and (dx, dy, dz) is the offset
        // from destination where it spawns. Zero offsets → particle
        // spawns at the tome's position and stays put while it fades.
        // count=0 → emit exactly one particle (sendParticles' count
        // arg is a "extra" multiplier; count=1 spawns two).
        level.sendParticles(
            ParticleTypes.ENCHANT,
            px + jx, py + jy, pz + jz, 0,
            0.0, 0.0, 0.0, 0.0,
        )
    }

    /** Pick a source-and-return pair by **raycasting** out from the
     *  cataloger's eyes in random directions. Each ray uses a uniform
     *  yaw `[0, 360)` and a uniform pitch in `±[PITCH_RANGE_DEG]`, then
     *  is traced [TRACE_DISTANCE] blocks. If the first block the ray
     *  hits is a Sselith bookshelf, that's a successful pick.
     *
     *  - **Source**: one ray. Must hit a bookshelf — if it misses or
     *    hits some other block, the whole attempt fails and the goal
     *    re-cools down. The cataloger stays mobile.
     *  - **Return**: a second, independent ray. If that one also hits
     *    a Sselith bookshelf, the tome returns to that one (different
     *    shelf from source). Otherwise the return falls back to source
     *    and the inbound flight retraces.
     *
     *  A POI cube-scan is no longer involved — the cataloger doesn't
     *  consider every shelf in range, only the one a thrown-dart-style
     *  ray happens to land on. */
    private fun pickBookshelfPair(): Pair<BlockPos, BlockPos>? {
        val source = raycastForBookshelf() ?: return null
        val ret = raycastForBookshelf() ?: source
        return source to ret
    }

    /** Fire a single ray from the cataloger's eyes in a random
     *  direction (yaw uniform in `[0, 360)`, pitch uniform in
     *  `±[PITCH_RANGE_DEG]`) out to [TRACE_DISTANCE] blocks. Returns
     *  the immutable [BlockPos] iff the ray's *first* block hit is a
     *  Sselith bookshelf; null on miss, on a non-bookshelf hit, or on
     *  a non-server level. */
    private fun raycastForBookshelf(): BlockPos? {
        val level = mob.level() as? ServerLevel ?: return null
        val sselithBookshelf = EKBlocks.SSELITH_BOOKSHELF.get()

        // Match vanilla's calculateViewVector convention exactly so
        // "pitch" / "yaw" here behave like the entity-look values you'd
        // see elsewhere in the codebase.
        val yawDeg = mob.random.nextFloat() * 360f
        val pitchDeg = (mob.random.nextFloat() * 2f - 1f) * PITCH_RANGE_DEG
        val yawRad = yawDeg * (Math.PI / 180.0)
        val pitchRad = pitchDeg * (Math.PI / 180.0)
        val cosPitch = Math.cos(pitchRad)
        val dx = -Math.sin(yawRad) * cosPitch
        val dy = -Math.sin(pitchRad)
        val dz = Math.cos(yawRad) * cosPitch

        val from = Vec3(mob.x, mob.eyeY, mob.z)
        val to = Vec3(
            from.x + dx * TRACE_DISTANCE,
            from.y + dy * TRACE_DISTANCE,
            from.z + dz * TRACE_DISTANCE,
        )
        val hit = level.clip(
            ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mob),
        )
        if (hit.type != HitResult.Type.BLOCK) return null
        val pos = hit.blockPos
        if (!level.getBlockState(pos).`is`(sselithBookshelf)) return null
        return pos.immutable()
    }

    companion object {

        private val LOG = LogUtils.getLogger()

        /** Per-eligible-tick odds: 1 / N chance to fire. With Goal-
         *  selector polling canUse roughly every tick when no higher-
         *  priority goal is running, 1200 yields an expected ~60 s
         *  between summons of idle catalogers near eligible shelves. */
        private const val TRIGGER_DENOMINATOR = 1200

        /** Cooldown after a failed scan (no eligible bookshelf), so
         *  we don't re-roll the gate every tick burning the scan. */
        private const val SCAN_MISS_COOLDOWN_TICKS: Long = 100L

        /** Cooldown after a completed summon before this cataloger
         *  can summon again. Stops back-to-back summons without making
         *  the flourish feel periodic. */
        private const val COOLDOWN_AFTER_TICKS: Long = 20L * 30L

        /** Maximum reach (blocks) of the bookshelf-pick raycast. The
         *  cataloger looks in a random direction; if the first block
         *  the ray meets within this many blocks is a Sselith
         *  bookshelf, that's the target. */
        private const val TRACE_DISTANCE = 32.0

        /** Half-width (degrees) of the random-pitch band around the
         *  horizon. ±30° keeps the ray mostly horizontal so it can find
         *  shelves on the same floor without burying itself in stone
         *  above or below. */
        private const val PITCH_RANGE_DEG = 30f

        /** Tick interval between trail-particle spawns. 4 → 5/s, ≈35
         *  glyphs per ~140-tick flight phase — visible as a faint wake
         *  without saturating the air around the tome. */
        private const val TRAIL_INTERVAL_TICKS = 4L

        /** Per-axis position jitter (blocks) applied to each trail
         *  glyph spawn so a stream of glyphs reads as scattered rather
         *  than perfectly stacked. */
        private const val TRAIL_JITTER = 0.08
    }
}
