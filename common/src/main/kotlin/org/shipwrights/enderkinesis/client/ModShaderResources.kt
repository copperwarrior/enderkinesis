package org.shipwrights.enderkinesis.client

import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceProvider

/** ShaderInstance reads `minecraft:shaders/core/<name>`; this looks in [namespace] first. */
internal fun namespacedShaderProvider(delegate: ResourceProvider, namespace: String) =
    ResourceProvider { loc ->
        if (loc.namespace == ResourceLocation.DEFAULT_NAMESPACE) {
            val attempt = delegate.getResource(ResourceLocation(namespace, loc.path))
            if (attempt.isPresent) attempt else delegate.getResource(loc)
        } else {
            delegate.getResource(loc)
        }
    }
