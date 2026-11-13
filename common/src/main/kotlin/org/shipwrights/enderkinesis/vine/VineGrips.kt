package org.shipwrights.enderkinesis.vine

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.level.ServerLevel
import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.chains.ChainLifecycle
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.mod.common.shipObjectWorld

/** Node [node] of a vine grips ship [shipId] at [local], a shipyard point. The vine saves it; node ids do not survive a reload. */
class VineGrip(val node: Int, val shipId: Long, local: Vector3dc) {
    val local: Vector3dc = Vector3d(local)

    fun save(): CompoundTag = CompoundTag().apply {
        putInt("node", node)
        putLong("ship", shipId)
        putDouble("x", local.x()); putDouble("y", local.y()); putDouble("z", local.z())
    }

    companion object {
        fun load(tag: CompoundTag) = VineGrip(
            tag.getInt("node"), tag.getLong("ship"), Vector3d(tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z")),
        )
    }
}

internal fun CompoundTag.putGrips(grips: List<VineGrip>) {
    if (grips.isNotEmpty()) put("grips", ListTag().apply { grips.forEach { add(it.save()) } })
}

internal fun CompoundTag.getGrips(): List<VineGrip> {
    val list = getList("grips", Tag.TAG_COMPOUND.toInt())
    return List(list.size) { VineGrip.load(list.getCompound(it)) }
}

/** The grips of [run] of a vine's nodes, counted again from the run's first node. */
internal fun List<VineGrip>.inRun(run: IntRange): List<VineGrip> =
    filter { it.node in run }.map { VineGrip(it.node - run.first, it.shipId, it.local) }

/**
 * Keeps each gripping vine's physics grips in step with its chain: makes them again when the chain is new (a load or a
 * rebuild), takes them away while the vine fades, and forgets the saved grips that broke.
 */
@OptIn(GameTickOnly::class)
internal fun ServerLevel.tickHullGrips(data: WoggakVineData) {
    for (vine in data.vines) {
        if (vine.grips.isEmpty()) continue
        val live = vine.hullGrips
        if (live != null && live.any { it.broken }) {
            val broken = live.filter { it.broken }.map { it.node }.toSet()
            vine.grips = vine.grips.filter { it.node !in broken }
            vine.hullGrips = null
            data.jointsDirty = true
            data.touch(vine)
            data.setDirty()
        }
        if (vine.fadeStart >= 0) {
            if (vine.hullGrips != null) data.jointsDirty = true
            vine.hullGrips = null
            continue
        }
        if (vine.hullGrips != null && vine.hullGripChain == vine.chain.value) continue
        val chain = shipObjectWorld.allChains.getById(vine.chain) ?: continue
        if (chain.status.lifecycle != ChainLifecycle.ACTIVE) continue
        val ids = chain.nodeIds
        vine.hullGrips = vine.grips.filter { it.node in 1 until ids.size - 1 }
            .map { HullGrip(ids[it.node], it.shipId, it.local, it.node) }.toTypedArray()
        vine.hullGripChain = vine.chain.value
        data.jointsDirty = true
    }
}
