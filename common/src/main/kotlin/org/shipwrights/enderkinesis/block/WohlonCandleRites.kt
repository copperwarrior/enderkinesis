package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CandleBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import org.shipwrights.enderkinesis.block.WohlonnogondoniaPortalRitual.PatternSlot
import org.shipwrights.enderkinesis.dimension.Wohlonnogondonia
import org.shipwrights.enderkinesis.entity.WohlonRitePedestal
import org.shipwrights.enderkinesis.registry.EKItems
import org.shipwrights.enderkinesis.registry.EKSounds

/** Candle rites lit from a center of four light blue candles; each one raises a [WohlonRitePedestal] for its offerings. */
object WohlonCandleRites {

    private const val CENTER_CANDLES = 4

    enum class Rite(pattern: List<PatternSlot>) {
        REACHING_ROOT(
            listOf(
                blue(0, -2, 1),
                blue(-2, 0, 1), blue(2, 0, 1),
                blue(0, 2, 1),
            ),
        ),
        ROLLING_WATERS(
            listOf(
                blue(-1, -3, 1), blue(1, -3, 1),
                blue(0, -2, 2),
                blue(-3, -1, 1), blue(0, -1, 3), blue(3, -1, 1),
                blue(-2, 0, 2), blue(-1, 0, 3), blue(1, 0, 3), blue(2, 0, 2),
                blue(-3, 1, 1), blue(0, 1, 3), blue(3, 1, 1),
                blue(0, 2, 2),
                blue(-1, 3, 1), blue(1, 3, 1),
            ),
        ),
        // A heptagon of gem-coloured stacks, clockwise from north, with a blue spoke in to each.
        STAFF(
            listOf(
                PatternSlot(0, -6, 4, Blocks.YELLOW_CANDLE), PatternSlot(5, -4, 4, Blocks.BLACK_CANDLE),
                PatternSlot(6, 1, 4, Blocks.GREEN_CANDLE), PatternSlot(3, 5, 4, Blocks.CYAN_CANDLE),
                PatternSlot(-3, 5, 4, Blocks.WHITE_CANDLE), PatternSlot(-6, 1, 4, Blocks.PINK_CANDLE),
                PatternSlot(-5, -4, 4, Blocks.RED_CANDLE),
                blue(0, -4, 3), blue(3, -2, 3), blue(4, 1, 3), blue(2, 4, 3),
                blue(-2, 4, 3), blue(-4, 1, 3), blue(-3, -2, 3),
                blue(0, -2, 2), blue(2, 0, 2), blue(0, 2, 2), blue(-2, 0, 2),
            ),
        );

        val rotations: List<List<PatternSlot>> = WohlonnogondoniaPortalRitual.rotations(pattern)

        fun canStart(level: ServerLevel, center: BlockPos): Boolean = when (this) {
            REACHING_ROOT -> true
            ROLLING_WATERS -> level.isRainingAt(center.above())
            STAFF -> level.dimension() == Wohlonnogondonia.LEVEL_KEY
        }

        /** True when [stack] is the next offering after [offered]. */
        fun wants(offered: List<ItemStack>, stack: ItemStack): Boolean = when (this) {
            REACHING_ROOT -> offered.isEmpty() && stack.`is`(EKItems.WEYYE_FRUIT.get())
            ROLLING_WATERS -> offered.isEmpty() && stack.`is`(Items.HEART_OF_THE_SEA)
            STAFF ->
                if (offered.isEmpty()) WohlonStaffRites.forGem(stack.item) != null
                else staffRecipe(offered)?.slots?.getOrNull(offered.size - 1)?.let(stack::`is`) == true
        }

        fun isComplete(offered: List<ItemStack>): Boolean = when (this) {
            REACHING_ROOT, ROLLING_WATERS -> offered.size == 1
            STAFF -> staffRecipe(offered)?.let { offered.size == 1 + it.slots.size } == true
        }

        /** The item the rite makes ([ItemStack.EMPTY] for none), or null when it has nothing to act on and gives the offerings back. */
        fun resolve(level: ServerLevel, center: BlockPos, offered: List<ItemStack>): ItemStack? = when (this) {
            REACHING_ROOT -> WohlonnogondoniaPortalManager.nearestPortal(level, center)
                ?.let { WohlonReachingRoot.start(level, it, center); ItemStack.EMPTY }
            ROLLING_WATERS -> if (WohlonRollingWaters.start(level, center)) ItemStack.EMPTY else null
            STAFF -> staffRecipe(offered)?.let { ItemStack(it.result) }
        }
    }

    fun staffRecipe(offered: List<ItemStack>): WohlonStaffRites.Recipe? =
        offered.firstOrNull()?.let { WohlonStaffRites.forGem(it.item) }

    private fun blue(dx: Int, dz: Int, count: Int) = PatternSlot(dx, dz, count, Blocks.BLUE_CANDLE)

    fun isLitCenter(state: BlockState): Boolean =
        state.`is`(Blocks.LIGHT_BLUE_CANDLE) &&
            state.getValue(CandleBlock.CANDLES) == CENTER_CANDLES &&
            state.getValue(CandleBlock.LIT)

    @JvmStatic
    fun onCandlePlaced(level: Level, pos: BlockPos, state: BlockState, oldState: BlockState) {
        if (level.isClientSide || !oldState.`is`(Blocks.LIGHT_BLUE_CANDLE)) return
        if (oldState.getValue(CandleBlock.LIT) || !isLitCenter(state)) return
        val serverLevel = level as? ServerLevel ?: return
        val rite = Rite.entries.firstOrNull { WohlonnogondoniaPortalRitual.matchesAny(serverLevel, pos, it.rotations) } ?: return
        if (!rite.canStart(serverLevel, pos)) return
        if (serverLevel.getEntitiesOfClass(WohlonRitePedestal::class.java, AABB(pos)).isNotEmpty()) return
        serverLevel.addFreshEntity(WohlonRitePedestal(serverLevel, pos, rite))
        serverLevel.playSound(
            null, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5,
            EKSounds.WOHLON_RITUAL_IGNITE.get(), SoundSource.BLOCKS, 2f, 1.2f,
        )
    }
}
