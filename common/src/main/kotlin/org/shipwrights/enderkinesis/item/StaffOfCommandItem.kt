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
import org.valkyrienskies.mod.common.getShipManagingPos

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
        if (be !is Commandable && !VanillaCommandables.supports(level.getBlockState(blockPos))) {
            return InteractionResult.PASS
        }

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
        val state = level.getBlockState(blockPos)
        val blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.block)
        val added = togglePosition(stack, blockPos, dim, blockId)
        val blockName = state.block.name
        val msg = if (added) "item.enderkinesis.staff_of_command.added"
                  else "item.enderkinesis.staff_of_command.removed"
        player.displayClientMessage(Component.translatable(msg, blockName), true)
        return InteractionResult.sidedSuccess(level.isClientSide)
    }

    /** Held-staff hygiene: every 5 s (and on every dispatch) verify each listed
     *  block position still holds the block TYPE it was added as — broken, replaced,
     *  or moved blocks invalidate their entries instead of haunting the wheel. */
    override fun inventoryTick(
        stack: ItemStack,
        level: Level,
        entity: net.minecraft.world.entity.Entity,
        slotId: Int,
        isSelected: Boolean,
    ) {
        super.inventoryTick(stack, level, entity, slotId, isSelected)
        if (!isSelected || level !is ServerLevel) return
        if (level.gameTime % VALIDATE_INTERVAL_TICKS != 0L) return
        validateEntries(level, stack)
    }

    /** Staff-click a Constriliar → toggle it in the command list (mirrors block adds).
     *  Reached only because the entity's mobInteract PASSes on staff clicks — vanilla
     *  calls mobInteract before ItemStack.interactLivingEntity. */
    override fun interactLivingEntity(
        stack: ItemStack,
        player: Player,
        target: net.minecraft.world.entity.LivingEntity,
        hand: InteractionHand,
    ): InteractionResult {
        val level = player.level()
        // Shulkonaut: staff-click TRANSFERS the staff's commanded-block list to it.
        if (target is org.shipwrights.enderkinesis.entity.ShulkonautEntity) {
            if (level.isClientSide) return InteractionResult.SUCCESS
            val heldStaff = player.getItemInHand(hand)
            val entries = readEntries(heldStaff)
            val entityEntries = readEntityEntries(heldStaff)
            if (entries.isEmpty() && entityEntries.isEmpty()) {
                player.displayClientMessage(
                    Component.translatable("item.enderkinesis.staff_of_command.nothing_to_transfer"), true,
                )
                return InteractionResult.SUCCESS
            }
            val added = target.receiveCommandedBlocks(entries) +
                target.receiveCommandedEntities(entityEntries)
            writeEntries(heldStaff, emptyList())
            writeEntityEntries(heldStaff, emptyList())
            player.displayClientMessage(
                Component.translatable(
                    "item.enderkinesis.staff_of_command.transferred", added, target.commandedCount(),
                ),
                true,
            )
            return InteractionResult.SUCCESS
        }
        if (target !is org.shipwrights.enderkinesis.entity.ConstriliarEntity) return InteractionResult.PASS
        if (level.isClientSide) return InteractionResult.SUCCESS
        // NOT the `stack` param: in creative, Player.interactOn passes a COPY of the
        // held stack here, so NBT written to it is discarded. Toggle the real one.
        val heldStack = player.getItemInHand(hand)
        val typeId = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(target.type)
        val added = toggleEntity(heldStack, target.uuid, level.dimension().location(), typeId)
        val msg = if (added) "item.enderkinesis.staff_of_command.added"
                  else "item.enderkinesis.staff_of_command.removed"
        player.displayClientMessage(Component.translatable(msg, target.name), true)
        return InteractionResult.SUCCESS
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
        val fired = dispatch(player, level, stack, contextStack, aim = false)
        if (fired > 0) {
            player.displayClientMessage(
                Component.translatable("item.enderkinesis.staff_of_command.fired", fired), true,
            )
        }
        return InteractionResultHolder.sidedSuccess(stack, false)
    }

    override fun appendHoverText(stack: ItemStack, level: Level?, tooltip: MutableList<Component>, flag: TooltipFlag) {
        val count = readEntries(stack).size + readEntityEntries(stack).size
        tooltip.add(Component.translatable("item.enderkinesis.staff_of_command.tooltip.count", count))
    }

    companion object {
        /** Server-side event hooks that can't ride the Item overrides: end
         *  crystals aren't LivingEntities, so their add-toggle comes through
         *  Architectury's INTERACT_ENTITY instead of [interactLivingEntity]. */
        fun initEvents() {
            dev.architectury.event.events.common.InteractionEvent.INTERACT_ENTITY.register { player, entity, hand ->
                val stack = player.getItemInHand(hand)
                if (stack.item !is StaffOfCommandItem) return@register dev.architectury.event.EventResult.pass()
                if (entity !is net.minecraft.world.entity.boss.enderdragon.EndCrystal) {
                    return@register dev.architectury.event.EventResult.pass()
                }
                val level = player.level()
                if (level.isClientSide) return@register dev.architectury.event.EventResult.interruptTrue()
                val typeId = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.type)
                val added = toggleEntity(stack, entity.uuid, level.dimension().location(), typeId)
                val msg = if (added) "item.enderkinesis.staff_of_command.added"
                          else "item.enderkinesis.staff_of_command.removed"
                player.displayClientMessage(Component.translatable(msg, entity.name), true)
                dev.architectury.event.EventResult.interruptTrue()
            }
        }

        const val ENTRIES_TAG: String = "CommandedBlocks"
        const val ENTITY_ENTRIES_TAG: String = "CommandedEntities"
        const val POS_TAG: String = "Pos"
        const val DIM_TAG: String = "Dim"
        const val UUID_TAG: String = "Uuid"
        const val BLOCK_TAG: String = "Block"
        const val TYPE_TAG: String = "Type"
        const val SELECTED_KEY_TAG: String = "SelectedKey"

        /** Wheel keys whose AIM handler emits its own actionbar status —
         *  [CommandNetwork]'s generic "Aimed N construct(s)" overlay is
         *  suppressed for these so the device's message survives. */
        val SELF_MESSAGING_KEYS: Set<String> = setOf(
            "${org.shipwrights.enderkinesis.EnderkinesisMod.MOD_ID}:ender_astrolabe",
        )

        /** Entity id of the constriliar — the wheel key its entries group
         *  under, and the migration default for pre-[TYPE_TAG] entries. */
        val CONSTRILIAR_ID: ResourceLocation =
            ResourceLocation(org.shipwrights.enderkinesis.EnderkinesisMod.MOD_ID,
                org.shipwrights.enderkinesis.entity.ConstriliarEntity.ID_PATH)

        /** The wheel's slots: one per distinct commandable block type present
         *  in the staff's list (first-add order, keyed by block id), then one
         *  per distinct commanded entity type (keyed by entity id). Empty
         *  list = nothing commanded = no wheel. */
        fun availableKeys(stack: ItemStack): List<String> {
            val keys = ArrayList<String>()
            for (entry in readEntries(stack)) {
                val key = entry.blockId?.toString() ?: continue
                if (key !in keys) keys.add(key)
            }
            for (entry in readEntityEntries(stack)) {
                val key = entry.typeId.toString()
                if (key !in keys) keys.add(key)
            }
            return keys
        }

        /** Selected key, snapped back into [availableKeys] when the stored one
         *  no longer exists (its last entry was removed). Null when the list is
         *  empty. Read-only — the snap isn't written back; [cycleSelected] and
         *  dispatch both re-derive it the same way. */
        fun getSelectedKey(stack: ItemStack): String? {
            val keys = availableKeys(stack)
            if (keys.isEmpty()) return null
            val stored = stack.tag?.getString(SELECTED_KEY_TAG)
            return if (stored != null && stored in keys) stored else keys.first()
        }

        fun cycleSelected(stack: ItemStack, delta: Int) {
            val keys = availableKeys(stack)
            if (keys.size < 2) return
            val current = keys.indexOf(getSelectedKey(stack)).coerceAtLeast(0)
            val next = keys[(((current + delta) % keys.size) + keys.size) % keys.size]
            stack.orCreateTag.putString(SELECTED_KEY_TAG, next)
        }

        /** Shared dispatch loop for both clicks. `aim = true` is the left-click
         *  "Aim To" (devices orient toward the gaze; note blocks cycle);
         *  `aim = false` is the right-click "Go To / Trigger" (devices actuate;
         *  constriliars walk). Only entries matching the staff's selected
         *  wheel key fire — block ids and entity type ids share one key space,
         *  so both lists are scanned against the same key. Returns how many
         *  devices were reached. */
        /** Drop entries whose position no longer holds their stamped block type.
         *  Unverifiable entries (other dimension, unloaded chunk) are kept — absence
         *  of evidence isn't a broken block. Legacy null-stamp entries get stamped
         *  from the live block when it's still commandable, dropped otherwise. */
        fun validateEntries(level: ServerLevel, stack: ItemStack) {
            val entries = readEntries(stack)
            if (entries.isEmpty()) return
            val dim = level.dimension().location()
            var changed = false
            val kept = ArrayList<Entry>(entries.size)
            for (e in entries) {
                if (e.dim != dim || !level.hasChunkAt(e.pos)) {
                    kept.add(e)
                    continue
                }
                val state = level.getBlockState(e.pos)
                val actual = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.block)
                when {
                    e.blockId == actual -> kept.add(e)
                    e.blockId == null -> {
                        val stillCommandable = level.getBlockEntity(e.pos) is Commandable ||
                            VanillaCommandables.supports(state)
                        if (stillCommandable) kept.add(Entry(e.pos, e.dim, actual))
                        changed = true
                    }
                    else -> changed = true   // block replaced/broken — invalidate
                }
            }
            if (changed) writeEntries(stack, kept)
        }

        fun dispatch(
            player: Player, level: Level, stack: ItemStack, contextStack: ItemStack, aim: Boolean,
        ): Int {
            (level as? ServerLevel)?.let { validateEntries(it, stack) }
            val selectedKey = getSelectedKey(stack) ?: return 0
            val currentDim = level.dimension().location()
            val serverLevel = level as? ServerLevel
            var fired = 0
            for (entry in readEntries(stack)) {
                if (entry.blockId?.toString() != selectedKey) continue      // other family
                if (entry.dim != currentDim) continue                       // other dimension
                val ship = level.getLoadedShipManagingPos(entry.pos)
                if (!isReachable(player, level, entry.pos, ship)) continue  // out of range
                val be = level.getBlockEntity(entry.pos)
                val handled = when {
                    be is Commandable -> {
                        if (aim) be.executeAimCommand(player, ship, contextStack)
                        else be.executeStaffCommand(player, ship, contextStack)
                        true
                    }
                    serverLevel != null -> {
                        val state = level.getBlockState(entry.pos)
                        if (!VanillaCommandables.supports(state)) false     // block removed / replaced
                        else if (aim) VanillaCommandables.aim(player, serverLevel, entry.pos, state)
                        else VanillaCommandables.trigger(player, serverLevel, entry.pos, state)
                    }
                    else -> false
                }
                if (!handled) continue
                if (serverLevel != null) spawnBlockSpark(serverLevel, entry.pos, ship)
                fired++
            }
            if (serverLevel != null) {
                var gaze: GazeTarget? = null
                var moveSlot = 0
                for (entry in readEntityEntries(stack)) {
                    if (entry.typeId.toString() != selectedKey) continue
                    if (entry.dim != currentDim) continue
                    val entity = serverLevel.getEntity(entry.uuid) ?: continue
                    when (entity) {
                        is org.shipwrights.enderkinesis.entity.ConstriliarEntity -> {
                            // Aim = turn toward the gaze hit (TRACKING it if it's a
                            // ship block); trigger = walk to it (a sitting one only
                            // turns — entity-side).
                            val target = gaze ?: gazeHit(serverLevel, player).also { gaze = it }
                            if (aim) {
                                val local = target.shipLocal
                                if (local != null) {
                                    entity.commandFaceTowardShipPoint(target.shipId, local, target.pos)
                                } else {
                                    entity.commandFaceToward(target.pos)
                                }
                            } else {
                                // Spread each commanded walker to its own slot around the
                                // gaze point (tangent-plane phyllotaxis) so they gather
                                // beside each other instead of stacking on one block.
                                entity.commandMoveTo(constriliarSlot(moveSlot++, target.pos, target.normal))
                            }
                        }
                        is net.minecraft.world.entity.boss.enderdragon.EndCrystal -> {
                            if (aim) continue
                            // Generic hurt routes through EndCrystal.hurt's
                            // remove-and-explode branch — the vanilla "shot the
                            // crystal" detonation.
                            entity.hurt(serverLevel.damageSources().generic(), 1.0f)
                        }
                        else -> continue
                    }
                    spawnBlockSpark(serverLevel, entity.blockPosition(), null)
                    fired++
                }
            }
            if (fired > 0 && serverLevel != null) spawnGazeBeam(serverLevel, player)
            return fired
        }

        /** [blockId] is stamped at add-time so the wheel (client) and the type
         *  filter can group entries without loading the target chunk. Null only
         *  for entries written before the tag existed — those never match a
         *  wheel slot; re-add the block to migrate. */
        data class Entry(val pos: BlockPos, val dim: ResourceLocation, val blockId: ResourceLocation?)

        /** [typeId] follows the same at-add-time stamping as [Entry.blockId].
         *  Entries from before the tag existed default to the constriliar id —
         *  they were the only commandable entity back then. */
        data class EntityEntry(val uuid: java.util.UUID, val dim: ResourceLocation, val typeId: ResourceLocation)

        fun readEntityEntries(stack: ItemStack): List<EntityEntry> {
            val tag = stack.tag ?: return emptyList()
            val list = tag.getList(ENTITY_ENTRIES_TAG, Tag.TAG_COMPOUND.toInt())
            val out = ArrayList<EntityEntry>(list.size)
            for (i in 0 until list.size) {
                val c = list.getCompound(i)
                if (!c.hasUUID(UUID_TAG)) continue
                val dim = ResourceLocation.tryParse(c.getString(DIM_TAG)) ?: continue
                val typeId = if (c.contains(TYPE_TAG, Tag.TAG_STRING.toInt())) {
                    ResourceLocation.tryParse(c.getString(TYPE_TAG))
                } else null
                out.add(EntityEntry(c.getUUID(UUID_TAG), dim, typeId ?: CONSTRILIAR_ID))
            }
            return out
        }

        internal fun writeEntityEntries(stack: ItemStack, entries: List<EntityEntry>) {
            val tag = stack.orCreateTag
            val list = ListTag()
            for (e in entries) {
                val c = CompoundTag()
                c.putUUID(UUID_TAG, e.uuid)
                c.putString(DIM_TAG, e.dim.toString())
                c.putString(TYPE_TAG, e.typeId.toString())
                list.add(c)
            }
            tag.put(ENTITY_ENTRIES_TAG, list)
        }

        internal fun toggleEntity(
            stack: ItemStack, uuid: java.util.UUID, dim: ResourceLocation, typeId: ResourceLocation,
        ): Boolean {
            val entries = readEntityEntries(stack).toMutableList()
            val existing = entries.indexOfFirst { it.uuid == uuid && it.dim == dim }
            val added: Boolean
            if (existing >= 0) {
                entries.removeAt(existing); added = false
            } else {
                entries.add(EntityEntry(uuid, dim, typeId)); added = true
            }
            writeEntityEntries(stack, entries)
            return added
        }

        /** Where the wielder's gaze lands — the "go there" spot for commanded entities. */
        /** Gaze hit with ship classification: [shipId]/[shipLocal] set when the ray
         *  landed on a SHIP block (VS2's clip is mixed-frame: .location WORLD,
         *  .blockPos SHIPYARD), so consumers can TRACK the block through the ship's
         *  motion instead of latching where it used to be. */
        class GazeTarget(
            val pos: net.minecraft.world.phys.Vec3,
            val shipId: Long,
            val shipLocal: org.joml.Vector3d?,
            val normal: net.minecraft.world.phys.Vec3,
        )

        private fun gazeHit(serverLevel: ServerLevel, player: Player): GazeTarget {
            val eye = player.getEyePosition(1f)
            val look = player.getViewVector(1f)
            // The gaze ends at the edge of the loaded area; a server clip loads chunks.
            val reach = org.shipwrights.enderkinesis.util.LoadedRay.range(serverLevel, eye, look, BEAM_GAZE_REACH)
            val end = eye.add(look.scale(reach))
            val hit = serverLevel.clip(
                net.minecraft.world.level.ClipContext(
                    eye, end,
                    net.minecraft.world.level.ClipContext.Block.OUTLINE,
                    net.minecraft.world.level.ClipContext.Fluid.NONE,
                    player,
                ),
            )
            val normal = if (hit.type == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                val d = hit.direction
                net.minecraft.world.phys.Vec3(d.stepX.toDouble(), d.stepY.toDouble(), d.stepZ.toDouble())
            } else {
                net.minecraft.world.phys.Vec3(0.0, 1.0, 0.0)
            }
            if (hit.type == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                val ship = serverLevel.getShipManagingPos(hit.blockPos)
                if (ship != null) {
                    val local = ship.transform.worldToShip.transformPosition(
                        org.joml.Vector3d(hit.location.x, hit.location.y, hit.location.z),
                    )
                    return GazeTarget(hit.location, ship.id, local, normal)
                }
            }
            return GazeTarget(hit.location, -1L, null, normal)
        }

        /** Slot [index] of a phyllotaxis spiral around [center], laid out in the tangent
         *  plane of the surface [normal] so slots stay on the walkable surface. Slot 0 is
         *  the centre; radius grows as √index so any count packs evenly. */
        private fun constriliarSlot(
            index: Int, center: net.minecraft.world.phys.Vec3, normal: net.minecraft.world.phys.Vec3,
        ): net.minecraft.world.phys.Vec3 {
            if (index == 0) return center
            val n = org.joml.Vector3d(normal.x, normal.y, normal.z)
            if (n.lengthSquared() < 1.0e-6) n.set(0.0, 1.0, 0.0) else n.normalize()
            val ref = if (Math.abs(n.y) > 0.9) org.joml.Vector3d(1.0, 0.0, 0.0) else org.joml.Vector3d(0.0, 1.0, 0.0)
            val t1 = org.joml.Vector3d(ref).cross(n).normalize()
            val t2 = org.joml.Vector3d(n).cross(t1).normalize()
            val angle = index * GOLDEN_ANGLE
            val r = SLOT_SPACING * Math.sqrt(index.toDouble())
            val cx = Math.cos(angle) * r
            val cz = Math.sin(angle) * r
            return center.add(t1.x * cx + t2.x * cz, t1.y * cx + t2.y * cz, t1.z * cx + t2.z * cz)
        }

        fun readEntries(stack: ItemStack): List<Entry> {
            val tag = stack.tag ?: return emptyList()
            val list = tag.getList(ENTRIES_TAG, Tag.TAG_COMPOUND.toInt())
            val out = ArrayList<Entry>(list.size)
            for (i in 0 until list.size) {
                val c = list.getCompound(i)
                val pos = BlockPos.of(c.getLong(POS_TAG))
                val dim = ResourceLocation.tryParse(c.getString(DIM_TAG)) ?: continue
                val blockId = if (c.contains(BLOCK_TAG, Tag.TAG_STRING.toInt())) {
                    ResourceLocation.tryParse(c.getString(BLOCK_TAG))
                } else null
                out.add(Entry(pos, dim, blockId))
            }
            return out
        }

        /** Held-staff validation cadence (5 s). */
        private const val VALIDATE_INTERVAL_TICKS = 100L

        internal fun writeEntries(stack: ItemStack, entries: List<Entry>) {
            val tag = stack.orCreateTag
            val list = ListTag()
            for (e in entries) {
                val c = CompoundTag()
                c.putLong(POS_TAG, e.pos.asLong())
                c.putString(DIM_TAG, e.dim.toString())
                e.blockId?.let { c.putString(BLOCK_TAG, it.toString()) }
                list.add(c)
            }
            tag.put(ENTRIES_TAG, list)
        }

        /** Add `(pos, dim)` if absent, remove it if present. Returns `true` when added. */
        private fun togglePosition(
            stack: ItemStack, pos: BlockPos, dim: ResourceLocation, blockId: ResourceLocation,
        ): Boolean {
            val entries = readEntries(stack).toMutableList()
            val existing = entries.indexOfFirst { it.pos == pos && it.dim == dim }
            val added: Boolean
            if (existing >= 0) {
                entries.removeAt(existing); added = false
            } else {
                entries.add(Entry(pos, dim, blockId)); added = true
            }
            writeEntries(stack, entries)
            return added
        }

        private fun clearEntries(stack: ItemStack) {
            val tag = stack.tag ?: return
            tag.remove(ENTRIES_TAG)
            tag.remove(ENTITY_ENTRIES_TAG)
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
        /** Formation spread for a group "go there": golden-angle spiral, one footprint apart. */
        private const val GOLDEN_ANGLE = 2.399963229728653
        private const val SLOT_SPACING = 1.6
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
            val reach = org.shipwrights.enderkinesis.util.LoadedRay.range(serverLevel, eye, look, BEAM_GAZE_REACH)
            val end = eye.add(look.scale(reach))
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
        internal fun spawnBlockSpark(serverLevel: ServerLevel, pos: BlockPos, ship: Ship?) {
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
