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

    /** Sureibjin dream-coast ambience — continuous looping track played at the
     *  coastline and over the ocean. Cuts out when the player walks deep into
     *  the western sand dune. See [org.shipwrights.enderkinesis.client.SureibjinAmbience]. */
    val SUREIBJIN_AMBIENCE_LOOP: RegistrySupplier<SoundEvent> = ambient("ambient.sureibjin.loop")

    /** Sselith's Repertory dimension ambience — original murmur cues
     *  (5 samples × `r` companions = 10 takes; sound manager picks one
     *  per play) plus the continuous [SSELITH_AMBIENT_LOOP] base track. */
    val SSELITH_MURMUR: RegistrySupplier<SoundEvent> = ambient("ambient.sselith_repertory.murmur")
    val SSELITH_AMBIENT_LOOP: RegistrySupplier<SoundEvent> = ambient("ambient.sselith_repertory.loop")

    /** Sselith Madness whisper cues — one multi-variant event holding the
     *  five `whispers_N` samples; the sound manager picks one per play, so
     *  every play is a random whisper. Scheduling lives in
     *  [org.shipwrights.enderkinesis.client.SselithAmbience]: gap shrinks
     *  with the player's madness level (rare at L1, near-constant at L10),
     *  any dimension. */
    val SSELITH_WHISPERS: RegistrySupplier<SoundEvent> = ambient("ambient.sselith_repertory.whispers")

    /** The non-mono crossing sounds are stereo, so the client applies no distance or direction.
     *  Send them to the traveller only. */
    val SSELITH_HISS: RegistrySupplier<SoundEvent> = ambient("event.sselith_repertory.hiss")
    val SSELITH_HISS_MONO: RegistrySupplier<SoundEvent> = ambient("event.sselith_repertory.hiss_mono")
    val SSELITH_RETURN: RegistrySupplier<SoundEvent> = ambient("event.sselith_repertory.return")
    val SSELITH_RETURN_MONO: RegistrySupplier<SoundEvent> = ambient("event.sselith_repertory.return_mono")

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

    /** Magic-missile detonation blast — vanilla firework-rocket blast audio, but
     *  re-registered as a *variable-range* event (same attenuation curve as
     *  `entity.generic.explode`). Paired with a vanilla-explosion-style volume of
     *  `4.0f` at the call site this gives ~64 blocks of audible range, vs. the 320
     *  blocks vanilla `FIREWORK_ROCKET_BLAST` reaches at volume 20.
     *  Backed by `minecraft:entity/firework/blast1`/`blast2` via `sounds.json`. */
    val MISSILE_BURST_BLAST: RegistrySupplier<SoundEvent> = SOUNDS.register("missile_burst_blast") {
        SoundEvent.createVariableRangeEvent(EnderkinesisMod.id("missile_burst_blast"))
    }

    val SUNDERING_START: RegistrySupplier<SoundEvent> = ambient("item.staff_of_sundering.start")
    val SUNDERING_BEAM_LOOPS: List<RegistrySupplier<SoundEvent>> =
        (1..4).map { ambient("item.staff_of_sundering.beam_loop_$it") }
    val SUNDERING_STAGE_UP: RegistrySupplier<SoundEvent> = ambient("item.staff_of_sundering.stage_up")
    val SUNDERING_IMPACT_LOOP: RegistrySupplier<SoundEvent> = ambient("item.staff_of_sundering.impact_loop")
    val SUNDERING_BURN: RegistrySupplier<SoundEvent> = ambient("item.staff_of_sundering.burn")
    val SUNDERING_STOP: RegistrySupplier<SoundEvent> = ambient("item.staff_of_sundering.stop")

    val CONCEALMENT_CLOAK: RegistrySupplier<SoundEvent> = ambient("item.staff_of_concealment.cloak")
    val CONCEALMENT_HUM_LOOP: RegistrySupplier<SoundEvent> = ambient("item.staff_of_concealment.hum_loop")
    val CONCEALMENT_DECLOAK: RegistrySupplier<SoundEvent> = ambient("item.staff_of_concealment.decloak")

    val AEGIS_RAISE: RegistrySupplier<SoundEvent> = ambient("item.staff_of_aegis.raise")
    val AEGIS_HUM_LOOP: RegistrySupplier<SoundEvent> = ambient("item.staff_of_aegis.hum_loop")
    val AEGIS_DEFLECT: RegistrySupplier<SoundEvent> = ambient("item.staff_of_aegis.deflect")
    val AEGIS_PUSH: RegistrySupplier<SoundEvent> = ambient("item.staff_of_aegis.push")
    val AEGIS_SHATTER: RegistrySupplier<SoundEvent> = ambient("item.staff_of_aegis.shatter")
    val AEGIS_SHARD_BOUNCE: RegistrySupplier<SoundEvent> = ambient("item.staff_of_aegis.shard_bounce")

    val WYLLAND_GRAB: RegistrySupplier<SoundEvent> = ambient("item.wylland_tome.grab")
    val WYLLAND_RIP: RegistrySupplier<SoundEvent> = ambient("item.wylland_tome.rip")
    val WYLLAND_HOLD_LOOP: RegistrySupplier<SoundEvent> = ambient("item.wylland_tome.hold_loop")
    val WYLLAND_RELEASE: RegistrySupplier<SoundEvent> = ambient("item.wylland_tome.release")
    val WYLLAND_SEVER: RegistrySupplier<SoundEvent> = ambient("item.wylland_tome.sever")
    val WYLLAND_SPENT: RegistrySupplier<SoundEvent> = ambient("item.wylland_tome.spent")
    val WYLLAND_REPAIR: RegistrySupplier<SoundEvent> = ambient("item.wylland_tome.repair")

    private fun ambient(name: String): RegistrySupplier<SoundEvent> =
        SOUNDS.register(name) { SoundEvent.createVariableRangeEvent(EnderkinesisMod.id(name)) }

    fun register() = SOUNDS.register()

    @Suppress("unused")
    private fun id(path: String): ResourceLocation = EnderkinesisMod.id(path)
}
