package org.shipwrights.enderkinesis.item

import com.google.gson.JsonParseException
import net.minecraft.network.chat.Component

// Written books store each page as component JSON; a book and quill stores plain text, which is not valid JSON.
private fun parsePage(raw: String): Component? =
    try {
        Component.Serializer.fromJson(raw)
    } catch (_: JsonParseException) {
        null
    }

/** A stored book page as a component; plain text becomes a literal. */
fun pageComponent(raw: String): Component = parsePage(raw) ?: Component.literal(raw)

/** The plain text of a stored book page; plain text stays as it is. */
fun pageText(raw: String): String = parsePage(raw)?.string ?: raw
