package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.world.phys.Vec3

// The Cataloger tome draws here, not in CatalogerRenderer, so the Cataloger's frustum culling cannot hide it.
// The entity pass also lets water drawn later composite over a waterlogged bud.
fun renderAfterEntities(poseStack: PoseStack, buffers: MultiBufferSource, camera: Vec3, partialTick: Float) {
    CatalogerTomeWorldRenderer.renderAll(poseStack, buffers, camera.x, camera.y, camera.z, partialTick)
    YgannBarnacleDeathRenderer.renderAll(poseStack, buffers, camera.x, camera.y, camera.z, partialTick)
    WogorBudRenderer.renderAll(poseStack, buffers, camera.x, camera.y, camera.z, partialTick)
    WohlonMudCreepRenderer.renderAll(poseStack, buffers, camera.x, camera.y, camera.z, partialTick)
    WoggakVineRenderer.renderAll(poseStack, buffers, camera.x, camera.y, camera.z, partialTick)
}

// After translucent blocks, so additive beams composite over water and glass.
fun renderAfterTranslucent(poseStack: PoseStack, buffers: MultiBufferSource, camera: Vec3, partialTick: Float) {
    OrbBeamLineRenderer.renderAll(poseStack, buffers, camera.x, camera.y, camera.z, partialTick)
    LodestarDartTrailRenderer.renderAll(poseStack, buffers, camera.x, camera.y, camera.z, partialTick)
    CrepusculiteLatticeMeshRenderer.renderAll(poseStack, buffers, camera.x, camera.y, camera.z, partialTick)
    AegisClient.renderAll(poseStack, buffers, camera.x, camera.y, camera.z, partialTick)
    FusionClient.renderAll(poseStack, buffers, camera.x, camera.y, camera.z, partialTick)
    SunderingClient.renderAll(poseStack, buffers, camera.x, camera.y, camera.z, partialTick)
    EchoCannonClient.renderAll(poseStack, buffers, camera.x, camera.y, camera.z, partialTick)
    PlanGhostRenderer.renderAll(poseStack, buffers, camera.x, camera.y, camera.z, partialTick)
    VineCutHighlight.renderAll(poseStack, buffers, camera.x, camera.y, camera.z, partialTick)
}
