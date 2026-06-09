package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.GlobalPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.Containers
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.CompassItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.MapItem
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.saveddata.maps.MapDecoration
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.phys.BlockHitResult
import org.shipwrights.enderkinesis.blockentity.EyeroscopeBlockEntity
import org.shipwrights.enderkinesis.registry.EKBlockEntities

/**
 * Holds a world-frame target yaw; a PD torque around world Y turns the host ship until
 * [FACING] (in world space, via `shipToWorldRotation`) matches it. Right-click captures
 * either the player's look (empty hand) or a tracked compass's pin position. Pin reads
 * world-XZ only — Y and dimension are ignored. Nature's/Explorer's Compass support is
 * NBT-only with no compile-time dep on those mods.
 */
class EyeroscopeBlock(properties: BlockBehaviour.Properties) :
    HorizontalDirectionalBlock(properties), EntityBlock {

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState {
        // FACING = front face (the artwork side); `.opposite` matches end_portal_frame's
        // "place so the front looks at the player" convention.
        return defaultBlockState().setValue(FACING, context.horizontalDirection.opposite)
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        EyeroscopeBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(
        level: Level, state: BlockState, type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? {
        if (level.isClientSide) return null
        if (type != EKBlockEntities.EYEROSCOPE.get()) return null
        return BlockEntityTicker { lvl, p, st, be ->
            (be as? EyeroscopeBlockEntity)?.serverTick(lvl as ServerLevel, p, st)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun use(
        state: BlockState, level: Level, pos: BlockPos,
        player: Player, hand: InteractionHand, hit: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.sidedSuccess(true)
        val be = level.getBlockEntity(pos) as? EyeroscopeBlockEntity ?: return InteractionResult.PASS
        val held = player.getItemInHand(hand)

        val pin = resolveCompassPin(held, player)
        if (pin != null) {
            val old = be.compassStack
            val captured = held.copyWithCount(1)
            be.insertCompass(captured, pin)
            if (!player.abilities.instabuild) held.shrink(1)
            if (!old.isEmpty) {
                if (!player.addItem(old)) player.drop(old, false)
            }
            return InteractionResult.CONSUME
        }

        if (held.isEmpty) {
            if (!be.compassStack.isEmpty) {
                val out = be.ejectCompass()
                if (!player.addItem(out)) player.drop(out, false)
                return InteractionResult.CONSUME
            }
            // VS2 mixes ship rotation into Player.getLookAngle, so this is the player's
            // *world-frame* look direction even on a turned ship.
            val look = player.lookAngle
            val targetYawRad = Math.atan2(-look.x, look.z).toFloat()
            be.setStaticTargetYaw(targetYawRad)
            return InteractionResult.CONSUME
        }

        return InteractionResult.PASS
    }

    private fun resolveCompassPin(stack: ItemStack, player: Player): BlockPos? {
        if (stack.`is`(Items.COMPASS)) {
            val tag = stack.tag ?: return null
            if (!CompassItem.isLodestoneCompass(stack)) return null
            val gp: GlobalPos = CompassItem.getLodestonePosition(tag) ?: return null
            return gp.pos()
        }
        if (stack.`is`(Items.RECOVERY_COMPASS)) {
            return player.lastDeathLocation.map { it.pos() }.orElse(null)
        }
        return resolveModdedFoundPin(stack) ?: resolveMapPin(stack, player.level(), player)
    }

    /** Filled map with an "X-marks-the-spot" decoration — buried-treasure red X, explorer
     *  map mansion/monument markers, generic target points, plus the modded-friendly
     *  RED_X / RED_MARKER aliases. Player / banner / frame decorations are ignored.
     *
     *  Picks the **closest** destination decoration to the player (who is at right-click
     *  distance from the eyeroscope, so this equals "closest to the eyeroscope" to within a
     *  couple of blocks). Most maps have a single destination so the choice is unambiguous;
     *  for atlas-style mods with many destinations on one map this lets the player
     *  disambiguate by where they stand to insert. */
    private fun resolveMapPin(stack: ItemStack, level: Level, player: Player): BlockPos? {
        if (!stack.`is`(Items.FILLED_MAP)) return null
        val mapData = MapItem.getSavedData(stack, level) ?: return null
        // Decoration x/y are signed bytes in [-128, 127] representing positions on the
        // 128×128 map grid via `pixel = (x + 128) / 2`. Each pixel covers 2^scale blocks
        // centred on (centerX, centerZ); the pixel at (64, 64) is dead-centre.
        val pixelSize = (1 shl mapData.scale.toInt()).toDouble()
        val obsX = player.x
        val obsZ = player.z
        var bestX = 0.0
        var bestZ = 0.0
        var bestDistSq = Double.MAX_VALUE
        // Iterate via the public `getDecorations()` (the `decorations` field is package-
        // private). Explicit `getType()`/`getX()`/`getY()` because Kotlin resolves both the
        // private byte fields and the public getters and refuses to pick one.
        for (dec in mapData.getDecorations()) {
            if (!isDestinationDecoration(dec.getType())) continue
            val wx = mapData.centerX + ((dec.getX() + 128) * 0.5 - 64) * pixelSize
            val wz = mapData.centerZ + ((dec.getY() + 128) * 0.5 - 64) * pixelSize
            val dx = wx - obsX
            val dz = wz - obsZ
            val distSq = dx * dx + dz * dz
            if (distSq < bestDistSq) {
                bestX = wx
                bestZ = wz
                bestDistSq = distSq
            }
        }
        if (bestDistSq == Double.MAX_VALUE) return null      // no destination decoration
        return BlockPos(bestX.toInt(), 0, bestZ.toInt())
    }

    /** Decoration types that mark a navigable destination on the map. PLAYER /
     *  PLAYER_OFF_MAP / FRAME / banner colours are dynamic / cosmetic and never a
     *  steering target. */
    private fun isDestinationDecoration(type: MapDecoration.Type): Boolean = when (type) {
        MapDecoration.Type.TARGET_X,
        MapDecoration.Type.TARGET_POINT,
        MapDecoration.Type.RED_X,
        MapDecoration.Type.RED_MARKER,
        MapDecoration.Type.MANSION,
        MapDecoration.Type.MONUMENT -> true
        else -> false
    }

    /** Nature's/Explorer's Compass share the same NBT shape (`State` int, `FoundX`/`FoundZ`).
     *  Y is omitted by the mods; eyeroscope steers on XZ only. */
    private fun resolveModdedFoundPin(stack: ItemStack): BlockPos? {
        val id = BuiltInRegistries.ITEM.getKey(stack.item)
        if (id.namespace != "naturescompass" && id.namespace != "explorerscompass") return null
        val tag = stack.tag ?: return null
        if (!tag.contains("State") || tag.getInt("State") != COMPASS_STATE_FOUND) return null
        if (!tag.contains("FoundX") || !tag.contains("FoundZ")) return null
        return BlockPos(tag.getInt("FoundX"), 0, tag.getInt("FoundZ"))
    }

    private companion object {
        /** `CompassState.FOUND` — shared between Nature's and Explorer's Compass. */
        const val COMPASS_STATE_FOUND: Int = 2
    }

    @Deprecated("Deprecated in Java")
    override fun hasAnalogOutputSignal(state: BlockState): Boolean = true

    @Deprecated("Deprecated in Java")
    override fun getAnalogOutputSignal(state: BlockState, level: Level, pos: BlockPos): Int =
        (level.getBlockEntity(pos) as? EyeroscopeBlockEntity)?.comparatorSignal ?: 0

    @Deprecated("Deprecated in Java")
    override fun onRemove(
        state: BlockState, level: Level, pos: BlockPos, newState: BlockState, isMoving: Boolean,
    ) {
        if (!state.`is`(newState.block)) {
            (level.getBlockEntity(pos) as? EyeroscopeBlockEntity)?.let { be ->
                if (!be.compassStack.isEmpty) {
                    Containers.dropItemStack(level, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, be.compassStack)
                }
            }
            super.onRemove(state, level, pos, newState, isMoving)
        }
    }
}
