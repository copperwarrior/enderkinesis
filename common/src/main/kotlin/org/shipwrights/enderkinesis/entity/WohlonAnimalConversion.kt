package org.shipwrights.enderkinesis.entity

import dev.architectury.event.events.common.TickEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.animal.Animal
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.entity.EntityTypeTest
import net.minecraft.world.phys.AABB
import org.shipwrights.enderkinesis.block.WohlonnogondoniaSpreader
import org.shipwrights.enderkinesis.dimension.Wohlonnogondonia
import org.shipwrights.enderkinesis.mixin.LivingEntityDropEquipmentInvoker
import org.shipwrights.enderkinesis.registry.EKEntities

/**
 * Passive-mob → Prismatic Goat conversion driver. Any vanilla / modded
 * [Animal] that wanders into a Wohlonnogondonia biome cell starts a
 * conversion timer; while the timer runs the mob visibly twitches (yaw
 * jolts, hurt-flash, soul-particle puffs); when the timer expires the
 * mob is discarded and a [PrismaticGoat] takes its place.
 *
 * Named mobs and the Prismatic Goat itself are exempt — the named-mob
 * exemption matches vanilla "this is the player's pet, don't break it"
 * convention used by zombie-villager curing, lightning-cow-mooshroom,
 * etc.
 */
object WohlonAnimalConversion {

    /** How often (game ticks) we sweep every loaded level for animals
     *  to enroll into / drop out of the conversion pool. 20 ticks = 1 s,
     *  fast enough that an animal entering a freshly-converted biome
     *  cell starts twitching within a second. */
    private const val DETECT_INTERVAL_TICKS: Long = 20L

    /** How often (game ticks) we tick each in-progress conversion's
     *  twitch visual + check for completion. 5 ticks = 4 Hz — frequent
     *  enough that the twitch reads as a jerky possession, not a
     *  once-per-second pulse. */
    private const val ANIMATE_INTERVAL_TICKS: Long = 5L

    /** Total ticks from "entered Wohlon" to "becomes a Prismatic Goat".
     *  100 ticks = 5 s — long enough to read as a deliberate
     *  transformation, short enough that it's not waiting around. */
    private const val CONVERSION_TICKS: Long = 100L

    /** Hard cap on how long an entry can sit in [converting] without
     *  being touched by [animateStep]. If we never resolve the UUID for
     *  this many ticks (entity died, despawned, moved to an unloaded
     *  chunk) the entry is evicted so the map can't grow unbounded.
     *  Generous — 5× the normal conversion window. */
    private const val MAX_AGE_TICKS: Long = CONVERSION_TICKS * 5

    /** UUID → game time the conversion started. Cleared when the
     *  animal leaves Wohlon, completes conversion, or stops existing. */
    private val converting: ConcurrentHashMap<UUID, Long> = ConcurrentHashMap()

    fun init() {
        TickEvent.SERVER_LEVEL_POST.register(::tickLevel)
    }

    private fun tickLevel(level: ServerLevel) {
        val gameTime = level.gameTime
        if (gameTime % DETECT_INTERVAL_TICKS == 0L) detectStep(level, gameTime)
        if (gameTime % ANIMATE_INTERVAL_TICKS == 0L) animateStep(level, gameTime)
    }

    /** 1 Hz: only walk animals that could plausibly be in a Wohlon
     *  biome — i.e. animals inside a tainted chunk (anywhere the
     *  spreader has ever painted Wohlon) on a non-Wohlon dimension, or
     *  any animal inside the Wohlonnogondonia dimension itself. Levels
     *  with no Wohlon footprint at all are skipped entirely.
     *
     *  Removal of stale entries is handled by [animateStep] — it drops
     *  any tracked UUID it finds outside Wohlon biome or fails to
     *  resolve, so detect doesn't need a global animal walk to prune. */
    private fun detectStep(level: ServerLevel, gameTime: Long) {
        if (level.dimension() == Wohlonnogondonia.LEVEL_KEY) {
            // Every chunk in the Wohlon dim is Wohlon biome by default,
            // and natural-spawn rules + low animal density there keep
            // the scan size small — fall through to a whole-level pass.
            scanAnimals(level, null, gameTime)
            return
        }
        val taintedChunks = WohlonnogondoniaSpreader.getTaintedChunkSnapshot(level)
        if (taintedChunks.isEmpty()) return

        val chunkSource = level.chunkSource
        val minY = level.minBuildHeight.toDouble()
        val maxY = level.maxBuildHeight.toDouble()
        for (chunkLong in taintedChunks) {
            val cx = ChunkPos.getX(chunkLong)
            val cz = ChunkPos.getZ(chunkLong)
            if (chunkSource.getChunkNow(cx, cz) == null) continue
            val aabb = AABB(
                cx * 16.0, minY, cz * 16.0,
                cx * 16.0 + 16.0, maxY, cz * 16.0 + 16.0,
            )
            scanAnimals(level, aabb, gameTime)
        }
    }

    /** Enroll candidates in the supplied region — `aabb = null` means
     *  the whole level. Wohlon-biome membership is checked per animal
     *  because tainted chunks aren't entirely Wohlon (the biome is
     *  per-quarter-cell within the chunk). */
    private fun scanAnimals(level: ServerLevel, aabb: AABB?, gameTime: Long) {
        val animals = if (aabb == null) {
            level.getEntities(
                EntityTypeTest.forClass(Animal::class.java),
                { isConvertable(it) },
            )
        } else {
            level.getEntities(
                EntityTypeTest.forClass(Animal::class.java),
                aabb,
                { isConvertable(it) },
            )
        }
        for (animal in animals) {
            if (level.getBiome(animal.blockPosition()).`is`(Wohlonnogondonia.BIOME_KEY)) {
                converting.putIfAbsent(animal.uuid, gameTime)
            }
        }
    }

    /** True iff [animal] is a candidate for enrollment / further
     *  ticking — alive, not removed, not the conversion target itself,
     *  not player-named (matches vanilla pet convention). */
    private fun isConvertable(animal: Animal): Boolean {
        if (!animal.isAlive || animal.isRemoved) return false
        if (animal is PrismaticGoat) return false
        if (animal.hasCustomName()) return false
        return true
    }

    /** 4 Hz: for each in-progress conversion in *this* level, advance
     *  the visual twitch and finalize when the conversion window
     *  elapses. Also drops entries that have left Wohlon, been named,
     *  died, or aged out past [MAX_AGE_TICKS] (safety net for entries
     *  whose entity de-spawned or moved to an unloaded chunk so no
     *  level can resolve them). */
    private fun animateStep(level: ServerLevel, gameTime: Long) {
        if (converting.isEmpty()) return
        val iter = converting.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (gameTime - entry.value > MAX_AGE_TICKS) {
                iter.remove()
                continue
            }
            val animal = level.getEntity(entry.key) as? Animal ?: continue
            if (!isConvertable(animal)) {
                iter.remove()
                continue
            }
            if (!level.getBiome(animal.blockPosition()).`is`(Wohlonnogondonia.BIOME_KEY)) {
                iter.remove()
                continue
            }
            val elapsed = gameTime - entry.value
            if (elapsed >= CONVERSION_TICKS) {
                convertToPrismaticGoat(level, animal)
                iter.remove()
            } else {
                applyTwitch(level, animal, elapsed.toFloat() / CONVERSION_TICKS.toFloat())
            }
        }
    }

    /** Per-tick twitch effects on an in-progress conversion. Intensity
     *  ramps with [progress] (0..1) so the mob looks calm early and
     *  visibly possessed by the end. */
    private fun applyTwitch(level: ServerLevel, animal: Animal, progress: Float) {
        val random = level.random

        // Yaw jolt — random sign, magnitude grows with progress. Updates
        // all three yaw fields so the head and body twist together
        // rather than the body lagging behind on the next entity tick.
        val twitchDegrees = (random.nextFloat() - 0.5f) * 90f * progress
        val newYRot = animal.yRot + twitchDegrees
        animal.yRot = newYRot
        animal.yHeadRot = newYRot
        animal.yBodyRot = newYRot

        // Hurt-flash — vanilla animation byte 2. Fires on a coin flip per
        // animate tick so the flicker reads as irregular twitching
        // rather than a steady pulse.
        if (random.nextFloat() < 0.5f) {
            level.broadcastEntityEvent(animal, 2.toByte())
        }

        // Soul-particle puff from the animal's torso volume — sparse
        // early, denser late.
        val particleCount = (1 + progress * 4f).toInt()
        level.sendParticles(
            ParticleTypes.SOUL,
            animal.x, animal.y + animal.bbHeight * 0.5,
            animal.z,
            particleCount,
            animal.bbWidth.toDouble() * 0.5,
            animal.bbHeight.toDouble() * 0.3,
            animal.bbWidth.toDouble() * 0.5,
            0.0,
        )

        // Rare "the wild is speaking through this creature" sound.
        if (random.nextFloat() < 0.05f) {
            level.playSound(
                null, animal.x, animal.y, animal.z,
                SoundEvents.ENDERMAN_STARE, SoundSource.HOSTILE,
                0.3f, 1.5f + random.nextFloat() * 0.4f,
            )
        }
    }

    /** Replace [source] with a fresh [PrismaticGoat] at the same pose.
     *  Inherits persistence + invulnerability + baby flag so the
     *  resulting goat keeps the relevant attributes that the original
     *  animal had. */
    private fun convertToPrismaticGoat(level: ServerLevel, source: Animal) {
        val goat = EKEntities.PRISMATIC_GOAT.get().create(level) ?: return
        goat.moveTo(source.x, source.y, source.z, source.yRot, source.xRot)
        if (source.isPersistenceRequired) goat.setPersistenceRequired()
        if (source.isBaby) goat.setBaby(true)
        goat.isInvulnerable = source.isInvulnerable

        level.addFreshEntity(goat)

        level.playSound(
            null, source.x, source.y, source.z,
            SoundEvents.ENDERMAN_TELEPORT, SoundSource.NEUTRAL,
            1.0f, 0.7f,
        )
        level.sendParticles(
            ParticleTypes.SOUL,
            source.x, source.y + source.bbHeight * 0.5, source.z,
            40,
            source.bbWidth.toDouble(),
            source.bbHeight.toDouble() * 0.5,
            source.bbWidth.toDouble(),
            0.05,
        )

        dropCarriedItems(source)
        source.discard()
    }

    /** `discard` skips death loot, so the saddle, armour, chest contents and lead drop first. */
    private fun dropCarriedItems(source: Animal) {
        if (source.isLeashed) source.dropLeash(true, true)
        (source as LivingEntityDropEquipmentInvoker).`enderkinesis$dropEquipment`()
        // Hands only: horse armour is also in the CHEST slot, and `dropEquipment` drops it already.
        for (hand in InteractionHand.values()) {
            val held = source.getItemInHand(hand)
            if (held.isEmpty) continue
            source.spawnAtLocation(held.copy())
            source.setItemInHand(hand, ItemStack.EMPTY)
        }
    }
}
