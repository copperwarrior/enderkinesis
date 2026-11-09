package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.DirectionalBlock
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import org.shipwrights.enderkinesis.blockentity.GasVentBlockEntity
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import org.shipwrights.enderkinesis.spell.GasField
import org.shipwrights.enderkinesis.spell.GasJets
import org.shipwrights.enderkinesis.spell.GasKind
import org.shipwrights.enderkinesis.spell.voxelsLoaded
import org.valkyrienskies.mod.common.isBlockInShipyard
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.mod.common.getShipManagingPos

/** Pulls gas of every kind in from the back face and pushes it out of the front face ([BlockStateProperties.FACING], toward the placer). The redstone signal sets the strength, 0 to 15; it never reverses. */
class GasVentBlock(properties: BlockBehaviour.Properties) : DirectionalBlock(properties), EntityBlock {

    init {
        registerDefaultState(
            stateDefinition.any()
                .setValue(BlockStateProperties.FACING, Direction.NORTH)
                .setValue(BlockStateProperties.POWER, 0),
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(BlockStateProperties.FACING, BlockStateProperties.POWER)
    }

    @Suppress("OVERRIDE_DEPRECATION") // Forced by vanilla 1.20.1.
    override fun rotate(state: BlockState, rotation: Rotation): BlockState = state.rotateFacing(rotation)

    @Suppress("OVERRIDE_DEPRECATION") // Forced by vanilla 1.20.1.
    override fun mirror(state: BlockState, mirror: Mirror): BlockState = state.mirrorFacing(mirror)

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState = defaultBlockState()
        .setValue(BlockStateProperties.FACING, context.nearestLookingDirection.opposite)
        .setValue(BlockStateProperties.POWER, context.level.getBestNeighborSignal(context.clickedPos))

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun neighborChanged(state: BlockState, level: Level, pos: BlockPos, block: Block, fromPos: BlockPos, moving: Boolean) {
        if (level.isClientSide) return
        val power = level.getBestNeighborSignal(pos)
        if (power != state.getValue(BlockStateProperties.POWER)) {
            level.setBlock(pos, state.setValue(BlockStateProperties.POWER, power), Block.UPDATE_ALL)
        }
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = GasVentBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? {
        if (level.isClientSide || type != EKBlockEntities.GAS_VENT.get()) return null
        return BlockEntityTicker { lvl, pos, st, _ -> pass(lvl as ServerLevel, pos, st) }
    }

    @OptIn(VsBeta::class)
    private fun pass(level: ServerLevel, pos: BlockPos, state: BlockState) {
        val power = state.getValue(BlockStateProperties.POWER)
        if (power <= 0) return
        val front = state.getValue(BlockStateProperties.FACING)
        val ship = if (level.isBlockInShipyard(pos)) level.getShipManagingPos(pos) else null
        if (ship != null && !ship.voxelsLoaded()) return
        // Gas mass and speed scale with the ship scale s, so the recoil goes with s cubed.
        val s = ship?.transform?.shipToWorldScaling?.x() ?: 1.0
        // Gas ahead that nears the cap of 2 throttles the drain and the jet, so a chain of vents balances.
        val strength = power / MAX_POWER * GasField.ventThrottle(level, pos, front)
        if (strength <= 0f) return
        val thrust = minOf(GasField.vent(level, pos, front, strength, jetId(pos)).toDouble(), MAX_THRUST_NEWTONS) * s * s * s
        // The parcels carry the recoil: the ship of the vent gets it only for the gas that leaves the ship (GasJetSim).
        feedJets(level, pos, front, strength, thrust)
    }

    /** One jet for each kind the vent drained, from the middle of the front face, in the frame of the vent. */
    private fun feedJets(level: ServerLevel, pos: BlockPos, front: Direction, strength: Float, thrust: Double) {
        val frame = if (level.isBlockInShipyard(pos)) level.getShipManagingPos(pos)?.id ?: 0L else 0L
        var total = 0f
        for (kind in GasKind.entries) total += GasField.ventMass(kind)
        val intake = pos.relative(front.opposite)
        for (kind in GasKind.entries) {
            val mass = GasField.ventMass(kind)
            // A jet at its parcel cap drains nothing, but it still sends what it holds.
            if (mass <= 0f && !GasJets.hasBudget(level, jetId(pos), kind)) continue
            GasJets.hold(
                level, jetId(pos), kind, frame,
                pos.x + 0.5 + front.stepX * JET_START, pos.y + 0.5 + front.stepY * JET_START, pos.z + 0.5 + front.stepZ * JET_START,
                front.stepX.toDouble(), front.stepY.toDouble(), front.stepZ.toDouble(), strength, mass,
                if (total > 0f) thrust * mass / total else 0.0, intake = intake,
            )
        }
    }

    private fun jetId(pos: BlockPos): Long = pos.asLong()

    /**
     * How much of [give] a Hrass Sack can put in the intake now: none while the vent cannot drain, and never more than
     * one feed waits there, so the vent sends all of it.
     */
    fun intakeRoom(level: ServerLevel, pos: BlockPos, state: BlockState, kind: GasKind, give: Float): Float {
        val power = state.getValue(BlockStateProperties.POWER)
        if (power <= 0) return 0f
        val ship = if (level.isBlockInShipyard(pos)) level.getShipManagingPos(pos) else null
        if (ship != null && !ship.voxelsLoaded()) return 0f
        if (power / MAX_POWER * GasField.ventThrottle(level, pos, state.getValue(BlockStateProperties.FACING)) <= 0f) return 0f
        val waiting = GasField.amount(level, inputPos(state, pos), kind)
        return minOf(give - waiting, GasJets.intakeRoom(level, jetId(pos), kind, give)).coerceAtLeast(0f)
    }

    /** The cell the gas enters from, in the frame of the vent. */
    fun inputPos(state: BlockState, pos: BlockPos): BlockPos = pos.relative(state.getValue(BlockStateProperties.FACING).opposite)

    private companion object {
        const val MAX_POWER = 15f
        const val JET_START = 0.6
        // Recoil cap of one vent at ship scale 1; it scales with s cubed like the rest of the recoil.
        const val MAX_THRUST_NEWTONS = 200_000.0
    }
}
