package org.shipwrights.enderkinesis.client

import net.minecraft.client.model.EntityModel
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.geom.PartPose
import net.minecraft.world.entity.LivingEntity
import org.shipwrights.enderkinesis.spell.SpellVisuals
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap

// A frozen tumble still turns the whole model (see `LivingEntityRendererFrozenTumbleMixin`).
object FrozenPoses {
    private class Snapshot(val bodyRot: Float) {
        var model: EntityModel<*>? = null
        var parts: List<Pair<ModelPart, PartPose>>? = null
    }

    // Render thread only.
    private val snapshots = WeakHashMap<LivingEntity, Snapshot>()
    // Reflection results, found once for each model class.
    private val partFields = HashMap<Class<*>, List<Field>>()

    /** Called first each frame, so it starts the snapshot on freeze and forgets it on thaw. */
    @JvmStatic
    fun bodyRot(entity: LivingEntity, rot: Float): Float {
        if (!SpellVisuals.isFrozen(entity)) {
            snapshots.remove(entity)
            return rot
        }
        return snapshots.getOrPut(entity) { Snapshot(rot) }.bodyRot
    }

    /** The first frozen frame stores the pose; each later frame restores it. */
    @JvmStatic
    fun posed(entity: LivingEntity, model: EntityModel<*>) {
        val snap = snapshots[entity] ?: return
        val kept = snap.parts
        if (kept == null || snap.model !== model) {
            snap.model = model
            snap.parts = parts(model).map { it to it.storePose() }
            return
        }
        for ((part, pose) in kept) part.loadPose(pose)
    }

    private fun parts(model: EntityModel<*>): List<ModelPart> {
        val out = Collections.newSetFromMap(IdentityHashMap<ModelPart, Boolean>())
        for (field in fieldsOf(model.javaClass)) {
            when (val v = field.get(model)) {
                is ModelPart -> v.allParts.forEach { out += it }
                is Array<*> -> v.forEach { p -> (p as? ModelPart)?.allParts?.forEach { out += it } }
            }
        }
        return out.toList()
    }

    private fun fieldsOf(type: Class<*>): List<Field> = partFields.getOrPut(type) {
        val fields = ArrayList<Field>()
        var c: Class<*>? = type
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                val t = f.type
                if (t != ModelPart::class.java && !(t.isArray && t.componentType == ModelPart::class.java)) continue
                if (runCatching { f.isAccessible = true }.isSuccess) fields += f
            }
            c = c.superclass
        }
        fields
    }

    fun reset() = snapshots.clear()
}
