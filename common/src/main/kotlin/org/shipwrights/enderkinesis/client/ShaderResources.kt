package org.shipwrights.enderkinesis.client

import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceProvider
import org.shipwrights.enderkinesis.EnderkinesisMod

// Vanilla ShaderInstance reads `shaders/core/<name>` in the minecraft namespace only, and fails on a namespaced name.
/** Looks up `minecraft:` resources in our namespace first, so our core shaders can live under `assets/enderkinesis`. */
fun ResourceProvider.preferringModNamespace(): ResourceProvider = ResourceProvider { loc ->
    if (loc.namespace == ResourceLocation.DEFAULT_NAMESPACE) {
        val attempt = getResource(EnderkinesisMod.id(loc.path))
        if (attempt.isPresent) attempt else getResource(loc)
    } else {
        getResource(loc)
    }
}
