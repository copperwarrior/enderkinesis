package org.shipwrights.enderkinesis.client

import dev.architectury.registry.registries.RegistrySupplier
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.entity.SignatureEntity
import org.shipwrights.enderkinesis.entity.WeheloTearEntity
import org.shipwrights.enderkinesis.entity.SingularityEntity
import org.shipwrights.enderkinesis.entity.SpellBoltEntity
import org.shipwrights.enderkinesis.item.UlderScrollItem
import org.shipwrights.enderkinesis.item.UlderScrollItem.Companion.ChargeMode
import org.shipwrights.enderkinesis.registry.EKSounds
import org.shipwrights.enderkinesis.registry.EKSounds.SpellElementSounds
import org.shipwrights.enderkinesis.spell.Element
import org.shipwrights.enderkinesis.spell.Shape
import org.shipwrights.enderkinesis.spell.Signature
import org.shipwrights.enderkinesis.spell.Spell
import org.shipwrights.enderkinesis.spell.SpellSounds
import org.shipwrights.enderkinesis.spell.SpellVisuals
import org.shipwrights.enderkinesis.spell.signature.BodySignatures.Faespark
import org.shipwrights.enderkinesis.spell.signature.BodySignatures.HungeringMaw
import org.shipwrights.enderkinesis.spell.signature.BodySignatures.NorthStar
import org.shipwrights.enderkinesis.spell.signature.BodySignatures.Rotdance
import org.shipwrights.enderkinesis.spell.signature.BodySignatures.Supernova
import org.shipwrights.enderkinesis.spell.signature.FieldSignatures.AcidRain
import org.shipwrights.enderkinesis.spell.signature.FieldSignatures.AuroraVeil
import org.shipwrights.enderkinesis.spell.signature.FieldSignatures.GustOfGlass
import org.shipwrights.enderkinesis.spell.signature.FieldSignatures.NetherRift
import org.shipwrights.enderkinesis.spell.signature.FieldSignatures.StormCloud
import org.shipwrights.enderkinesis.spell.signature.LinkSignatures.VampiresBallroom
import org.shipwrights.enderkinesis.spell.signature.LinkSignatures.JudgementPillar
import org.shipwrights.enderkinesis.spell.signature.LinkSignatures.SupplicationOfSselith
import org.shipwrights.enderkinesis.spell.signature.ReferenceSignatures.PrismLance
import org.shipwrights.enderkinesis.spell.signature.ReferenceSignatures.Rootquake
import org.shipwrights.enderkinesis.spell.signature.WaveSignatures.Avalanche
import org.shipwrights.enderkinesis.spell.signature.WaveSignatures.Bloodwave
import org.shipwrights.enderkinesis.spell.signature.WaveSignatures.GardenSanguine
import org.shipwrights.enderkinesis.spell.signature.WaveSignatures.Shroomspread
import org.shipwrights.enderkinesis.spell.signature.WaveSignatures.YgannsGrasp
import org.shipwrights.enderkinesis.client.signature.WaveSignatureVisuals.AvalancheVisual
import org.shipwrights.enderkinesis.client.signature.WaveSignatureVisuals.YgannsGraspVisual

object SpellSoundsClient {

    private enum class Hold(
        val opening: (SpellElementSounds) -> RegistrySupplier<SoundEvent>?,
        val loop: (SpellElementSounds) -> RegistrySupplier<SoundEvent>?,
        val closing: ((SpellElementSounds) -> RegistrySupplier<SoundEvent>?)?,
        val loopFrom: Int,
    ) {
        AREA({ it.areaCharge }, { it.areaHold }, null, UlderScrollItem.BOLT_FULL_CHARGE_TICKS),
        SELF({ it.selfCharge }, { it.selfHold }, null, UlderScrollItem.BOLT_FULL_CHARGE_TICKS),
        BOLT({ it.boltCharge }, { it.boltHold }, null, UlderScrollItem.BOLT_FULL_CHARGE_TICKS),
        SPRAY({ it.sprayStart }, { it.sprayLoop }, { it.sprayStop }, 0),
        BEAM({ it.beamStart }, { it.beamLoop }, { it.beamStop }, 0),
        CHAIN({ chaos(it, EKSounds.SPELL_CHAOS_CHAIN_START) }, { chaos(it, EKSounds.SPELL_CHAOS_CHAIN_LOOP) }, { chaos(it, EKSounds.SPELL_CHAOS_CHAIN_STOP) }, 0),
        /** Its sounds come from [SIGNATURE_CHARGES], not from the elements. */
        SIGNATURE({ null }, { null }, null, UlderScrollItem.BOLT_FULL_CHARGE_TICKS),
    }

    /** [loopFrom] overrides the hold's own tick for the switch to [loop]. */
    private class Voice(
        val opening: RegistrySupplier<SoundEvent>?, val loop: RegistrySupplier<SoundEvent>?, val closing: RegistrySupplier<SoundEvent>?, val volume: Float,
        val loopFrom: Int? = null,
    )

    private val SIGNATURE_CHARGES = mapOf(
        Signature.BLACK_HOLE_SUN to Voice(EKSounds.SIGNATURE_BLACK_HOLE_SUN_CHARGE, EKSounds.SIGNATURE_BLACK_HOLE_SUN_CHARGE_HOLD, null, 1f),
        Signature.STARFALL to Voice(EKSounds.SIGNATURE_STARFALL_CHARGE, EKSounds.SIGNATURE_STARFALL_CHARGE_HOLD, null, 1f),
        Signature.NORTH_STAR to Voice(EKSounds.SIGNATURE_NORTH_STAR_CHARGE, EKSounds.SIGNATURE_NORTH_STAR_CHARGE_HOLD, null, 1f),
        Signature.HUNGERING_MAW to Voice(EKSounds.SIGNATURE_HUNGERING_MAW_CHARGE, EKSounds.SIGNATURE_HUNGERING_MAW_CHARGE_HOLD, null, 1f),
        Signature.NETHER_RIFT to Voice(EKSounds.SIGNATURE_NETHER_RIFT_CHARGE, EKSounds.SIGNATURE_NETHER_RIFT_CHARGE_HOLD, null, 1f),
        Signature.YGANNS_GRASP to Voice(EKSounds.SIGNATURE_YGANNS_GRASP_CHARGE, EKSounds.SIGNATURE_YGANNS_GRASP_CHARGE_HOLD, null, 1f),
        Signature.AVALANCHE to Voice(EKSounds.SIGNATURE_AVALANCHE_CHARGE, EKSounds.SIGNATURE_AVALANCHE_CHARGE_HOLD, null, 1f),
        Signature.STILLPOINT to Voice(EKSounds.SIGNATURE_STILLPOINT_CHARGE, EKSounds.SIGNATURE_STILLPOINT_CHARGE_HOLD, null, 1f),
        Signature.AURORA_VEIL to Voice(EKSounds.SIGNATURE_AURORA_VEIL_CHARGE, EKSounds.SIGNATURE_AURORA_VEIL_CHARGE_HOLD, null, 1f),
        Signature.FIREWEED to Voice(EKSounds.SIGNATURE_FIREWEED_CHARGE, EKSounds.SIGNATURE_FIREWEED_CHARGE_HOLD, null, 1f),
        Signature.BLOODWAVE to Voice(EKSounds.SIGNATURE_BLOODWAVE_CHARGE, EKSounds.SIGNATURE_BLOODWAVE_CHARGE_HOLD, null, 1f),
        Signature.ROTDANCE to Voice(EKSounds.SIGNATURE_ROTDANCE_CHARGE, EKSounds.SIGNATURE_ROTDANCE_CHARGE_HOLD, null, 1f),
        Signature.ACID_RAIN to Voice(EKSounds.SIGNATURE_ACID_RAIN_CHARGE, EKSounds.SIGNATURE_ACID_RAIN_CHARGE_HOLD, null, 1f),
        Signature.STORM_CLOUD to Voice(EKSounds.SIGNATURE_STORM_CLOUD_CHARGE, EKSounds.SIGNATURE_STORM_CLOUD_CHARGE_HOLD, null, 1f),
        Signature.GUST_OF_GLASS to Voice(EKSounds.SIGNATURE_GUST_OF_GLASS_CHARGE, EKSounds.SIGNATURE_GUST_OF_GLASS_CHARGE_HOLD, null, 1f),
        Signature.ROOTQUAKE to Voice(EKSounds.SIGNATURE_ROOTQUAKE_CHARGE, EKSounds.SIGNATURE_ROOTQUAKE_CHARGE_HOLD, null, 1f),
        Signature.SHROOMSPREAD to Voice(EKSounds.SIGNATURE_SHROOMSPREAD_CHARGE, EKSounds.SIGNATURE_SHROOMSPREAD_CHARGE_HOLD, null, 1f),
        Signature.JUDGEMENT_PILLAR to Voice(EKSounds.SIGNATURE_JUDGEMENT_PILLAR_CHARGE, EKSounds.SIGNATURE_JUDGEMENT_PILLAR_CHARGE_HOLD, null, 1f),
        Signature.SUPERNOVA to Voice(EKSounds.SIGNATURE_SUPERNOVA_CHARGE, EKSounds.SIGNATURE_SUPERNOVA_CHARGE_HOLD, null, 1f, Supernova.CHARGE_TICKS),
        Signature.VAMPIRES_BALLROOM to Voice(EKSounds.SIGNATURE_VAMPIRES_BALLROOM_CHARGE, EKSounds.SIGNATURE_VAMPIRES_BALLROOM_CHARGE_HOLD, null, 1f),
    )

    /** Plays while [playing] holds. A loop that stops holding ends, so phases can hand over from one loop to the next.
     *  [at] places the sound away from the entity. */
    private class SignatureLoop(
        val sound: RegistrySupplier<SoundEvent>, val volume: Float, val at: ((Entity) -> Vec3)? = null, val playing: (SignatureEntity) -> Boolean = { true },
    )

    /** Each loop keeps the volume, so the range, of the vanilla sound it replaced. */
    private val SIGNATURE_LOOPS = mapOf(
        Signature.BLACK_HOLE_SUN to listOf(SignatureLoop(EKSounds.SIGNATURE_BLACK_HOLE_SUN_CORE_LOOP, 3f)),
        Signature.STARFALL to listOf(SignatureLoop(EKSounds.SIGNATURE_STARFALL_SKY_LOOP, 2f)),
        Signature.NORTH_STAR to listOf(
            SignatureLoop(EKSounds.SIGNATURE_NORTH_STAR_FLIGHT_LOOP, 1f) { NorthStar.stuckAt(it) <= 0 },
            SignatureLoop(EKSounds.SIGNATURE_NORTH_STAR_STAR_LOOP, 1.2f) { NorthStar.stuckAt(it) > 0 },
        ),
        Signature.HUNGERING_MAW to listOf(
            SignatureLoop(EKSounds.SIGNATURE_HUNGERING_MAW_MAW_LOOP, 1.2f) { it.tickCount < it.life - HungeringMaw.CLOSE },
        ),
        Signature.NETHER_RIFT to listOf(
            SignatureLoop(EKSounds.SIGNATURE_NETHER_RIFT_RIFT_LOOP, 3f) { it.tickCount < NetherRift.CLOSE },
        ),
        Signature.YGANNS_GRASP to listOf(
            SignatureLoop(EKSounds.SIGNATURE_YGANNS_GRASP_FRONT_LOOP, 1.6f, { YgannsGraspVisual.front(it as SignatureEntity) }) { it.tickCount < YgannsGrasp.END },
        ),
        Signature.AVALANCHE to listOf(
            SignatureLoop(EKSounds.SIGNATURE_AVALANCHE_SLIDE_LOOP, 2f, { AvalancheVisual.lead(it as SignatureEntity) }) { it.tickCount < Avalanche.endTick(it.power) },
        ),
        Signature.BLOODWAVE to listOf(
            SignatureLoop(EKSounds.SIGNATURE_BLOODWAVE_WAVE_LOOP, 2f) { it.tickCount < Bloodwave.endTick(it.power) },
        ),
        Signature.GARDEN_SANGUINE to listOf(
            SignatureLoop(EKSounds.SIGNATURE_GARDEN_SANGUINE_SPRAY_LOOP, 1f) { (it.data and GardenSanguine.RELEASED) == 0 },
            SignatureLoop(EKSounds.SIGNATURE_GARDEN_SANGUINE_GARDEN_LOOP, 1.4f, { (it as SignatureEntity).point }),
        ),
        Signature.ROTDANCE to listOf(
            SignatureLoop(EKSounds.SIGNATURE_ROTDANCE_SWARM_LOOP, 1.2f) { it.data != Rotdance.FADING },
        ),
        Signature.ACID_RAIN to listOf(
            SignatureLoop(EKSounds.SIGNATURE_ACID_RAIN_RAIN_LOOP, 2f) { AcidRain.raining(it, it.tickCount) },
        ),
        Signature.STORM_CLOUD to listOf(
            SignatureLoop(EKSounds.SIGNATURE_STORM_CLOUD_STORM_LOOP, 2f) { StormCloud.raining(it, it.tickCount) },
        ),
        Signature.FAESPARK to listOf(
            SignatureLoop(EKSounds.SIGNATURE_FAESPARK_HAND_LOOP, 1f) { Faespark.isHand(it) },
            SignatureLoop(EKSounds.SIGNATURE_FAESPARK_SPARK_LOOP, 1f) { !Faespark.isHand(it) },
        ),
        Signature.VAMPIRES_BALLROOM to listOf(
            SignatureLoop(EKSounds.SIGNATURE_VAMPIRES_BALLROOM_WEB_LOOP, 1.4f) { VampiresBallroom.kind(it.data) == VampiresBallroom.Kind.WEB },
        ),
        Signature.GUST_OF_GLASS to listOf(
            SignatureLoop(EKSounds.SIGNATURE_GUST_OF_GLASS_GUST_LOOP, 1.4f) { it.tickCount < GustOfGlass.LIFE - GustOfGlass.BREAK },
        ),
        Signature.ROOTQUAKE to listOf(
            SignatureLoop(EKSounds.SIGNATURE_ROOTQUAKE_QUAKE_LOOP, 2f, { Rootquake.front(it as SignatureEntity, it.tickCount.toDouble()) }) { it.tickCount < Rootquake.lastRise(it) },
        ),
        Signature.SHROOMSPREAD to listOf(
            SignatureLoop(EKSounds.SIGNATURE_SHROOMSPREAD_FLIGHT_LOOP, 1f) { Shroomspread.since(it) < 0 },
            SignatureLoop(EKSounds.SIGNATURE_SHROOMSPREAD_COLONY_LOOP, 1.4f, { (it as SignatureEntity).point }) { Shroomspread.since(it) in 0 until Shroomspread.FADE_AT },
        ),
        Signature.PRISM_LANCE to listOf(
            SignatureLoop(EKSounds.SIGNATURE_PRISM_LANCE_LANCE_LOOP, 1f),
            SignatureLoop(EKSounds.SIGNATURE_PRISM_LANCE_PRISM_LOOP, 1.2f, { (it as SignatureEntity).point }) { PrismLance.splitting(it) },
        ),
        Signature.TRANSFUSION to listOf(SignatureLoop(EKSounds.SIGNATURE_TRANSFUSION_BEAM_LOOP, 1f)),
        Signature.JUDGEMENT_PILLAR to listOf(
            SignatureLoop(EKSounds.SIGNATURE_JUDGEMENT_PILLAR_PILLAR_LOOP, 2f) { it.tickCount < JudgementPillar.ACTIVE },
        ),
        Signature.SUPERNOVA to listOf(
            SignatureLoop(EKSounds.SIGNATURE_SUPERNOVA_STAR_LOOP, 1.5f) { Supernova.phase(it) == Supernova.FLYING },
            SignatureLoop(EKSounds.SIGNATURE_SUPERNOVA_REMNANT_LOOP, 1.5f) { Supernova.phase(it) == Supernova.BURST && it.tickCount < it.life - Supernova.FADE_BEFORE },
        ),
        Signature.SUPPLICATION_OF_SSELITH to listOf(
            SignatureLoop(EKSounds.SIGNATURE_SUPPLICATION_OF_SSELITH_BEAM_LOOP, 1f) { !SupplicationOfSselith.Pool.isPool(it.data) },
            SignatureLoop(EKSounds.SIGNATURE_SUPPLICATION_OF_SSELITH_STRIKE_LOOP, 1.4f, { (it as SignatureEntity).point }) { SupplicationOfSselith.striking(it) },
            SignatureLoop(EKSounds.SIGNATURE_SUPPLICATION_OF_SSELITH_POOL_LOOP, 1f) {
                SupplicationOfSselith.Pool.isPool(it.data) && it.tickCount < it.life - SupplicationOfSselith.Pool.END_BEFORE
            },
        ),
        Signature.STILLPOINT to listOf(SignatureLoop(EKSounds.SIGNATURE_STILLPOINT_STILL_LOOP, 1.5f)),
        Signature.AURORA_VEIL to listOf(
            SignatureLoop(EKSounds.SIGNATURE_AURORA_VEIL_VEIL_LOOP, 1.2f, { it.position().add(0.0, AuroraVeil.HEIGHT / 2, 0.0) }) { it.tickCount < AuroraVeil.FADING },
        ),
    )
    /** Loop keys put an element ordinal, or this plus a loop index, in the low 8 bits. */
    private const val SIGNATURE_SLOT = 128

    /** Chain is Chaos's own shape, so only the Chaos voice has chain hold sounds. */
    private fun chaos(sounds: SpellElementSounds, sound: RegistrySupplier<SoundEvent>) = sound.takeIf { sounds === EKSounds.SPELL_CHAOS }

    private class Session(val hold: Hold, val spell: Spell, val voices: List<Voice>, val loopFrom: Int) {
        val playing = ArrayList<FollowSound>()
        var looping = false
    }

    private class Loop(
        val entity: Entity, val event: RegistrySupplier<SoundEvent>, val ending: RegistrySupplier<SoundEvent>?, val volume: Float, val startAt: Long,
    ) {
        var sound: FollowSound? = null
        var seen = 0L
    }

    /** [startAfter] lets an opening one-shot finish before the loop starts. */
    private class Aura(val element: Element, val visual: Int, val loop: RegistrySupplier<SoundEvent>, val end: RegistrySupplier<SoundEvent>, val startAfter: Int)

    // Render thread only.
    private val sessions = HashMap<Player, Session>()
    private val loops = Long2ObjectOpenHashMap<Loop>()
    private var stamp = 0L
    private var melts = 0
    private val delayed = ArrayList<Delayed>()

    private class Delayed(val at: Vec3, val sound: RegistrySupplier<SoundEvent>, val volume: Float, val pitch: Float, val due: Long)

    /** [maxDelay] is in seconds. */
    private class ArcJitter(val maxDelay: Double, val pitchMin: Float, val pitchMax: Float)

    private val ARC = ArcJitter(0.12, 0.9f, 1.1f)
    private val BLOOD_ARC = ArcJitter(0.08, 0.92f, 1.08f)
    private const val MAX_DELAYED = 64
    private const val NANOS = 1.0e9

    private val AURAS = listOf(
        cloak(Element.PYRE, SpellVisuals.FLAME_CLOAK, EKSounds.SPELL_PYRE_MANTLE),
        cloak(Element.WINTER, SpellVisuals.FROST_CLOAK, EKSounds.SPELL_WINTER_MANTLE),
        cloak(Element.CHAOS, SpellVisuals.LIGHTNING_CLOAK, EKSounds.SPELL_CHAOS_MANTLE),
        cloak(Element.BLOOD, SpellVisuals.ABSORPTION_CLOAK, EKSounds.SPELL_BLOOD_MANTLE),
        cloak(Element.CORRUPTION, SpellVisuals.POISON_CLOAK, EKSounds.SPELL_CORRUPTION_MANTLE),
        cloak(Element.VOID, SpellVisuals.VOID_CLOAK, EKSounds.SPELL_VOID_MANTLE),
        Aura(Element.SUNFIRE, SpellVisuals.HALO, EKSounds.SPELL_SUNFIRE_PALE_CROWN_LOOP, EKSounds.SPELL_SUNFIRE_PALE_CROWN_END, HALO_ON_TICKS),
    )
    private const val AURA_BITS = SpellVisuals.CLOAKS or SpellVisuals.HALO
    /** The length of pale_crown_on. */
    private const val HALO_ON_TICKS = 24

    private fun cloak(element: Element, visual: Int, sounds: EKSounds.MantleSounds) = Aura(element, visual, sounds.loop, sounds.end, 0)

    /** One Area cast can melt hundreds of snow blocks in a tick, and each sound takes a channel. */
    private const val MAX_MELTS_PER_TICK = 4

    fun reset() {
        delayed.clear()
        for (session in sessions.values) session.playing.forEach(FollowSound::end)
        sessions.clear()
        for (loop in loops.values) loop.sound?.end()
        loops.clear()
        MiasmaSounds.reset()
    }

    fun tick(level: ClientLevel) {
        stamp++
        melts = 0
        playDue(level)
        tickHolds(level)
        tickLoops(level)
        MiasmaSounds.tick()
    }

    fun playAt(level: ClientLevel, at: Vec3, sound: RegistrySupplier<SoundEvent>, volume: Float = 1f, pitch: Float = 1f) =
        level.playLocalSound(at.x, at.y, at.z, sound.get(), SoundSource.PLAYERS, volume, pitch, false)

    /** Arcs of one pulse start together, so a random delay and pitch keep them off a beat. */
    fun arc(level: ClientLevel, at: Vec3, element: Element, volume: Float = 1f) {
        val sound = SpellSounds.of(element)?.chain ?: return
        val jitter = if (element == Element.BLOOD) BLOOD_ARC else ARC
        val pitch = jitter.pitchMin + level.random.nextFloat() * (jitter.pitchMax - jitter.pitchMin)
        if (delayed.size >= MAX_DELAYED) return playAt(level, at, sound, volume, pitch)
        val delay = (level.random.nextDouble() * jitter.maxDelay * NANOS).toLong()
        delayed += Delayed(at, sound, volume, pitch, System.nanoTime() + delay)
    }

    fun playDue(level: ClientLevel) {
        if (delayed.isEmpty()) return
        val now = System.nanoTime()
        delayed.removeIf { d -> (d.due <= now).also { if (it) playAt(level, d.at, d.sound, d.volume, d.pitch) } }
    }

    fun melt(level: ClientLevel, at: Vec3) {
        if (melts >= MAX_MELTS_PER_TICK) return
        melts++
        playAt(level, at, EKSounds.SPELL_PYRE_MELT)
    }

    private fun tickHolds(level: ClientLevel) {
        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            val (player, session) = iterator.next()
            val present = !player.isRemoved && player.level() === level
            val spell = heldSpell(player)
            if (present && spell != null && holdOf(player, spell) == session.hold && spell.magnitudes == session.spell.magnitudes) continue
            session.playing.forEach(FollowSound::end)
            if (present) for (voice in session.voices) voice.closing?.let { play(player, it, voice.volume, false) }
            iterator.remove()
        }
        for (player in level.players()) {
            val session = sessions[player] ?: start(player) ?: continue
            if (!session.looping && player.ticksUsingItem >= session.loopFrom) {
                session.looping = true
                for (voice in session.voices) voice.loop?.let { session.playing += play(player, it, voice.volume, true) }
            }
        }
    }

    private fun start(player: Player): Session? {
        val spell = heldSpell(player) ?: return null
        val hold = holdOf(player, spell) ?: return null
        val voices = if (hold == Hold.SIGNATURE) listOfNotNull(SIGNATURE_CHARGES[spell.signature]) else ArrayList<Voice>().also { list ->
            SpellSounds.forEach(spell) { sounds, volume -> list += Voice(hold.opening(sounds), hold.loop(sounds), hold.closing?.invoke(sounds), volume) }
        }
        if (voices.isEmpty()) return null
        val session = Session(hold, spell, voices, voices.firstNotNullOfOrNull { it.loopFrom } ?: hold.loopFrom)
        for (voice in voices) voice.opening?.let { session.playing += play(player, it, voice.volume, false) }
        sessions[player] = session
        return session
    }

    private fun heldSpell(player: Player): Spell? = if (player.isUsingItem) UlderScrollItem.spellOf(player.useItem) else null

    /** Signatures have their own behaviour, so they play only their own charge sounds, not the shape sounds. */
    private fun holdOf(player: Player, spell: Spell): Hold? = when (UlderScrollItem.chargeMode(player.useItem)) {
        ChargeMode.AREA -> Hold.AREA
        ChargeMode.SELF -> Hold.SELF
        null -> if (spell.signature != null) Hold.SIGNATURE.takeIf { spell.signature in SIGNATURE_CHARGES } else when (spell.shape) {
            Shape.BOLT -> Hold.BOLT
            Shape.SPRAY -> Hold.SPRAY
            Shape.BEAM -> Hold.BEAM
            Shape.CHAIN -> Hold.CHAIN
            else -> null
        }
    }

    private fun tickLoops(level: ClientLevel) {
        for (entity in level.entitiesForRendering()) {
            if (entity is SpellBoltEntity) {
                for (element in entity.elements()) SpellSounds.of(element)?.let { keep(entity, element.ordinal, it.boltFlight, null, 0) { 1f } }
            } else if (entity is SingularityEntity) {
                keep(entity, Element.VOID.ordinal, EKSounds.SPELL_VOID_SINGULARITY_LOOP, null, 0) { 1f }
            } else if (entity is WeheloTearEntity) {
                if (entity.tickCount < WeheloTearEntity.LIFE_TICKS - WeheloTearEntity.CLOSE_TICKS) keep(entity, 0, EKSounds.WEHELO_TEAR_LOOP, null, 0) { 1f }
            } else if (entity is SignatureEntity) {
                SIGNATURE_LOOPS[entity.signature]?.forEachIndexed { i, loop ->
                    if (loop.playing(entity)) keep(entity, SIGNATURE_SLOT + i, loop.sound, null, 0, loop.at) { loop.volume }
                }
            } else if (entity is LivingEntity) {
                val bits = SpellVisuals.clientBits(entity.id)
                if (bits and AURA_BITS == 0) continue
                for (aura in AURAS) {
                    if (bits and aura.visual == 0) continue
                    keep(entity, aura.element.ordinal, aura.loop, aura.end, aura.startAfter) {
                        SpellSounds.magnitudeVolume(SpellVisuals.clientCastMagnitude(entity.id, aura.visual))
                    }
                }
            }
        }
        val iterator = loops.values.iterator()
        while (iterator.hasNext()) {
            val loop = iterator.next()
            if (loop.seen == stamp && loop.sound?.isStopped != true) continue
            loop.sound?.end()
            val present = !loop.entity.isRemoved && loop.entity.level() === level
            if (present) loop.ending?.let { play(loop.entity, it, loop.volume, false) }
            iterator.remove()
        }
    }

    private inline fun keep(
        entity: Entity, slot: Int, sound: RegistrySupplier<SoundEvent>, ending: RegistrySupplier<SoundEvent>?, startAfter: Int,
        noinline at: ((Entity) -> Vec3)? = null, volume: () -> Float,
    ) {
        val key = (entity.id.toLong() shl 8) or slot.toLong()
        val loop = loops.get(key) ?: Loop(entity, sound, ending, volume(), stamp + startAfter).also { loops.put(key, it) }
        loop.seen = stamp
        if (loop.sound == null && stamp >= loop.startAt) loop.sound = play(entity, loop.event, loop.volume, true, at)
    }

    private fun play(entity: Entity, sound: RegistrySupplier<SoundEvent>, volume: Float, looping: Boolean, at: ((Entity) -> Vec3)? = null): FollowSound =
        FollowSound(entity, sound.get(), volume, looping, at).also { Minecraft.getInstance().soundManager.play(it) }

    private class FollowSound(
        private val entity: Entity, event: SoundEvent, volume: Float, looping: Boolean, private val at: ((Entity) -> Vec3)?,
    ) : AbstractTickableSoundInstance(event, SoundSource.PLAYERS, SoundInstance.createUnseededRandom()) {

        init {
            this.looping = looping
            this.volume = volume
            delay = 0
            // A source at the listener pans at random, because it moves each tick and the listener each frame.
            if (at == null && entity === Minecraft.getInstance().cameraEntity) {
                relative = true
                attenuation = SoundInstance.Attenuation.NONE
            } else follow()
        }

        override fun tick() {
            if (entity.isRemoved) stop() else follow()
        }

        private fun follow() {
            if (relative) return
            if (at != null) {
                val p = at.invoke(entity)
                x = p.x; y = p.y; z = p.z
                return
            }
            x = entity.x
            y = entity.y + entity.bbHeight * 0.5
            z = entity.z
        }

        fun end() = stop()
    }
}
