package org.shipwrights.enderkinesis.dimension

import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level
import net.minecraft.world.level.dimension.DimensionType
import org.shipwrights.enderkinesis.EnderkinesisMod

/**
 * Identity for **Ygann's Abyss** — the totally-void dimension whose only inhabitable real
 * estate is shipyard-bounded (i.e., the player can only stand on / build on VS2 ships that
 * exist in the dimension). End sky, no time, no weather.
 *
 * The dimension is intentionally hidden:
 *  - [DimensionFilter] still permits it (the Almanac/Astrolabe lookup needs it), but
 *    [AlmanacData.addVisited] refuses to record it from a player entering normally. The only
 *    legitimate way for an Almanac to gain the entry is the lore-book copy that sits on the
 *    lectern inside the Roaming End Ship structure (seeded by [SeedLecternAlmanacProcessor]).
 *  - [YgannAbyssGuard] enforces shipyard-only placement at runtime.
 */
object YgannAbyss {
    val ID: ResourceLocation = EnderkinesisMod.id("ygann_abyss")
    val LEVEL_KEY: ResourceKey<Level> = ResourceKey.create(Registries.DIMENSION, ID)
    val DIMENSION_TYPE_KEY: ResourceKey<DimensionType> = ResourceKey.create(Registries.DIMENSION_TYPE, ID)
}
