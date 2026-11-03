package org.shipwrights.enderkinesis.client.volume

import net.minecraft.client.renderer.EffectInstance
import org.joml.FrustumIntersection
import org.joml.Matrix4f
import org.joml.Matrix4fc

/** Camera data for the volume passes of one frame. */
class VolumeView {
    val viewProj = Matrix4f()
    val invViewProj = Matrix4f()
    private val frustum = FrustumIntersection()
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
        frustum.set(viewProj)
        depthA = projection.m22()
        depthB = projection.m32()
        this.camX = camX
        this.camY = camY
        this.camZ = camZ
    }

    /** Camera-relative box. A box outside the frustum gives no fragments, so a skipped draw changes nothing. */
    @Suppress("LongParameterList") // Mirrors FrustumIntersection.testAab.
    fun boxVisible(minX: Float, minY: Float, minZ: Float, maxX: Float, maxY: Float, maxZ: Float): Boolean =
        frustum.testAab(minX - CULL_PAD, minY - CULL_PAD, minZ - CULL_PAD, maxX + CULL_PAD, maxY + CULL_PAD, maxZ + CULL_PAD)

    fun pushViewProj(effect: EffectInstance) = effect.safeGetUniform("ViewProj").set(viewProj)

    fun pushInvViewProj(effect: EffectInstance) = effect.safeGetUniform("InvViewProj").set(invViewProj)

    fun pushDepthParams(effect: EffectInstance) = effect.safeGetUniform("DepthParams").set(depthA, depthB)

    private companion object {
        // Float error at a frustum plane must not cull a box that shows a sliver.
        const val CULL_PAD = 0.0625f
    }
}
