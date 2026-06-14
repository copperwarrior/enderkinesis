package org.shipwrights.enderkinesis.item

import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-tome pulse profile for the orb-network beam pulses rendered by
 * [org.shipwrights.enderkinesis.client.OrbBeamLineRenderer]. Each tome linking an orb pair
 * gets its own pulse train along the beam — different tomes can have wildly different speed,
 * spacing, wander, and direction, so a multi-tome link reads as several distinct pulse trains
 * running concurrently rather than one shared train cycling colours.
 *
 *  - [progression] — speed multiplier on the baseline pulse-travel speed (blocks per second).
 *    1.0 = baseline; >1 = faster pulses, <1 = slower.
 *  - [cohesion] — 0..1; higher = less wander. 0 lets pulses arc up to the full baseline
 *    perpendicular offset at the beam midpoint; 1 keeps them on-axis.
 *  - [frequency] — pulse-density multiplier on the baseline. Spacing = base / frequency, so
 *    >1 means denser pulses (more closely spaced), <1 means sparser.
 *  - [reciprocal] — when true, render a mirror pulse train flowing RECV→SEND alongside the
 *    forward SEND→RECV train. Reads as a two-way exchange — appropriate for tomes whose
 *    physical effect is naturally symmetric (binding/chaining) or actively bidirectional
 *    (pulling/pushing pairs that tug both ends).
 */
data class TomePulseProfile(
    val progression: Double = 1.0,
    val cohesion: Double = 0.5,
    val frequency: Double = 1.0,
    val reciprocal: Boolean = false,
)

/**
 * Dynamic profile adjuster, called every frame from the beam renderer to derive a live
 * profile from the static base. Signal/Pulling/Pushing use this to scale pulse settings with
 * the orb's POWER blockstate so a winching pull at full strength visibly moves faster and
 * tighter than an idle one.
 *
 * Runs once per beam per frame on the render thread — must stay cheap (blockstate reads only).
 */
fun interface TomePulseAdjuster {
    fun adjust(
        base: TomePulseProfile, level: Level, sendPos: BlockPos, recvPos: BlockPos,
    ): TomePulseProfile
}

/** Registry: tome `kind` → static profile (+ optional dynamic adjuster). */
object TomePulseProfiles {

    /** Fallback for unregistered tomes — baseline settings on every axis. */
    val DEFAULT: TomePulseProfile = TomePulseProfile()

    private val profiles = ConcurrentHashMap<ResourceLocation, TomePulseProfile>()
    private val adjusters = ConcurrentHashMap<ResourceLocation, TomePulseAdjuster>()

    fun register(kind: ResourceLocation, profile: TomePulseProfile) {
        profiles[kind] = profile
    }

    fun registerAdjuster(kind: ResourceLocation, adjuster: TomePulseAdjuster) {
        adjusters[kind] = adjuster
    }

    fun forKind(kind: ResourceLocation?): TomePulseProfile =
        kind?.let { profiles[it] } ?: DEFAULT

    fun resolve(
        kind: ResourceLocation, level: Level, sendPos: BlockPos, recvPos: BlockPos,
    ): TomePulseProfile {
        val base = forKind(kind)
        val adjuster = adjusters[kind] ?: return base
        return adjuster.adjust(base, level, sendPos, recvPos)
    }
}
