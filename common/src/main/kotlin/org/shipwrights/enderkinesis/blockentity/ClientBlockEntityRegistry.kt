package org.shipwrights.enderkinesis.blockentity

import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** The loaded block entities of one type in each client level. A block entity on a ship is in
 *  a shipyard chunk, so a scan of the chunks near the player does not find it. */
open class ClientBlockEntityRegistry<T : BlockEntity> {

    private val byLevel: ConcurrentHashMap<Level, MutableSet<T>> = ConcurrentHashMap()

    fun register(level: Level, be: T) {
        byLevel.computeIfAbsent(level) {
            Collections.newSetFromMap(ConcurrentHashMap())
        }.add(be)
    }

    fun unregister(level: Level, be: T) {
        byLevel[level]?.remove(be)
    }

    fun all(level: Level): Collection<T> = byLevel[level] ?: emptyList()

    /** The map key is the level. Without this, an old level and its chunks stay in memory. */
    fun dropLevels(clientSide: Boolean) {
        byLevel.keys.removeIf { it.isClientSide == clientSide }
    }
}

object EnderAstrolabeClientRegistry : ClientBlockEntityRegistry<EnderAstrolabeBlockEntity>()

object EyeroscopeClientRegistry : ClientBlockEntityRegistry<EyeroscopeBlockEntity>()

object PlanarAnchorClientRegistry : ClientBlockEntityRegistry<PlanarAnchorBlockEntity>()

object AetherPadClientRegistry : ClientBlockEntityRegistry<AetherPadBlockEntity>()

object VoidHarnessClientRegistry : ClientBlockEntityRegistry<VoidHarnessBlockEntity>()

object VoidHookClientRegistry : ClientBlockEntityRegistry<VoidHookBlockEntity>()

object HeartOfTheWildClientRegistry : ClientBlockEntityRegistry<HeartOfTheWildBlockEntity>()
