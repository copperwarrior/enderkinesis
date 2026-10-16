package org.shipwrights.enderkinesis.client

import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceProvider

/** ShaderInstance fails on a namespaced shader name, so it looks up `minecraft:` paths in
 *  [namespace] first. */
internal fun namespacedShaderResources(delegate: ResourceProvider, namespace: String): ResourceProvider =
    ResourceProvider { loc ->
        if (loc.namespace == ResourceLocation.DEFAULT_NAMESPACE) {
            val attempt = delegate.getResource(ResourceLocation(namespace, loc.path))
            if (attempt.isPresent) attempt else delegate.getResource(loc)
        } else {
            delegate.getResource(loc)
        }
    }
