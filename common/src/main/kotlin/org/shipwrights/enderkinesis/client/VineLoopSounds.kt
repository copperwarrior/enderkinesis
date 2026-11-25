package org.shipwrights.enderkinesis.client

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.Entity
import org.shipwrights.enderkinesis.registry.EKSounds

/** The level tick and the sound tick are not in step, so a front that one effects pass did not see keeps its loop. */
private const val BURN_LOOP_EXPIRE_TICKS = 2L
private const val MAX_BURN_LOOPS = 8
/** Bits of a burn loop key for the front index; the chain id has the rest. */
private const val BURN_FRONT_BITS = 4
private const val BURN_FRONT_SLOTS = 1 shl BURN_FRONT_BITS

/** Client thread only. */
private val burnLoops = Long2ObjectOpenHashMap<VineBurnLoop>()

/** Keeps the fire loop of front [front] of vine [chainId] alive at its link for this tick, or starts it. */
internal fun ClientLevel.keepBurnLoop(chainId: Long, front: Int, x: Double, y: Double, z: Double) {
    if (front !in 0 until BURN_FRONT_SLOTS) return
    val key = (chainId shl BURN_FRONT_BITS) or front.toLong()
    val sounds = Minecraft.getInstance().soundManager
    val loop = burnLoops.get(key)
    if (loop != null && !loop.isStopped && sounds.isActive(loop)) {
        loop.keep(gameTime, x, y, z)
        return
    }
    if (loop != null) sounds.stop(loop) else if (burnLoops.size >= MAX_BURN_LOOPS) return
    burnLoops.put(key, VineBurnLoop(this).also { it.keep(gameTime, x, y, z); sounds.play(it) })
}

/** Forgets the loops that stopped: their front died, or the sound engine dropped them. */
internal fun pruneBurnLoops() {
    if (burnLoops.isEmpty()) return
    val sounds = Minecraft.getInstance().soundManager
    val iterator = burnLoops.values.iterator()
    while (iterator.hasNext()) {
        val loop = iterator.next()
        if (loop.isStopped || !sounds.isActive(loop)) iterator.remove()
    }
}

internal fun clearBurnLoops() {
    val sounds = Minecraft.getInstance().soundManager
    for (loop in burnLoops.values) sounds.stop(loop)
    burnLoops.clear()
}

private class VineBurnLoop(val level: ClientLevel) : AbstractTickableSoundInstance(
    EKSounds.WOGGAK_VINE_BURN_LOOP.get(),
    SoundSource.BLOCKS,
    SoundInstance.createUnseededRandom(),
) {
    private var seenTick = 0L

    init {
        looping = true
        delay = 0
    }

    fun keep(tick: Long, x: Double, y: Double, z: Double) {
        seenTick = tick
        this.x = x
        this.y = y
        this.z = z
    }

    override fun tick() {
        if (Minecraft.getInstance().level !== level || level.gameTime - seenTick > BURN_LOOP_EXPIRE_TICKS) stop()
    }
}

/** Client thread only; the reel loop of each player that reels, by entity id. */
private val reelLoops = Int2ObjectOpenHashMap<VineReelLoop>()

/** Starts or ends the reel loop of entity [entityId]; an end plays the reel stop where that player is. */
internal fun ClientLevel.setReelLoop(entityId: Int, start: Boolean) {
    val sounds = Minecraft.getInstance().soundManager
    reelLoops.remove(entityId)?.let(sounds::stop)
    val entity = getEntity(entityId) ?: return
    if (!start) {
        playLocalSound(entity.x, entity.y, entity.z, EKSounds.WOGGAK_VINE_REEL_STOP.get(), SoundSource.PLAYERS, 1f, 1f, false)
        return
    }
    reelLoops.put(entityId, VineReelLoop(entity).also(sounds::play))
}

internal fun clearReelLoops() {
    val sounds = Minecraft.getInstance().soundManager
    for (loop in reelLoops.values) sounds.stop(loop)
    reelLoops.clear()
}

private class VineReelLoop(val entity: Entity) : AbstractTickableSoundInstance(
    EKSounds.WOGGAK_VINE_REEL_LOOP.get(),
    SoundSource.PLAYERS,
    SoundInstance.createUnseededRandom(),
) {
    init {
        looping = true
        delay = 0
        follow()
    }

    override fun tick() {
        if (entity.isRemoved || Minecraft.getInstance().level !== entity.level()) {
            stop()
            return
        }
        follow()
    }

    private fun follow() {
        x = entity.x
        y = entity.y
        z = entity.z
    }
}
