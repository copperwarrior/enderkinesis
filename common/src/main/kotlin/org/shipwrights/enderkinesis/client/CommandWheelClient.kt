package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.systems.RenderSystem
import dev.architectury.event.events.client.ClientGuiEvent
import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.shipwrights.enderkinesis.item.CommandNetwork
import org.shipwrights.enderkinesis.item.StaffOfCommandItem
import org.shipwrights.enderkinesis.registry.EKItems

/** Client side of the Staff of Command's type selector: capture shift+scroll
 *  while the staff is held, send the cycle delta to the server, and render a
 *  vertical wheel on the right edge of the screen. Wheel slots are DYNAMIC —
 *  one per distinct commandable block type actually present in the staff's
 *  commanded list (icon = that block's item model), plus a Scroll-of-Animation
 *  slot when constriliars are commanded. Nothing commanded = no wheel. */
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

    private var animIndex: Float = 0f
    @Volatile private var predictedKey: String? = null
    @Volatile private var predictedAtMs: Long = 0L

    @JvmStatic
    fun init() {
        ClientGuiEvent.RENDER_HUD.register(::renderHud)
    }

    /** Shift+scroll while the staff is held: cycle the selected type. Called
     *  from `MouseHandlerCommandScrollMixin`; returns true when consumed
     *  (mixin cancels the vanilla hotbar swap). Not consumed with fewer than
     *  two wheel slots — there's nothing to cycle. */
    @JvmStatic
    fun tryConsumeScroll(verticalScroll: Double): Boolean {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return false
        val staff = findStaff(player) ?: return false
        val keys = StaffOfCommandItem.availableKeys(staff)
        if (keys.size < 2) return false

        val delta = if (verticalScroll > 0) -1 else 1
        val current = keys.indexOf(selectedKey(staff, keys)).coerceAtLeast(0)
        predictedKey = keys[Math.floorMod(current + delta, keys.size)]
        predictedAtMs = System.currentTimeMillis()

        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeInt(delta)
        NetworkManager.sendToServer(CommandNetwork.CYCLE_TYPE, buf)

        mc.level?.playLocalSound(
            player.x, player.y, player.z,
            SoundEvents.UI_BUTTON_CLICK.value(),
            net.minecraft.sounds.SoundSource.PLAYERS,
            0.5f, 1.6f, false,
        )
        return true
    }

    private fun findStaff(player: net.minecraft.world.entity.player.Player): ItemStack? {
        for (hand in arrayOf(InteractionHand.MAIN_HAND, InteractionHand.OFF_HAND)) {
            val held = player.getItemInHand(hand)
            if (held.item == EKItems.STAFF_OF_COMMAND.get()) return held
        }
        return null
    }

    /** Selected key with local prediction: the just-scrolled-to key wins while
     *  the cycle packet round-trips; afterwards the synced NBT is
     *  authoritative. */
    private fun selectedKey(staff: ItemStack, keys: List<String>): String? {
        val predicted = predictedKey
        if (predicted != null &&
            System.currentTimeMillis() - predictedAtMs < PREDICT_WINDOW_MS &&
            predicted in keys
        ) return predicted
        return StaffOfCommandItem.getSelectedKey(staff)
    }

    /** Wheel-slot icon: the commanded block's own item model; constriliars
     *  wear the Scroll of Animation. Entity keys resolve through the ITEM
     *  registry too (end crystals have a same-id item). Unresolvable ids
     *  (block gone after a mod update) fall back to a barrier so the slot
     *  stays visible + removable. */
    private fun iconFor(key: String): ItemStack {
        if (key == StaffOfCommandItem.CONSTRILIAR_ID.toString()) {
            return ItemStack(EKItems.SCROLL_OF_ANIMATION.get())
        }
        val id = ResourceLocation.tryParse(key) ?: return ItemStack(Items.BARRIER)
        val item = BuiltInRegistries.ITEM.get(id)
        return if (item == Items.AIR) ItemStack(Items.BARRIER) else ItemStack(item)
    }

    /** Slot label. The constriliar slot borrows the Scroll of Animation's
     *  ICON but labels as the entity ("Constriliar") — the scroll is a
     *  stand-in model, not the thing being commanded. Everything else reads
     *  its icon item's name. */
    private fun labelFor(key: String): net.minecraft.network.chat.Component {
        if (key == StaffOfCommandItem.CONSTRILIAR_ID.toString()) {
            return org.shipwrights.enderkinesis.registry.EKEntities.CONSTRILIAR.get().description
        }
        return iconFor(key).hoverName
    }

    private fun renderHud(gfx: GuiGraphics, partialTick: Float) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val staff = findStaff(player) ?: return
        val keys = StaffOfCommandItem.availableKeys(staff)
        if (keys.isEmpty()) return
        val n = keys.size
        val target = keys.indexOf(selectedKey(staff, keys)).coerceAtLeast(0)

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
            gfx.renderItem(iconFor(keys[0]), centreX, centreY)
        } else {
            for (slot in -WINDOW_HALF..WINDOW_HALF + 1) {
                val idxF = Math.floor(animIndex.toDouble()).toInt() + slot
                val offset = idxF - animIndex
                if (offset < -WINDOW_HALF - 0.999f || offset > WINDOW_HALF + 0.999f) continue
                val key = keys[Math.floorMod(idxF, n)]
                val y = centreY + Math.round(offset * SLOT_SPACING)
                // Distance falloff: centre = opaque, edges fade out.
                val dist = Math.abs(offset) / (WINDOW_HALF + 1f)
                val alpha = (1f - dist).coerceIn(0.15f, 1f)
                RenderSystem.setShaderColor(1f, 1f, 1f, alpha)
                gfx.renderItem(iconFor(key), centreX, y)
            }
        }
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)

        // Label for the selected family, right-aligned beside the wheel.
        val name = labelFor(keys[target])
        val tw = mc.font.width(name)
        gfx.drawString(mc.font, name, centreX - 8 - tw, centreY + 4, 0xFFFFFFFF.toInt(), true)
        RenderSystem.disableBlend()
    }
}
