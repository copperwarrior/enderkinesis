package org.shipwrights.enderkinesis.ritual

import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.PlayerEvent
import dev.architectury.event.events.common.TickEvent
import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.saveddata.SavedData
import org.shipwrights.enderkinesis.EnderkinesisMod

/** Ritual of Sun Erasure: the sun fades out and the Overworld stays as dark as a thunderstorm until night. */
object EraseSun : RitualEffect {
    override fun resolve(context: RitualContext, offered: List<ItemStack>): ItemStack? =
        if (SunErasure.start(context.level.server)) ItemStack.EMPTY else null
}

/**
 * The erasure as day times: the sun fades out from [START] over [FADE_TICKS], and the dark lasts until [END].
 * Mixins read [darkness] where vanilla reads the rain and thunder levels for sky darkness, sky colour and the sun.
 */
object SunErasure {

    val S2C_STATE: ResourceLocation = EnderkinesisMod.id("ritual/sun_erasure")

    private const val NONE = Long.MIN_VALUE
    private const val FADE_TICKS = 200f
    // The sky roots fade out over this after the end.
    private const val ROOT_FADE_TICKS = 200L
    private const val DAY_TICKS = 24_000L
    // Vanilla night starts here; the sun is under the horizon.
    private const val NIGHT_START = 13_000L
    // Night clears the erasure once the roots faded, so a /time jump back into the old window does not bring it back.
    private const val CLEAR_TIME = NIGHT_START + ROOT_FADE_TICKS
    // Sky light 15 - 14 = 1. Vanilla rain and thunder stop at 11 (night), so the erasure sets it directly.
    private const val ERASED_SKY_DARKEN = 14
    private const val DATA_NAME = "enderkinesis_sun_erasure"

    // Server: written on the server thread, read by the level tick. Client: written on the render thread.
    @Volatile private var serverStart = NONE
    @Volatile private var serverEnd = NONE
    @Volatile private var clientStart = NONE
    @Volatile private var clientEnd = NONE

    fun init() {
        LifecycleEvent.SERVER_STARTED.register { server ->
            val data = data(server)
            serverStart = data.start
            serverEnd = data.end
        }
        LifecycleEvent.SERVER_STOPPED.register { _ ->
            serverStart = NONE
            serverEnd = NONE
        }
        PlayerEvent.PLAYER_JOIN.register { player -> send(player) }
        TickEvent.SERVER_POST.register { server ->
            if (serverStart != NONE && Math.floorMod(server.overworld().dayTime, DAY_TICKS) >= CLEAR_TIME) clear(server)
        }
    }

    private fun clear(server: MinecraftServer) {
        serverStart = NONE
        serverEnd = NONE
        data(server).apply {
            start = NONE
            end = NONE
            setDirty()
        }
        for (player in server.playerList.players) send(player)
    }

    /** False when an erasure is already on, or it is night. */
    fun start(server: MinecraftServer): Boolean {
        val overworld = server.overworld()
        val now = overworld.dayTime
        if (isOn(now, serverStart, serverEnd)) return false
        val end = Math.floorDiv(now, DAY_TICKS) * DAY_TICKS + NIGHT_START
        if (now >= end) return false
        serverStart = now
        serverEnd = end
        data(server).apply {
            start = now
            this.end = end
            setDirty()
        }
        for (player in server.playerList.players) send(player)
        return true
    }

    /** The larger of [weather] and the erasure's darkness: rain or thunder level, 0 to 1. */
    @JvmStatic
    fun darkness(level: Level, weather: Float): Float = maxOf(weather, fade(level))

    /** Server sky light: [vanilla] skyDarken, raised toward [ERASED_SKY_DARKEN]. */
    @JvmStatic
    fun skyDarken(level: Level, vanilla: Int): Int = maxOf(vanilla, Math.round(ERASED_SKY_DARKEN * fade(level)))

    /** Client sky brightness for the lightmap: [vanilla], taken down toward 0. */
    @JvmStatic
    fun skyBrightness(level: Level, vanilla: Float): Float = vanilla * (1f - fade(level))

    private fun fade(level: Level): Float {
        if (level.dimension() != Level.OVERWORLD) return 0f
        val start = if (level.isClientSide) clientStart else serverStart
        val end = if (level.isClientSide) clientEnd else serverEnd
        val now = level.dayTime
        if (!isOn(now, start, end)) return 0f
        return ((now - start) / FADE_TICKS).coerceIn(0f, 1f)
    }

    /** Client: how far the sky roots grew toward the sun, 0 to 1. They grow with the sun's fade. */
    fun rootGrowth(time: Double): Float =
        if (clientStart == NONE) 0f else ((time - clientStart) / FADE_TICKS).toFloat().coerceIn(0f, 1f)

    /** Client: 1 while the erasure is on, then down to 0 as the roots fade out after the end. */
    fun rootPresence(time: Double): Float {
        val start = clientStart
        val end = clientEnd
        if (start == NONE || time < start) return 0f
        return (1.0 - (time - end) / ROOT_FADE_TICKS).toFloat().coerceIn(0f, 1f)
    }

    fun receive(start: Long, end: Long) {
        clientStart = start
        clientEnd = end
    }

    private fun isOn(now: Long, start: Long, end: Long) = start != NONE && now in start until end

    private fun send(player: ServerPlayer) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeLong(serverStart)
        buf.writeLong(serverEnd)
        NetworkManager.sendToPlayer(player, S2C_STATE, buf)
    }

    private fun data(server: MinecraftServer): Data =
        server.overworld().dataStorage.computeIfAbsent(Data::load, ::Data, DATA_NAME)

    private class Data(var start: Long = NONE, var end: Long = NONE) : SavedData() {
        override fun save(tag: CompoundTag): CompoundTag = tag.apply {
            putLong("start", start)
            putLong("end", end)
        }

        companion object {
            fun load(tag: CompoundTag) = Data(tag.getLong("start"), tag.getLong("end"))
        }
    }
}
