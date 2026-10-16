package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientTickEvent
import dev.architectury.networking.NetworkManager
import dev.architectury.registry.registries.RegistrySupplier
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.core.BlockPos
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import org.shipwrights.enderkinesis.block.WohlonPortalNetwork
import org.shipwrights.enderkinesis.registry.EKSounds

object WohlonPortalSounds {

    private const val PORTAL_FADE_TICKS: Int = 20
    private const val GROW_FADE_TICKS: Int = 40
    /** Two missed syncs. Then the source is gone or out of sync range. */
    private const val STALE_TICKS: Long = WohlonPortalNetwork.SYNC_INTERVAL_TICKS * 2 + 10
    /** The audible range of each loop, plus a small margin. */
    private const val PORTAL_START_RANGE: Double = 16.0 + 2.0
    private const val GROW_START_RANGE: Double = 48.0 + 2.0

    private class Source(
        val pos: BlockPos,
        val event: RegistrySupplier<SoundEvent>,
        val fadeTicks: Int,
        startRange: Double,
        var seenTick: Long,
    ) {
        val startRangeSqr = startRange * startRange
        /** False while a tree job waits for its chunks. Always true for a portal. */
        var active = true
        var loop: SourceLoop? = null
    }

    /** Client thread only. Keyed by packed block position. A grow loop and a portal can share a position. */
    private val portals = Long2ObjectOpenHashMap<Source>()
    private val trees = Long2ObjectOpenHashMap<Source>()

    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, WohlonPortalNetwork.S2C_PORTALS) { buf, _ ->
            val portalCount = buf.readVarInt()
            val portalPos = LongArray(portalCount)
            val mother = BooleanArray(portalCount)
            for (i in 0 until portalCount) {
                portalPos[i] = buf.readLong()
                mother[i] = buf.readBoolean()
            }
            val treeCount = buf.readVarInt()
            val trunkPos = LongArray(treeCount)
            val growing = BooleanArray(treeCount)
            for (i in 0 until treeCount) {
                trunkPos[i] = buf.readLong()
                growing[i] = buf.readBoolean()
            }
            Minecraft.getInstance().execute { receive(portalPos, mother, trunkPos, growing) }
        }
        ClientTickEvent.CLIENT_LEVEL_POST.register(::tick)
    }

    fun onDisconnect() {
        val sounds = Minecraft.getInstance().soundManager
        for (map in arrayOf(portals, trees)) {
            map.values.forEach { source -> source.loop?.let(sounds::stop) }
            map.clear()
        }
    }

    private fun receive(portalPos: LongArray, mother: BooleanArray, trunkPos: LongArray, growing: BooleanArray) {
        val now = Minecraft.getInstance().level?.gameTime ?: return
        for (i in portalPos.indices) {
            val event = if (mother[i]) EKSounds.WOHLON_MOTHER_PORTAL_LOOP else EKSounds.WOHLON_PORTAL_LOOP
            see(portals, portalPos[i], now) { Source(it, event, PORTAL_FADE_TICKS, PORTAL_START_RANGE, now) }
        }
        for (i in trunkPos.indices) {
            val tree = see(trees, trunkPos[i], now) {
                Source(it, EKSounds.WOHLON_RITUAL_GROW_LOOP, GROW_FADE_TICKS, GROW_START_RANGE, now)
            }
            tree.active = growing[i]
        }
    }

    private inline fun see(
        map: Long2ObjectOpenHashMap<Source>, packed: Long, now: Long, create: (BlockPos) -> Source,
    ): Source {
        val source = map.get(packed)
        if (source != null) {
            source.seenTick = now
            return source
        }
        return create(BlockPos.of(packed)).also { map.put(packed, it) }
    }

    private fun tick(level: ClientLevel) {
        tickSources(level, portals)
        tickSources(level, trees)
    }

    private fun tickSources(level: ClientLevel, map: Long2ObjectOpenHashMap<Source>) {
        if (map.isEmpty()) return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val sounds = mc.soundManager
        val now = level.gameTime
        val iterator = map.values.iterator()
        while (iterator.hasNext()) {
            val source = iterator.next()
            val loop = source.loop
            // A level change stops all sounds and does not call stop() on the instance.
            val playing = loop != null && !loop.isStopped && sounds.isActive(loop)
            if (!playing) source.loop = null
            if (isStale(source, now)) {
                if (!playing) iterator.remove()
                continue
            }
            if (playing || !source.active || !inRange(source, player.x, player.y, player.z)) continue
            source.loop = SourceLoop(level, source).also(sounds::play)
        }
    }

    private fun isStale(source: Source, now: Long): Boolean = now - source.seenTick > STALE_TICKS

    private fun inRange(source: Source, x: Double, y: Double, z: Double): Boolean =
        source.pos.distToCenterSqr(x, y, z) <= source.startRangeSqr

    /** The engine sets the range at the start, so the 48 block grow range comes from attenuation_distance. */
    private class SourceLoop(
        private val level: ClientLevel,
        private val source: Source,
    ) : AbstractTickableSoundInstance(source.event.get(), SoundSource.BLOCKS, SoundInstance.createUnseededRandom()) {

        init {
            looping = true
            delay = 0
            volume = 0f
            x = source.pos.x + 0.5
            y = source.pos.y + 0.5
            z = source.pos.z + 0.5
        }

        // The fade starts at volume 0. The sound engine drops a silent sound without this.
        override fun canStartSilent(): Boolean = true

        override fun tick() {
            val player = Minecraft.getInstance().player
            val live = player != null && player.level() === level && source.active &&
                !isStale(source, level.gameTime) && inRange(source, player.x, player.y, player.z)
            if (live) {
                volume = (volume + 1f / source.fadeTicks).coerceAtMost(1f)
                return
            }
            volume -= 1f / source.fadeTicks
            if (volume <= 0f) stop()
        }
    }
}
