package org.shipwrights.enderkinesis.spell

import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.registry.EKSounds
import java.util.WeakHashMap

object SpellSounds {
    private val BY_ELEMENT: Map<Element, EKSounds.SpellElementSounds> = mapOf(
        Element.PYRE to EKSounds.SPELL_PYRE,
        Element.WINTER to EKSounds.SPELL_WINTER,
        Element.GENESIS to EKSounds.SPELL_GENESIS,
        Element.GRAVITY to EKSounds.SPELL_GRAVITY,
        Element.QUINTESSENCE to EKSounds.SPELL_QUINTESSENCE,
        Element.CHAOS to EKSounds.SPELL_CHAOS,
        Element.BLOOD to EKSounds.SPELL_BLOOD,
        Element.SUNFIRE to EKSounds.SPELL_SUNFIRE,
        Element.CORRUPTION to EKSounds.SPELL_CORRUPTION,
        Element.VOID to EKSounds.SPELL_VOID,
    )

    fun of(element: Element): EKSounds.SpellElementSounds? = BY_ELEMENT[element]

    fun hasAny(spell: Spell): Boolean = spell.magnitudes.keys.any { it in BY_ELEMENT }

    /** The files are the magnitude-1 sounds. Above volume 1 the sound engine adds range, not loudness. */
    fun magnitudeVolume(magnitude: Float): Float = 1f + (magnitude.coerceIn(1f, MAX_MAGNITUDE) - 1f) / (MAX_MAGNITUDE - 1f)

    fun chargeVolume(charge: Float): Float = 0.6f + 0.4f * charge

    fun play(level: ServerLevel, at: Vec3, sound: RegistrySupplier<SoundEvent>, volume: Float = 1f, pitch: Float = 1f) =
        level.playSound(null, at.x, at.y, at.z, sound.get(), SoundSource.PLAYERS, volume, pitch)

    // Server thread only.
    private val lastSmites = WeakHashMap<LivingEntity, Long>()

    /** Sunfire streams and the Halo hit many times a second, so each undead smites at most once a second. */
    fun smite(level: ServerLevel, undead: LivingEntity, volume: Float) {
        val now = level.gameTime
        val last = lastSmites[undead]
        if (last != null && now - last in 0 until SMITE_EVERY_TICKS) return
        lastSmites[undead] = now
        play(level, undead.position(), EKSounds.SPELL_SUNFIRE_SMITE, volume)
    }

    /** A spell plays the sounds of each element it carries, each at the volume of its own magnitude. */
    inline fun forEach(spell: Spell, f: (EKSounds.SpellElementSounds, Float) -> Unit) {
        for ((element, magnitude) in spell.magnitudes) of(element)?.let { f(it, magnitudeVolume(magnitude)) }
    }

    /** Plays once, at the first block that one application changes. Off-beat stream shares stay quiet, so a held stream plays at most once each pulse. */
    class FirstChange(ctx: SpellContext, private val sound: RegistrySupplier<SoundEvent>, private val volume: Float) {
        private val level = ctx.level
        private var done = !ctx.beat

        fun at(pos: BlockPos) {
            if (done) return
            done = true
            play(level, Vec3.atCenterOf(pos), sound, volume)
        }
    }

    private const val MAX_MAGNITUDE = 7f
    private const val SMITE_EVERY_TICKS = 20L
}
