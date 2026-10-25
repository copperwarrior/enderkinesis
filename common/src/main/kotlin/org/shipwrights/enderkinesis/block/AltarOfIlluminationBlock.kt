package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.TagKey
import net.minecraft.core.registries.Registries
import net.minecraft.world.Containers
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
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
import org.shipwrights.enderkinesis.item.MysticShardItem
import org.shipwrights.enderkinesis.registry.EKItems
import org.shipwrights.enderkinesis.spell.Inscription
import org.shipwrights.enderkinesis.spell.Spell
import org.shipwrights.enderkinesis.registry.EKSounds

/**
 * Altar of Illumination, where Mystic Scrolls are made. Right-click with:
 *
 * - paper (`#enderkinesis:altar_paper`) to lay down a blank scroll;
 *
 * Components go in any order: shards gather on the altar before the paper does, and only
 * finishing needs paper, glyphs and ink together.
 * - an Ichaeric Ink Bottle to pour it into the basin, up to [MAX_INK] bottles ([INK]);
 * - a Mystic Shard to inscribe it, resolving opposites and fusions (see [Inscription]);
 * - the Penfeather of the Accursed Owl to finish the scroll, using one bottle of ink.
 *
 * An empty hand reads out the stored ink.
 */
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
        val shard = held.item as? MysticShardItem
        val action = when {
            held.`is`(PAPER_TAG) && !altar.hasScroll -> Action.BLANK
            held.`is`(EKItems.ICHAERIC_INK_BOTTLE.get()) && state.getValue(INK) < MAX_INK -> Action.INK
            shard != null -> Action.INSCRIBE
            held.`is`(EKItems.PENFEATHER_OF_THE_ACCURSED_OWL.get()) -> Action.FINISH
            held.isEmpty && hand == InteractionHand.MAIN_HAND -> Action.READOUT
            else -> return InteractionResult.PASS
        }
        if (level.isClientSide) return InteractionResult.SUCCESS
        when (action) {
            Action.BLANK -> placeBlank(level, pos, player, held, altar)
            Action.INK -> addInk(state, level, pos, player, hand)
            Action.INSCRIBE -> inscribe(level, pos, player, held, shard!!, altar)
            Action.FINISH -> finish(state, level, pos, player, altar)
            Action.READOUT -> inkReadout(player, state.getValue(INK))
        }
        return InteractionResult.CONSUME
    }

    private enum class Action { BLANK, INK, INSCRIBE, FINISH, READOUT }

    private fun placeBlank(level: Level, pos: BlockPos, player: Player, held: ItemStack, altar: AltarOfIlluminationBlockEntity) {
        altar.placeBlank(held)
        if (!player.abilities.instabuild) held.shrink(1)
        sound(level, pos, SoundEvents.BOOK_PAGE_TURN)
        level.gameEvent(player, GameEvent.BLOCK_CHANGE, pos)
    }

    private fun addInk(state: BlockState, level: Level, pos: BlockPos, player: Player, hand: InteractionHand) {
        val ink = state.getValue(INK) + 1
        level.setBlock(pos, state.setValue(INK, ink), UPDATE_ALL)
        player.setItemInHand(hand, ItemUtils.createFilledResult(player.getItemInHand(hand), player, ItemStack(Items.GLASS_BOTTLE)))
        sound(level, pos, EKSounds.ALTAR_INK.get())
        level.gameEvent(player, GameEvent.BLOCK_CHANGE, pos)
        inkReadout(player, ink)
    }

    private fun inscribe(
        level: Level, pos: BlockPos, player: Player, held: ItemStack, shard: MysticShardItem,
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
                sound(level, pos, EKSounds.ALTAR_ANNIHILATE.get(), pitch = 1.4f)
                (level as? ServerLevel)?.sendParticles(ParticleTypes.SMOKE, pos.x + 0.5, pos.y + 1.4, pos.z + 0.5, 12, 0.2, 0.2, 0.2, 0.02)
                player.displayClientMessage(
                    Component.translatable(ANNIHILATED_KEY, shard.element.displayName(), outcome.hit.displayName()), true,
                )
            }
            is Inscription.Outcome.Fused -> sound(level, pos, EKSounds.ALTAR_FUSE.get(), pitch = 0.8f)
            Inscription.Outcome.Inscribed -> sound(level, pos, EKSounds.ALTAR_INSCRIBE.get())
        }
    }

    private fun finish(state: BlockState, level: Level, pos: BlockPos, player: Player, altar: AltarOfIlluminationBlockEntity) {
        val ink = state.getValue(INK)
        when {
            !altar.hasScroll -> player.displayClientMessage(Component.translatable(NO_SCROLL_KEY), true)
            altar.glyphs.isEmpty() -> player.displayClientMessage(Component.translatable(EMPTY_KEY), true)
            ink == 0 -> player.displayClientMessage(Component.translatable(NO_INK_KEY), true)
            else -> {
                val name = Spell(altar.glyphs).name()?.toComponent()
                val scroll = altar.finish()
                level.setBlock(pos, state.setValue(INK, ink - 1), UPDATE_ALL)
                if (!player.inventory.add(scroll)) {
                    Containers.dropItemStack(level, pos.x + 0.5, pos.y + 1.1, pos.z + 0.5, scroll)
                }
                sound(level, pos, EKSounds.ALTAR_FINISH.get())
                level.gameEvent(player, GameEvent.BLOCK_CHANGE, pos)
                if (name != null) player.displayClientMessage(Component.translatable(FINISHED_KEY, name), true)
            }
        }
    }

    private fun inkReadout(player: Player, ink: Int) {
        player.displayClientMessage(Component.translatable(IchaericInkJarBlock.CONTENTS_KEY, ink, MAX_INK), true)
    }

    private fun sound(level: Level, pos: BlockPos, sound: SoundEvent, pitch: Float = 1f) {
        level.playSound(null, pos, sound, SoundSource.BLOCKS, 1f, pitch)
    }

    @Deprecated("Deprecated in Java")
    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, movedByPiston: Boolean) {
        if (!state.`is`(newState.block)) {
            val drops = (level.getBlockEntity(pos) as? AltarOfIlluminationBlockEntity)?.drops().orEmpty().toMutableList()
            val ink = state.getValue(INK)
            if (ink > 0) drops += ItemStack(EKItems.ICHAERIC_INK_BOTTLE.get(), ink)
            drops.forEach { Containers.dropItemStack(level, pos.x + 0.5, pos.y + 1.0, pos.z + 0.5, it) }
        }
        @Suppress("DEPRECATION")
        super.onRemove(state, level, pos, newState, movedByPiston)
    }

    companion object {
        const val MAX_INK = 3
        /** Bottles of ink in the basin; each level has its own fill model. */
        val INK: IntegerProperty = IntegerProperty.create("ink", 0, MAX_INK)

        /** Items that lay down a blank scroll. */
        val PAPER_TAG: TagKey<Item> = TagKey.create(Registries.ITEM, EnderkinesisMod.id("altar_paper"))

        const val NO_SCROLL_KEY = "message.enderkinesis.altar_of_illumination.no_scroll"
        const val FULL_KEY = "message.enderkinesis.altar_of_illumination.full"
        const val ANNIHILATED_KEY = "message.enderkinesis.altar_of_illumination.annihilated"
        const val EMPTY_KEY = "message.enderkinesis.altar_of_illumination.empty"
        const val NO_INK_KEY = "message.enderkinesis.altar_of_illumination.no_ink"
        const val FINISHED_KEY = "message.enderkinesis.altar_of_illumination.finished"

        /** Base, column and top, following the model. */
        private val SHAPE: VoxelShape = Shapes.or(
            box(0.0, 0.0, 0.0, 16.0, 2.0, 16.0),
            box(3.0, 2.0, 3.0, 13.0, 12.0, 13.0),
            box(1.0, 12.0, 1.0, 15.0, 16.0, 15.0),
        )
    }
}
