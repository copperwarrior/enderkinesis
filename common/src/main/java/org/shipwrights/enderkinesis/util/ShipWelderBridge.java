package org.shipwrights.enderkinesis.util;

import com.mojang.logging.LogUtils;
import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Clearable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.mod.common.assembly.ICopyableBlock;
import org.valkyrienskies.mod.common.assembly.ShipWelder;
import org.valkyrienskies.mod.common.assembly.WeldRotation;
import org.valkyrienskies.mod.common.util.SplittingDisablerAttachment;

/**
 * Welds two ships at their current pose with VS2's private ShipWelder.prepare and merge, which
 * skips the VS2 pull-into-alignment session. Kotlin cannot call VS2's internal ShipWelder; Java
 * reflection can. ShipWelderMoveMixin still sees the merge, so ShipMoves follows it.
 */
public final class ShipWelderBridge {
    private static final Logger LOG = LogUtils.getLogger();
    private static final String FAILED = "failed";
    /** [moveBlock] refusal: the block cannot survive at that cell, but can elsewhere. */
    public static final String UNSUPPORTED = "unsupported";

    @Nullable private static final Method PREPARE;
    @Nullable private static final Method MERGE;
    @Nullable private static final Method GET_PLAN;
    @Nullable private static final Method GET_REFUSAL;

    static {
        Method prepare = null;
        Method merge = null;
        Method getPlan = null;
        Method getRefusal = null;
        try {
            Class<?> preparation = Class.forName("org.valkyrienskies.mod.common.assembly.ShipWelder$Preparation");
            Class<?> plan = Class.forName("org.valkyrienskies.mod.common.assembly.ShipWelder$Plan");
            prepare = ShipWelder.class.getDeclaredMethod("prepare", ServerLevel.class, LoadedServerShip.class,
                BlockPos.class, Direction.class, LoadedServerShip.class, BlockPos.class, Direction.class, WeldRotation.class);
            merge = ShipWelder.class.getDeclaredMethod("merge", ServerLevel.class, LoadedServerShip.class,
                BlockPos.class, Direction.class, LoadedServerShip.class, BlockPos.class, plan);
            getPlan = preparation.getMethod("getPlan");
            getRefusal = preparation.getMethod("getRefusal");
            for (Method m : new Method[] {prepare, merge, getPlan, getRefusal}) m.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.error("VS2 ShipWelder changed; the Staff of Fusion cannot weld", e);
            prepare = null;
        }
        PREPARE = prepare;
        MERGE = merge;
        GET_PLAN = getPlan;
        GET_REFUSAL = getRefusal;
    }

    private ShipWelderBridge() {}

    /** False when VS2's private welder methods are missing; the error is logged when this class loads. */
    public static boolean isAvailable() {
        return PREPARE != null && MERGE != null && GET_PLAN != null && GET_REFUSAL != null;
    }

    /** Returns null when [source] was merged into [target], else a VS2 refusal code ("jointed", "overlap", ...). */
    @Nullable
    public static String weld(ServerLevel level,
                              LoadedServerShip target, BlockPos targetPos, Direction targetFace,
                              LoadedServerShip source, BlockPos sourcePos, Direction sourceFace) {
        if (!isAvailable()) return FAILED;
        try {
            Object preparation = PREPARE.invoke(ShipWelder.INSTANCE, level, target, targetPos, targetFace,
                source, sourcePos, sourceFace, null);
            String refusal = (String) GET_REFUSAL.invoke(preparation);
            if (refusal != null) return refusal;
            return (String) MERGE.invoke(ShipWelder.INSTANCE, level, target, targetPos, targetFace,
                source, sourcePos, GET_PLAN.invoke(preparation));
        } catch (ReflectiveOperationException e) {
            LOG.error("Could not weld ship {} into {}", source.getId(), target.getId(), e);
            return FAILED;
        }
    }

    /**
     * Moves the block at [from] into [target] at [to], turned by the grid rotation with axes [xAxis],
     * [yAxis], [zAxis]. As in VS2's merge: the copy is placed before the source is cleared, and a
     * Clearable source is emptied, so a container cannot be duplicated. Returns null on success.
     */
    @Nullable
    public static String moveBlock(ServerLevel level, LoadedServerShip target, BlockPos from, BlockPos to,
                                   Direction xAxis, Direction yAxis, Direction zAxis) {
        SplittingDisablerAttachment splitting = target.getAttachment(SplittingDisablerAttachment.class);
        if (splitting != null && !splitting.canSplit()) return "busy";
        WeldRotation rotation = new WeldRotation(xAxis, yAxis, zAxis);
        Rotation yaw = rotation.getYaw();
        BlockState state = level.getBlockState(from);
        if (state.isAir()) return "empty";
        // ICopyableBlock needs ship ids and centers for a whole-ship move, which one block does not have.
        if (yaw == null && state.hasBlockEntity() || state.getBlock() instanceof ICopyableBlock) return "rotation";
        BlockState turned = rotation.rotateState(state);
        if (turned == null) return "rotation";
        if (!target.getChunkClaim().contains(to.getX() >> 4, to.getZ() >> 4) || level.isOutsideBuildHeight(to)) return "bounds";
        if (!level.getBlockState(to).isAir()) return "overlap";
        // Vanilla's placement rule: plants, torches and the like need support at the new cell.
        if (!turned.canSurvive(level, to)) return UNSUPPORTED;
        BlockEntity source = level.getBlockEntity(from);
        CompoundTag tag = source == null ? null : source.saveWithFullMetadata();
        level.getChunkAt(to).setBlockState(to, turned, false);
        if (tag != null) {
            BlockEntity placed = level.getBlockEntity(to);
            if (placed != null) {
                tag.putInt("x", to.getX());
                tag.putInt("y", to.getY());
                tag.putInt("z", to.getZ());
                placed.load(tag);
                placed.setChanged();
            }
        }
        if (source != null) Clearable.tryClear(source);
        level.removeBlockEntity(from);
        level.getChunkAt(from).setBlockState(from, Blocks.AIR.defaultBlockState(), false);
        notifyBlock(level, from);
        notifyBlock(level, to);
        return null;
    }

    private static void notifyBlock(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
        level.blockUpdated(pos, state.getBlock());
        level.getChunkSource().getLightEngine().checkBlock(pos);
    }
}
