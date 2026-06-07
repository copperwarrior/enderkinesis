package org.shipwrights.enderkinesis.item

/**
 * Thread-local channel for the [DisintegrationTomeOrbBehavior]'s Looting level. Vanilla's
 * [net.minecraft.world.level.storage.loot.functions.LootingEnchantFunction] reads the
 * looting bonus from the killer entity's main-hand enchantments — useless to us, since the
 * disintegration beam has no entity at all. The mixin
 * `LootingEnchantFunctionMixin` consults this holder *before* the vanilla
 * killer-entity path; when set to a positive level, the mixin applies the standard vanilla
 * looting math with our injected level and short-circuits the function.
 *
 * Set [set] before calling [net.minecraft.world.entity.LivingEntity.hurt] for a killing
 * blow; the loot rolls happen synchronously on the same thread before [hurt] returns, so
 * the override is in scope for exactly the right calls. Always pair with [clear] in a
 * `try`/`finally` so unrelated loot rolls on the same thread don't pick up our level.
 *
 * The holder is global rather than per-orb because vanilla calls the function from inside
 * its own death-drop machinery — we can't pass the orb's level explicitly. Thread-local
 * scoping is sufficient: server-side loot rolls always happen on the main server thread,
 * and we set/clear inside a single synchronous call.
 */
object DisintegrationLootingOverride {

    private val holder = ThreadLocal<Int?>()

    @JvmStatic
    fun set(level: Int) {
        if (level > 0) holder.set(level) else holder.remove()
    }

    @JvmStatic
    fun clear() {
        holder.remove()
    }

    /** Current override, or null if no disintegration beam is currently driving a loot roll. */
    @JvmStatic
    fun get(): Int? = holder.get()
}
