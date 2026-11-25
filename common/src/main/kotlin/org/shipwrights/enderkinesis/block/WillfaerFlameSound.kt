package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import net.minecraft.world.level.Level
import org.shipwrights.enderkinesis.registry.EKSounds

private const val FLAME_SOUND_CHANCE = 100

fun Level.playWillfaerFlame(pos: BlockPos, random: RandomSource) {
    if (random.nextInt(FLAME_SOUND_CHANCE) != 0) return
    playLocalSound(
        pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, EKSounds.WILLFAER_FLAME.get(), SoundSource.BLOCKS,
        0.6f + random.nextFloat() * 0.4f, 0.9f + random.nextFloat() * 0.2f, false,
    )
}
