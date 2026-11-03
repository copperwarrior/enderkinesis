package org.shipwrights.enderkinesis.block

import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.TagKey
import net.minecraft.core.registries.Registries
import net.minecraft.world.Containers
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ItemUtils
import net.minecraft.world.item.Items
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.level.gameevent.GameEvent
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.blockentity.AltarOfIlluminationBlockEntity
import org.shipwrights.enderkinesis.item.UlderShardItem
import org.shipwrights.enderkinesis.registry.EKItems
import org.shipwrights.enderkinesis.registry.EKSounds
import org.shipwrights.enderkinesis.spell.Inscription
import org.shipwrights.enderkinesis.spell.Spell

class AltarOfIlluminationBlock(properties: Properties) : BaseEntityBlock(properties) {

    init {
        registerDefaultState(stateDefinition.any().setValue(INK, 0))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(INK)
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        AltarOfIlluminationBlockEntity(pos, state)

    @Deprecated("Deprecated in Java")
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    @Deprecated("Deprecated in Java")
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        SHAPE

    @Deprecated("Deprecated in Java")
    override fun use(
        state: BlockState, level: Level, pos: BlockPos,
        player: Player, hand: InteractionHand, hit: BlockHitResult,
    ): InteractionResult {
        val altar = level.getBlockEntity(pos) as? AltarOfIlluminationBlockEntity ?: return InteractionResult.PASS
        val held = player.getItemInHand(hand)
        val shard = held.item as? UlderShardItem
        val action = when {
            held.`is`(PAPER_TAG) && !altar.hasScroll -> Action.BLANK
            held.`is`(EKItems.ICHAERIC_INK_BOTTLE.get()) && state.getValue(INK) < MAX_INK -> Action.INK
            shard != null -> Action.INSCRIBE
            held.`is`(EKItems.PENFEATHER_OF_THE_ACCURSED_OWL.get()) -> Action.FINISH
            held.isEmpty && hand == InteractionHand.MAIN_HAND && !altar.ready.isEmpty -> Action.TAKE
            // Only when the off hand has nothing to do here, so it still gets its turn.
            held.isEmpty && hand == InteractionHand.MAIN_HAND && !usable(player.offhandItem, state, altar) -> Action.READOUT
            else -> return InteractionResult.PASS
        }
        if (level.isClientSide) return InteractionResult.SUCCESS
        when (action) {
            Action.BLANK -> placeBlank(level, pos, player, held, altar)
            Action.INK -> addInk(state, level, pos, player, hand)
            Action.INSCRIBE -> inscribe(level, pos, player, held, shard!!, altar)
            Action.FINISH -> finish(state, level, pos, player, altar)
            Action.TAKE -> take(level, pos, player, altar)
            Action.READOUT -> inkReadout(player, state.getValue(INK))
        }
        return InteractionResult.CONSUME
    }

    private enum class Action { BLANK, INK, INSCRIBE, FINISH, TAKE, READOUT }

    private fun usable(stack: ItemStack, state: BlockState, altar: AltarOfIlluminationBlockEntity): Boolean =
        (stack.`is`(PAPER_TAG) && !altar.hasScroll) ||
            (stack.`is`(EKItems.ICHAERIC_INK_BOTTLE.get()) && state.getValue(INK) < MAX_INK) ||
            stack.item is UlderShardItem || stack.`is`(EKItems.PENFEATHER_OF_THE_ACCURSED_OWL.get())

    private fun placeBlank(level: Level, pos: BlockPos, player: Player, held: ItemStack, altar: AltarOfIlluminationBlockEntity) {
        altar.placeBlank(held)
        if (!player.abilities.instabuild) held.shrink(1)
        play(level, pos, EKSounds.ALTAR_OF_ILLUMINATION_PAPER)
        level.gameEvent(player, GameEvent.BLOCK_CHANGE, pos)
    }

    private fun addInk(state: BlockState, level: Level, pos: BlockPos, player: Player, hand: InteractionHand) {
        val ink = state.getValue(INK) + 1
        level.setBlock(pos, state.setValue(INK, ink), UPDATE_ALL)
        player.setItemInHand(hand, ItemUtils.createFilledResult(player.getItemInHand(hand), player, ItemStack(Items.GLASS_BOTTLE)))
        level.playSound(null, pos, EKSounds.ICHAERIC_INK_JAR_POUR.get(), SoundSource.BLOCKS, 1f, 0.9f + 0.04f * ink)
        level.gameEvent(player, GameEvent.BLOCK_CHANGE, pos)
        inkReadout(player, ink)
    }

    private fun inscribe(
        level: Level, pos: BlockPos, player: Player, held: ItemStack, shard: UlderShardItem,
        altar: AltarOfIlluminationBlockEntity,
    ) {
        val outcome = altar.inscribe(shard.element)
        if (outcome == null) {
            player.displayClientMessage(Component.translatable(FULL_KEY, Inscription.MAX_GLYPHS), true)
            return
        }
        if (!player.abilities.instabuild) held.shrink(1)
        level.gameEvent(player, GameEvent.BLOCK_CHANGE, pos)
        when (outcome) {
            is Inscription.Outcome.Annihilated -> {
                play(level, pos, EKSounds.ALTAR_OF_ILLUMINATION_ANNIHILATE)
                player.displayClientMessage(
                    Component.translatable(ANNIHILATED_KEY, shard.element.displayName(), outcome.hit.displayName()), true,
                )
            }
            is Inscription.Outcome.Fused -> play(level, pos, EKSounds.ALTAR_OF_ILLUMINATION_FUSE)
            Inscription.Outcome.Inscribed -> play(level, pos, EKSounds.ALTAR_OF_ILLUMINATION_INSCRIBE)
        }
    }

    private fun finish(state: BlockState, level: Level, pos: BlockPos, player: Player, altar: AltarOfIlluminationBlockEntity) {
        val ink = state.getValue(INK)
        when {
            !altar.ready.isEmpty -> player.displayClientMessage(Component.translatable(WAITING_KEY), true)
            !altar.hasScroll -> player.displayClientMessage(Component.translatable(NO_SCROLL_KEY), true)
            altar.glyphs.isEmpty() -> player.displayClientMessage(Component.translatable(EMPTY_KEY), true)
            ink == 0 -> player.displayClientMessage(Component.translatable(NO_INK_KEY), true)
            else -> {
                val name = Spell(altar.glyphs).name()?.toComponent()
                val glyphs = altar.glyphs.size
                altar.finish(ink)
                // The bottle goes when the scroll rises out of the ink.
                level.scheduleTick(pos, this, AltarOfIlluminationBlockEntity.riseTicks(glyphs))
                play(level, pos, EKSounds.ALTAR_OF_ILLUMINATION_FINISH)
                level.gameEvent(player, GameEvent.BLOCK_CHANGE, pos)
                if (name != null) player.displayClientMessage(Component.translatable(FINISHED_KEY, name), true)
            }
        }
    }

    private fun take(level: Level, pos: BlockPos, player: Player, altar: AltarOfIlluminationBlockEntity) {
        val scroll = altar.takeReady()
        if (!player.inventory.add(scroll)) Containers.dropItemStack(level, pos.x + 0.5, pos.y + 1.4, pos.z + 0.5, scroll)
        play(level, pos, EKSounds.ALTAR_OF_ILLUMINATION_TAKE)
        level.gameEvent(player, GameEvent.BLOCK_CHANGE, pos)
    }

    private fun play(level: Level, pos: BlockPos, sound: RegistrySupplier<SoundEvent>) {
        level.playSound(null, pos, sound.get(), SoundSource.BLOCKS, 1f, 1f)
    }

    @Deprecated("Deprecated in Java")
    override fun tick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        val altar = level.getBlockEntity(pos) as? AltarOfIlluminationBlockEntity ?: return
        if (altar.takeInkDrop()) level.setBlock(pos, state.setValue(INK, (state.getValue(INK) - 1).coerceAtLeast(0)), UPDATE_ALL)
    }

    private fun inkReadout(player: Player, ink: Int) {
        player.displayClientMessage(Component.translatable(IchaericInkJarBlock.CONTENTS_KEY, ink, MAX_INK), true)
    }

    @Deprecated("Deprecated in Java")
    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, movedByPiston: Boolean) {
        if (!state.`is`(newState.block)) {
            val altar = level.getBlockEntity(pos) as? AltarOfIlluminationBlockEntity
            val drops = altar?.drops().orEmpty().toMutableList()
            // A pending finish has already spent its bottle.
            val ink = state.getValue(INK) - if (altar?.inkDropPending == true) 1 else 0
            if (ink > 0) drops += ItemStack(EKItems.ICHAERIC_INK_BOTTLE.get(), ink)
            drops.forEach { Containers.dropItemStack(level, pos.x + 0.5, pos.y + 1.0, pos.z + 0.5, it) }
        }
        @Suppress("DEPRECATION") // Vanilla still routes onRemove through the deprecated override.
        super.onRemove(state, level, pos, newState, movedByPiston)
    }

    companion object {
        const val MAX_INK = 3
        /** Each level has its own fill model. */
        val INK: IntegerProperty = IntegerProperty.create("ink", 0, MAX_INK)

        val PAPER_TAG: TagKey<Item> = TagKey.create(Registries.ITEM, EnderkinesisMod.id("altar_paper"))

        const val NO_SCROLL_KEY = "message.enderkinesis.altar_of_illumination.no_scroll"
        const val FULL_KEY = "message.enderkinesis.altar_of_illumination.full"
        const val ANNIHILATED_KEY = "message.enderkinesis.altar_of_illumination.annihilated"
        const val EMPTY_KEY = "message.enderkinesis.altar_of_illumination.empty"
        const val NO_INK_KEY = "message.enderkinesis.altar_of_illumination.no_ink"
        const val FINISHED_KEY = "message.enderkinesis.altar_of_illumination.finished"
        const val WAITING_KEY = "message.enderkinesis.altar_of_illumination.waiting"

        private val SHAPE: VoxelShape = Shapes.or(
            box(0.0, 0.0, 0.0, 16.0, 2.0, 16.0),
            box(3.0, 2.0, 3.0, 13.0, 12.0, 13.0),
            box(1.0, 12.0, 1.0, 15.0, 16.0, 15.0),
        )
    }
}
