package org.shipwrights.enderkinesis.item

import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import java.math.BigDecimal
import java.math.RoundingMode

/** What a Silverpoint reads from a block or an entity, as kept on a Strike Readout. */
sealed interface StrikeReadout {
    val owner: String

    /** Picks the sketch's field notes; fixed on the paper so it looks the same each time it opens. */
    val sketchSeed: Int
    val title: Component

    /** Null when the subject cannot be drawn. */
    val sketch: SketchSpec?

    fun rounded(): Component
    fun lines(): List<Component>
    fun save(): CompoundTag

    /** The page that the name goes over. */
    val propertiesPage: Int
        get() = if (sketch == null) 0 else 1

    /** A full-page sketch, then the properties. */
    fun pages(): List<Component> {
        val properties = Component.empty()
        lines().forEachIndexed { i, line ->
            if (i > 0) properties.append("\n")
            properties.append(line)
        }
        val sketchPage = sketch?.let { Component.literal(SketchTag.of(it)) }
        return listOfNotNull(sketchPage, properties)
    }

    companion object {
        // Fills the page: 14 text lines.
        const val SKETCH_SIZE = 122
        const val KEY_KIND = "kind"
        const val KEY_OWNER = "owner"
        const val KEY_SKETCH_SEED = "sketch_seed"

        // Papers from before entity readouts have no kind.
        fun load(tag: CompoundTag): StrikeReadout? = when (tag.getString(KEY_KIND)) {
            EntityReadout.KIND -> EntityReadout.load(tag)
            PictureReadout.KIND -> PictureReadout.load(tag)
            else -> BlockPhysicsReadout.load(tag)
        }

        fun line(key: String, vararg values: Any): Component =
            Component.translatable("message.enderkinesis.strike_readout.$key", *values)

        fun round(value: Double, places: Int): String =
            BigDecimal.valueOf(value).setScale(places, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }
}
