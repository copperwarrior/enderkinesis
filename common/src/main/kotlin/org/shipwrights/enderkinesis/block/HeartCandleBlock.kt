package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.CandleBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * Heart Candle — a single-only candle.
 *
 * Behaves like a vanilla candle for every interaction (flint+steel
 * lighting, empty-hand right-click extinguish while lit, projectile
 * extinguish via vanilla `AbstractCandleBlock.onProjectileHit`,
 * waterloggable, emits light when LIT), but never accepts a second
 * candle in the same block — right-clicking it with a candle item
 * places the new candle on an adjacent face via vanilla's normal
 * block-placement fallback instead of stacking inside.
 *
 * Implementation: extend [CandleBlock] (cheapest path — inherits the
 * full candle behaviour pack) and override [canBeReplaced] to
 * return `false` so vanilla's "absorb the candle item into the
 * existing block by incrementing CANDLES" path never fires. The
 * `CANDLES` blockstate property is still defined by the parent
 * class for state-definition compatibility, but it's pinned at 1
 * for every reachable state of this block — the blockstate JSON
 * only ships variants for `candles=1`.
 *
 * Membership in the `minecraft:candles` block + item tags is what
 * makes flint+steel and `AbstractCandleBlock.canLight` recognise
 * this block as something they can light. Data files ship those
 * tag entries.
 */
class HeartCandleBlock(properties: Properties) : CandleBlock(properties) {

    override fun canBeReplaced(state: BlockState, context: BlockPlaceContext): Boolean = false

    /** Detect the LIT-false → LIT-true transition and, on that
     *  exact moment, try to fire the Wohlon portal ritual: check
     *  the surrounding 8-light-blue-candle pattern + the local
     *  biome against [WohlonnogondoniaPortalRitual.VALID_BIOMES_TAG],
     *  and if both pass register the candle's *position* with
     *  [WohlonnogondoniaPortalManager].
     *
     *  The block itself is NOT modified — the heart candle
     *  remains a normal lit candle, and the player can
     *  extinguish / relight / break it freely. The portal point
     *  lives in the manager's per-dimension SavedData, completely
     *  independent of whatever sits at that BlockPos. Once
     *  registered the portal is permanent — relighting the candle
     *  again just re-adds the same position to the manager's set
     *  (idempotent, no harm).
     *
     *  Failure cases (wrong pattern, wrong biome) are silent —
     *  the candle stays lit as a normal candle. `onPlace` is the
     *  canonical hook for state-change side effects since vanilla's
     *  `FlintAndSteelItem.useOn` writes the LIT=true state via
     *  `Level.setBlock`, which routes through here. */
    override fun onPlace(state: BlockState, level: Level, pos: BlockPos, oldState: BlockState, isMoving: Boolean) {
        super.onPlace(state, level, pos, oldState, isMoving)
        if (level.isClientSide) return
        if (!oldState.`is`(this)) return
        val wasLit = oldState.getValue(LIT)
        val isLit = state.getValue(LIT)
        if (wasLit || !isLit) return
        val serverLevel = level as? ServerLevel ?: return
        if (!WohlonnogondoniaPortalRitual.checkPattern(serverLevel, pos)) return
        if (!WohlonnogondoniaPortalRitual.isBiomeValid(serverLevel, pos)) return
        // Portal anchor lives 2 blocks above the candle — the
        // candle is the catalyst, but the portal floats in the
        // air above it (where the player walks *into* it). The
        // manager's particle/teleport logic centres on whatever
        // BlockPos is registered, so the +2 Y offset is applied
        // here at the registration site, not inside the manager.
        val portalPos = pos.above(2)
        WohlonnogondoniaPortalManager.addPortal(serverLevel, portalPos)
        // Seed a 16-block sphere of Wohlon biome around the
        // newly-created portal, gradually over 15 s. After the
        // seed completes the natural 3-cell/min spread takes
        // over and the Wohlon footprint keeps growing.
        WohlonnogondoniaSpreader.startPortalSeed(serverLevel, portalPos)
        // Grow the Wohlon tree above the candle: fits the
        // ground plane in a 16-radius disc, builds the skeleton
        // along the plane normal, schedules bud / leaf
        // placements so the tree appears to grow outward over
        // ~30–90 s.
        WohlonnogondoniaTreeGrower.startTree(serverLevel, portalPos)
    }

    /** Flame particle position, anchored to the top of the wax
     *  pillar in the model (Y 12 in model space → Y 0.75 in
     *  block space). Vanilla [CandleBlock] defaults to Y 0.5 for
     *  CANDLES=1 — which would float the flame mid-wax for our
     *  taller heart-candle silhouette — so we override here.
     *  Centred in X/Z at 0.5. Returned as a single-element list
     *  matching CandleBlock's expected `Iterable<Vec3>` shape. */
    override fun getParticleOffsets(state: BlockState): Iterable<Vec3> = FLAME_OFFSETS

    /** Selection / collision shape that actually matches the
     *  model. Vanilla [CandleBlock]'s `ONE_AABB` is a single
     *  `7..9 × 0..6 × 7..9` pillar — fine for a vanilla candle
     *  which is the same shape end-to-end, but on the heart
     *  candle that hitbox covers only the upper wick and floats
     *  over an empty pixel band where the 6 × 6 wax base lives.
     *  Resulting bug: aiming at the visible base doesn't
     *  register a hit, and the flame's selection box is two
     *  pixels above where the player expects.
     *
     *  Custom shape unions the two model groups:
     *   - **Base wax block** at `5..11` × `0..6` × `5..11`
     *     (the 6 × 6 × 6 lower cube in `heart_candle.json`).
     *   - **Upper wick pillar** at `7..9` × `6..12` × `7..9`
     *     (the 2 × 6 × 2 column that sits on top of the base).
     *
     *  The two decorative flame quads at `Y 12..13` are too thin
     *  to be worth a selection box and would only complicate
     *  ray-tracing; the wick pillar's top face at `Y 12` is the
     *  natural target for right-click interactions anyway. */
    override fun getShape(
        state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext,
    ): VoxelShape = SHAPE

    companion object {
        /** Flame particle position. Y 14 in model space = 14/16 in
         *  block space, lined up with the flame quad in
         *  `heart_candle_lit.json` (and the unlit candle's wick
         *  quad in `heart_candle.json` — same coordinates). */
        private val FLAME_OFFSETS: List<Vec3> = listOf(Vec3(0.5, 14.0 / 16.0, 0.5))

        /** Selection / collision shape — see [getShape] for why. */
        private val SHAPE: VoxelShape = Shapes.or(
            Block.box(5.0, 0.0, 5.0, 11.0, 6.0, 11.0),
            Block.box(7.0, 6.0, 7.0, 9.0, 12.0, 9.0),
        )
    }
}
