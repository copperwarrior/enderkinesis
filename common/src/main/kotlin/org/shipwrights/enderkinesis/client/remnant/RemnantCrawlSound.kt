package org.shipwrights.enderkinesis.client.remnant

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundSource
import org.shipwrights.enderkinesis.registry.EKSounds

/** One crawl loop for each mass, at the centroid of its moving cubes near the local player. Client thread only. */
internal object RemnantCrawlSound {

    const val RANGE: Double = EKSounds.REMNANT_CRAWL_RANGE.toDouble()

    /** The step per tick that counts as moving, the mean step that gives full volume, and the volume then. */
    const val MIN_SPEED: Double = 0.004
    private const val FULL_SPEED = 0.015
    private const val PEAK_VOLUME = 0.6f
    private const val FADE_TICKS = 20

    /** Conjoined cube volume that gives full volume: the hunt size, 10 fresh drips of radius 0.3. */
    private const val FULL_MASS_VOLUME = 10 * 0.3 * 0.3 * 0.3

    /** The crawl event streams, and vanilla gives streams only 8 channels. */
    private const val MAX_LOOPS = 4

    private val masses = ArrayList<Mass>()
    private val byId = Int2ObjectOpenHashMap<Mass>()

    /** Clears this tick's sums. Call before the cubes are counted, also on ticks that count none. */
    fun begin() {
        for (i in masses.indices) masses[i].reset()
    }

    /** Counts one cube of mass [massId]. Only a cube that is [near] and moves at [speed] places and drives the loop. */
    fun add(massId: Int, x: Double, y: Double, z: Double, radius: Double, speed: Double, near: Boolean) {
        val mass = byId.get(massId) ?: Mass(massId).also {
            byId.put(massId, it)
            masses.add(it)
        }
        mass.volume += radius * radius * radius
        if (!near || speed < MIN_SPEED) return
        mass.moving++
        mass.sumX += x
        mass.sumY += y
        mass.sumZ += z
        mass.sumSpeed += speed
    }

    /** Starts a loop for each moving mass that has none, and drops masses that are gone and silent. */
    fun end() {
        val sounds = Minecraft.getInstance().soundManager
        var playing = 0
        for (i in masses.indices) if (masses[i].isPlaying()) playing++
        var i = masses.size - 1
        while (i >= 0) {
            val mass = masses[i]
            if (mass.moving > 0 && !mass.isPlaying() && playing < MAX_LOOPS) {
                mass.loop = CrawlLoop(mass).also(sounds::play)
                playing++
            }
            if (mass.volume <= 0.0 && !mass.isPlaying()) remove(i)
            i--
        }
    }

    private fun remove(index: Int) {
        val mass = masses[index]
        val last = masses.removeAt(masses.size - 1)
        if (index < masses.size) masses[index] = last
        byId.remove(mass.id)
    }

    private class Mass(val id: Int) {
        var moving = 0
        var volume = 0.0
        var sumX = 0.0
        var sumY = 0.0
        var sumZ = 0.0
        var sumSpeed = 0.0
        var loop: CrawlLoop? = null

        fun reset() {
            moving = 0
            volume = 0.0
            sumX = 0.0
            sumY = 0.0
            sumZ = 0.0
            sumSpeed = 0.0
        }

        // A level change stops all sounds and does not call stop() on the instance.
        fun isPlaying(): Boolean {
            val current = loop ?: return false
            return !current.isStopped && Minecraft.getInstance().soundManager.isActive(current)
        }

        fun targetVolume(): Float {
            if (moving == 0) return 0f
            val pace = (sumSpeed / moving / FULL_SPEED).coerceIn(0.0, 1.0)
            val size = (volume / FULL_MASS_VOLUME).coerceIn(0.0, 1.0)
            return PEAK_VOLUME * (pace * size).toFloat()
        }
    }

    private class CrawlLoop(private val mass: Mass) : AbstractTickableSoundInstance(
        EKSounds.REMNANT_CRAWL.get(), SoundSource.HOSTILE, SoundInstance.createUnseededRandom(),
    ) {

        init {
            looping = true
            delay = 0
            volume = 0f
            move()
        }

        // The fade starts at volume 0. The sound engine drops a silent sound without this.
        override fun canStartSilent(): Boolean = true

        override fun tick() {
            move()
            val goal = mass.targetVolume()
            val step = PEAK_VOLUME / FADE_TICKS
            volume = if (volume < goal) (volume + step).coerceAtMost(goal) else (volume - step).coerceAtLeast(goal)
            if (goal <= 0f && volume <= 0f) stop()
        }

        /** Holds the last centroid while nothing moves, so the fade-out stays where the mass settled. */
        private fun move() {
            if (mass.moving == 0) return
            x = mass.sumX / mass.moving
            y = mass.sumY / mass.moving
            z = mass.sumZ / mass.moving
        }
    }
}
