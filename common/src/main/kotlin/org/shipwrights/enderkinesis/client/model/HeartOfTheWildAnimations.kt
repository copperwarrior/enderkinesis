package org.shipwrights.enderkinesis.client.model

import net.minecraft.client.animation.AnimationChannel
import net.minecraft.client.animation.AnimationDefinition
import net.minecraft.client.animation.Keyframe
import net.minecraft.client.animation.KeyframeAnimations

/**
 * Keyframes from `heart.bbmodel`. [BEATING] loops on `Util.getMillis`; [SUMMON] plays once
 * when the ritual places a heart and ends on the rest pose, where [BEATING] starts.
 */
internal object HeartOfTheWildAnimations {

    private val ROT = AnimationChannel.Targets.ROTATION
    private val POS = AnimationChannel.Targets.POSITION
    private val SCL = AnimationChannel.Targets.SCALE
    private val LIN = AnimationChannel.Interpolations.LINEAR

    // Values are already in model space: Blockbench rotation X/Y and position X are negated.
    private fun kfRot(t: Float, x: Float, y: Float, z: Float, interp: AnimationChannel.Interpolation) =
        Keyframe(t, KeyframeAnimations.degreeVec(x, y, z), interp)

    private fun kfPos(t: Float, x: Float, y: Float, z: Float, interp: AnimationChannel.Interpolation) =
        Keyframe(t, KeyframeAnimations.posVec(x, y, z), interp)

    // scaleVec takes doubles in 1.20.1 — it subtracts 1 first, so
    // it wants the extra precision before the cast back to float.
    private fun kfScl(t: Float, x: Double, y: Double, z: Double, interp: AnimationChannel.Interpolation) =
        Keyframe(t, KeyframeAnimations.scaleVec(x, y, z), interp)

    val BEATING: AnimationDefinition = AnimationDefinition.Builder.withLength(17f / 24f).looping()
        .addAnimation("bone1", AnimationChannel(POS,
            kfPos(0.0000f, 0.0f, 0.0f, 0.0f, LIN),
            kfPos(0.0833f, 0.0f, 0.0f, 0.25f, LIN),
            kfPos(0.2083f, 0.0f, 1.33f, 0.75f, LIN),
            kfPos(0.3750f, 0.0f, 1.83f, 0.75f, LIN),
            kfPos(0.7083f, 0.0f, 0.0f, 0.0f, LIN),
        ))
        .addAnimation("bone1", AnimationChannel(SCL,
            kfScl(0.0000f, 1.0, 1.0, 1.0, LIN),
            kfScl(0.0833f, 1.0964, 0.9964, 1.0607, LIN),
            kfScl(0.1667f, 1.25, 0.8, 1.1, LIN),
            kfScl(0.3333f, 1.3, 0.725, 1.15, LIN),
            kfScl(0.5833f, 1.05, 1.0, 1.05, LIN),
            kfScl(0.7083f, 1.0, 1.0, 1.0, LIN),
        ))
        .addAnimation("bone2", AnimationChannel(POS,
            kfPos(0.0000f, 0.0f, 0.0f, 0.0f, LIN),
            kfPos(0.2500f, 0.0f, -1.33f, 0.17f, LIN),
            kfPos(0.3750f, 0.0f, -0.87f, 0.25f, LIN),
            kfPos(0.4583f, 0.0f, 0.5f, 0.25f, LIN),
            kfPos(0.5833f, 0.0f, 0.75f, 0.0f, LIN),
            kfPos(0.7083f, 0.0f, 0.0f, 0.0f, LIN),
        ))
        .addAnimation("bone2", AnimationChannel(SCL,
            kfScl(0.0000f, 1.0, 1.0, 1.0, LIN),
            kfScl(0.1667f, 1.05, 1.0, 1.025, LIN),
            kfScl(0.2917f, 1.0833, 1.1833, 1.0667, LIN),
            kfScl(0.3750f, 1.1111, 1.0944, 1.0889, LIN),
            kfScl(0.4583f, 1.175, 0.975, 1.075, LIN),
            kfScl(0.5833f, 1.1751, 0.9249, 1.1, LIN),
            kfScl(0.7083f, 1.0, 1.0, 1.0, LIN),
        ))
        .addAnimation("bone3", AnimationChannel(POS,
            kfPos(0.0000f, 0.0f, 0.0f, 0.0f, LIN),
            kfPos(0.1667f, 0.0f, -0.75f, 0.05f, LIN),
            kfPos(0.2500f, 0.0f, -0.63f, 0.175f, LIN),
            kfPos(0.4167f, 0.0f, 0.79f, 0.5f, LIN),
            kfPos(0.4583f, 0.0f, 1.37f, 0.58f, LIN),
            kfPos(0.5833f, 0.0f, 1.79f, 0.75f, LIN),
            kfPos(0.7083f, 0.0f, 0.0f, 0.0f, LIN),
        ))
        .addAnimation("bone3", AnimationChannel(SCL,
            kfScl(0.0000f, 1.0, 1.0, 1.0, LIN),
            kfScl(0.1667f, 1.05, 0.9, 1.075, LIN),
            kfScl(0.2917f, 1.1, 1.0, 1.125, LIN),
            kfScl(0.3750f, 0.9667, 1.1, 0.9833, LIN),
            kfScl(0.4583f, 0.8417, 1.225, 0.8583, LIN),
            kfScl(0.5833f, 0.8167, 1.25, 0.8333, LIN),
            kfScl(0.7083f, 1.0, 1.0, 1.0, LIN),
        ))
        .addAnimation("arety1", AnimationChannel(ROT,
            kfRot(0.0000f, 0.0f, 0.0f, 0.0f, LIN),
            kfRot(0.1667f, 0.0f, 0.0f, -9.55f, LIN),
            kfRot(0.3333f, 0.0f, 0.0f, -15.39f, LIN),
            kfRot(0.4583f, 0.0f, 0.0f, 5.0f, LIN),
            kfRot(0.5833f, 0.0f, 0.0f, 6.5f, LIN),
            kfRot(0.7083f, 0.0f, 0.0f, 0.0f, LIN),
        ))
        .addAnimation("arety1", AnimationChannel(POS,
            kfPos(0.0000f, 0.0f, 0.0f, 0.0f, LIN),
            kfPos(0.1667f, -0.25f, -0.75f, 0.0f, LIN),
            kfPos(0.2500f, -0.25f, -0.64f, -0.5f, LIN),
            kfPos(0.3750f, 0.0f, -0.33f, -0.75f, LIN),
            kfPos(0.4583f, -0.5f, 0.5f, -0.81f, LIN),
            kfPos(0.5833f, -0.5f, 0.5f, -1.06f, LIN),
            kfPos(0.7083f, 0.0f, 0.0f, 0.0f, LIN),
        ))
        .addAnimation("artety2", AnimationChannel(ROT,
            kfRot(0.0000f, 0.0f, 0.0f, 0.0f, LIN),
            kfRot(0.1250f, 0.0f, 0.0f, 5.0f, LIN),
            kfRot(0.2083f, 0.0f, 0.0f, 1.61f, LIN),
            kfRot(0.4167f, 0.0f, 0.0f, -3.89f, LIN),
            kfRot(0.5833f, 0.0f, 0.0f, 0.34f, LIN),
            kfRot(0.7083f, 0.0f, 0.0f, 0.0f, LIN),
        ))
        .addAnimation("artety2", AnimationChannel(POS,
            kfPos(0.0000f, 0.0f, 0.0f, 0.0f, LIN),
            kfPos(0.1250f, 0.33f, -0.2f, 0.55f, LIN),
            kfPos(0.2083f, 0.8f, -0.25f, 0.91f, LIN),
            kfPos(0.3333f, 1.05f, -0.25f, 1.41f, LIN),
            kfPos(0.5833f, -0.08f, 0.68f, 0.49f, LIN),
            kfPos(0.7083f, 0.0f, 0.0f, 0.0f, LIN),
        ))
        .addAnimation("arthreery", AnimationChannel(ROT,
            kfRot(0.0000f, 0.0f, 0.0f, 0.0f, LIN),
            kfRot(0.0833f, 0.0f, 0.0f, 4.0f, LIN),
            kfRot(0.1667f, 0.0f, 0.0f, -14.19f, LIN),
            kfRot(0.2917f, 0.0f, 0.0f, -18.39f, LIN),
            kfRot(0.5000f, 0.0f, 0.0f, -19.66f, LIN),
            kfRot(0.7083f, 0.0f, 0.0f, 0.0f, LIN),
        ))
        .addAnimation("arthreery", AnimationChannel(POS,
            kfPos(0.0000f, 0.0f, 0.0f, 0.0f, LIN),
            kfPos(0.0833f, 0.17f, 0.0f, 0.25f, LIN),
            kfPos(0.1667f, 0.37f, -0.25f, 0.7f, LIN),
            kfPos(0.2917f, 0.75f, -0.25f, 0.54f, LIN),
            kfPos(0.5000f, 0.42f, 0.11f, 0.3f, LIN),
            kfPos(0.7083f, 0.0f, 0.0f, 0.0f, LIN),
        ))
        .build()

    val SUMMON: AnimationDefinition = AnimationDefinition.Builder.withLength(14f / 24f)
        .addAnimation("Heart2", AnimationChannel(ROT,
            kfRot(0.0000f, -22.5f, 0.0f, 245.0f, LIN),
            kfRot(0.3333f, 4.9366f, -0.9762f, 14.4366f, LIN),
            kfRot(0.4583f, 5.0f, 0.0f, 5.5f, LIN),
            kfRot(0.5833f, 0.0f, 0.0f, 0.0f, LIN),
        ))
        .addAnimation("Heart2", AnimationChannel(POS,
            kfPos(0.0000f, -2.0f, -3.0f, 0.0f, LIN),
            kfPos(0.1250f, 0.0f, 0.0f, -0.37f, LIN),
            kfPos(0.2083f, 2.0f, -1.0f, -0.75f, LIN),
            kfPos(0.3333f, 0.0f, -1.0f, -0.75f, LIN),
            kfPos(0.4583f, 0.0f, -0.6f, -0.45f, LIN),
            kfPos(0.5833f, 0.0f, 0.0f, 0.0f, LIN),
        ))
        .addAnimation("Heart2", AnimationChannel(SCL,
            kfScl(0.0000f, 0.2, 0.2, 0.2, LIN),
            kfScl(0.1667f, 0.75, 0.5955, 0.75, LIN),
            kfScl(0.3333f, 0.95, 1.05, 0.95, LIN),
            kfScl(0.5833f, 1.0, 1.0, 1.0, LIN),
        ))
        .addAnimation("bone3", AnimationChannel(POS,
            kfPos(0.2500f, 0.0f, 1.0f, 1.0f, LIN),
            kfPos(0.3750f, 0.0f, -0.5f, 0.0f, LIN),
            kfPos(0.5833f, 0.0f, 0.0f, 0.0f, LIN),
        ))
        .addAnimation("bone3", AnimationChannel(SCL,
            kfScl(0.2500f, 1.1, 0.9, 1.1, LIN),
            kfScl(0.3750f, 0.9, 1.1, 0.9, LIN),
            kfScl(0.5833f, 1.0, 1.0, 1.0, LIN),
        ))
        .addAnimation("arety1", AnimationChannel(ROT,
            kfRot(0.0000f, 0.0f, 0.0f, -55.0f, LIN),
            kfRot(0.2917f, 0.0f, 0.0f, -40.0f, LIN),
            kfRot(0.5000f, 0.0f, 0.0f, 0.0f, LIN),
        ))
        .addAnimation("arety1", AnimationChannel(POS,
            kfPos(0.0000f, 0.0f, -5.0f, 0.0f, LIN),
            kfPos(0.2917f, 0.0f, -2.0f, 0.0f, LIN),
            kfPos(0.5000f, 0.0f, 0.0f, 0.0f, LIN),
        ))
        .addAnimation("arety1", AnimationChannel(SCL,
            kfScl(0.0000f, 0.4, 0.7, 1.0, LIN),
            kfScl(0.5000f, 1.0, 1.0, 1.0, LIN),
        ))
        .addAnimation("artety2", AnimationChannel(ROT,
            kfRot(0.3333f, 0.0f, 0.0f, -22.5f, LIN),
            kfRot(0.5417f, 0.0f, 0.0f, 0.0f, LIN),
        ))
        .addAnimation("artety2", AnimationChannel(POS,
            kfPos(0.1250f, 0.0f, -4.0f, -0.75f, LIN),
            kfPos(0.3333f, 0.0f, -1.0f, -0.75f, LIN),
            kfPos(0.5417f, 0.0f, 0.0f, 0.0f, LIN),
        ))
        .addAnimation("artety2", AnimationChannel(SCL,
            kfScl(0.1250f, 0.7, 0.7, 0.7, LIN),
            kfScl(0.3333f, 1.0, 1.0, 1.0, LIN),
            kfScl(0.5417f, 1.0, 1.0, 1.0, LIN),
        ))
        .addAnimation("arthreery", AnimationChannel(ROT,
            kfRot(0.3333f, 0.0f, 0.0f, -45.0f, LIN),
            kfRot(0.5417f, 0.0f, 0.0f, 0.0f, LIN),
        ))
        .addAnimation("arthreery", AnimationChannel(POS,
            kfPos(0.0833f, -1.0f, -5.0f, 0.5f, LIN),
            kfPos(0.3333f, 0.0f, -2.0f, -0.25f, LIN),
            kfPos(0.5417f, 0.0f, 0.0f, 0.0f, LIN),
        ))
        .addAnimation("arthreery", AnimationChannel(SCL,
            kfScl(0.0833f, 0.6, 0.6, 0.6, LIN),
            kfScl(0.3333f, 1.0, 1.0, 1.0, LIN),
            kfScl(0.5417f, 1.0, 1.0, 1.0, LIN),
        ))
        .build()

    val BEAT_MILLIS: Long = (BEATING.lengthInSeconds() * 1000f).toLong()
    val SUMMON_MILLIS: Long = (SUMMON.lengthInSeconds() * 1000f).toLong()
}
