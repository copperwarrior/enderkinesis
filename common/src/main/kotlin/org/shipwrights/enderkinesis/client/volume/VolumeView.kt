package org.shipwrights.enderkinesis.client.volume

import net.minecraft.client.renderer.EffectInstance
import org.joml.Matrix4f
import org.joml.Matrix4fc

/** Camera data for the volume passes of one frame. */
class VolumeView {
    val viewProj = Matrix4f()
    val invViewProj = Matrix4f()
    var camX = 0.0
        private set
    var camY = 0.0
        private set
    var camZ = 0.0
        private set

    // Linear depth = depthB / (ndcZ + depthA).
    var depthA = 0f
        private set
    var depthB = 0f
        private set

    fun update(projection: Matrix4fc, pose: Matrix4fc, camX: Double, camY: Double, camZ: Double) {
        // The pose holds only the camera rotation, so the inverse maps clip space to camera-relative world space.
        viewProj.set(projection).mul(pose)
        invViewProj.set(viewProj).invert()
        depthA = projection.m22()
        depthB = projection.m32()
        this.camX = camX
        this.camY = camY
        this.camZ = camZ
    }

    fun pushViewProj(effect: EffectInstance) = effect.safeGetUniform("ViewProj").set(viewProj)

    fun pushInvViewProj(effect: EffectInstance) = effect.safeGetUniform("InvViewProj").set(invViewProj)

    fun pushDepthParams(effect: EffectInstance) = effect.safeGetUniform("DepthParams").set(depthA, depthB)
}
