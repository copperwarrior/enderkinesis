package org.shipwrights.enderkinesis.item

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component

/**
 * A Silverpoint sketch of the view; the picture is already drawn, so it keeps no seed or notes of its own. A paper
 * from before the picture store holds its [packed] picture; a newer one holds only its [key] in the store.
 */
class PictureReadout private constructor(
    override val owner: String,
    val key: String,
    override val sketchColour: Boolean,
    val packed: ByteArray?,
) : StrikeReadout {

    override val sketchSeed: Int get() = 0
    override val title: Component get() = Component.translatable("message.enderkinesis.strike_readout.view")
    override val sketch: SketchSpec
        get() = SketchSpec(SketchSubject.Picture(key), 0, StrikeReadout.SKETCH_SIZE, notes = false, colour = sketchColour)
    override val propertiesPage: Int get() = NO_PAGE

    override fun rounded(): Component = title
    override fun lines(): List<Component> = emptyList()
    override fun pages(): List<Component> = listOf(Component.literal(SketchTag.of(sketch)))

    override fun save(): CompoundTag = CompoundTag().apply {
        putString(StrikeReadout.KEY_KIND, KIND)
        putString(StrikeReadout.KEY_OWNER, owner)
        if (packed != null) {
            putByteArray(KEY_PICTURE, packed)
        } else {
            putString(KEY_ID, key)
            putBoolean(KEY_COLOUR, sketchColour)
        }
    }

    companion object {
        const val KIND = "picture"
        const val NO_PAGE = -1
        private const val KEY_PICTURE = "picture"
        private const val KEY_ID = "picture_id"
        private const val KEY_COLOUR = "colour"

        /** A paper whose picture is in the store under [id]. */
        fun stored(owner: String, id: String, colour: Boolean): PictureReadout {
            require(SilverpointPicture.isId(id)) { "bad picture id: $id" }
            return PictureReadout(owner, id, colour, null)
        }

        fun load(tag: CompoundTag): PictureReadout? {
            val owner = tag.getString(StrikeReadout.KEY_OWNER)
            if (tag.contains(KEY_ID, Tag.TAG_STRING.toInt())) {
                val id = tag.getString(KEY_ID)
                return if (SilverpointPicture.isId(id)) PictureReadout(owner, id, tag.getBoolean(KEY_COLOUR), null) else null
            }
            val packed = tag.getByteArray(KEY_PICTURE)
            if (SilverpointPicture.sideOf(packed) == null) return null
            return PictureReadout(owner, SilverpointPicture.key(packed), SilverpointPicture.isColour(packed), packed)
        }
    }
}
