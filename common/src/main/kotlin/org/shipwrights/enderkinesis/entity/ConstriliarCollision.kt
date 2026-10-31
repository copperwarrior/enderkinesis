package org.shipwrights.enderkinesis.entity

import com.mojang.logging.LogUtils
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet
import org.valkyrienskies.mod.common.config.MassDatapackResolver
import org.valkyrienskies.mod.common.hooks.VSGameEvents

/** The walker senses the world with the collision its VS2 body has, plus fluids, which
 *  it walks on. Otherwise the hover and feet stand on blocks the body falls through. */
internal object ConstriliarCollision {

    /** The shape VS2 gives [state] at [pos] for ship bodies: the outline for solid states
     *  (a cobweb is a full cube to a ship, though it has no collision), else the
     *  collision shape; empty for [shipPassable] blocks. Mirrors MassDatapackResolver. */
    fun shipShape(level: BlockGetter, pos: BlockPos, state: BlockState): VoxelShape = when {
        shipPassable(state) -> Shapes.empty()
        state.isSolid -> state.getShape(level, pos)
        else -> state.getCollisionShape(level, pos)
    }

    /** vs_mass `no_collision` (leaves and the like). VS2 2.4 keeps such a block solid-typed
     *  with an empty shape, so only the resolver's flag tells. The map and its
     *  package-private value class are not API, and the getter's name differs between
     *  VS2 builds, so both are read by reflection. */
    fun shipPassable(state: BlockState): Boolean {
        val vs = vsMass ?: return false
        return passableSnapshot(vs)?.blocks?.contains(state.block) ?: vs.lookup(state.block)
    }

    private class VsMass(val map: Map<ResourceLocation, Any>, val noCollision: java.lang.reflect.Method) {
        fun lookup(block: Block): Boolean {
            val info = map[BuiltInRegistries.BLOCK.getKey(block)] ?: return false
            return noCollision.invoke(info) == true
        }
    }

    private class Snapshot(val mapSize: Int, val generation: Int, val blocks: ReferenceOpenHashSet<Block>)

    // Read from server, client and worker threads: publish only immutable snapshots.
    @Volatile private var snapshot: Snapshot? = null
    @Volatile private var generation = 0

    private fun passableSnapshot(vs: VsMass): Snapshot? {
        val gen = generation
        val size = vs.map.size
        snapshot?.let { if (it.mapSize == size && it.generation == gen) return it }
        // The resolver can refill its map on another thread during a reload.
        val blocks = try {
            ReferenceOpenHashSet<Block>().apply {
                for ((id, info) in vs.map) {
                    if (vs.noCollision.invoke(info) == true) BuiltInRegistries.BLOCK.getOptional(id).ifPresent { add(it) }
                }
            }
        } catch (_: ConcurrentModificationException) {
            return null
        }
        return Snapshot(size, gen, blocks).also { snapshot = it }
    }

    // Declared before vsMass: object properties initialise in order.
    private val NO_COLLISION_GETTERS = listOf("getNoCollision", "getNoCollisionOverride")

    // The resolver fills this one map in place on each datapack load.
    @Suppress("UNCHECKED_CAST")
    private val vsMass: VsMass? = runCatching {
        val map = MassDatapackResolver::class.java.getDeclaredField("map").apply { isAccessible = true }.get(null)
            as Map<ResourceLocation, Any>
        val info = Class.forName("org.valkyrienskies.mod.common.config.VSBlockStateInfo")
        val getter = NO_COLLISION_GETTERS.firstNotNullOf { name ->
            runCatching { info.getDeclaredMethod(name) }.getOrNull()
        }.apply { isAccessible = true }
        VsMass(map, getter).also { vs ->
            val count = vs.map.values.count { getter.invoke(it) == true }
            LogUtils.getLogger().info("[EK constriliar] VS2 no_collision blocks: {}", count)
        }
    }.onFailure { LogUtils.getLogger().warn("[EK constriliar] no VS2 no_collision data; leaves count as solid", it) }
        .getOrNull()

    init {
        // Tag-based vs_mass entries can replace map values without a change in map size.
        if (vsMass != null) VSGameEvents.tagsAreLoaded.on { _, _ -> generation++ }
    }

    /** A collider clip that sees fluids and skips [shipPassable] blocks. VS2 rebuilds the
     *  context for each ship it clips, so on ships the filter does not apply. */
    class Clip(from: Vec3, to: Vec3, entity: Entity?) :
        ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, entity) {
        override fun getBlockShape(state: BlockState, level: BlockGetter, pos: BlockPos): VoxelShape =
            if (shipPassable(state)) Shapes.empty() else super.getBlockShape(state, level, pos)
    }
}
