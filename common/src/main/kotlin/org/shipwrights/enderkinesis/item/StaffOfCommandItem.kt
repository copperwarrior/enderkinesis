package org.shipwrights.enderkinesis.item

import net.minecraft.core.BlockPos
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
import org.joml.Vector3d
import org.joml.Vector3f
import org.shipwrights.enderkinesis.blockentity.Commandable
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.mod.common.getLoadedShipManagingPos

/** Staff of Command — right-click a [Commandable] block to add it to this stack's command
 *  list (re-click to remove); right-click open air to fire every listed block's command in
 *  one pass; **shift-right-click open air** to wipe the list.
 *
 *  The block in the wielder's *other* hand becomes the [Commandable]'s context parameter,
 *  letting each block expose modal sub-behaviours (empty hand = default).
 *
 *  ## Reach + dimension rules
 *  - Stored entries carry the dimension they were added in. Firing only acts on entries
 *    whose dimension matches the wielder's current level — same-dimension is enforced at
 *    both add-time (trivially: you can only add blocks you're looking at) and fire-time.
 *  - **Ship target**: reachable when (a) the wielder is currently on the same ship, or
 *    (b) the wielder isn't on any ship AND the target's world position is within 64.
 *  - **World target**: reachable when within 64 blocks of the wielder.
 *
 *  Out-of-range / out-of-dimension / ship-unloaded entries are silently skipped without
 *  being purged — re-entering range or returning to the dimension re-arms them. */
class StaffOfCommandItem(properties: Properties) : Item(properties) {

    override fun useOn(context: UseOnContext): InteractionResult {
        val player = context.player ?: return InteractionResult.PASS
        val level = context.level
        val blockPos = context.clickedPos
        val be = level.getBlockEntity(blockPos)
        if (be !is Commandable) return InteractionResult.PASS

        val targetShip = level.getLoadedShipManagingPos(blockPos)
        if (!isReachable(player, level, blockPos, targetShip)) {
            if (!level.isClientSide) {
                player.displayClientMessage(
                    Component.translatable("item.enderkinesis.staff_of_command.out_of_range"), true,
                )
            }
            return InteractionResult.FAIL
        }

        if (level.isClientSide) return InteractionResult.SUCCESS

        val stack = context.itemInHand
        val dim = level.dimension().location()
        val added = togglePosition(stack, blockPos, dim)
        val blockName = level.getBlockState(blockPos).block.name
        val msg = if (added) "item.enderkinesis.staff_of_command.added"
                  else "item.enderkinesis.staff_of_command.removed"
        player.displayClientMessage(Component.translatable(msg, blockName), true)
        return InteractionResult.sidedSuccess(level.isClientSide)
    }

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        if (level.isClientSide) {
            return InteractionResultHolder.sidedSuccess(stack, true)
        }

        // Shift-right-click air → wipe the list entirely.
        if (player.isShiftKeyDown) {
            clearEntries(stack)
            player.displayClientMessage(
                Component.translatable("item.enderkinesis.staff_of_command.cleared"), true,
            )
            return InteractionResultHolder.sidedSuccess(stack, false)
        }

        val contextStack = if (hand == InteractionHand.MAIN_HAND) player.offhandItem else player.mainHandItem
        val entries = readEntries(stack)
        val currentDim = level.dimension().location()
        val serverLevel = level as? ServerLevel
        var fired = 0
        for (entry in entries) {
            if (entry.dim != currentDim) continue                       // other dimension
            val ship = level.getLoadedShipManagingPos(entry.pos)
            if (!isReachable(player, level, entry.pos, ship)) continue  // out of range
            val be = level.getBlockEntity(entry.pos)
            if (be !is Commandable) continue                            // block removed / replaced
            be.executeStaffCommand(player, ship, contextStack)
            if (serverLevel != null) spawnBlockSpark(serverLevel, entry.pos, ship)
            fired++
        }
        if (fired > 0) {
            if (serverLevel != null) spawnGazeBeam(serverLevel, player)
            player.displayClientMessage(
                Component.translatable("item.enderkinesis.staff_of_command.fired", fired), true,
            )
        }
        return InteractionResultHolder.sidedSuccess(stack, false)
    }

    override fun appendHoverText(stack: ItemStack, level: Level?, tooltip: MutableList<Component>, flag: TooltipFlag) {
        val entries = readEntries(stack)
        tooltip.add(Component.translatable("item.enderkinesis.staff_of_command.tooltip.count", entries.size))
    }

    companion object {
        const val ENTRIES_TAG: String = "CommandedBlocks"
        const val POS_TAG: String = "Pos"
        const val DIM_TAG: String = "Dim"

        data class Entry(val pos: BlockPos, val dim: ResourceLocation)

        fun readEntries(stack: ItemStack): List<Entry> {
            val tag = stack.tag ?: return emptyList()
            val list = tag.getList(ENTRIES_TAG, Tag.TAG_COMPOUND.toInt())
            val out = ArrayList<Entry>(list.size)
            for (i in 0 until list.size) {
                val c = list.getCompound(i)
                val pos = BlockPos.of(c.getLong(POS_TAG))
                val dim = ResourceLocation.tryParse(c.getString(DIM_TAG)) ?: continue
                out.add(Entry(pos, dim))
            }
            return out
        }

        private fun writeEntries(stack: ItemStack, entries: List<Entry>) {
            val tag = stack.orCreateTag
            val list = ListTag()
            for (e in entries) {
                val c = CompoundTag()
                c.putLong(POS_TAG, e.pos.asLong())
                c.putString(DIM_TAG, e.dim.toString())
                list.add(c)
            }
            tag.put(ENTRIES_TAG, list)
        }

        /** Add `(pos, dim)` if absent, remove it if present. Returns `true` when added. */
        private fun togglePosition(stack: ItemStack, pos: BlockPos, dim: ResourceLocation): Boolean {
            val entries = readEntries(stack).toMutableList()
            val existing = entries.indexOfFirst { it.pos == pos && it.dim == dim }
            val added: Boolean
            if (existing >= 0) {
                entries.removeAt(existing); added = false
            } else {
                entries.add(Entry(pos, dim)); added = true
            }
            writeEntries(stack, entries)
            return added
        }

        private fun clearEntries(stack: ItemStack) {
            val tag = stack.tag ?: return
            tag.remove(ENTRIES_TAG)
            if (tag.isEmpty) stack.tag = null
        }

        /** Reachability shared by add (`useOn`) and fire (`use`). Stored positions are
         *  either shipyard or world coords; we re-resolve the ship at check time so a
         *  ship that unloads silently skips. The gate is "chunk is server-loaded" —
         *  past the firing player's render distance the chunk isn't loaded for them,
         *  so the staff naturally caps at render distance without a hardcoded radius.
         *  Replaces the previous 64-block constant that matched vanilla BER-render
         *  distance and made the staff stop working on BEs visible but past 64. */
        private fun isReachable(player: Player, level: Level, targetPos: BlockPos, targetShip: Ship?): Boolean {
            if (targetShip != null) {
                val playerStandingPos = BlockPos.containing(player.x, player.y - 0.05, player.z)
                val playerShip = level.getLoadedShipManagingPos(playerStandingPos)
                if (playerShip != null && playerShip.id == targetShip.id) return true
                if (playerShip != null) return false  // wielder on a *different* ship
            }
            // VS2 ship blocks live in the level's chunk map at shipyard coords, so the
            // same hasChunkAt call covers both world and ship targets.
            return level.hasChunkAt(targetPos)
        }

        private val BEAM_COLOR = Vector3f(1.0f, 0.15f, 0.15f)
        private val DUST_BEAM = DustParticleOptions(BEAM_COLOR, 0.7f)
        private val DUST_SPARK = DustParticleOptions(BEAM_COLOR, 1.0f)
        private const val BEAM_PARTICLES_PER_BLOCK = 2.0
        private const val BEAM_MAX_PARTICLES = 128
        private const val BEAM_GAZE_REACH = 256.0
        private const val SPARK_COUNT = 8
        private const val SPARK_SPREAD = 0.22
        private const val SPARK_SPEED = 0.02

        /** Single beam tracing the wielder's gaze: from their hand position to wherever
         *  the raycast hits (or `BEAM_GAZE_REACH` along the look direction on a miss).
         *  Shown once per fire — independent of how many commanded blocks the staff
         *  dispatched to, since the gaze is what's being commanded. */
        private fun spawnGazeBeam(serverLevel: ServerLevel, player: Player) {
            val eye = player.getEyePosition(1f)
            val look = player.getViewVector(1f)
            val end = eye.add(look.scale(BEAM_GAZE_REACH))
            val hit = serverLevel.clip(
                net.minecraft.world.level.ClipContext(
                    eye, end,
                    net.minecraft.world.level.ClipContext.Block.OUTLINE,
                    net.minecraft.world.level.ClipContext.Fluid.NONE,
                    player,
                ),
            )
            val tx = hit.location.x
            val ty = hit.location.y
            val tz = hit.location.z

            // Hand sits below + ahead of the eye. Approximates a right-hand wand grip
            // without dragging in the first/third-person model split.
            val right = look.cross(net.minecraft.world.phys.Vec3(0.0, 1.0, 0.0)).normalize()
            val ox = eye.x + right.x * 0.25 + look.x * 0.4
            val oy = eye.y - 0.4 + look.y * 0.4
            val oz = eye.z + right.z * 0.25 + look.z * 0.4

            val dx = tx - ox; val dy = ty - oy; val dz = tz - oz
            val dist = Math.sqrt(dx * dx + dy * dy + dz * dz)
            val steps = Math.min(BEAM_MAX_PARTICLES, (dist * BEAM_PARTICLES_PER_BLOCK).toInt())
            for (i in 1..steps) {
                val t = i.toDouble() / steps
                serverLevel.sendParticles(
                    DUST_BEAM,
                    ox + dx * t, oy + dy * t, oz + dz * t,
                    1, 0.0, 0.0, 0.0, 0.0,
                )
            }
        }

        /** Witch-style red dust puff at a commanded block. Ship-mounted blocks are
         *  projected through `shipToWorld` so the spark lands on the rendered block. */
        private fun spawnBlockSpark(serverLevel: ServerLevel, pos: BlockPos, ship: Ship?) {
            val tx: Double; val ty: Double; val tz: Double
            if (ship != null) {
                val v = Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
                ship.transform.shipToWorld.transformPosition(v)
                tx = v.x; ty = v.y; tz = v.z
            } else {
                tx = pos.x + 0.5; ty = pos.y + 0.5; tz = pos.z + 0.5
            }
            serverLevel.sendParticles(
                DUST_SPARK,
                tx, ty, tz,
                SPARK_COUNT, SPARK_SPREAD, SPARK_SPREAD, SPARK_SPREAD, SPARK_SPEED,
            )
        }
    }
}
