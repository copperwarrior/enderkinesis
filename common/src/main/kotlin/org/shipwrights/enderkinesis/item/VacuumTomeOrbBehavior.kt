package org.shipwrights.enderkinesis.item

import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.blockentity.OrbOfLinkingBlockEntity

/**
 * Tome of Vacuum behavior — like Tome of Transportation, but the **source is dropped item
 * entities** in a 5×5×3 region around the SEND orb instead of a container at the active
 * face. Everything else (round-robin to receivers, in-flight tracking, visual flight,
 * bounce-on-failure) is shared with the rest of the item-mover suite via
 * [ItemMoverTomeOrbBehavior].
 *
 *  - Box is centred on the orb's geometric world centre (which is itself offset toward
 *    the active face by [OrbOfLinkingBlockEntity]'s ACTIVE_FACE_OFFSET = 0.25 b).
 *  - 5 blocks wide × 5 blocks tall in the two axes perpendicular to FACING; 3 blocks
 *    deep along FACING. So for a floor-mounted orb (FACING=DOWN), the box is roughly
 *    `x: orb.x ± 2.5`, `y: orb.y ± 1.5`, `z: orb.z ± 2.5`. The "behind the orb" portion
 *    of the depth axis ends up inside the support block and is naturally empty of items.
 *  - Item entities are skipped while their pickup delay is non-zero. This stops a stack
 *    that just bounced back from a full receiver from being re-vacuumed instantly — it
 *    gets its 10-tick vanilla cooldown before re-entering the rotation.
 *  - On bounce-back at delivery failure, the default [pushBackToSource] drops the stack
 *    as an item entity at the SEND orb. The vacuum will eventually re-suck it after its
 *    pickup delay elapses; if the receiver is still full, it bounces again. So the
 *    bounce semantic survives without a source container.
 */
object VacuumTomeOrbBehavior : ItemMoverTomeOrbBehavior() {

    override val tomeKind: ResourceLocation = EnderkinesisMod.id("tome_of_vacuum")

    /** Half-extent (blocks) of the suction box along the FACING axis. Total depth = 2× this. */
    private const val HALF_DEPTH: Double = 1.5
    /** Half-extent (blocks) along each of the two axes perpendicular to FACING. */
    private const val HALF_WIDTH: Double = 2.5

    override fun pullSourceStack(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity): ItemStack? {
        val target = firstSuctionCandidate(level, sendBe) ?: return null
        // Split one item off and re-set so the entity-data watcher syncs the new count to
        // tracking clients (mutating the returned stack in place wouldn't trigger the data
        // watcher). The entity stays alive as long as the stack has count > 0, so a stack
        // of 64 takes 64 ticks of vacuum sweeps to fully drain.
        val current = target.item
        val pulled = current.split(1)
        if (current.isEmpty) target.discard()
        else target.item = current
        return pulled
    }

    /** Peek the next item we'd suck up — without modifying the entity. Lets [tryDispatch]
     *  ask the destination "do you specifically want this?" before committing to a pull. */
    override fun peekSourceStack(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity): ItemStack? {
        val target = firstSuctionCandidate(level, sendBe) ?: return null
        return target.item.copy().also { it.count = 1 }
    }

    /** Candidate filter: alive, off cooldown, non-empty, and (if the SEND orb has a filter
     *  set) matching that filter — so a Vacuum orb filtered to dragon's breath ignores all
     *  other dropped items in its suction box. */
    private fun firstSuctionCandidate(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity): ItemEntity? {
        val centre = sendBe.worldCenter() ?: return null
        val box = suctionBox(centre, sendBe.facing)
        val filter = sendBe.filter
        return level.getEntitiesOfClass(ItemEntity::class.java, box) { entity ->
            entity.isAlive && !entity.hasPickUpDelay() && !entity.item.isEmpty &&
                matchesFilter(filter, entity.item)
        }.firstOrNull()
    }

    /** Axis-aligned suction box centred on the orb. The 3-deep axis is aligned with FACING;
     *  the other two axes get the 5-wide / 5-tall span. */
    private fun suctionBox(centre: Vec3, facing: Direction): AABB {
        val dx = if (facing.axis == Direction.Axis.X) HALF_DEPTH else HALF_WIDTH
        val dy = if (facing.axis == Direction.Axis.Y) HALF_DEPTH else HALF_WIDTH
        val dz = if (facing.axis == Direction.Axis.Z) HALF_DEPTH else HALF_WIDTH
        return AABB(
            centre.x - dx, centre.y - dy, centre.z - dz,
            centre.x + dx, centre.y + dy, centre.z + dz,
        )
    }

    /** Same item-aware destination filter as Transportation, plus the RECEIVE-orb filter
     *  check: a filtered receiver refuses items that don't match its filter even if the
     *  underlying container has room. */
    override fun canReceiverAccept(
        level: ServerLevel, recvBe: OrbOfLinkingBlockEntity, stack: ItemStack,
    ): Boolean {
        if (!matchesFilter(recvBe.filter, stack)) return false
        val recvFacing = recvBe.facing
        val destPos = recvBe.blockPos.relative(recvFacing)
        val dest = resolveContainer(level, destPos) ?: return false
        return hasSpaceFor(dest, recvFacing.opposite, stack)
    }
}
