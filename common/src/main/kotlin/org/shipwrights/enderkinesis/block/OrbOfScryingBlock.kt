package org.shipwrights.enderkinesis.block

import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.StringRepresentable
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.WoolCarpetBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DirectionProperty
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape
import org.joml.Vector3d
import org.shipwrights.enderkinesis.blockentity.OrbOfScryingBlockEntity
import org.shipwrights.enderkinesis.scrying.ScryingOrbRegistry
import org.shipwrights.enderkinesis.scrying.ScryingSessionManager
import org.valkyrienskies.mod.common.getShipManagingPos

/**
 * Orb of Scrying — remote-viewing companion block to the Orb of Linking.
 *
 *  - **Persistent registry.** Each placed orb is added to a per-dimension
 *    [ScryingOrbRegistry] SavedData on placement and removed on break. The registry
 *    serializes positions to disk independent of chunk-load state — every placed orb
 *    is findable from any source regardless of where players are. No persistent
 *    chunk-force; the orb's chunk only gets loaded on demand when a session targets it
 *    (via [ScryingSessionManager]'s session-scoped tickets).
 *  - **Remote view on right-click.** Sweeps for OTHER scrying orbs in the player's look
 *    direction and switches the camera there.
 *  - **Carpet cover.** Right-click with a coloured carpet to drape it over the orb; the
 *    blockstate's [CARPET] property selects the matching `orb_link_scrying_covered_<color>`
 *    model and the BER skips drawing the orb/haze. Right-clicking a covered orb with
 *    anything else pops the carpet back out and uncovers it. A covered orb cannot be used
 *    to scry — the carpet is an opt-in privacy gate so other players can't peek through.
 */
class OrbOfScryingBlock(properties: BlockBehaviour.Properties) : Block(properties), EntityBlock {

    init {
        registerDefaultState(
            stateDefinition.any()
                .setValue(FACING, Direction.DOWN)
                .setValue(CARPET, ScryingCarpet.NONE)
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING, CARPET)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? =
        defaultBlockState()
            .setValue(FACING, context.clickedFace.opposite)
            .setValue(CARPET, ScryingCarpet.NONE)

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        OrbOfScryingBlockEntity(pos, state)

    override fun getShape(
        state: BlockState, level: BlockGetter, pos: BlockPos, ctx: CollisionContext,
    ): VoxelShape = when (state.getValue(FACING)) {
        Direction.DOWN -> SHAPE_DOWN
        Direction.UP -> SHAPE_UP
        Direction.NORTH -> SHAPE_NORTH
        Direction.SOUTH -> SHAPE_SOUTH
        Direction.EAST -> SHAPE_EAST
        Direction.WEST -> SHAPE_WEST
    }

    /** Right-click priority:
     *  1. **Covered + anything**: pop the carpet, uncover. (Even a non-carpet click — the
     *     intent is "remove the cover"; you have to uncover before you can scry.)
     *  2. **Uncovered + carpet**: cover with that colour, consume one carpet.
     *  3. **Uncovered + non-carpet**: original scry sweep. */
    @Deprecated("Deprecated in Java")
    override fun use(
        state: BlockState, level: Level, pos: BlockPos,
        player: Player, hand: InteractionHand, hit: BlockHitResult,
    ): InteractionResult {
        val currentCarpet = state.getValue(CARPET)
        val held = player.getItemInHand(hand)

        if (currentCarpet != ScryingCarpet.NONE) {
            if (!level.isClientSide) {
                val server = level as ServerLevel
                popResource(server, pos, ItemStack(currentCarpet.carpetItem()))
                level.setBlock(pos, state.setValue(CARPET, ScryingCarpet.NONE), UPDATE_ALL)
                level.playSound(null, pos, SoundEvents.WOOL_BREAK, SoundSource.BLOCKS, 0.6f, 1.0f)
            }
            return InteractionResult.sidedSuccess(level.isClientSide)
        }

        val carpetBlock = (held.item as? BlockItem)?.block as? WoolCarpetBlock
        if (carpetBlock != null) {
            if (!level.isClientSide) {
                val newCarpet = ScryingCarpet.fromDye(carpetBlock.color)
                level.setBlock(pos, state.setValue(CARPET, newCarpet), UPDATE_ALL)
                level.playSound(null, pos, SoundEvents.WOOL_PLACE, SoundSource.BLOCKS, 0.6f, 1.0f)
                if (!player.abilities.instabuild) held.shrink(1)
            }
            return InteractionResult.sidedSuccess(level.isClientSide)
        }

        if (level.isClientSide) return InteractionResult.SUCCESS
        val server = level as ServerLevel
        val sourceWorld = shipyardToWorld(server, pos)
        if (player.eyePosition.distanceToSqr(sourceWorld) > MAX_PROXIMITY_BLOCKS * MAX_PROXIMITY_BLOCKS) {
            player.displayClientMessage(
                Component.translatable("block.enderkinesis.orb_of_scrying.too_far")
                    .withStyle(ChatFormatting.GRAY),
                true,
            )
            return InteractionResult.FAIL
        }
        val target = findScryingTarget(server, pos, sourceWorld, player) ?: run {
            player.displayClientMessage(
                Component.translatable("block.enderkinesis.orb_of_scrying.no_target")
                    .withStyle(ChatFormatting.GRAY),
                true,
            )
            return InteractionResult.CONSUME
        }
        ScryingSessionManager.mount(
            player as net.minecraft.server.level.ServerPlayer,
            pos.immutable(),
            target,
        )
        return InteractionResult.CONSUME
    }

    /** Iterate every registered scrying orb in this dimension; reject ourselves and any
     *  CURRENTLY-COVERED orb (can't see through wool); keep candidates within the player's
     *  look cone; return the closest one in world distance.
     *
     *  No distance cap. Orb positions are persisted in [ScryingOrbRegistry] (per-dimension
     *  SavedData), so any orb placed in this dimension is findable regardless of whether
     *  its chunk is currently loaded. Iteration cost is bounded by orb count, not distance.
     *
     *  Block-state check on each candidate (`level.getBlockState`) will synchronously load
     *  the candidate's chunk if it isn't loaded — required to verify the carpet cover
     *  status, but a synchronous load per candidate could be expensive at scale. We do
     *  the cheap world-distance + look-cone filter on the registry entries first and only
     *  hit `getBlockState` when a candidate survives both gates, so most candidates skip
     *  the chunk load entirely. */
    private fun findScryingTarget(
        level: ServerLevel, sourcePos: BlockPos, sourceWorld: Vec3, player: Player,
    ): BlockPos? {
        val look = player.lookAngle
        var best: BlockPos? = null
        var bestDistSq = Double.MAX_VALUE
        for (candidatePos in ScryingOrbRegistry.get(level).all()) {
            if (candidatePos == sourcePos) continue
            val candidateWorld = shipyardToWorld(level, candidatePos)
            val dx = candidateWorld.x - sourceWorld.x
            val dy = candidateWorld.y - sourceWorld.y
            val dz = candidateWorld.z - sourceWorld.z
            val distSq = dx * dx + dy * dy + dz * dz
            val len = Math.sqrt(distSq)
            if (len < 1e-6) continue
            val dot = (dx * look.x + dy * look.y + dz * look.z) / len
            if (dot < DIRECTION_THRESHOLD) continue
            if (distSq >= bestDistSq) continue
            val candidateState = level.getBlockState(candidatePos)
            if (candidateState.block !is OrbOfScryingBlock) continue
            if (candidateState.getValue(CARPET) != ScryingCarpet.NONE) continue
            bestDistSq = distSq
            best = candidatePos.immutable()
        }
        return best
    }

    private fun shipyardToWorld(level: ServerLevel, pos: BlockPos): Vec3 {
        val ship = level.getShipManagingPos(pos)
        val v = Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        ship?.shipToWorld?.transformPosition(v)
        return Vec3(v.x, v.y, v.z)
    }

    /** Real removal (break, explosion, piston, setBlock from another mod). Drop the
     *  orb from the persistent registry, pop the carpet so the player gets it back,
     *  and tear down any live scrying session anchored on this orb (as source OR
     *  target) so the viewer doesn't keep staring at a frozen-in-time camera view
     *  through a block that no longer exists. Wired through `Block.onRemove` rather
     *  than `BE.setRemoved` so the registry edit only fires on real state changes
     *  and never on chunk unload. */
    @Deprecated("Deprecated in Java")
    override fun onRemove(
        state: BlockState, level: Level, pos: BlockPos, newState: BlockState, isMoving: Boolean,
    ) {
        if (!state.`is`(newState.block) && level is ServerLevel) {
            val carpet = state.getValue(CARPET)
            if (carpet != ScryingCarpet.NONE) {
                popResource(level, pos, ItemStack(carpet.carpetItem()))
            }
            ScryingOrbRegistry.get(level).remove(pos)
            ScryingSessionManager.endSessionsForOrb(level, pos)
        }
        super.onRemove(state, level, pos, newState, isMoving)
    }

    companion object {
        val FACING: DirectionProperty = BlockStateProperties.FACING
        val CARPET: EnumProperty<ScryingCarpet> = EnumProperty.create("carpet", ScryingCarpet::class.java)

        private const val MAX_PROXIMITY_BLOCKS: Double = 1.5
        private const val DIRECTION_THRESHOLD: Double = 0.5

        private val SHAPE_DOWN: VoxelShape = box(2.0, 0.0, 2.0, 14.0, 12.0, 14.0)
        private val SHAPE_UP: VoxelShape = box(2.0, 4.0, 2.0, 14.0, 16.0, 14.0)
        private val SHAPE_NORTH: VoxelShape = box(2.0, 2.0, 0.0, 14.0, 14.0, 12.0)
        private val SHAPE_SOUTH: VoxelShape = box(2.0, 2.0, 4.0, 14.0, 14.0, 16.0)
        private val SHAPE_EAST: VoxelShape = box(4.0, 2.0, 2.0, 16.0, 14.0, 14.0)
        private val SHAPE_WEST: VoxelShape = box(0.0, 2.0, 2.0, 12.0, 14.0, 14.0)
    }
}

/** Blockstate value for the orb's carpet cover. NONE = bare orb; the other 16 entries
 *  mirror [DyeColor]. Kept as a single 17-value enum (rather than DyeColor + a separate
 *  bool) so each visible variant maps to exactly one blockstate row. */
enum class ScryingCarpet(private val sName: String) : StringRepresentable {
    NONE("none"),
    WHITE("white"),
    ORANGE("orange"),
    MAGENTA("magenta"),
    LIGHT_BLUE("light_blue"),
    YELLOW("yellow"),
    LIME("lime"),
    PINK("pink"),
    GRAY("gray"),
    LIGHT_GRAY("light_gray"),
    CYAN("cyan"),
    PURPLE("purple"),
    BLUE("blue"),
    BROWN("brown"),
    GREEN("green"),
    RED("red"),
    BLACK("black");

    override fun getSerializedName(): String = sName

    fun carpetItem(): Item = when (this) {
        NONE -> Items.AIR
        WHITE -> Items.WHITE_CARPET
        ORANGE -> Items.ORANGE_CARPET
        MAGENTA -> Items.MAGENTA_CARPET
        LIGHT_BLUE -> Items.LIGHT_BLUE_CARPET
        YELLOW -> Items.YELLOW_CARPET
        LIME -> Items.LIME_CARPET
        PINK -> Items.PINK_CARPET
        GRAY -> Items.GRAY_CARPET
        LIGHT_GRAY -> Items.LIGHT_GRAY_CARPET
        CYAN -> Items.CYAN_CARPET
        PURPLE -> Items.PURPLE_CARPET
        BLUE -> Items.BLUE_CARPET
        BROWN -> Items.BROWN_CARPET
        GREEN -> Items.GREEN_CARPET
        RED -> Items.RED_CARPET
        BLACK -> Items.BLACK_CARPET
    }

    companion object {
        fun fromDye(d: DyeColor): ScryingCarpet = valueOf(d.name)
    }
}
