@file:JvmName("WoodHeartsHud")

package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientPlayerEvent
import dev.architectury.networking.NetworkManager
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.resources.ResourceLocation
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.item.WOOD_HEARTS_PACKET

private val GUI_ICONS = ResourceLocation("textures/gui/icons.png")
private val HEARTS = EnderkinesisMod.id("textures/gui/hearts.png")

// hearts.png: a 4x2 grid of 13x13 cells. The 9x9 heart body sits 2 px in, so twigs can pass its edge.
private const val SHEET_WIDTH = 52
private const val SHEET_HEIGHT = 26
private const val CELL = 13
private const val CELL_INSET = 2
private const val COLUMNS = SHEET_WIDTH / CELL
private const val HEART_SIZE = 9

private const val WOOD_FULL = 0
private const val WOOD_HALF = 1
private const val WOOD_EMPTY = 2
private const val TRYPOEMIA_RED_HALF = 4
private const val TRYPOEMIA_WOOD_HALF = 5
private const val TRYPOEMIA_EMPTY = 6
private const val TRYPOEMIA_EMPTY_STYLES = 2

/** The local player's wood, in half hearts, and whether the crown is worn. Client thread only. */
private var woodHalfHearts = 0
private var crownWorn = false

fun initWoodHeartsClient() {
    NetworkManager.registerReceiver(NetworkManager.Side.S2C, WOOD_HEARTS_PACKET) { buf, ctx ->
        val halfHearts = buf.readVarInt()
        val worn = buf.readBoolean()
        ctx.queue {
            woodHalfHearts = halfHearts
            crownWorn = worn
        }
    }
    ClientPlayerEvent.CLIENT_PLAYER_QUIT.register {
        woodHalfHearts = 0
        crownWorn = false
    }
}

/**
 * Draws the container of health heart [index] (one heart = two half hearts). [special] is a poison, wither or
 * frozen heart type, which keeps the vanilla look. Returns false so vanilla draws it.
 */
fun drawHeartContainer(graphics: GuiGraphics, index: Int, x: Int, y: Int, health: Int, special: Boolean, trypoemia: Boolean): Boolean {
    if (special) return false
    val empty = index * 2 >= health
    val sprite = when {
        trypoemia && empty -> TRYPOEMIA_EMPTY + index % TRYPOEMIA_EMPTY_STYLES
        crownWorn -> WOOD_EMPTY
        else -> return false
    }
    blitSprite(graphics, x, y, sprite)
    return true
}

/**
 * Draws health heart [index]. Wood fills the top [woodHalfHearts] of [health]. Returns false so vanilla draws it.
 * [fleshHalfU] is vanilla's half sprite for the current heart type.
 */
fun drawHealthHeart(
    graphics: GuiGraphics, index: Int, x: Int, y: Int, v: Int, health: Int,
    special: Boolean, trypoemia: Boolean, fleshHalfU: Int,
): Boolean {
    val left = index * 2
    val half = left + 1 == health
    val sick = trypoemia && !special
    val firstWood = health - woodHalfHearts
    if (woodHalfHearts <= 0 || left + 1 < firstWood) {
        if (!(half && sick)) return false
        blitSprite(graphics, x, y, TRYPOEMIA_RED_HALF)
        return true
    }
    if (left >= firstWood) {
        val sprite = when {
            !half -> WOOD_FULL
            sick -> TRYPOEMIA_WOOD_HALF
            else -> WOOD_HALF
        }
        blitSprite(graphics, x, y, sprite)
        return true
    }
    // The left half is flesh and the right half is wood.
    blitSprite(graphics, x, y, WOOD_FULL)
    graphics.blit(GUI_ICONS, x, y, fleshHalfU, v, HEART_SIZE, HEART_SIZE)
    return true
}

private fun blitSprite(graphics: GuiGraphics, x: Int, y: Int, sprite: Int) {
    val u = (sprite % COLUMNS) * CELL
    val v = (sprite / COLUMNS) * CELL
    graphics.blit(
        HEARTS, x - CELL_INSET, y - CELL_INSET, u.toFloat(), v.toFloat(), CELL, CELL, SHEET_WIDTH, SHEET_HEIGHT,
    )
}
