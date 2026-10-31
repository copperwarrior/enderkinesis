package org.shipwrights.enderkinesis.spell

import dev.architectury.event.EventResult
import dev.architectury.event.events.common.InteractionEvent
import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.TickEvent
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.block.RuneBlock
import org.shipwrights.enderkinesis.blockentity.RuneBlockEntity
import org.shipwrights.enderkinesis.item.MysticScrollItem
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.shipwrights.enderkinesis.util.ShipMoves
import org.valkyrienskies.mod.common.dimensionId
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID

/**
 * Places runes and sets them off. A triggered rune always casts on the next tick, never in
 * the tick that set it off, so a rune set off by another spell (a cascade) waits a tick;
 * as each rune is used once, every cascade ends.
 *
 * Moving blocks between ships removes and re-places them. [suppressMoves] (and VS2's own
 * moves, via [ShipMoves.isMovingBlocks]) keeps that from setting runes off. A rune phased by
 * an Abyssal Pocket never goes off, however it's touched.
 *
 * Writing a rune shares the scrolls' cooldown with Area casts and imbuing (see
 * [MysticScrollItem.startCooldown]).
 */
object RuneTriggers {

    /** A rune set off, to cast next tick. [rune] is the block entity that was set off, so
     *  only that rune is used up, never a newer one written in its place since. */
    private class Pending(val level: ServerLevel, val pos: BlockPos, val rune: RuneBlockEntity, val spell: Spell, val owner: UUID?, val tick: Int, val facing: Direction)

    // Game thread only. Cleared when the server stops, so no level outlives its world.
    private val pending = ArrayList<Pending>()
    /** The runes in [pending], by identity: each is set off once. */
    private val queued: MutableSet<RuneBlockEntity> = Collections.newSetFromMap(IdentityHashMap())
    private var removing: Pair<ServerLevel, BlockPos>? = null
    private var suppressedLevel: ServerLevel? = null

    fun init() {
        InteractionEvent.LEFT_CLICK_BLOCK.register { player, hand, pos, face -> place(player, hand, pos, face) }
        TickEvent.SERVER_LEVEL_PRE.register(TickEvent.ServerLevelTick(::fire))
        TickEvent.SERVER_PRE.register(TickEvent.Server(::drainContacts))
        LifecycleEvent.SERVER_STOPPING.register {
            pending.clear()
            queued.clear()
            removing = null
            suppressedLevel = null
        }
    }

    /** Runs [action] with rune triggers off in [level], for moves of blocks between ships
     *  that VS2 doesn't announce (Binding Roots block merges). */
    fun <T> suppressMoves(level: ServerLevel, action: () -> T): T {
        val outer = suppressedLevel
        suppressedLevel = level
        try {
            return action()
        } finally {
            suppressedLevel = outer
        }
    }

    private fun suppressed(level: ServerLevel): Boolean = level === suppressedLevel || ShipMoves.isMovingBlocks(level)

    /** Sets off the rune at [pos]; it casts next tick. */
    fun trigger(level: ServerLevel, pos: BlockPos) {
        if (suppressed(level)) return
        val rune = level.getBlockEntity(pos) as? RuneBlockEntity ?: return
        enqueue(level, pos, rune)
    }

    /** The rune at [pos] is being removed (mined, exploded, pushed, replaced). */
    fun onRemoved(level: ServerLevel, pos: BlockPos, rune: RuneBlockEntity) {
        if (suppressed(level) || removing == (level to pos)) return
        enqueue(level, pos, rune)
    }

    private fun enqueue(level: ServerLevel, pos: BlockPos, rune: RuneBlockEntity) {
        // Phased with its host by an Abyssal Pocket: out of the world, so out of reach.
        if (AbyssalPockets.isPhased(level, pos)) return
        if (!queued.add(rune)) return
        pending += Pending(level, pos.immutable(), rune, rune.spell, rune.owner, level.server.tickCount, RuneBlock.facing(rune.blockState))
    }

    /** Casts the runes set off before this tick. Runes set off while casting wait a tick. */
    private fun fire(level: ServerLevel) {
        if (pending.isEmpty()) return
        val now = level.server.tickCount
        val due = pending.filter { it.level === level && it.tick < now }
        if (due.isEmpty()) return
        pending.removeAll(due.toSet())
        for (rune in due) {
            queued.remove(rune.rune)
            consume(level, rune)
            cast(level, rune)
        }
    }

    /** Removes the rune that was set off, if it's still standing, without setting it off
     *  again; its water stays. A rune since written in its place is left alone. */
    private fun consume(level: ServerLevel, rune: Pending) {
        val pos = rune.pos
        if (level.getBlockEntity(pos) !== rune.rune) return
        val state = level.getBlockState(pos)
        if (state.block !is RuneBlock) return
        removing = level to pos
        try {
            val left = if (state.getValue(RuneBlock.WATERLOGGED)) Blocks.WATER.defaultBlockState() else Blocks.AIR.defaultBlockState()
            level.setBlock(pos, left, 3)
        } finally {
            removing = null
        }
    }

    /** An Area burst around the rune. No caster, so nothing is spared; Blood's buff goes to
     *  the owner if they're online, and otherwise fizzles. */
    private fun cast(level: ServerLevel, rune: Pending) {
        val centre = Vec3.atCenterOf(rune.pos)
        val owner = rune.owner?.let { level.server.playerList.getPlayer(it) }
        val ctx = SpellContext(level, null, centre, rune.owner, buffRecipient = owner, nearestRecipient = false, use = SpellUse.RUNE)
        // Not an Area cast (a Void rune opens a pocket), but it goes off as Area waves from the rune.
        SpellVfx.runeBurst(level, rune.pos, rune.facing, rune.spell)
        SpellCasting.burst(ctx, rune.spell, centre, area = false, waves = true)
    }

    private fun drainContacts(server: MinecraftServer) {
        RuneCollisionRouter.drain { dimension, pos ->
            val level = server.allLevels.firstOrNull { it.dimensionId == dimension } ?: return@drain
            // Contact counts once the rune is armed, as with entities (see RuneBlock.entityInside).
            val rune = level.getBlockEntity(pos) as? RuneBlockEntity ?: return@drain
            if (rune.isArmed()) trigger(level, pos)
        }
    }

    // -----------------------------------------------------------------------
    // Placement: left-click a block face with a scroll
    // -----------------------------------------------------------------------

    /** Writes the scroll in [hand] as a rune on [face] of the block at [pos]. The rune goes in
     *  the space in front of the face, which must be air or still source water.
     *
     *  A spell scroll never mines, so every block click with one is cancelled, placed or not.
     *  Otherwise a face with no room, or creative's repeated clicks once the rune is down,
     *  would break the block. It must be cancelled as accepted (`interruptTrue`): Fabric's
     *  client only sends the click to the server, where the rune is placed, when it's
     *  accepted. Forge sends it either way.
     *
     *  While the scrolls are cooling down (see [MysticScrollItem.coolingDown]) nothing is
     *  written and the arm doesn't move, but the click is still cancelled. */
    private fun place(player: Player, hand: InteractionHand, pos: BlockPos, face: Direction): EventResult {
        val stack = player.getItemInHand(hand)
        val spell = MysticScrollItem.spellOf(stack) ?: return EventResult.pass()
        val level = player.level()
        val target = pos.relative(face)
        val host = level.getBlockState(pos)
        val there = level.getBlockState(target)
        val water = there.`is`(Blocks.WATER) && there.fluidState.isSource
        if (MysticScrollItem.coolingDown(player)) return EventResult.interruptTrue()
        if (host.isAir || host.block is RuneBlock || !(there.isAir || water)) return EventResult.interruptTrue()
        if (level !is ServerLevel) {
            MysticScrollItem.clientUsed?.invoke(player, MysticScrollItem.Companion.OtherUse.RUNE, hand)
            return EventResult.interruptTrue()
        }
        val rune = EKBlocks.RUNE.get().defaultBlockState().setValue(RuneBlock.FACING, face).setValue(RuneBlock.WATERLOGGED, water)
        val ctx = SpellContext(level, player, player.position())
        if (!ctx.setBlock(target, rune)) return EventResult.interruptTrue()
        (level.getBlockEntity(target) as? RuneBlockEntity)?.write(spell, player.uuid, host)
        (player as? ServerPlayer)?.let { SpellNetwork.broadcastUsed(level, it, MysticScrollItem.Companion.OtherUse.RUNE, hand) }
        SpellVfx.inscribe(level, target, player, hand, spell)
        MysticScrollItem.spend(level, player, stack)
        MysticScrollItem.startCooldown(player)
        player.displayClientMessage(Component.translatable(PLACED_KEY), true)
        return EventResult.interruptTrue()
    }

    const val PLACED_KEY = "message.enderkinesis.rune.placed"
}
