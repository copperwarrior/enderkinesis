package org.shipwrights.enderkinesis.client.model

import net.minecraft.client.animation.AnimationChannel
import net.minecraft.client.animation.AnimationDefinition
import net.minecraft.client.animation.Keyframe
import net.minecraft.client.animation.KeyframeAnimations

/** Blockbench-exported keyframes; [PULSE] is a 2.5 s looping idle driven by `Util.getMillis`. */
internal object HeartOfTheWildAnimations {

    private val ROT = AnimationChannel.Targets.ROTATION
    private val POS = AnimationChannel.Targets.POSITION
    private val SCL = AnimationChannel.Targets.SCALE
    private val LIN = AnimationChannel.Interpolations.LINEAR
    private val CR = AnimationChannel.Interpolations.CATMULLROM

    private fun kfRot(t: Float, x: Float, y: Float, z: Float, interp: AnimationChannel.Interpolation) =
        Keyframe(t, KeyframeAnimations.degreeVec(x, y, z), interp)

    private fun kfPos(t: Float, x: Float, y: Float, z: Float, interp: AnimationChannel.Interpolation) =
        Keyframe(t, KeyframeAnimations.posVec(x, y, z), interp)

    // scaleVec takes doubles in 1.20.1 — it subtracts 1 first, so
    // it wants the extra precision before the cast back to float.
    private fun kfScl(t: Float, x: Double, y: Double, z: Double, interp: AnimationChannel.Interpolation) =
        Keyframe(t, KeyframeAnimations.scaleVec(x, y, z), interp)

    val PULSE: AnimationDefinition = AnimationDefinition.Builder.withLength(2.5f).looping()
        .addAnimation("heart_lchamber", AnimationChannel(ROT,
            kfRot(1.1667f, -44.639f, -8.421f, -59.6385f, LIN),
            kfRot(1.6667f, -44.639f, -8.421f, -59.6385f, LIN),
        ))
        .addAnimation("heart_lchamber", AnimationChannel(SCL,
            kfScl(0.0000f, 1.0938, 1.1937, 1.0938, CR),
            kfScl(0.1667f, 1.1000, 1.2000, 1.1000, CR),
            kfScl(1.1667f, 1.0000, 1.1000, 1.0000, CR),
            kfScl(1.2917f, 1.1523, 1.1773, 1.1523, CR),
            kfScl(1.6667f, 1.0000, 1.1000, 1.0000, CR),
            kfScl(2.5000f, 1.0938, 1.1937, 1.0938, CR),
        ))
        .addAnimation("heart_aorta", AnimationChannel(ROT,
            kfRot(0.0000f, -12.2459f, -9.7597f, 2.1459f, CR),
            kfRot(1.1667f, -12.25f,   -9.76f,   2.15f,   CR),
            kfRot(1.2917f,  -0.2968f, -10.8388f, 4.8116f, CR),
            kfRot(2.5000f, -12.2459f, -9.7597f, 2.1459f, CR),
        ))
        .addAnimation("heart_aorta", AnimationChannel(POS,
            kfPos(0.0000f, 0.0f, 0.00f, 0.0f, LIN),
            kfPos(1.1667f, 0.0f, 0.89f, 0.0f, CR),
            kfPos(1.2917f, 0.0f, 1.87f, 0.0f, CR),
            kfPos(2.5000f, 0.0f, 0.00f, 0.0f, LIN),
        ))
        .addAnimation("heart_cv", AnimationChannel(ROT,
            kfRot(0.0000f, 0.0f, 0.0f, -25.0f, CR),
            kfRot(1.0000f, 0.0f, 0.0f,  -7.5f, CR),
            kfRot(1.5000f, 0.0f, 0.0f,  -7.5f, CR),
            kfRot(2.5000f, 0.0f, 0.0f, -25.0f, CR),
        ))
        .addAnimation("heart_cv", AnimationChannel(POS,
            kfPos(0.0000f,  1.00f, 0.00f,  0.00f, LIN),
            kfPos(1.0000f,  0.00f, 0.75f, -1.25f, LIN),
            kfPos(1.0833f, -0.25f, 1.75f, -1.25f, LIN),
            kfPos(1.5000f,  0.00f, 0.75f, -1.25f, LIN),
            kfPos(2.5000f,  1.00f, 0.00f,  0.00f, LIN),
        ))
        .addAnimation("heart_cv", AnimationChannel(SCL,
            kfScl(0.0000f, 1.0, 1.0, 1.0, CR),
            kfScl(1.0000f, 1.0, 1.0, 1.0, CR),
            kfScl(1.0833f, 0.8, 1.0, 0.9, CR),
            kfScl(1.5000f, 1.0, 1.0, 1.0, CR),
            kfScl(2.5000f, 1.0, 1.0, 1.0, CR),
        ))
        .addAnimation("root", AnimationChannel(POS,
            kfPos(0.0000f, 1.0f, -1.0f, 0.0f, LIN),
        ))
        .addAnimation("root", AnimationChannel(SCL,
            kfScl(0.0000f, 0.9, 0.9, 0.9, LIN),
        ))
        .addAnimation("heart_rchamber", AnimationChannel(ROT,
            kfRot(0.0000f, -44.639f, -8.421f, -59.6385f, LIN),
            kfRot(2.5000f, -44.639f, -8.421f, -59.6385f, LIN),
        ))
        .addAnimation("heart_rchamber", AnimationChannel(SCL,
            kfScl(0.0000f, 1.0000, 1.1000, 1.0000, CR),
            kfScl(1.0000f, 1.1000, 1.2000, 1.1000, CR),
            kfScl(1.5000f, 1.1000, 1.2000, 1.1000, CR),
            kfScl(1.6250f, 1.1523, 1.1773, 1.1523, CR),
            kfScl(2.5000f, 1.0000, 1.1000, 1.0000, CR),
        ))
        .build()
}
