package org.shipwrights.enderkinesis.registry

import dev.architectury.registry.registries.DeferredRegister
import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.properties.BlockSetType
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
    val YGANN_REJECT: RegistrySupplier<SoundEvent> = ambient("ambient.ygann_abyss.reject")
    val YGANN_MADNESS_WHISPERS: RegistrySupplier<SoundEvent> = ambient("ambient.ygann_abyss.madness_whispers")

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
    val SSELITH_ECLIPSE_BEGIN: RegistrySupplier<SoundEvent> = ambient("ambient.sselith_repertory.eclipse_begin")
    val SSELITH_ECLIPSE_LOOP: RegistrySupplier<SoundEvent> = ambient("ambient.sselith_repertory.eclipse_loop")
    val SSELITH_ECLIPSE_END: RegistrySupplier<SoundEvent> = ambient("ambient.sselith_repertory.eclipse_end")
    val SSELITH_LIGHT_BURN: RegistrySupplier<SoundEvent> = ambient("event.sselith_repertory.light_burn")
    val SSELITH_DUST_DEATH: RegistrySupplier<SoundEvent> = ambient("event.sselith_repertory.dust_death")
    val SSELITH_ITEM_DUST: RegistrySupplier<SoundEvent> = ambient("event.sselith_repertory.item_dust")
    val SSELITH_MADNESS_STAGE: RegistrySupplier<SoundEvent> = ambient("event.sselith_repertory.madness_stage")
    val SSELITH_MADNESS_GRIP: RegistrySupplier<SoundEvent> = ambient("event.sselith_repertory.madness_grip")
    val SSELITH_MADNESS_RELEASE: RegistrySupplier<SoundEvent> = ambient("event.sselith_repertory.madness_release")
    val SSELITH_TOME_CALL: RegistrySupplier<SoundEvent> = ambient("event.sselith_repertory.tome_call")
    val SSELITH_TOME_FLIGHT: RegistrySupplier<SoundEvent> = ambient("event.sselith_repertory.tome_flight")
    val SSELITH_TOME_OPEN: RegistrySupplier<SoundEvent> = ambient("event.sselith_repertory.tome_open")
    val SSELITH_TOME_READ_LOOP: RegistrySupplier<SoundEvent> = ambient("event.sselith_repertory.tome_read_loop")
    val SSELITH_TOME_CLOSE: RegistrySupplier<SoundEvent> = ambient("event.sselith_repertory.tome_close")
    val SSELITH_TOME_GIFT: RegistrySupplier<SoundEvent> = ambient("event.sselith_repertory.tome_gift")
    val SSELITH_TOME_SHELVE: RegistrySupplier<SoundEvent> = ambient("event.sselith_repertory.tome_shelve")
    val SSELITH_MOTH_FAR: RegistrySupplier<SoundEvent> = ambient("ambient.sselith_repertory.moth_far")
    val SSELITH_VOID_FALL: RegistrySupplier<SoundEvent> = ambient("ambient.sselith_repertory.void_fall")
    val SSELITH_INVOCATION: RegistrySupplier<SoundEvent> = ambient("ambient.sselith_repertory.invocation")
    val SSELITH_OWL_ESCAPE: RegistrySupplier<SoundEvent> = ambient("event.sselith_repertory.owl_escape")
    val SSELITH_VOID_WRAP: RegistrySupplier<SoundEvent> = ambient("event.sselith_repertory.void_wrap")
    val SSELITH_GLYPH_STORM: RegistrySupplier<SoundEvent> = ambient("event.sselith_repertory.glyph_storm")
    val SSELITH_ARCHIVE_COLLAPSE: RegistrySupplier<SoundEvent> = ambient("event.sselith_repertory.archive_collapse")
    val SSELITH_ARCHIVE_LOOP: RegistrySupplier<SoundEvent> = SOUNDS.register("entity.sselith_repertory.archive") {
        SoundEvent.createFixedRangeEvent(EnderkinesisMod.id("entity.sselith_repertory.archive"), ARCHIVE_LOOP_RANGE)
    }
    const val ARCHIVE_LOOP_RANGE: Float = 16f

    val REMNANT_FORM: RegistrySupplier<SoundEvent> = ambient("entity.irreverant_remnant.form")
    val REMNANT_SPLAT: RegistrySupplier<SoundEvent> = ambient("entity.irreverant_remnant.splat")
    val REMNANT_CRAWL: RegistrySupplier<SoundEvent> = SOUNDS.register("entity.irreverant_remnant.crawl") {
        SoundEvent.createFixedRangeEvent(EnderkinesisMod.id("entity.irreverant_remnant.crawl"), REMNANT_CRAWL_RANGE)
    }
    const val REMNANT_CRAWL_RANGE: Float = 16f
    val REMNANT_SNAP: RegistrySupplier<SoundEvent> = ambient("entity.irreverant_remnant.snap")
    val REMNANT_SEEP: RegistrySupplier<SoundEvent> = ambient("entity.irreverant_remnant.seep")
    val REMNANT_CONSUME: RegistrySupplier<SoundEvent> = ambient("entity.irreverant_remnant.consume")
    val REMNANT_STIR: RegistrySupplier<SoundEvent> = ambient("entity.irreverant_remnant.stir")

    val SSELITH_MOTH_WING: RegistrySupplier<SoundEvent> = ambient("entity.sselith_moth.wing")
    val SSELITH_MOTH_TAKEOFF: RegistrySupplier<SoundEvent> = ambient("entity.sselith_moth.takeoff")
    val SSELITH_MOTH_LAND: RegistrySupplier<SoundEvent> = ambient("entity.sselith_moth.land")
    val SSELITH_MOTH_HURT: RegistrySupplier<SoundEvent> = ambient("entity.sselith_moth.hurt")
    val SSELITH_MOTH_DEATH: RegistrySupplier<SoundEvent> = ambient("entity.sselith_moth.death")

    val SSELITH_SHELF_STEP: SoundEvent = eager("block.sselith_shelf.step")
    val SSELITH_SHELF_PLACE: SoundEvent = eager("block.sselith_shelf.place")
    val SSELITH_SHELF_BREAK: SoundEvent = eager("block.sselith_shelf.break")
    val SSELITH_SHELF_TRAPDOOR_OPEN: SoundEvent = eager("block.sselith_shelf.trapdoor_open")
    val SSELITH_SHELF_TRAPDOOR_CLOSE: SoundEvent = eager("block.sselith_shelf.trapdoor_close")
    val SSELITH_SHELF_SOUND_TYPE: SoundType = SoundType(
        1f, 1f,
        SSELITH_SHELF_BREAK, SSELITH_SHELF_STEP, SSELITH_SHELF_PLACE,
        SSELITH_SHELF_STEP, SSELITH_SHELF_STEP,
    )
    /** The flags and the door, plate and button sounds are a copy of [BlockSetType.STONE]. The trapdoor
     *  only reads sounds from it, so it needs no entry in the private BlockSetType registry. */
    val SSELITH_TRAPDOOR_SET_TYPE: BlockSetType = BlockSetType(
        "enderkinesis:sselith", true, SSELITH_SHELF_SOUND_TYPE,
        SoundEvents.IRON_DOOR_CLOSE, SoundEvents.IRON_DOOR_OPEN,
        SSELITH_SHELF_TRAPDOOR_CLOSE, SSELITH_SHELF_TRAPDOOR_OPEN,
        SoundEvents.STONE_PRESSURE_PLATE_CLICK_OFF, SoundEvents.STONE_PRESSURE_PLATE_CLICK_ON,
        SoundEvents.STONE_BUTTON_CLICK_OFF, SoundEvents.STONE_BUTTON_CLICK_ON,
    )

    /** Silent "music" for the Ygann Abyss biome. A biome with no music falls back to the default game
     *  music (Musics.GAME); pointing the biome's music at this silent track replaces that with nothing,
     *  so the dimension has no music — only its ambience. */
    val YGANN_MUSIC_SILENCE: RegistrySupplier<SoundEvent> = ambient("music.ygann_abyss.silence")

    val MUSIC_WOHLONNOGONDONIA: RegistrySupplier<SoundEvent> = ambient("music.wohlonnogondonia")
    val WOHLON_AMBIENT_LOOP: RegistrySupplier<SoundEvent> = ambient("ambient.wohlonnogondonia.loop")
    val WOHLON_MOOD: RegistrySupplier<SoundEvent> = ambient("ambient.wohlonnogondonia.mood")
    val WOHLON_ADDITIONS: RegistrySupplier<SoundEvent> = ambient("ambient.wohlonnogondonia.additions")
    val WOHLON_BED_WETLAND: RegistrySupplier<SoundEvent> = ambient("ambient.wohlonnogondonia.ecology.bed_wetland")
    val WOHLON_BED_CANOPY: RegistrySupplier<SoundEvent> = ambient("ambient.wohlonnogondonia.ecology.bed_canopy")
    val WOHLON_BED_HOLLOW: RegistrySupplier<SoundEvent> = ambient("ambient.wohlonnogondonia.ecology.bed_hollow")
    val WOHLON_SPOT_BOUGH: RegistrySupplier<SoundEvent> = ambient("ambient.wohlonnogondonia.ecology.bough")
    val WOHLON_SPOT_FROG: RegistrySupplier<SoundEvent> = ambient("ambient.wohlonnogondonia.ecology.frog")
    val WOHLON_SPOT_FUNGUS: RegistrySupplier<SoundEvent> = ambient("ambient.wohlonnogondonia.ecology.fungus")
    val WOHLON_SPOT_HEARTFIRE: RegistrySupplier<SoundEvent> = ambient("ambient.wohlonnogondonia.ecology.heartfire")
    val WOHLON_SPOT_MUD: RegistrySupplier<SoundEvent> = ambient("ambient.wohlonnogondonia.ecology.mud")
    val WOHLON_SPOT_PAD: RegistrySupplier<SoundEvent> = ambient("ambient.wohlonnogondonia.ecology.pad")
    val WOHLON_SPOT_REED: RegistrySupplier<SoundEvent> = ambient("ambient.wohlonnogondonia.ecology.reed")
    val WOHLON_SPOT_ROOTS: RegistrySupplier<SoundEvent> = ambient("ambient.wohlonnogondonia.ecology.roots")
    val WOHLON_SPOT_ROT: RegistrySupplier<SoundEvent> = ambient("ambient.wohlonnogondonia.ecology.rot")
    val WOHLON_SPOT_WATER: RegistrySupplier<SoundEvent> = ambient("ambient.wohlonnogondonia.ecology.water")
    val WOHLON_PORTAL_LOOP: RegistrySupplier<SoundEvent> = ambient("ambient.wohlonnogondonia.portal_loop")
    val WOHLON_MOTHER_PORTAL_LOOP: RegistrySupplier<SoundEvent> =
        ambient("ambient.wohlonnogondonia.mother_portal_loop")
    val WOHLON_RITUAL_IGNITE: RegistrySupplier<SoundEvent> = ambient("ambient.wohlonnogondonia.ritual.ignite")
    val WOHLON_RITUAL_HEART_ARRIVE: RegistrySupplier<SoundEvent> =
        ambient("ambient.wohlonnogondonia.ritual.heart_arrive")
    val WOHLON_RITUAL_GROW_LOOP: RegistrySupplier<SoundEvent> = ambient("ambient.wohlonnogondonia.ritual.grow_loop")
    val WOHLON_RITUAL_GROW_ROOTS: RegistrySupplier<SoundEvent> =
        ambient("ambient.wohlonnogondonia.ritual.grow_roots")
    val WOHLON_RITUAL_GROW_WOOD: RegistrySupplier<SoundEvent> = ambient("ambient.wohlonnogondonia.ritual.grow_wood")
    val WOHLON_RITUAL_GROW_LEAVES: RegistrySupplier<SoundEvent> =
        ambient("ambient.wohlonnogondonia.ritual.grow_leaves")
    val WOHLON_RITUAL_COMPLETE: RegistrySupplier<SoundEvent> = ambient("ambient.wohlonnogondonia.ritual.complete")
    val HEART_OF_THE_WILD_HEARTBEAT: RegistrySupplier<SoundEvent> = ambient("block.heart_of_the_wild.heartbeat")
    val HEART_OF_THE_WILD_MOTHER_HEARTBEAT: RegistrySupplier<SoundEvent> =
        ambient("block.heart_of_the_wild.mother_heartbeat")
    val WOHLON_PORTAL_TRAVEL: RegistrySupplier<SoundEvent> = ambient("ambient.wohlonnogondonia.portal_travel")
    val WOHLON_RITUAL_SPREAD: RegistrySupplier<SoundEvent> = ambient("ambient.wohlonnogondonia.ritual.spread")
    val WOHLON_RITUAL_GHOST: RegistrySupplier<SoundEvent> = ambient("ambient.wohlonnogondonia.ritual.ghost")
    val WOHLON_RITUAL_CALL: RegistrySupplier<SoundEvent> = ambient("ambient.wohlonnogondonia.ritual.call")
    val HEART_OF_THE_WILD_TENDRIL_GROW: RegistrySupplier<SoundEvent> =
        ambient("block.heart_of_the_wild.tendril_grow")
    val HEART_OF_THE_WILD_BREAK: SoundEvent = eager("block.heart_of_the_wild.break")
    val HEART_OF_THE_WILD_PLACE: SoundEvent = eager("block.heart_of_the_wild.place")
    val HEART_OF_THE_WILD_HIT: SoundEvent = eager("block.heart_of_the_wild.hit")
    val HEART_OF_THE_WILD_STEP: SoundEvent = eager("block.heart_of_the_wild.step")
    val HEART_OF_THE_WILD_SOUND_TYPE: SoundType = SoundType(
        1f, 1f,
        HEART_OF_THE_WILD_BREAK, HEART_OF_THE_WILD_STEP, HEART_OF_THE_WILD_PLACE,
        HEART_OF_THE_WILD_HIT, HEART_OF_THE_WILD_STEP,
    )
    val FRACTAL_ORB_LOOP: RegistrySupplier<SoundEvent> = SOUNDS.register("block.fractal_projector.orb_loop") {
        SoundEvent.createFixedRangeEvent(EnderkinesisMod.id("block.fractal_projector.orb_loop"), FRACTAL_ORB_LOOP_RANGE)
    }
    const val FRACTAL_ORB_LOOP_RANGE: Float = 24f
    val FRACTAL_ORB_COLLIDE: RegistrySupplier<SoundEvent> = ambient("block.fractal_projector.orb_collide")
    val CONSTRILIAR_AWAKEN: RegistrySupplier<SoundEvent> = ambient("entity.constriliar.awaken")
    val CONSTRILIAR_AMBIENT: RegistrySupplier<SoundEvent> = ambient("entity.constriliar.ambient")
    val CONSTRILIAR_ACKNOWLEDGE: RegistrySupplier<SoundEvent> = ambient("entity.constriliar.acknowledge")
    val CONSTRILIAR_HAPPY: RegistrySupplier<SoundEvent> = ambient("entity.constriliar.happy")
    val CONSTRILIAR_SIT: RegistrySupplier<SoundEvent> = ambient("entity.constriliar.sit")
    val CONSTRILIAR_STAND: RegistrySupplier<SoundEvent> = ambient("entity.constriliar.stand")
    val CONSTRILIAR_JUMP: RegistrySupplier<SoundEvent> = ambient("entity.constriliar.jump")
    val CONSTRILIAR_LAND: RegistrySupplier<SoundEvent> = ambient("entity.constriliar.land")
    val CONSTRILIAR_IMPACT: RegistrySupplier<SoundEvent> = ambient("entity.constriliar.impact")
    val CONSTRILIAR_IMPACT_HEAVY: RegistrySupplier<SoundEvent> = ambient("entity.constriliar.impact_heavy")
    val CONSTRILIAR_PANIC: RegistrySupplier<SoundEvent> = ambient("entity.constriliar.panic")
    val CONSTRILIAR_STRAIN: RegistrySupplier<SoundEvent> = ambient("entity.constriliar.strain")
    val CONSTRILIAR_DEATH: RegistrySupplier<SoundEvent> = ambient("entity.constriliar.death")
    val SHULKONAUT_AMBIENT: RegistrySupplier<SoundEvent> = ambient("entity.shulkonaut.ambient")
    val SHULKONAUT_STUDY: RegistrySupplier<SoundEvent> = ambient("entity.shulkonaut.study")
    val SHULKONAUT_REJECT: RegistrySupplier<SoundEvent> = ambient("entity.shulkonaut.reject")
    val SHULKONAUT_CLEAR: RegistrySupplier<SoundEvent> = ambient("entity.shulkonaut.clear")
    val SHULKONAUT_TRANSFER: RegistrySupplier<SoundEvent> = ambient("entity.shulkonaut.transfer")
    val SHULKONAUT_SPOT: RegistrySupplier<SoundEvent> = ambient("entity.shulkonaut.spot")
    val SHULKONAUT_COMMAND: RegistrySupplier<SoundEvent> = ambient("entity.shulkonaut.command")
    val SHULKONAUT_HURT: RegistrySupplier<SoundEvent> = ambient("entity.shulkonaut.hurt")
    val SHULKONAUT_DEATH: RegistrySupplier<SoundEvent> = ambient("entity.shulkonaut.death")
    val WIK_LAK_BIND: RegistrySupplier<SoundEvent> = ambient("entity.wik_lak_host.bind")

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

    /** Magic-missile detonation blast. A *variable-range* event (same attenuation curve as
     *  `entity.generic.explode`): a volume of `4.0f` at the call site gives ~64 blocks of
     *  audible range. */
    val MISSILE_BURST_BLAST: RegistrySupplier<SoundEvent> = SOUNDS.register("missile_burst_blast") {
        SoundEvent.createVariableRangeEvent(EnderkinesisMod.id("missile_burst_blast"))
    }
    val ROD_OF_LEVITATION_LIFT: RegistrySupplier<SoundEvent> = ambient("item.rod_of_levitation.lift")
    val ROD_OF_LEVITATION_NUDGE: RegistrySupplier<SoundEvent> = ambient("item.rod_of_levitation.nudge")
    val MAGIC_MISSILE_LAUNCH: RegistrySupplier<SoundEvent> = ambient("entity.magic_missile.launch")
    val MAGIC_MISSILE_FIZZLE: RegistrySupplier<SoundEvent> = ambient("entity.magic_missile.fizzle")
    val MAGIC_MISSILE_LAUNCHER_LOAD: RegistrySupplier<SoundEvent> = ambient("block.magic_missile_launcher.load")
    val MAGIC_MISSILE_LAUNCHER_UNLOAD: RegistrySupplier<SoundEvent> =
        ambient("block.magic_missile_launcher.unload")

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

    val FUSION_CAST: RegistrySupplier<SoundEvent> = ambient("item.staff_of_fusion.cast")
    val FUSION_HUM_LOOP: RegistrySupplier<SoundEvent> = ambient("item.staff_of_fusion.hum_loop")
    val FUSION_PULL_LOOP: RegistrySupplier<SoundEvent> = ambient("item.staff_of_fusion.pull_loop")
    val FUSION_CONTACT: RegistrySupplier<SoundEvent> = ambient("item.staff_of_fusion.contact")
    val FUSION_WELD: RegistrySupplier<SoundEvent> = ambient("item.staff_of_fusion.weld")
    val FUSION_RELEASE: RegistrySupplier<SoundEvent> = ambient("item.staff_of_fusion.release")

    val AEGIS_RAISE: RegistrySupplier<SoundEvent> = ambient("item.staff_of_aegis.raise")
    val AEGIS_HUM_LOOP: RegistrySupplier<SoundEvent> = ambient("item.staff_of_aegis.hum_loop")
    val AEGIS_DEFLECT: RegistrySupplier<SoundEvent> = ambient("item.staff_of_aegis.deflect")
    val AEGIS_PUSH: RegistrySupplier<SoundEvent> = ambient("item.staff_of_aegis.push")
    val AEGIS_SHATTER: RegistrySupplier<SoundEvent> = ambient("item.staff_of_aegis.shatter")
    val AEGIS_SHARD_BOUNCE: RegistrySupplier<SoundEvent> = ambient("item.staff_of_aegis.shard_bounce")
    val INCANTATION_OF_WINDS_START: RegistrySupplier<SoundEvent> = ambient("item.incantation_of_winds.start")
    val INCANTATION_OF_WINDS_LOOP: RegistrySupplier<SoundEvent> = ambient("item.incantation_of_winds.loop")
    val INCANTATION_OF_WINDS_STOP: RegistrySupplier<SoundEvent> = ambient("item.incantation_of_winds.stop")

    val WYLLAND_GRAB: RegistrySupplier<SoundEvent> = ambient("item.wylland_tome.grab")
    val WYLLAND_RIP: RegistrySupplier<SoundEvent> = ambient("item.wylland_tome.rip")
    val WYLLAND_HOLD_LOOP: RegistrySupplier<SoundEvent> = ambient("item.wylland_tome.hold_loop")
    val WYLLAND_RELEASE: RegistrySupplier<SoundEvent> = ambient("item.wylland_tome.release")
    val WYLLAND_SEVER: RegistrySupplier<SoundEvent> = ambient("item.wylland_tome.sever")
    val WYLLAND_SPENT: RegistrySupplier<SoundEvent> = ambient("item.wylland_tome.spent")
    val WYLLAND_REPAIR: RegistrySupplier<SoundEvent> = ambient("item.wylland_tome.repair")

    val PARALLELOGRAPH_ACTIVATE: RegistrySupplier<SoundEvent> = ambient("item.parallelograph.activate")

    val CREPUSCULITE_CHARM_HOVER: RegistrySupplier<SoundEvent> = ambient("item.crepusculite_charm.hover")
    val CREPUSCULITE_CHARM_BREAK: RegistrySupplier<SoundEvent> = ambient("item.crepusculite_charm.break")

    val CREPUSCULITE_LATTICE_HUM: RegistrySupplier<SoundEvent> = ambient("block.crepusculite_lattice.hum")
    val CREPUSCULITE_LATTICE_ACTIVATE: RegistrySupplier<SoundEvent> = ambient("block.crepusculite_lattice.activate")
    val CREPUSCULITE_LATTICE_DEACTIVATE: RegistrySupplier<SoundEvent> =
        ambient("block.crepusculite_lattice.deactivate")
    val CREPUSCULITE_LATTICE_OCEAN_BED: RegistrySupplier<SoundEvent> =
        ambient("block.crepusculite_lattice.ocean_bed")
    val CREPUSCULITE_LATTICE_ASSEMBLE: RegistrySupplier<SoundEvent> = ambient("block.crepusculite_lattice.assemble")
    val CREPUSCULITE_LATTICE_ASSEMBLE_REFUSED: RegistrySupplier<SoundEvent> =
        ambient("block.crepusculite_lattice.assemble_refused")
    val CREPUSCULITE_LATTICE_COLLAPSE: RegistrySupplier<SoundEvent> = ambient("block.crepusculite_lattice.collapse")
    val CREPUSCULITE_LATTICE_SPLASH: RegistrySupplier<SoundEvent> = ambient("block.crepusculite_lattice.splash")
    val CREPUSCULITE_LATTICE_CATCH: RegistrySupplier<SoundEvent> = ambient("block.crepusculite_lattice.catch")
    val CREPUSCULITE_LATTICE_SEA_LEVEL_UP: RegistrySupplier<SoundEvent> =
        ambient("block.crepusculite_lattice.sea_level_up")
    val CREPUSCULITE_LATTICE_SEA_LEVEL_DOWN: RegistrySupplier<SoundEvent> =
        ambient("block.crepusculite_lattice.sea_level_down")
    val CREPUSCULITE_LATTICE_TINT: RegistrySupplier<SoundEvent> = ambient("block.crepusculite_lattice.tint")
    val CREPUSCULITE_LATTICE_ALMANAC: RegistrySupplier<SoundEvent> = ambient("block.crepusculite_lattice.almanac")
    val CREPUSCULITE_LATTICE_WAKE: RegistrySupplier<SoundEvent> = ambient("block.crepusculite_lattice.wake")

    val GAMYLAN_CAPTURE: RegistrySupplier<SoundEvent> = ambient("item.wand_of_gamylan.capture")
    val GAMYLAN_CAGE_HIT: RegistrySupplier<SoundEvent> = ambient("entity.gamylan_cage.hit")
    val GAMYLAN_CAGE_SHATTER: RegistrySupplier<SoundEvent> = ambient("entity.gamylan_cage.shatter")

    val CRYSTAL_PEDESTAL_CONJURE: RegistrySupplier<SoundEvent> = ambient("block.crystal_pedestal.conjure")
    val CRYSTAL_PEDESTAL_DOCK: RegistrySupplier<SoundEvent> = ambient("block.crystal_pedestal.dock")
    val CRYSTAL_PEDESTAL_RELEASE: RegistrySupplier<SoundEvent> = ambient("block.crystal_pedestal.release")
    val CRYSTAL_PEDESTAL_ALIGN: RegistrySupplier<SoundEvent> = ambient("block.crystal_pedestal.align")
    val CRYSTAL_PEDESTAL_PLACE: SoundEvent = eager("block.crystal_pedestal.place")
    val CRYSTAL_PEDESTAL_BREAK: SoundEvent = eager("block.crystal_pedestal.break")
    val CRYSTAL_PEDESTAL_SOUND_TYPE: SoundType = SoundType(
        1f, 1f,
        CRYSTAL_PEDESTAL_BREAK, SoundEvents.DEEPSLATE_BRICKS_STEP, CRYSTAL_PEDESTAL_PLACE,
        SoundEvents.DEEPSLATE_BRICKS_HIT, SoundEvents.DEEPSLATE_BRICKS_FALL,
    )

    val PLANAR_ANCHOR_FIRE: RegistrySupplier<SoundEvent> = ambient("block.planar_anchor.fire")
    val PLANAR_ANCHOR_RELEASE: RegistrySupplier<SoundEvent> = ambient("block.planar_anchor.release")
    val PLANAR_ANCHOR_LATCH: RegistrySupplier<SoundEvent> = ambient("block.planar_anchor.latch")
    val PLANAR_ANCHOR_HOLD: RegistrySupplier<SoundEvent> = ambient("block.planar_anchor.hold")
    val PLANAR_ANCHOR_PORTAL: RegistrySupplier<SoundEvent> = ambient("block.planar_anchor.portal")
    val PLANAR_ANCHOR_PLACE: SoundEvent = eager("block.planar_anchor.place")
    val PLANAR_ANCHOR_BREAK: SoundEvent = eager("block.planar_anchor.break")
    val PLANAR_ANCHOR_SOUND_TYPE: SoundType = SoundType(
        1f, 1f,
        PLANAR_ANCHOR_BREAK, SoundEvents.STONE_STEP, PLANAR_ANCHOR_PLACE,
        SoundEvents.STONE_HIT, SoundEvents.STONE_FALL,
    )

    val DREAM_SAND_STEP: SoundEvent = eager("block.dream_sand.step")
    val DREAM_SAND_SOUND_TYPE: SoundType = SoundType(
        1f, 1f,
        SoundEvents.SAND_BREAK, DREAM_SAND_STEP, SoundEvents.SAND_PLACE,
        SoundEvents.SAND_HIT, SoundEvents.SAND_FALL,
    )

    val CREPUSCULITE_STEP: SoundEvent = eager("block.crepusculite.step")
    val CREPUSCULITE_PLACE: SoundEvent = eager("block.crepusculite.place")
    val CREPUSCULITE_BREAK: SoundEvent = eager("block.crepusculite.break")
    val CREPUSCULITE_CHIME: SoundEvent = eager("block.crepusculite.chime")
    val CREPUSCULITE_RESONATE: SoundEvent = eager("block.crepusculite.resonate")
    val AETHER_PAD_PLACE: SoundEvent = eager("block.aether_pad.place")
    val AETHER_PAD_BREAK: SoundEvent = eager("block.aether_pad.break")
    val AETHER_PAD_SOUND_TYPE: SoundType = SoundType(
        1f, 1f,
        AETHER_PAD_BREAK, CREPUSCULITE_STEP, AETHER_PAD_PLACE,
        CREPUSCULITE_STEP, CREPUSCULITE_STEP,
    )
    val SHULKER_STRUT_OPEN: RegistrySupplier<SoundEvent> = ambient("block.shulker_strut.open")
    val SHULKER_STRUT_CLOSE: RegistrySupplier<SoundEvent> = ambient("block.shulker_strut.close")
    val SHULKER_STRUT_EXTEND: RegistrySupplier<SoundEvent> = ambient("block.shulker_strut.extend")
    val SHULKER_STRUT_RETRACT: RegistrySupplier<SoundEvent> = ambient("block.shulker_strut.retract")
    val SHULKER_STRUT_PLACE: SoundEvent = eager("block.shulker_strut.place")
    val SHULKER_STRUT_BREAK: SoundEvent = eager("block.shulker_strut.break")
    val SHULKER_STRUT_SOUND_TYPE: SoundType = SoundType(
        1f, 1f,
        SHULKER_STRUT_BREAK, SoundEvents.STONE_STEP, SHULKER_STRUT_PLACE,
        SoundEvents.STONE_HIT, SoundEvents.STONE_FALL,
    )
    val SHULKER_PUFFER_PLACE: SoundEvent = eager("block.shulker_puffer.place")
    val SHULKER_PUFFER_BREAK: SoundEvent = eager("block.shulker_puffer.break")
    val SHULKER_PUFFER_SOUND_TYPE: SoundType = SoundType(
        1f, 1f,
        SHULKER_PUFFER_BREAK, SoundEvents.STONE_STEP, SHULKER_PUFFER_PLACE,
        SoundEvents.STONE_HIT, SoundEvents.STONE_FALL,
    )
    val ICHAERIC_INK_BOTTLE_FILL: RegistrySupplier<SoundEvent> = ambient("block.ichaeric_ink.bottle_fill")
    val ICHAERIC_INK_BOTTLE_FILL_LAST: RegistrySupplier<SoundEvent> = ambient("block.ichaeric_ink.bottle_fill_last")
    // Architectury fluid attributes take a SoundEvent, not a supplier.
    val ICHAERIC_INK_BUCKET_FILL: SoundEvent = eager("block.ichaeric_ink.bucket_fill")
    val ICHAERIC_INK_BUCKET_EMPTY: SoundEvent = eager("block.ichaeric_ink.bucket_empty")
    val ICHAERIC_INK_JAR_POUR: RegistrySupplier<SoundEvent> = ambient("block.ichaeric_ink.jar_pour")
    val ICHAERIC_INK_GLASS_PLACE: SoundEvent = eager("block.ichaeric_ink.glass_place")
    val ICHAERIC_INK_GLASS_BREAK: SoundEvent = eager("block.ichaeric_ink.glass_break")
    val ICHAERIC_INK_GLASS_SOUND_TYPE: SoundType = SoundType(
        1f, 1f,
        ICHAERIC_INK_GLASS_BREAK, SoundEvents.GLASS_STEP, ICHAERIC_INK_GLASS_PLACE,
        SoundEvents.GLASS_HIT, SoundEvents.GLASS_FALL,
    )
    val ICHAERIC_INK_SPLASH_IN: RegistrySupplier<SoundEvent> = ambient("block.ichaeric_ink.splash_in")
    val ICHAERIC_INK_SPLASH_OUT: RegistrySupplier<SoundEvent> = ambient("block.ichaeric_ink.splash_out")
    val ICHAERIC_INK_SWIM: RegistrySupplier<SoundEvent> = ambient("block.ichaeric_ink.swim")
    val ICHAERIC_INK_UNDER_LOOP: RegistrySupplier<SoundEvent> = ambient("block.ichaeric_ink.under_loop")
    val ICHAERIC_INK_FLOW: RegistrySupplier<SoundEvent> = ambient("block.ichaeric_ink.flow")
    val INEGRIC_RETORT_DRIP: RegistrySupplier<SoundEvent> = ambient("block.inegric_retort.drip")
    val INEGRIC_RETORT_SPLAT: RegistrySupplier<SoundEvent> = ambient("block.inegric_retort.splat")
    val INEGRIC_RETORT_DRIP_LAND: RegistrySupplier<SoundEvent> = ambient("block.inegric_retort.drip_land")
    val SCRIBES_DESK_LOAD_PAPER: RegistrySupplier<SoundEvent> = ambient("block.scribes_desk.load_paper")
    val SCRIBES_DESK_LOAD_QUILL: RegistrySupplier<SoundEvent> = ambient("block.scribes_desk.load_quill")
    val SCRIBES_DESK_LOAD_INK: RegistrySupplier<SoundEvent> = ambient("block.scribes_desk.load_ink")
    val SCRIBES_DESK_INSCRIBE: RegistrySupplier<SoundEvent> = ambient("block.scribes_desk.inscribe")
    val OARSCRIBE_START: RegistrySupplier<SoundEvent> = ambient("block.oarscribe.start")
    val OARSCRIBE_LOOP: RegistrySupplier<SoundEvent> = ambient("block.oarscribe.loop")
    val OARSCRIBE_STOP: RegistrySupplier<SoundEvent> = ambient("block.oarscribe.stop")
    val OARSCRIBE_TURN: RegistrySupplier<SoundEvent> = ambient("block.oarscribe.turn")
    val OARSCRIBE_MIRROR: RegistrySupplier<SoundEvent> = ambient("block.oarscribe.mirror")
    val OARSCRIBE_LOAD_QUILL: RegistrySupplier<SoundEvent> = ambient("block.oarscribe.load_quill")
    val OARSCRIBE_PEN_BREAK: RegistrySupplier<SoundEvent> = ambient("block.oarscribe.pen_break")
    val JAR_RECEPTACLE_JAR_IN: RegistrySupplier<SoundEvent> = ambient("block.jar_receptacle.jar_in")
    val JAR_RECEPTACLE_JAR_OUT: RegistrySupplier<SoundEvent> = ambient("block.jar_receptacle.jar_out")
    val JAR_RECEPTACLE_FEED: RegistrySupplier<SoundEvent> = ambient("block.jar_receptacle.feed")
    val CREPUSCULITE_SOUND_TYPE: SoundType = SoundType(
        1f, 1f,
        CREPUSCULITE_BREAK, CREPUSCULITE_STEP, CREPUSCULITE_PLACE,
        CREPUSCULITE_STEP, CREPUSCULITE_STEP,
    )

    val CREPUSCULITE_GLASS_STEP: SoundEvent = eager("block.crepusculite_glass.step")
    val CREPUSCULITE_GLASS_PLACE: SoundEvent = eager("block.crepusculite_glass.place")
    val CREPUSCULITE_GLASS_BREAK: SoundEvent = eager("block.crepusculite_glass.break")
    val CREPUSCULITE_GLASS_SOUND_TYPE: SoundType = SoundType(
        1f, 1f,
        CREPUSCULITE_GLASS_BREAK, CREPUSCULITE_GLASS_STEP, CREPUSCULITE_GLASS_PLACE,
        CREPUSCULITE_GLASS_STEP, CREPUSCULITE_GLASS_STEP,
    )

    val VOID_HARNESS_PLACE: SoundEvent = eager("block.void_harness.place")
    val VOID_HARNESS_BREAK: SoundEvent = eager("block.void_harness.break")
    val VOID_HARNESS_SOUND_TYPE: SoundType = SoundType(
        1f, 1f,
        VOID_HARNESS_BREAK, CREPUSCULITE_GLASS_STEP, VOID_HARNESS_PLACE,
        CREPUSCULITE_GLASS_STEP, CREPUSCULITE_GLASS_STEP,
    )
    val VOID_HOOK_PLACE: SoundEvent = eager("block.void_hook.place")
    val VOID_HOOK_BREAK: SoundEvent = eager("block.void_hook.break")
    val VOID_HOOK_SOUND_TYPE: SoundType = SoundType(
        1f, 1f,
        VOID_HOOK_BREAK, CREPUSCULITE_GLASS_STEP, VOID_HOOK_PLACE,
        CREPUSCULITE_GLASS_STEP, CREPUSCULITE_GLASS_STEP,
    )
    val VOID_HOOK_LOOP: RegistrySupplier<SoundEvent> = SOUNDS.register("block.void_hook.loop") {
        SoundEvent.createFixedRangeEvent(EnderkinesisMod.id("block.void_hook.loop"), VOID_HOOK_LOOP_RANGE)
    }
    const val VOID_HOOK_LOOP_RANGE: Float = 16f
    val VOID_HOOK_POWER_ON: RegistrySupplier<SoundEvent> = ambient("block.void_hook.power_on")
    val VOID_HOOK_POWER_OFF: RegistrySupplier<SoundEvent> = ambient("block.void_hook.power_off")
    val VOID_HOOK_RIP: RegistrySupplier<SoundEvent> = ambient("block.void_hook.rip")

    val VOID_HARNESS_SPIN_UP: RegistrySupplier<SoundEvent> = ambient("block.void_harness.spin_up")
    val VOID_HARNESS_WIND_DOWN: RegistrySupplier<SoundEvent> = ambient("block.void_harness.wind_down")
    val VOID_HARNESS_HUM: RegistrySupplier<SoundEvent> = SOUNDS.register("block.void_harness.hum") {
        SoundEvent.createFixedRangeEvent(EnderkinesisMod.id("block.void_harness.hum"), VOID_HARNESS_LOOP_RANGE)
    }
    val VOID_HARNESS_STRAIN: RegistrySupplier<SoundEvent> = SOUNDS.register("block.void_harness.strain") {
        SoundEvent.createFixedRangeEvent(EnderkinesisMod.id("block.void_harness.strain"), VOID_HARNESS_LOOP_RANGE)
    }
    const val VOID_HARNESS_LOOP_RANGE: Float = 16f

    val AETHER_PAD_LOOP: RegistrySupplier<SoundEvent> = SOUNDS.register("block.aether_pad.loop") {
        SoundEvent.createFixedRangeEvent(EnderkinesisMod.id("block.aether_pad.loop"), AETHER_PAD_LOOP_RANGE)
    }
    const val AETHER_PAD_LOOP_RANGE: Float = 16f
    val AETHER_PAD_POWER_ON: RegistrySupplier<SoundEvent> = ambient("block.aether_pad.power_on")
    val AETHER_PAD_POWER_OFF: RegistrySupplier<SoundEvent> = ambient("block.aether_pad.power_off")
    val AETHER_PAD_GROUND: RegistrySupplier<SoundEvent> = ambient("block.aether_pad.ground")

    val SHULKER_PUFFER_LOOP: RegistrySupplier<SoundEvent> = SOUNDS.register("block.shulker_puffer.loop") {
        SoundEvent.createFixedRangeEvent(EnderkinesisMod.id("block.shulker_puffer.loop"), SHULKER_PUFFER_LOOP_RANGE)
    }
    val SHULKER_PUFFER_LOOP_FUEL: RegistrySupplier<SoundEvent> = SOUNDS.register("block.shulker_puffer.loop_fuel") {
        SoundEvent.createFixedRangeEvent(
            EnderkinesisMod.id("block.shulker_puffer.loop_fuel"), SHULKER_PUFFER_LOOP_RANGE,
        )
    }
    const val SHULKER_PUFFER_LOOP_RANGE: Float = 16f
    val SHULKER_PUFFER_POWER_ON: RegistrySupplier<SoundEvent> = ambient("block.shulker_puffer.power_on")
    val SHULKER_PUFFER_POWER_OFF: RegistrySupplier<SoundEvent> = ambient("block.shulker_puffer.power_off")
    val SHULKER_PUFFER_FUEL_INSERT: RegistrySupplier<SoundEvent> = ambient("block.shulker_puffer.fuel_insert")
    val SHULKER_PUFFER_FUEL_REMOVE: RegistrySupplier<SoundEvent> = ambient("block.shulker_puffer.fuel_remove")
    val SHULKER_PUFFER_FUEL_IGNITE: RegistrySupplier<SoundEvent> = ambient("block.shulker_puffer.fuel_ignite")
    val SHULKER_PUFFER_FUEL_OUT: RegistrySupplier<SoundEvent> = ambient("block.shulker_puffer.fuel_out")

    val ORB_OF_SCRYING_SCRY_BEGIN: RegistrySupplier<SoundEvent> = ambient("block.orb_of_scrying.scry_begin")
    val ORB_OF_SCRYING_SCRY_LOOP: RegistrySupplier<SoundEvent> = ambient("block.orb_of_scrying.scry_loop")
    val ORB_OF_SCRYING_SCRY_END: RegistrySupplier<SoundEvent> = ambient("block.orb_of_scrying.scry_end")
    val ORB_OF_SCRYING_SCRY_FAIL: RegistrySupplier<SoundEvent> = ambient("block.orb_of_scrying.scry_fail")
    val ORB_OF_SCRYING_CONVERT: RegistrySupplier<SoundEvent> = ambient("block.orb_of_scrying.convert")
    val ORB_OF_SCRYING_COVER: RegistrySupplier<SoundEvent> = ambient("block.orb_of_scrying.cover")
    val ORB_OF_SCRYING_UNCOVER: RegistrySupplier<SoundEvent> = ambient("block.orb_of_scrying.uncover")

    val EIDETIC_BOOKMARK_SAVE: RegistrySupplier<SoundEvent> = ambient("item.eidetic_bookmark.save")
    val EIDETIC_BOOKMARK_READ: RegistrySupplier<SoundEvent> = ambient("item.eidetic_bookmark.read")
    val EIDETIC_BOOKMARK_DRAW: RegistrySupplier<SoundEvent> = ambient("item.eidetic_bookmark.draw")
    val EIDETIC_BOOKMARK_READY_LOOP: RegistrySupplier<SoundEvent> = ambient("item.eidetic_bookmark.ready_loop")
    val EIDETIC_BOOKMARK_TRAVEL: RegistrySupplier<SoundEvent> = ambient("item.eidetic_bookmark.travel")
    val EIDETIC_BOOKMARK_FIZZLE: RegistrySupplier<SoundEvent> = ambient("item.eidetic_bookmark.fizzle")

    val SCROLL_OF_UNRAVELLING_UNRAVEL: RegistrySupplier<SoundEvent> = ambient("item.scroll_of_unravelling.unravel")

    val ORB_OF_LINKING_SELECT: RegistrySupplier<SoundEvent> = ambient("block.orb_of_linking.select")
    val ORB_OF_LINKING_UNLINK: RegistrySupplier<SoundEvent> = ambient("block.orb_of_linking.unlink")
    val ORB_OF_LINKING_DISMANTLE: RegistrySupplier<SoundEvent> = ambient("block.orb_of_linking.dismantle")
    val ORB_OF_LINKING_SNAP: RegistrySupplier<SoundEvent> = ambient("block.orb_of_linking.snap")
    val ORB_OF_LINKING_FILTER_SET: RegistrySupplier<SoundEvent> = ambient("block.orb_of_linking.filter_set")
    val ORB_OF_LINKING_FILTER_CLEAR: RegistrySupplier<SoundEvent> = ambient("block.orb_of_linking.filter_clear")
    val ORB_OF_LINKING_BOOK_SET: RegistrySupplier<SoundEvent> = ambient("block.orb_of_linking.book_set")
    val ORB_OF_LINKING_BOOK_RELEASE: RegistrySupplier<SoundEvent> = ambient("block.orb_of_linking.book_release")
    val ORB_OF_LINKING_BRAKE_ON: RegistrySupplier<SoundEvent> = ambient("block.orb_of_linking.brake_on")
    val ORB_OF_LINKING_BRAKE_OFF: RegistrySupplier<SoundEvent> = ambient("block.orb_of_linking.brake_off")
    val ORB_OF_LINKING_REEL_IN: RegistrySupplier<SoundEvent> = ambient("block.orb_of_linking.reel_in")
    val ORB_OF_LINKING_REEL_OUT: RegistrySupplier<SoundEvent> = ambient("block.orb_of_linking.reel_out")
    val ORB_OF_LINKING_RETURN: RegistrySupplier<SoundEvent> = ambient("block.orb_of_linking.return")
    val ORB_OF_LINKING_DROP: RegistrySupplier<SoundEvent> = ambient("block.orb_of_linking.drop")
    val ORB_OF_LINKING_REFUSE: RegistrySupplier<SoundEvent> = ambient("block.orb_of_linking.refuse")
    val ORB_OF_LINKING_SEND: RegistrySupplier<SoundEvent> = ambient("block.orb_of_linking.send")
    val ORB_OF_LINKING_ARRIVE: RegistrySupplier<SoundEvent> = ambient("block.orb_of_linking.arrive")
    val ORB_OF_LINKING_SUCK: RegistrySupplier<SoundEvent> = ambient("block.orb_of_linking.suck")
    val ORB_OF_LINKING_BEAM_LOOP: RegistrySupplier<SoundEvent> = ambient("block.orb_of_linking.beam_loop")
    val ORB_OF_LINKING_GRIND_LOOP: RegistrySupplier<SoundEvent> = ambient("block.orb_of_linking.grind_loop")
    val ORB_OF_LINKING_HUM_LOOP: RegistrySupplier<SoundEvent> = ambient("block.orb_of_linking.hum_loop")
    val ECHO_CANNON_CHARGE: RegistrySupplier<SoundEvent> = ambient("block.echo_cannon.charge")
    val ECHO_CANNON_FIRE: RegistrySupplier<SoundEvent> = ambient("block.echo_cannon.fire")
    val ECHO_CANNON_VENT: RegistrySupplier<SoundEvent> = ambient("block.echo_cannon.vent")
    val SCROLL_OF_SCULK_CATASTROPHE_USE: RegistrySupplier<SoundEvent> = ambient("item.scroll_of_sculk_catastrophe.use")
    val SCULK_CATASTROPHE: RegistrySupplier<SoundEvent> = ambient("event.sculk_catastrophe")

    /** Keyed by the tome kind path without its `tome_of_` prefix. */
    val ORB_OF_LINKING_LINK: Map<String, RegistrySupplier<SoundEvent>> = listOf(
        "binding", "chaining", "coupling", "disintegration", "elasticity", "hinging", "pulling",
        "pushing", "signal", "spring", "transportation", "vacuum", "ventriloquism",
    ).associateWith { ambient("block.orb_of_linking.link_$it") }

    class RobeSet(set: String) {
        val equipHead: RegistrySupplier<SoundEvent> = ambient("item.robes.$set.equip_head")
        val equipTop: RegistrySupplier<SoundEvent> = ambient("item.robes.$set.equip_top")
        val equipBottoms: RegistrySupplier<SoundEvent> = ambient("item.robes.$set.equip_bottoms")
        val equipFeet: RegistrySupplier<SoundEvent> = ambient("item.robes.$set.equip_feet")
        val flutterLoop: RegistrySupplier<SoundEvent> = ambient("item.robes.$set.flutter_loop")
    }

    val ROBES_END_CULT: RobeSet = RobeSet("end_cult")
    val ROBES_SCHOLAR: RobeSet = RobeSet("scholar")
    val ROBES_BLUE_WITCH: RobeSet = RobeSet("blue_witch")
    val ROBES_BLUE_WITCH_HAT_FLOP: RegistrySupplier<SoundEvent> = ambient("item.robes.blue_witch.hat_flop")

    val SATCHEL_OF_SHELVING_OPEN: RegistrySupplier<SoundEvent> = ambient("item.satchel_of_shelving.open")
    val SATCHEL_OF_SHELVING_CLOSE: RegistrySupplier<SoundEvent> = ambient("item.satchel_of_shelving.close")
    val SATCHEL_OF_SHELVING_UNLOAD: RegistrySupplier<SoundEvent> = ambient("item.satchel_of_shelving.unload")
    val SATCHEL_OF_SHELVING_REFUSE: RegistrySupplier<SoundEvent> = ambient("item.satchel_of_shelving.refuse")
    val SATCHEL_OF_SHELVING_ROW_ADD: RegistrySupplier<SoundEvent> = ambient("item.satchel_of_shelving.row_add")
    val SATCHEL_OF_SHELVING_ROW_REMOVE: RegistrySupplier<SoundEvent> = ambient("item.satchel_of_shelving.row_remove")
    val SATCHEL_OF_SHELVING_SCROLL_END: RegistrySupplier<SoundEvent> = ambient("item.satchel_of_shelving.scroll_end")
    val SATCHEL_OF_SHELVING_SKY_LOOP: RegistrySupplier<SoundEvent> = ambient("item.satchel_of_shelving.sky_loop")
    val SATCHEL_OF_SHELVING_EQUIP: RegistrySupplier<SoundEvent> = ambient("item.satchel_of_shelving.equip")
    val SATCHEL_OF_SHELVING_INSERT: RegistrySupplier<SoundEvent> = ambient("item.satchel_of_shelving.insert")
    val SATCHEL_OF_SHELVING_EXTRACT: RegistrySupplier<SoundEvent> = ambient("item.satchel_of_shelving.extract")
    val SATCHEL_OF_SHELVING_SCROLL: RegistrySupplier<SoundEvent> = ambient("item.satchel_of_shelving.scroll")

    val ENDER_ASTROLABE_STEP: SoundEvent = eager("block.ender_astrolabe.step")
    val ENDER_ASTROLABE_PLACE: SoundEvent = eager("block.ender_astrolabe.place")
    val ENDER_ASTROLABE_BREAK: SoundEvent = eager("block.ender_astrolabe.break")
    val ENDER_ASTROLABE_SOUND_TYPE: SoundType = SoundType(
        1f, 1f,
        ENDER_ASTROLABE_BREAK, ENDER_ASTROLABE_STEP, ENDER_ASTROLABE_PLACE,
        ENDER_ASTROLABE_STEP, ENDER_ASTROLABE_STEP,
    )
    val ENDER_ASTROLABE_TUNE: RegistrySupplier<SoundEvent> = ambient("block.ender_astrolabe.tune")
    val ENDER_ASTROLABE_CHARGE_START: RegistrySupplier<SoundEvent> = ambient("block.ender_astrolabe.charge_start")
    val ENDER_ASTROLABE_CHARGE_LOOP: RegistrySupplier<SoundEvent> = ambient("block.ender_astrolabe.charge_loop")
    val ENDER_ASTROLABE_JUMP: RegistrySupplier<SoundEvent> = ambient("block.ender_astrolabe.jump")
    val ENDER_ASTROLABE_FAIL: RegistrySupplier<SoundEvent> = ambient("block.ender_astrolabe.fail")
    val ENDER_ASTROLABE_CANCEL: RegistrySupplier<SoundEvent> = ambient("block.ender_astrolabe.cancel")

    val ANCRITE_STEP: SoundEvent = eager("block.ancrite.step")
    val ANCRITE_PLACE: SoundEvent = eager("block.ancrite.place")
    val ANCRITE_BREAK: SoundEvent = eager("block.ancrite.break")
    val ANCRITE_SOUND_TYPE: SoundType = SoundType(
        1f, 1f,
        ANCRITE_BREAK, ANCRITE_STEP, ANCRITE_PLACE,
        ANCRITE_STEP, ANCRITE_STEP,
    )

    val RAW_ANCRITE_STEP: SoundEvent = eager("block.raw_ancrite.step")
    val RAW_ANCRITE_PLACE: SoundEvent = eager("block.raw_ancrite.place")
    val RAW_ANCRITE_BREAK: SoundEvent = eager("block.raw_ancrite.break")
    val RAW_ANCRITE_SOUND_TYPE: SoundType = SoundType(
        1f, 1f,
        RAW_ANCRITE_BREAK, RAW_ANCRITE_STEP, RAW_ANCRITE_PLACE,
        RAW_ANCRITE_STEP, RAW_ANCRITE_STEP,
    )

    val ANCRITE_GRATE_STEP: SoundEvent = eager("block.ancrite_grate.step")
    val ANCRITE_GRATE_PLACE: SoundEvent = eager("block.ancrite_grate.place")
    val ANCRITE_GRATE_BREAK: SoundEvent = eager("block.ancrite_grate.break")
    val ANCRITE_GRATE_SOUND_TYPE: SoundType = SoundType(
        1f, 1f,
        ANCRITE_GRATE_BREAK, ANCRITE_GRATE_STEP, ANCRITE_GRATE_PLACE,
        ANCRITE_GRATE_STEP, ANCRITE_GRATE_STEP,
    )

    val ANCRITE_CHAIN_STEP: SoundEvent = eager("block.ancrite_chain.step")
    val ANCRITE_CHAIN_BREAK: SoundEvent = eager("block.ancrite_chain.break")
    val ANCRITE_CHAIN_SOUND_TYPE: SoundType = SoundType(
        1f, 1f,
        ANCRITE_CHAIN_BREAK, ANCRITE_CHAIN_STEP, ANCRITE_CHAIN_BREAK,
        ANCRITE_CHAIN_STEP, ANCRITE_CHAIN_STEP,
    )

    val EYEROSCOPE_PLACE: SoundEvent = eager("block.eyeroscope.place")
    val EYEROSCOPE_BREAK: SoundEvent = eager("block.eyeroscope.break")
    val EYEROSCOPE_SOUND_TYPE: SoundType = SoundType(
        1f, 1f,
        EYEROSCOPE_BREAK, ANCRITE_STEP, EYEROSCOPE_PLACE,
        ANCRITE_STEP, ANCRITE_STEP,
    )
    val EYEROSCOPE_AIM: RegistrySupplier<SoundEvent> = ambient("block.eyeroscope.aim")
    val EYEROSCOPE_INSERT: RegistrySupplier<SoundEvent> = ambient("block.eyeroscope.insert")
    val EYEROSCOPE_EJECT: RegistrySupplier<SoundEvent> = ambient("block.eyeroscope.eject")
    val EYEROSCOPE_BOOK_INSERT: RegistrySupplier<SoundEvent> = ambient("block.eyeroscope.book_insert")
    val EYEROSCOPE_BOOK_EJECT: RegistrySupplier<SoundEvent> = ambient("block.eyeroscope.book_eject")
    val EYEROSCOPE_UPGRADE: RegistrySupplier<SoundEvent> = ambient("block.eyeroscope.upgrade")
    /** Fixed range: the idle loop is audible only near the block. */
    val EYEROSCOPE_IDLE: RegistrySupplier<SoundEvent> = SOUNDS.register("block.eyeroscope.idle") {
        SoundEvent.createFixedRangeEvent(EnderkinesisMod.id("block.eyeroscope.idle"), EYEROSCOPE_IDLE_RANGE)
    }
    const val EYEROSCOPE_IDLE_RANGE: Float = 8f

    val ANCRITE_EYE_PLACE: SoundEvent = eager("block.ancrite_eye.place")
    val ANCRITE_EYE_BREAK: SoundEvent = eager("block.ancrite_eye.break")
    val ANCRITE_EYE_SOUND_TYPE: SoundType = SoundType(
        1f, 1f,
        ANCRITE_EYE_BREAK, ANCRITE_STEP, ANCRITE_EYE_PLACE,
        ANCRITE_EYE_PLACE, ANCRITE_STEP,
    )

    val ANCRITE_EYE_UP: RegistrySupplier<SoundEvent> = ambient("block.ancrite_eye.up")
    val ANCRITE_EYE_DOWN: RegistrySupplier<SoundEvent> = ambient("block.ancrite_eye.down")
    val ANCRITE_EYE_MAX: RegistrySupplier<SoundEvent> = ambient("block.ancrite_eye.max")

    val NOTE_BLOCK_CREPUSCULITE: SoundEvent = eager("block.note_block.crepusculite")

    val YGANN_BARNACLE_LATCH: SoundEvent = eager("block.ygann_barnacle.latch")
    val YGANN_BARNACLE_HIT: SoundEvent = eager("block.ygann_barnacle.hit")
    val YGANN_BARNACLE_BREAK: SoundEvent = eager("block.ygann_barnacle.break")
    val YGANN_BARNACLE_SOUND_TYPE: SoundType = SoundType(
        1f, 1f,
        YGANN_BARNACLE_BREAK, YGANN_BARNACLE_HIT, YGANN_BARNACLE_LATCH,
        YGANN_BARNACLE_HIT, YGANN_BARNACLE_HIT,
    )
    /** Fixed range: the pulse is audible only near the barnacle. */
    val YGANN_BARNACLE_PULSE: RegistrySupplier<SoundEvent> = SOUNDS.register("block.ygann_barnacle.pulse") {
        SoundEvent.createFixedRangeEvent(EnderkinesisMod.id("block.ygann_barnacle.pulse"), YGANN_BARNACLE_PULSE_RANGE)
    }
    const val YGANN_BARNACLE_PULSE_RANGE: Float = 8f
    val YGANN_BARNACLE_SWELL: RegistrySupplier<SoundEvent> = ambient("block.ygann_barnacle.swell")
    val YGANN_BARNACLE_FLINCH: RegistrySupplier<SoundEvent> = ambient("block.ygann_barnacle.flinch")
    val YGANN_BARNACLE_CUT: RegistrySupplier<SoundEvent> = ambient("block.ygann_barnacle.cut")
    val YGANN_BARNACLE_RIPPLE: RegistrySupplier<SoundEvent> = ambient("block.ygann_barnacle.ripple")
    val YGANN_BARNACLE_SEVER: RegistrySupplier<SoundEvent> = ambient("block.ygann_barnacle.sever")
    val YGANN_BARNACLE_DEATH: RegistrySupplier<SoundEvent> = ambient("block.ygann_barnacle.death")
    val YGANN_BARNACLE_BURN: RegistrySupplier<SoundEvent> = ambient("block.ygann_barnacle.burn")

    // Blocks register before sounds, and SoundType reads its events at block construction.
    // Thus the event instance must exist before the registry entry does.
    private fun eager(name: String): SoundEvent {
        val event = SoundEvent.createVariableRangeEvent(EnderkinesisMod.id(name))
        SOUNDS.register(name) { event }
        return event
    }

    private fun ambient(name: String): RegistrySupplier<SoundEvent> =
        SOUNDS.register(name) { SoundEvent.createVariableRangeEvent(EnderkinesisMod.id(name)) }

    fun register() = SOUNDS.register()

    @Suppress("unused")
    private fun id(path: String): ResourceLocation = EnderkinesisMod.id(path)
}
