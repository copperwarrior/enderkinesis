package org.shipwrights.enderkinesis.item

import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component

/** A Silverpoint sketch of the view; the picture is already drawn, so it keeps no seed or notes of its own. */
class PictureReadout(override val owner: String, val packed: ByteArray) : StrikeReadout {

    val key: String = SilverpointPicture.key(packed)

    override val sketchSeed: Int get() = 0
    override val title: Component get() = Component.translatable("message.enderkinesis.strike_readout.view")
    override val sketch: SketchSpec
        get() = SketchSpec(SketchSubject.Picture(key), 0, StrikeReadout.SKETCH_SIZE, notes = false)
    override val propertiesPage: Int get() = NO_PAGE

    override fun rounded(): Component = title
    override fun lines(): List<Component> = emptyList()
    override fun pages(): List<Component> = listOf(Component.literal(SketchTag.of(sketch)))

    override fun save(): CompoundTag = CompoundTag().apply {
        putString(StrikeReadout.KEY_KIND, KIND)
        putString(StrikeReadout.KEY_OWNER, owner)
        putByteArray(KEY_PICTURE, packed)
    }

    companion object {
        const val KIND = "picture"
        const val NO_PAGE = -1
        private const val KEY_PICTURE = "picture"

        fun load(tag: CompoundTag): PictureReadout? {
            val packed = tag.getByteArray(KEY_PICTURE)
            if (SilverpointPicture.sideOf(packed) == null) return null
            return PictureReadout(tag.getString(StrikeReadout.KEY_OWNER), packed)
        }
    }
}
