package org.shipwrights.enderkinesis.item

import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.phys.HitResult
import org.shipwrights.enderkinesis.construction.PlanCaptures
import org.shipwrights.enderkinesis.construction.PlanLimits
import org.shipwrights.enderkinesis.construction.PlanStorage
import org.shipwrights.enderkinesis.construction.cleanPlanName
import org.shipwrights.enderkinesis.util.loadedRayRange
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.isBlockInShipyard
import kotlin.math.floor

/** The plan capture corners on a Staff of Command stack and the server side of its capture wheel entries. */
object StaffBuild {
    private const val ROOT = "Build"
    private const val DIM = "Dim"
    private const val CAPTURE = "Capture"
    private const val FIRST = "First"
    private const val SECOND = "Second"
    private const val SHIP = "Ship"

    const val MAX_CORNER_DISTANCE: Double = 128.0

    /** The corners marked for a plan capture. A corner is null until it is set. With a [ship], the corners are in its shipyard. */
    data class CaptureBox(val dimension: ResourceKey<Level>, val ship: Long?, val first: BlockPos?, val second: BlockPos?) {
        val corners: Int get() = (if (first != null) 1 else 0) + (if (second != null) 1 else 0)

        /** Null until both corners are set. */
        fun box(): BoundingBox? = if (first != null && second != null) BoundingBox.fromCorners(first, second) else null
    }

    private fun root(stack: ItemStack): CompoundTag? = stack.tag?.getCompound(ROOT)

    fun capture(stack: ItemStack): CaptureBox? {
        val tag = root(stack)?.takeIf { it.contains(CAPTURE, Tag.TAG_COMPOUND.toInt()) }?.getCompound(CAPTURE) ?: return null
        val dim = ResourceLocation.tryParse(tag.getString(DIM)) ?: return null
        fun corner(name: String) = if (tag.contains(name, Tag.TAG_LONG.toInt())) BlockPos.of(tag.getLong(name)) else null
        val ship = if (tag.contains(SHIP, Tag.TAG_LONG.toInt())) tag.getLong(SHIP) else null
        return CaptureBox(ResourceKey.create(Registries.DIMENSION, dim), ship, corner(FIRST), corner(SECOND))
    }

    private fun writeCapture(stack: ItemStack, capture: CaptureBox) {
        val tag = CompoundTag()
        tag.putString(DIM, capture.dimension.location().toString())
        capture.first?.let { tag.putLong(FIRST, it.asLong()) }
        capture.second?.let { tag.putLong(SECOND, it.asLong()) }
        capture.ship?.let { tag.putLong(SHIP, it) }
        val root = stack.orCreateTag
        if (!root.contains(ROOT, Tag.TAG_COMPOUND.toInt())) root.put(ROOT, CompoundTag())
        root.getCompound(ROOT).put(CAPTURE, tag)
    }

    /** One wheel click on a capture entry. [aim] is the left click. */
    fun handle(player: ServerPlayer, stack: ItemStack, key: String, aim: Boolean) {
        when (key) {
            StaffBuildKeys.MARK -> markCorner(player, stack, first = aim)
            StaffBuildKeys.SAVE -> if (aim) stopCapture(player) else askCaptureName(player, stack)
            StaffBuildKeys.CLEAR -> if (!aim) clearCorners(player, stack)
        }
    }

    /** Left click sets the first corner and right click the second, on the block under the gaze. */
    private fun markCorner(player: ServerPlayer, stack: ItemStack, first: Boolean) {
        val level = player.serverLevel()
        val eye = player.getEyePosition(1f)
        val look = player.getViewVector(1f)
        // A server clip loads each chunk on its line, so the ray ends at the edge of the loaded area.
        val end = eye.add(look.scale(level.loadedRayRange(eye, look, MAX_CORNER_DISTANCE)))
        val hit = level.clip(ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
        if (hit.type != HitResult.Type.BLOCK) return player.tell("no_corner_target")
        // VS2 clip: a ship hit has a shipyard blockPos.
        val pos = hit.blockPos.immutable()
        val onShip = if (level.isBlockInShipyard(pos)) level.getShipManagingPos(pos) ?: return player.tell("no_corner_target") else null
        val ship = onShip?.id
        val old = capture(stack)?.takeIf { it.dimension == level.dimension() }
        val kept = if (first) old?.second else old?.first
        if (kept != null && old?.ship != ship) return player.tell("corner_other_frame")
        val box = if (first) CaptureBox(level.dimension(), ship, pos, kept) else CaptureBox(level.dimension(), ship, kept, pos)
        writeCapture(stack, box)
        val number = if (first) 1 else 2
        val whole = box.box()
        if (whole == null && onShip != null) {
            val centre = onShip.transform.positionInShip
            val dx = pos.x - floor(centre.x()).toInt()
            val dy = pos.y - floor(centre.y()).toInt()
            val dz = pos.z - floor(centre.z()).toInt()
            player.tell("corner_set_ship", number, dx, dy, dz)
        } else if (whole == null) {
            player.tell("corner_set", number, pos.x, pos.y, pos.z)
        } else {
            player.tell("corner_box", number, whole.xSpan, whole.ySpan, whole.zSpan)
        }
    }

    /** The box to save, or null after a refusal message. */
    private fun captureBox(player: ServerPlayer, stack: ItemStack): BoundingBox? {
        val capture = capture(stack)
        val box = capture?.box()
        val refusal = when {
            capture == null || box == null -> "no_box"
            capture.dimension != player.serverLevel().dimension() -> "capture_other_dimension"
            !player.serverLevel().holdsCorners(capture) -> "capture_ship_gone"
            PlanCaptures.running(player.uuid) -> "capture_running"
            !PlanStorage.canStore(player.server, player.uuid) -> "capture_full"
            else -> null
        }
        if (refusal != null) player.tell(refusal, PlanLimits.QUOTA)
        return if (refusal == null) box else null
    }

    // A deleted or split ship can leave the shipyard corners without their ship.
    private fun ServerLevel.holdsCorners(capture: CaptureBox): Boolean = capture.ship == null ||
        listOfNotNull(capture.first, capture.second).all { getShipManagingPos(it)?.id == capture.ship }

    private fun askCaptureName(player: ServerPlayer, stack: ItemStack) {
        val box = captureBox(player, stack) ?: return
        StaffBuildNetwork.openCaptureName(player, Vec3i(box.xSpan, box.ySpan, box.zSpan))
    }

    /** The name screen's answer. The server reads the box from the staff again; the client sends only the name. */
    fun saveCapture(player: ServerPlayer, stack: ItemStack, name: String) {
        val box = captureBox(player, stack) ?: return
        val clean = cleanPlanName(name)
        val refusal = PlanCaptures.start(player, player.serverLevel(), box, clean) { online, _ -> online?.let(::clearCapture) }
        if (refusal != null) player.tell(refusal, PlanLimits.QUOTA) else player.tell("capture_started", clean)
    }

    /** A finished save drops the corners from every staff the player carries. */
    private fun clearCapture(player: ServerPlayer) {
        for (stack in player.inventory.items + player.inventory.offhand) {
            if (stack.item is StaffOfCommandItem) root(stack)?.remove(CAPTURE)
        }
        player.containerMenu.broadcastChanges()
    }

    private fun clearCorners(player: ServerPlayer, stack: ItemStack) {
        val tag = root(stack) ?: return
        if (!tag.contains(CAPTURE)) return
        tag.remove(CAPTURE)
        player.tell("corners_cleared")
    }

    private fun stopCapture(player: ServerPlayer) {
        if (PlanCaptures.cancel(player.uuid)) player.tell("capture_stopped")
    }

    private fun ServerPlayer.tell(key: String, vararg args: Any) {
        displayClientMessage(Component.translatable("$MESSAGE_PREFIX$key", *args), true)
    }

    private const val MESSAGE_PREFIX = "item.enderkinesis.staff_of_command.build."
}
