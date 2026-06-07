package org.shipwrights.enderkinesis.entity

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.tags.TagKey
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.WallBlock
import net.minecraft.world.level.pathfinder.BlockPathTypes
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator
import org.shipwrights.enderkinesis.EnderkinesisMod

/**
 * Node evaluator that stops Catalogers from standing on — and therefore climbing
 * up — the deepslate [WallBlock]s that line Sselith's pathways, and the target
 * blocks they wander toward (lecterns, signs, …). Without it, the vanilla
 * [WalkNodeEvaluator] treats the flat top of any solid block as a walkable node,
 * so a cataloger would hop onto a lectern, then use it (and the path-side walls)
 * as footholds to climb up off the floor.
 *
 * ## How it works
 *
 * [WalkNodeEvaluator.getBlockPathType] (the per-cell classifier the pathfinder
 * calls for every node in the mob's footprint) returns [BlockPathTypes.WALKABLE]
 * for an air cell that sits on a solid block — i.e. "you can stand here". Wall
 * blocks classify as `FENCE` (so the pathfinder won't route *through* them) but
 * the cell on *top* of one is still WALKABLE, which is what lets a mob perch on a
 * wall once something lifts it up there. We intercept exactly that: if the block
 * providing the floor is a wall or a target block, the cell is downgraded to
 * [BlockPathTypes.BLOCKED] so no walkable node is ever created on top of it.
 *
 * Floors (polished/chiseled deepslate, deepslate brick/tile), stairs, slabs and
 * ladders are untouched, so the cataloger keeps wandering between layers exactly
 * as before — it just approaches lecterns and walls from an adjacent floor tile
 * and stares, instead of perching on them. A target walled in with no adjacent
 * floor simply becomes unreachable (and the goal's retry cooldown moves on) —
 * correct, since the only way to "reach" it before was the climb we're removing.
 */
class CatalogerNodeEvaluator : WalkNodeEvaluator() {

    private val below = BlockPos.MutableBlockPos()

    override fun getBlockPathType(level: BlockGetter, x: Int, y: Int, z: Int): BlockPathTypes {
        val base = super.getBlockPathType(level, x, y, z)
        // Only WALKABLE cells are "stand here on the block below" candidates; for
        // anything else the floor is irrelevant.
        if (base == BlockPathTypes.WALKABLE) {
            val floor = level.getBlockState(below.set(x, y - 1, z))
            if (floor.block is WallBlock || floor.`is`(CATALOGER_TARGETS)) {
                return BlockPathTypes.BLOCKED
            }
        }
        return base
    }

    private companion object {
        /** Block tag of every Cataloger POI-target (lecterns, signs, enchanting
         *  tables, chiseled bookshelves, gold blocks). Mirrors the POI predicate
         *  in [WanderToTaggedBlockGoal] — catalogers look at these, never stand
         *  on them. */
        val CATALOGER_TARGETS: TagKey<Block> =
            TagKey.create(Registries.BLOCK, EnderkinesisMod.id("cataloger_targets"))
    }
}
