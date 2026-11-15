package org.shipwrights.enderkinesis.dimension

import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level

/** Add each new Enderkinesis dimension here so it never gets weather. */
object WeatherlessDimensions {
    private val KEYS: Set<ResourceKey<Level>> = setOf(
        Wohlonnogondonia.LEVEL_KEY,
        SselithRepertory.LEVEL_KEY,
        YgannAbyss.LEVEL_KEY,
        Sureibjin.LEVEL_KEY,
        WeheloPocket.LEVEL_KEY,
    )

    @JvmStatic
    fun contains(key: ResourceKey<Level>): Boolean = key in KEYS
}
