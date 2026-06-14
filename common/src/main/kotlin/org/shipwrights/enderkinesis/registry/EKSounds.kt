package org.shipwrights.enderkinesis.registry

import dev.architectury.registry.registries.DeferredRegister
import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvent
import org.shipwrights.enderkinesis.EnderkinesisMod

/** Sound events added by Enderkinesis. */
object EKSounds {
    val SOUNDS: DeferredRegister<SoundEvent> =
        DeferredRegister.create(EnderkinesisMod.MOD_ID, Registries.SOUND_EVENT)

    /** Six interchangeable footstep samples for the Cataloger. The entity
     *  picks one at random on each step. */
    val CATALOGER_FOOTSTEPS: List<RegistrySupplier<SoundEvent>> = (1..6).map { idx ->
        val name = "entity.cataloger.footstep$idx"
        SOUNDS.register(name) { SoundEvent.createVariableRangeEvent(EnderkinesisMod.id(name)) }
    }

    /** Ygann Abyss ambience (see `YgannAbyssAmbience`): a continuous looping base track, plus drone
     *  and chant cues that surface uncommonly on top. Each cue is a multi-variant event (the sound
     *  manager randomizes the sample); the chants self-fade in their audio files. */
    val YGANN_AMBIENCE_LOOP: RegistrySupplier<SoundEvent> = ambient("ambient.ygann_abyss.loop")
    val YGANN_DRONE: RegistrySupplier<SoundEvent> = ambient("ambient.ygann_abyss.drone")
    val YGANN_CHANT: RegistrySupplier<SoundEvent> = ambient("ambient.ygann_abyss.chant")

    /** Sselith's Repertory dimension ambience — original murmur cues
     *  (5 samples × `r` companions = 10 takes; sound manager picks one
     *  per play) plus the continuous [SSELITH_AMBIENT_LOOP] base track. */
    val SSELITH_MURMUR: RegistrySupplier<SoundEvent> = ambient("ambient.sselith_repertory.murmur")
    val SSELITH_AMBIENT_LOOP: RegistrySupplier<SoundEvent> = ambient("ambient.sselith_repertory.loop")

    /** Sselith Madness whisper cues — one multi-variant event holding the
     *  five `whispers_N` samples; the sound manager picks one per play, so
     *  every play is a random whisper. Scheduling lives in
     *  [org.shipwrights.enderkinesis.client.SselithAmbience]: gap shrinks
     *  with the player's madness level (rare at L1, near-constant at L5),
     *  any dimension. */
    val SSELITH_WHISPERS: RegistrySupplier<SoundEvent> = ambient("ambient.sselith_repertory.whispers")

    /** Silent "music" for the Ygann Abyss biome. A biome with no music falls back to the default game
     *  music (Musics.GAME); pointing the biome's music at this silent track replaces that with nothing,
     *  so the dimension has no music — only its ambience. */
    val YGANN_MUSIC_SILENCE: RegistrySupplier<SoundEvent> = ambient("music.ygann_abyss.silence")

    /** Wohlonnogondonia's sole music track. Bound on the biome's
     *  `effects.music` block with `replace_current_music: true` so
     *  the dimension's track replaces anything the overworld music
     *  system was about to play and is the only thing the biome
     *  ever serves. Backing OGG at `assets/enderkinesis/sounds/music/wohlonnogondonia.ogg`. */
    val MUSIC_WOHLONNOGONDONIA: RegistrySupplier<SoundEvent> = ambient("music.wohlonnogondonia")

    /** Music disc "Enter the Mystery" by Tomato. Played by the
     *  Music Disc — Mystery item ([org.shipwrights.enderkinesis.registry.EKItems.MUSIC_DISC_MYSTERY])
     *  on jukeboxes. Backing OGG lives at
     *  `assets/enderkinesis/sounds/records/mystery.ogg`. */
    val MUSIC_DISC_MYSTERY: RegistrySupplier<SoundEvent> = ambient("music_disc.mystery")

    /** Prismatic-goat-horn cave-ambience instruments. 19 entries —
     *  matching the 19 vanilla cave ambient OGG files at
     *  `minecraft:ambient/cave/cave{1..19}` (verified against the
     *  1.20.1 asset index). Each sound event indirects to the
     *  vanilla OGG via `assets/enderkinesis/sounds.json` — we ship
     *  no audio of our own, just the registration. Each backs one
     *  `data/enderkinesis/instrument/cave_{N}.json` record; the
     *  prismatic goat picks one at random per-goat (UUID-seeded)
     *  the same way vanilla picks from `REGULAR_GOAT_HORNS`. */
    val PRISMATIC_HORN_CAVES: List<RegistrySupplier<SoundEvent>> = (1..19).map { idx ->
        val name = "horn.cave_$idx"
        SOUNDS.register(name) { SoundEvent.createVariableRangeEvent(EnderkinesisMod.id(name)) }
    }

    private fun ambient(name: String): RegistrySupplier<SoundEvent> =
        SOUNDS.register(name) { SoundEvent.createVariableRangeEvent(EnderkinesisMod.id(name)) }

    fun register() = SOUNDS.register()

    @Suppress("unused")
    private fun id(path: String): ResourceLocation = EnderkinesisMod.id(path)
}
