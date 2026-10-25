package org.shipwrights.enderkinesis.spell

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import org.joml.Quaternionf
import org.shipwrights.enderkinesis.registry.EKEffects
import java.util.WeakHashMap

/**
 * Which spell effects an entity visibly carries, for every client to draw: clients only
 * know their own player's effects. Sent as bits whenever one starts or ends, plus a frozen
 * mob's tumble every tick.
 *
 * The client's copy lives here, in common code, because common mixins read it too (a frozen
 * mob is solid to other entities on both sides).
 */
object SpellVisuals {
    const val CRYSTALLIZED = 1
    const val ENTANGLED = 2
    const val FROZEN = 4
    const val WEIGHTED = 8

    // Game thread only: the bits last sent for each entity.
    private val sent = WeakHashMap<LivingEntity, Int>()

    fun bits(entity: LivingEntity): Int {
        var bits = 0
        if (entity.hasEffect(EKEffects.CRYSTALLIZED.get())) bits = bits or CRYSTALLIZED
        if (entity.hasEffect(EKEffects.ENTANGLED.get())) bits = bits or ENTANGLED
        if (entity.hasEffect(EKEffects.FROZEN.get())) bits = bits or FROZEN
        if (entity.hasEffect(EKEffects.WEIGHTED.get())) bits = bits or WEIGHTED
        return bits
    }

    /** Called when one of these effects starts or ends on [entity]. */
    fun update(entity: LivingEntity) {
        val level = entity.level() as? ServerLevel ?: return
        val bits = bits(entity)
        if ((sent[entity] ?: 0) == bits) return
        if (bits == 0) sent.remove(entity) else sent[entity] = bits
        SpellNetwork.broadcastVisuals(level, entity, bits)
    }

    // -----------------------------------------------------------------------
    // Client copy (render thread)
    // -----------------------------------------------------------------------

    /** A frozen mob's body rotation since it froze: last tick's and this tick's. */
    class Pose(var previous: Quaternionf, var current: Quaternionf)

    private val clientBits = Int2IntOpenHashMap()
    private val clientPoses = Int2ObjectOpenHashMap<Pose>()

    fun setClientBits(id: Int, bits: Int) {
        if (bits == 0) clientBits.remove(id) else clientBits.put(id, bits)
        if (bits and FROZEN == 0) clientPoses.remove(id)
    }

    @JvmStatic
    fun clientBits(id: Int): Int = clientBits.get(id)

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
        clientPoses.clear()
    }

    /** Whether [entity] is frozen solid, on either side. */
    @JvmStatic
    fun isFrozen(entity: LivingEntity): Boolean =
        if (entity.level().isClientSide) clientBits.get(entity.id) and FROZEN != 0 else entity.hasEffect(EKEffects.FROZEN.get())
}
