package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.systems.RenderSystem
import dev.architectury.event.events.client.ClientGuiEvent
import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.shipwrights.enderkinesis.entity.RaisedSkeletonEntity
import org.shipwrights.enderkinesis.item.CommandNetwork
import org.shipwrights.enderkinesis.item.StaffBuildKeys
import org.shipwrights.enderkinesis.item.StaffOfCommandItem
import org.shipwrights.enderkinesis.item.heldCommandStaff
import org.shipwrights.enderkinesis.registry.EKEntities
import org.shipwrights.enderkinesis.registry.EKItems
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** Client side of the Staff of Command's type selector: capture shift+scroll
 *  while the staff is held, send the cycle delta to the server, and render a
 *  vertical wheel on the right edge of the screen. Wheel slots are DYNAMIC —
 *  one per distinct commandable block type actually present in the staff's
 *  commanded list (icon = that block's item model), plus a Scroll-of-Animation
 *  slot when constriliars are commanded, then the build entries, which always show. */
object CommandWheelClient {

    /** Vertical pixel distance between adjacent wheel slots. */
    private const val SLOT_SPACING: Int = 22

    /** Wheel slots drawn either side of the centre (window = 2·this + 1). */
    private const val WINDOW_HALF: Int = 1

    /** Per-frame easing factor toward the target index (exponential). */
    private const val EASE: Float = 0.35f

    /** How long a locally-predicted selection outweighs the (round-tripping)
     *  NBT value, ms. Covers the C2S → NBT-sync latency without letting a
     *  dropped packet desync the wheel forever. */
    private const val PREDICT_WINDOW_MS: Long = 1000L

    private const val DETAIL_LINE: Int = 11

    private val RAISED_SKELETON_KEY: String = RaisedSkeletonEntity.ID.toString()

    private var animIndex: Float = 0f
    @Volatile private var predictedKey: String? = null
    @Volatile private var predictedAtMs: Long = 0L

    // Render thread only. Staff writes are server-side, so a synced stack brings a new tag.
    private var wheel: Wheel? = null

    private class Wheel(val tag: CompoundTag?, val keys: List<String>, val stored: String?) {
        val icons: List<ItemStack> = keys.map { iconFor(it) }
        val labels: List<Component> = keys.map { labelFor(it) }
    }

    fun init() {
        ClientGuiEvent.RENDER_HUD.register(::renderHud)
        StaffBuildClient.init()
    }

    /** Shift+scroll while the staff is held: cycle the selected type. Called
     *  from `MouseHandlerCommandScrollMixin`; returns true when consumed
     *  (mixin cancels the vanilla hotbar swap). Not consumed with fewer than
     *  two wheel slots — there's nothing to cycle. */
    @JvmStatic
    fun tryConsumeScroll(verticalScroll: Double): Boolean {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return false
        val staff = player.heldCommandStaff() ?: return false
        val wheel = wheelOf(staff)
        val keys = wheel.keys
        if (keys.size < 2) return false

        val delta = if (verticalScroll > 0) -1 else 1
        val current = keys.indexOf(selectedKey(wheel)).coerceAtLeast(0)
        predictedKey = keys[(current + delta).mod(keys.size)]
        predictedAtMs = System.currentTimeMillis()

        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeInt(delta)
        NetworkManager.sendToServer(CommandNetwork.CYCLE_TYPE, buf)

        mc.level?.playLocalSound(
            player.x, player.y, player.z,
            SoundEvents.UI_BUTTON_CLICK.value(),
            SoundSource.PLAYERS,
            0.5f, 1.6f, false,
        )
        return true
    }

    private fun livePrediction(): String? =
        predictedKey?.takeIf { System.currentTimeMillis() - predictedAtMs < PREDICT_WINDOW_MS }

    private fun wheelOf(staff: ItemStack): Wheel {
        val tag = staff.tag
        wheel?.let { if (it.tag === tag) return it }
        return Wheel(tag, StaffOfCommandItem.availableKeys(staff), StaffOfCommandItem.getSelectedKey(staff))
            .also { wheel = it }
    }

    /** Selected key with local prediction: the just-scrolled-to key wins while
     *  the cycle packet round-trips; afterwards the synced NBT is
     *  authoritative. */
    private fun selectedKey(wheel: Wheel): String? {
        val predicted = livePrediction()
        if (predicted != null && predicted in wheel.keys) return predicted
        return wheel.stored
    }

    /** Wheel-slot icon: the commanded block's own item model; constriliars
     *  wear the Scroll of Animation. Entity keys resolve through the ITEM
     *  registry too (end crystals have a same-id item). Unresolvable ids
     *  (block gone after a mod update) fall back to a barrier so the slot
     *  stays visible + removable. */
    private fun iconFor(key: String): ItemStack {
        if (key in StaffBuildKeys.ALL) return ItemStack(buildIcon(key))
        if (key == StaffOfCommandItem.CONSTRILIAR_ID.toString()) {
            return ItemStack(EKItems.SCROLL_OF_ANIMATION.get())
        }
        if (key == RAISED_SKELETON_KEY) return ItemStack(EKItems.GRAND_STAFF_OF_THE_DEAD.get())
        val id = ResourceLocation.tryParse(key) ?: return ItemStack(Items.BARRIER)
        val item = BuiltInRegistries.ITEM.get(id)
        return if (item == Items.AIR) ItemStack(Items.BARRIER) else ItemStack(item)
    }

    private fun buildIcon(key: String): Item = when (key) {
        StaffBuildKeys.CLEAR -> Items.TNT
        StaffBuildKeys.MARK -> Items.TARGET
        StaffBuildKeys.SAVE -> Items.CARTOGRAPHY_TABLE
        else -> Items.BARRIER
    }

    /** Slot label. The constriliar slot borrows the Scroll of Animation's
     *  ICON but labels as the entity ("Constriliar") — the scroll is a
     *  stand-in model, not the thing being commanded. Everything else reads
     *  its icon item's name. */
    private fun labelFor(key: String): Component {
        if (key in StaffBuildKeys.ALL) {
            return Component.translatable(
                "item.enderkinesis.staff_of_command.build.key.${key.substringAfterLast('/')}",
            )
        }
        if (key == StaffOfCommandItem.CONSTRILIAR_ID.toString()) {
            return EKEntities.CONSTRILIAR.get().description
        }
        if (key == RAISED_SKELETON_KEY) {
            return EKEntities.RAISED_SKELETON.get().description
        }
        return iconFor(key).hoverName
    }

    private fun renderHud(gfx: GuiGraphics, partialTick: Float) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val staff = player.heldCommandStaff() ?: return
        val wheel = wheelOf(staff)
        val keys = wheel.keys
        if (keys.isEmpty()) return
        val n = keys.size
        val target = keys.indexOf(selectedKey(wheel)).coerceAtLeast(0)

        // Ease along the shortest wrap-around path so cycling off either end
        // spins the wheel the short way instead of rewinding through the list.
        var diff = (target - animIndex) % n
        if (diff > n / 2f) diff -= n
        if (diff < -n / 2f) diff += n
        animIndex = ((animIndex + diff * EASE) % n + n) % n

        val w = gfx.guiWidth()
        val h = gfx.guiHeight()
        val centreX = w - 22
        val centreY = h / 2

        // Selection frame behind the centre slot.
        gfx.fill(centreX - 3, centreY - 3, centreX + 19, centreY + 19, 0x66000000)
        gfx.fill(centreX - 3, centreY - 3, centreX + 19, centreY - 2, 0xAAFFFFFF.toInt())
        gfx.fill(centreX - 3, centreY + 18, centreX + 19, centreY + 19, 0xAAFFFFFF.toInt())

        RenderSystem.enableBlend()
        if (n == 1) {
            gfx.renderItem(wheel.icons[0], centreX, centreY)
        } else {
            for (slot in -WINDOW_HALF..WINDOW_HALF + 1) {
                val idxF = floor(animIndex).toInt() + slot
                val offset = idxF - animIndex
                if (offset < -WINDOW_HALF - 0.999f || offset > WINDOW_HALF + 0.999f) continue
                val y = centreY + (offset * SLOT_SPACING).roundToInt()
                // Distance falloff: centre = opaque, edges fade out.
                val dist = abs(offset) / (WINDOW_HALF + 1f)
                val alpha = (1f - dist).coerceIn(0.15f, 1f)
                RenderSystem.setShaderColor(1f, 1f, 1f, alpha)
                gfx.renderItem(wheel.icons[idxF.mod(n)], centreX, y)
            }
        }
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)

        // Label for the selected family, right-aligned beside the wheel.
        val name = wheel.labels[target]
        val tw = mc.font.width(name)
        gfx.drawString(mc.font, name, centreX - 8 - tw, centreY + 4, 0xFFFFFFFF.toInt(), true)
        if (keys[target] == StaffBuildKeys.MARK || keys[target] == StaffBuildKeys.SAVE) {
            val box = StaffBuildClient.view(staff).capture?.box()
            val detail = if (box == null) {
                Component.translatable("item.enderkinesis.staff_of_command.build.no_box")
            } else {
                Component.translatable(
                    "item.enderkinesis.staff_of_command.build.box_line", box.xSpan, box.ySpan, box.zSpan,
                )
            }
            val dw = mc.font.width(detail)
            gfx.drawString(mc.font, detail, centreX - 8 - dw, centreY + 4 + DETAIL_LINE, 0xFFAAAAAA.toInt(), true)
        }
        RenderSystem.disableBlend()
    }
}
