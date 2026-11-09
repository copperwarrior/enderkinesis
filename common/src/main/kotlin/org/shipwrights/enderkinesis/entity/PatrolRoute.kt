package org.shipwrights.enderkinesis.entity

import net.minecraft.nbt.Tag
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.item.pageText

/** Reads a patrol route from any item with book pages: every "X Y Z" triple, in order. */
object PatrolRoute {
    const val MAX_POINTS = 64

    private const val PAGES_TAG = "pages"
    private val NUMBER = "-?\\d+(?:\\.\\d+)?"
    private val TRIPLE = Regex("($NUMBER)[\\s,]+($NUMBER)[\\s,]+($NUMBER)")

    /** Null when [stack] has no pages; empty when its pages hold no coordinates. */
    fun read(stack: ItemStack): List<Vec3>? {
        val pages = stack.tag?.getList(PAGES_TAG, Tag.TAG_STRING.toInt()) ?: return null
        if (pages.isEmpty()) return null
        val points = ArrayList<Vec3>()
        for (i in 0 until pages.size) {
            for (match in TRIPLE.findAll(routeText(pages.getString(i)))) {
                if (points.size >= MAX_POINTS) return points
                val (x, y, z) = match.destructured
                points += Vec3(centered(x), y.toDouble(), centered(z))
            }
        }
        return points
    }

    // Written books and Old Paper store each page as component JSON; a book and quill stores plain text.
    private fun routeText(raw: String): String = if (raw.isEmpty() || raw[0] !in "{[\"") raw else pageText(raw)

    /** A whole-number coordinate names a block, so walk to its centre. */
    private fun centered(text: String): Double = if ('.' in text) text.toDouble() else text.toDouble() + 0.5
}
