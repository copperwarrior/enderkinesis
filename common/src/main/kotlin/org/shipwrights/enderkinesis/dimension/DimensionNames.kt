package org.shipwrights.enderkinesis.dimension

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.resources.ResourceLocation

/**
 * Human-facing dimension names. Prefers a "fancy" translated name (`dimension.<namespace>.<path>`,
 * the convention mods/datapacks use) and falls back to the bare path (`the_nether`) when no
 * translation exists — never the raw `namespace:path` id.
 */
object DimensionNames {
    fun display(dimension: ResourceLocation): MutableComponent =
        Component.translatableWithFallback(
            "dimension.${dimension.namespace}.${dimension.path}",
            dimension.path,
        )
}
