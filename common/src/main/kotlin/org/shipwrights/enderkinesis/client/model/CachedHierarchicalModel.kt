package org.shipwrights.enderkinesis.client.model

import java.util.Optional
import java.util.function.Function
import net.minecraft.client.model.HierarchicalModel
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.renderer.RenderType
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.Entity

/** [HierarchicalModel] for models animated every frame: caches part lookups. Render thread only. */
abstract class CachedHierarchicalModel<E : Entity> : HierarchicalModel<E> {
    constructor() : super()
    constructor(renderType: Function<ResourceLocation, RenderType>) : super(renderType)

    // Lazy: root() is not ready while the superclass constructor runs.
    private val allParts: List<ModelPart> by lazy(LazyThreadSafetyMode.NONE) { root().allParts.toList() }
    private val partsByName = HashMap<String, Optional<ModelPart>>()

    // KeyframeAnimations.animate resolves every bone by name through a stream search over all parts.
    override fun getAnyDescendantWithName(name: String): Optional<ModelPart> =
        partsByName.getOrPut(name) { super.getAnyDescendantWithName(name) }

    protected fun resetAllParts() {
        for (part in allParts) part.resetPose()
    }
}
