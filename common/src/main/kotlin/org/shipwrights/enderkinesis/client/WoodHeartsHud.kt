@file:JvmName("WoodHeartsHud")

package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientPlayerEvent
import dev.architectury.networking.NetworkManager
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.resources.ResourceLocation
import org.shipwrights.enderkinesis.item.WOOD_HEARTS_PACKET

private val GUI_ICONS = ResourceLocation("textures/gui/icons.png")

// Placeholder art: the vanilla frozen heart (the palest sprite) tinted to wogor wood.
private const val FROZEN_HEART_U = 178
private const val HALF_HEART_STEP = 9
private const val HEART_SIZE = 9
private const val WOOD_RED = 0.66f
private const val WOOD_GREEN = 0.46f
private const val WOOD_BLUE = 0.28f

/** The local player's wood, in half hearts. Client thread only. */
private var woodHalfHearts = 0

fun initWoodHeartsClient() {
    NetworkManager.registerReceiver(NetworkManager.Side.S2C, WOOD_HEARTS_PACKET) { buf, ctx ->
        val halfHearts = buf.readVarInt()
        ctx.queue { woodHalfHearts = halfHearts }
    }
    ClientPlayerEvent.CLIENT_PLAYER_QUIT.register { woodHalfHearts = 0 }
}

/**
 * Draws the health heart at [index] (one heart = two half hearts). Wood fills the top [woodHalfHearts] of
 * [health]. Returns false when the heart has no wood, so vanilla draws it. [fleshHalfU] is vanilla's half sprite.
 */
fun drawWoodHeart(graphics: GuiGraphics, index: Int, x: Int, y: Int, v: Int, health: Int, fleshHalfU: Int): Boolean {
    val wood = woodHalfHearts
    if (wood <= 0) return false
    val firstWood = health - wood
    val left = index * 2
    if (left + 1 < firstWood) return false
    val half = left + 1 == health
    if (left >= firstWood) {
        blitWood(graphics, x, y, v, half)
        return true
    }
    // The left half is flesh and the right half is wood.
    blitWood(graphics, x, y, v, false)
    graphics.blit(GUI_ICONS, x, y, fleshHalfU, v, HEART_SIZE, HEART_SIZE)
    return true
}

private fun blitWood(graphics: GuiGraphics, x: Int, y: Int, v: Int, half: Boolean) {
    graphics.setColor(WOOD_RED, WOOD_GREEN, WOOD_BLUE, 1f)
    graphics.blit(GUI_ICONS, x, y, FROZEN_HEART_U + if (half) HALF_HEART_STEP else 0, v, HEART_SIZE, HEART_SIZE)
    graphics.setColor(1f, 1f, 1f, 1f)
}
