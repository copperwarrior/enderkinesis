package org.shipwrights.enderkinesis.spell

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import org.joml.Quaternionf
import org.shipwrights.enderkinesis.effect.EndingEffects
import org.shipwrights.enderkinesis.registry.EKEffects
import java.util.WeakHashMap

/**
 * Which spell effects an entity visibly carries, for every client to draw: clients only
 * know their own player's effects. Sent as bits whenever one starts or ends (with the
 * magnitude each cloak, and the Halo, was cast at, which sets how dense it's drawn), plus a
 * frozen mob's tumble every tick.
 *
 * The client's copy lives here, in common code, because common mixins read it too (a frozen
 * mob is solid to other entities on both sides).
 */
object SpellVisuals {
    const val CRYSTALLIZED = 1
    const val ENTANGLED = 2
    const val FROZEN = 4
    const val WEIGHTED = 8
    const val HALO = 16
    const val POISON_CLOAK = 32
    const val FLAME_CLOAK = 64
    const val FROST_CLOAK = 128
    const val LIGHTNING_CLOAK = 256
    const val ABSORPTION_CLOAK = 512
    const val VOID_CLOAK = 1024
    /** Any of the elements' cloaks. */
    const val CLOAKS = POISON_CLOAK or FLAME_CLOAK or FROST_CLOAK or LIGHTNING_CLOAK or ABSORPTION_CLOAK or VOID_CLOAK
    /** The Halo's amplifier, which sets its radius, rides in the bits from here up. */
    private const val HALO_SHIFT = 12
    /** The visuals drawn as dense as they're cast (the cloaks and the Halo), in the order their
     *  magnitudes are sent. */
    private val CAST_ORDER = intArrayOf(POISON_CLOAK, FLAME_CLOAK, FROST_CLOAK, LIGHTNING_CLOAK, ABSORPTION_CLOAK, VOID_CLOAK, HALO)

    // Game thread only: what was last sent for each entity (its bits and the magnitudes with
    // them), and the magnitude each of its cloaks and its Halo was cast at.
    private val sent = WeakHashMap<LivingEntity, Long>()
    private val castAt = WeakHashMap<LivingEntity, FloatArray>()

    /** [entity]'s [visual] (a cloak's bit, or [HALO]) is cast at [magnitude]: call before giving
     *  its effect. */
    fun castMagnitude(entity: LivingEntity, visual: Int, magnitude: Float) {
        val i = CAST_ORDER.indexOf(visual)
        if (i >= 0) castAt.getOrPut(entity) { FloatArray(CAST_ORDER.size) }[i] = magnitude
    }

    /** The magnitudes of the cloaks and Halo in [bits] that [entity] wears, in tenths, in
     *  [CAST_ORDER] (one byte each, so up to 25.5). One given some other way than cast goes by
     *  its amplifier: a cloak's tier, or the Halo's magnitude. */
    fun castMagnitudes(entity: LivingEntity, bits: Int): ByteArray {
        val cast = castAt[entity]
        val effects = arrayOf(EKEffects.POISON_CLOAK, EKEffects.FLAME_CLOAK, EKEffects.FROST_CLOAK, EKEffects.LIGHTNING_CLOAK, EKEffects.ABSORPTION_CLOAK, EKEffects.VOID_CLOAK, EKEffects.HALO)
        val out = ArrayList<Byte>()
        for ((i, visual) in CAST_ORDER.withIndex()) {
            if (bits and visual == 0) continue
            val amplifier = entity.getEffect(effects[i].get())?.amplifier ?: 0
            val magnitude = cast?.get(i)?.takeIf { it > 0f } ?: when {
                visual == HALO -> amplifier + 1f
                amplifier == 0 -> 1f
                amplifier == 1 -> 3f
                else -> 5f
            }
            out += Math.round(magnitude * 10f).coerceIn(1, 255).toByte()
        }
        return out.toByteArray()
    }

    fun bits(entity: LivingEntity): Int {
        var bits = 0
        if (EndingEffects.stillHas(entity, EKEffects.CRYSTALLIZED.get())) bits = bits or CRYSTALLIZED
        if (EndingEffects.stillHas(entity, EKEffects.ENTANGLED.get())) bits = bits or ENTANGLED
        if (EndingEffects.stillHas(entity, EKEffects.FROZEN.get())) bits = bits or FROZEN
        if (EndingEffects.stillHas(entity, EKEffects.WEIGHTED.get())) bits = bits or WEIGHTED
        if (EndingEffects.stillHas(entity, EKEffects.HALO.get())) {
            bits = bits or HALO or ((entity.getEffect(EKEffects.HALO.get())?.amplifier ?: 0) shl HALO_SHIFT)
        }
        if (EndingEffects.stillHas(entity, EKEffects.POISON_CLOAK.get())) bits = bits or POISON_CLOAK
        if (EndingEffects.stillHas(entity, EKEffects.FLAME_CLOAK.get())) bits = bits or FLAME_CLOAK
        if (EndingEffects.stillHas(entity, EKEffects.FROST_CLOAK.get())) bits = bits or FROST_CLOAK
        if (EndingEffects.stillHas(entity, EKEffects.LIGHTNING_CLOAK.get())) bits = bits or LIGHTNING_CLOAK
        if (EndingEffects.stillHas(entity, EKEffects.ABSORPTION_CLOAK.get())) bits = bits or ABSORPTION_CLOAK
        if (EndingEffects.stillHas(entity, EKEffects.VOID_CLOAK.get())) bits = bits or VOID_CLOAK
        return bits
    }

    /** Called when one of these effects starts or ends on [entity]. */
    fun update(entity: LivingEntity) {
        val level = entity.level() as? ServerLevel ?: return
        val bits = bits(entity)
        val magnitudes = castMagnitudes(entity, bits)
        val key = (bits.toLong() shl 32) or (magnitudes.contentHashCode().toLong() and 0xFFFFFFFFL)
        if ((sent[entity] ?: 0L) == key) return
        if (bits == 0) sent.remove(entity) else sent[entity] = key
        SpellNetwork.broadcastVisuals(level, entity, bits, magnitudes)
    }

    // -----------------------------------------------------------------------
    // Client copy (render thread)
    // -----------------------------------------------------------------------

    /** A frozen mob's body rotation since it froze: last tick's and this tick's. */
    class Pose(var previous: Quaternionf, var current: Quaternionf)

    private val clientBits = Int2IntOpenHashMap()
    private val clientMagnitudes = Int2ObjectOpenHashMap<FloatArray>()
    private val clientPoses = Int2ObjectOpenHashMap<Pose>()

    /** [id]'s visual [bits], and the magnitudes (in tenths) of the cloaks and Halo among them,
     *  in [CAST_ORDER]. */
    fun setClientBits(id: Int, bits: Int, magnitudes: ByteArray = ByteArray(0)) {
        if (bits == 0) clientBits.remove(id) else clientBits.put(id, bits)
        var k = 0
        val all = FloatArray(CAST_ORDER.size)
        for ((i, visual) in CAST_ORDER.withIndex()) {
            if (bits and visual != 0 && k < magnitudes.size) all[i] = (magnitudes[k++].toInt() and 0xFF) / 10f
        }
        if (bits and (CLOAKS or HALO) == 0) clientMagnitudes.remove(id) else clientMagnitudes.put(id, all)
        if (bits and FROZEN == 0) clientPoses.remove(id)
    }

    @JvmStatic
    fun clientBits(id: Int): Int = clientBits.get(id)

    /** Whether any entity this client knows of carries a visual in [mask]. */
    fun anyClient(mask: Int): Boolean {
        val values = clientBits.values.iterator()
        while (values.hasNext()) if (values.nextInt() and mask != 0) return true
        return false
    }

    /** The magnitude entity [id]'s [visual] (a cloak's bit, or [HALO]) was cast at, as this
     *  client knows it; 1 if it doesn't. */
    fun clientCastMagnitude(id: Int, visual: Int): Float {
        val i = CAST_ORDER.indexOf(visual)
        val m = clientMagnitudes.get(id)?.getOrNull(i) ?: 0f
        return if (m > 0f) m else 1f
    }

    /** How far a Halo carried with [bits] reaches. */
    fun haloRadius(bits: Int): Double = org.shipwrights.enderkinesis.effect.HaloEffect.radius((bits ushr HALO_SHIFT) and 15)

    fun setClientPose(id: Int, rotation: Quaternionf) {
        val pose = clientPoses.get(id)
        if (pose == null) clientPoses.put(id, Pose(Quaternionf(rotation), rotation))
        else {
            pose.previous = pose.current
            pose.current = rotation
        }
    }

    /** The frozen mob [id]'s tumble at [partialTick], or null when it isn't frozen. */
    @JvmStatic
    fun clientPose(id: Int, partialTick: Float): Quaternionf? {
        val pose = clientPoses.get(id) ?: return null
        return Quaternionf(pose.previous).slerp(pose.current, partialTick)
    }

    fun resetClient() {
        clientBits.clear()
        clientMagnitudes.clear()
        clientPoses.clear()
    }

    /** Whether [entity] is frozen solid, on either side. */
    @JvmStatic
    fun isFrozen(entity: LivingEntity): Boolean =
        if (entity.level().isClientSide) clientBits.get(entity.id) and FROZEN != 0 else entity.hasEffect(EKEffects.FROZEN.get())
}
