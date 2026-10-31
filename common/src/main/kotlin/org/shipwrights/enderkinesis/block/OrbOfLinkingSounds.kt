package org.shipwrights.enderkinesis.block

import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.Level
import org.shipwrights.enderkinesis.registry.EKSounds

/** Server side. On a ship, [BlockPos] is a shipyard position; VS2 sends the sound to the players near the ship. */
object OrbOfLinkingSounds {

    private const val TOME_PREFIX = "tome_of_"

    fun play(level: Level, pos: BlockPos, event: RegistrySupplier<SoundEvent>) {
        level.playSound(null, pos, event.get(), SoundSource.BLOCKS, 1f, 1f)
    }

    fun playAt(level: Level, x: Double, y: Double, z: Double, event: RegistrySupplier<SoundEvent>) {
        level.playSound(null, x, y, z, event.get(), SoundSource.BLOCKS, 1f, 1f)
    }

    fun playLink(level: Level, pos: BlockPos, tomeKind: ResourceLocation) {
        val event = EKSounds.ORB_OF_LINKING_LINK[tomeKind.path.removePrefix(TOME_PREFIX)] ?: return
        play(level, pos, event)
    }
}
