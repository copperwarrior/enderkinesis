package org.shipwrights.enderkinesis.util

import com.google.gson.JsonParser
import net.minecraft.resources.ResourceLocation

private const val TAG_DEPTH = 8

/** Bootstrap binds no tags, so this merges every copy of the tag on the classpath, as a data load does. */
internal fun classpathBlockTag(tag: ResourceLocation, depth: Int = TAG_DEPTH): Set<ResourceLocation> {
    check(depth > 0) { "tag nesting too deep at $tag" }
    val ids = HashSet<ResourceLocation>()
    val copies = Thread.currentThread().contextClassLoader.getResources("data/${tag.namespace}/tags/blocks/${tag.path}.json")
    for (url in copies) {
        val json = url.openStream().reader().use { JsonParser.parseReader(it).asJsonObject }
        if (json.get("replace")?.asBoolean == true) ids.clear()
        for (value in json.getAsJsonArray("values")) {
            val id = if (value.isJsonObject) value.asJsonObject.get("id").asString else value.asString
            if (id.startsWith("#")) ids += classpathBlockTag(ResourceLocation(id.substring(1)), depth - 1) else ids += ResourceLocation(id)
        }
    }
    return ids
}
