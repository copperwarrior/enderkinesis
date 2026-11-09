package org.shipwrights.enderkinesis.spell

import org.valkyrienskies.mod.common.toWorldCoordinates
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
import org.shipwrights.enderkinesis.item.UlderScrollItem
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.shipwrights.enderkinesis.registry.EKSounds
import org.shipwrights.enderkinesis.util.ShipMoves
import org.valkyrienskies.mod.common.dimensionId
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID

/** A rune always casts on the next tick, so a cascade of runes waits one tick for each step and always ends. */
object RuneTriggers {

    /** [rune] is the triggered block entity, so a newer rune in the same position is not used up. */
    private class Pending(val level: ServerLevel, val pos: BlockPos, val rune: RuneBlockEntity, val spell: Spell, val owner: UUID?, val tick: Int, val facing: Direction)

    // Game thread only. Server stop clears these, so no level stays after its world.
    private val pending = ArrayList<Pending>()
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

    /** For block moves between ships that VS2 does not announce (Binding Roots merges). */
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

    fun trigger(level: ServerLevel, pos: BlockPos) {
        if (suppressed(level)) return
        val rune = level.getBlockEntity(pos) as? RuneBlockEntity ?: return
        enqueue(level, pos, rune)
    }

    fun onRemoved(level: ServerLevel, pos: BlockPos, rune: RuneBlockEntity) {
        if (suppressed(level) || removing == (level to pos)) return
        enqueue(level, pos, rune)
    }

    private fun enqueue(level: ServerLevel, pos: BlockPos, rune: RuneBlockEntity) {
        if (AbyssalPockets.isPhased(level, pos)) return
        if (!queued.add(rune)) return
        pending += Pending(level, pos.immutable(), rune, rune.spell, rune.owner, level.server.tickCount, RuneBlock.facing(rune.blockState))
    }

    private fun fire(level: ServerLevel) {
        if (pending.isEmpty()) return
        val now = level.server.tickCount
        val due = pending.filter { it.level === level && it.tick < now }
        if (due.isEmpty()) return
        pending.removeAll(due.toSet())
        for (rune in due) {
            queued.remove(rune.rune)
            if (consume(level, rune)) cast(level, rune)
        }
    }

    /** Returns false when the removal failed. A rune that stays must not cast again and again. */
    private fun consume(level: ServerLevel, rune: Pending): Boolean {
        val pos = rune.pos
        if (level.getBlockEntity(pos) !== rune.rune) return true
        val state = level.getBlockState(pos)
        if (state.block !is RuneBlock) return true
        removing = level to pos
        try {
            val left = if (state.getValue(RuneBlock.WATERLOGGED)) Blocks.WATER.defaultBlockState() else Blocks.AIR.defaultBlockState()
            return level.setBlock(pos, left, 3)
        } finally {
            removing = null
        }
    }

    /** No caster, so nothing is spared. Blood's buff goes to the owner if online. */
    private fun cast(level: ServerLevel, rune: Pending) {
        // A rune on a ship has a shipyard pos; the cast reads biomes, sky and entities in the world.
        val centre = level.toWorldCoordinates(Vec3.atCenterOf(rune.pos))
        val owner = rune.owner?.let { level.server.playerList.getPlayer(it) }
        val ctx = SpellContext(level, null, centre, rune.owner, buffRecipient = owner, nearestRecipient = false, use = SpellUse.RUNE)
        // Not an Area cast (a Void rune opens a pocket), but it shows as Area waves.
        SpellVfx.runeBurst(level, rune.pos, rune.facing, rune.spell)
        SpellSounds.play(level, centre, EKSounds.SPELL_RUNE_TRIGGER)
        SpellCasting.burst(ctx, rune.spell, centre, area = false, waves = true)
    }

    private fun drainContacts(server: MinecraftServer) {
        RuneCollisionRouter.drain { dimension, pos ->
            val level = server.allLevels.firstOrNull { it.dimensionId == dimension } ?: return@drain
            // Contact counts only when the rune is armed, as with entities (see RuneBlock.entityInside).
            val rune = level.getBlockEntity(pos) as? RuneBlockEntity ?: return@drain
            if (rune.isArmed()) trigger(level, pos)
        }
    }

    /** Cancel every block click with a scroll as accepted (`interruptTrue`), placed or not. If not,
     *  the block breaks, and the Fabric client sends the click to the server only when accepted. */
    private fun place(player: Player, hand: InteractionHand, pos: BlockPos, face: Direction): EventResult {
        val stack = player.getItemInHand(hand)
        val spell = UlderScrollItem.spellOf(stack) ?: return EventResult.pass()
        val level = player.level()
        val target = pos.relative(face)
        val host = level.getBlockState(pos)
        val there = level.getBlockState(target)
        val water = there.`is`(Blocks.WATER) && there.fluidState.isSource
        if (UlderScrollItem.coolingDown(player)) return EventResult.interruptTrue()
        if (host.isAir || host.block is RuneBlock || !(there.isAir || water)) return EventResult.interruptTrue()
        if (level !is ServerLevel) {
            UlderScrollItem.clientUsed?.invoke(player, UlderScrollItem.Companion.OtherUse.RUNE, hand)
            return EventResult.interruptTrue()
        }
        val rune = EKBlocks.RUNE.get().defaultBlockState().setValue(RuneBlock.FACING, face).setValue(RuneBlock.WATERLOGGED, water)
        val ctx = SpellContext(level, player, player.position())
        if (!ctx.setBlock(target, rune)) return EventResult.interruptTrue()
        (level.getBlockEntity(target) as? RuneBlockEntity)?.write(spell, player.uuid, host)
        SpellSounds.play(level, Vec3.atCenterOf(target), EKSounds.SPELL_RUNE_INSCRIBE)
        (player as? ServerPlayer)?.let { SpellNetwork.broadcastUsed(level, it, UlderScrollItem.Companion.OtherUse.RUNE, hand) }
        SpellVfx.inscribe(level, target, player, hand, spell)
        UlderScrollItem.spend(level, player, stack)
        UlderScrollItem.startCooldown(player)
        player.displayClientMessage(Component.translatable(PLACED_KEY), true)
        return EventResult.interruptTrue()
    }

    const val PLACED_KEY = "message.enderkinesis.rune.placed"
}
