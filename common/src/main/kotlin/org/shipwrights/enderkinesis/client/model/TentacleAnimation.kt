package org.shipwrights.enderkinesis.client.model

import net.minecraft.client.animation.AnimationChannel
import net.minecraft.client.animation.AnimationDefinition
import net.minecraft.client.animation.Keyframe
import net.minecraft.client.animation.KeyframeAnimations

/**
 * The tentacle's `idle_wave` loop, transcribed from the Blockbench export (linear interpolation,
 * 2.75 s loop). Only the per-segment ROTATION channels carry motion — the exported POSITION channels
 * and the `tent_base` rotation are all-zero no-ops, so they're omitted. Each segment's sway lags the
 * one beneath it, which reads as a whip travelling up the column.
 */
internal object TentacleAnimation {

    /** Loop length in milliseconds (`KeyframeAnimations.animate` takes accumulated ms). */
    const val LENGTH_MS: Long = 2750L

    private val ROT = AnimationChannel.Targets.ROTATION
    private val LERP = AnimationChannel.Interpolations.LINEAR

    private fun kf(time: Float, x: Float, y: Float, z: Float) =
        Keyframe(time, KeyframeAnimations.degreeVec(x, y, z), LERP)

    val IDLE_WAVE: AnimationDefinition = AnimationDefinition.Builder.withLength(2.75f).looping()
        .addAnimation("tend_1", AnimationChannel(ROT,
            kf(0.0f, 0.0f, 0.0f, 0.0f),
            kf(0.25f, 3.0f, 0.0f, 10.0f),
            kf(1.0417f, 11.8457f, -3.5219f, -30.4858f),
            kf(1.7917f, -26.6021f, 6.4762f, 1.6123f),
            kf(2.4167f, -4.0f, 0.0f, -7.5f),
            kf(2.75f, 0.0f, 0.0f, 0.0f),
        ))
        .addAnimation("tent_2", AnimationChannel(ROT,
            kf(0.0f, 4.7563f, -1.0398f, -1.1831f),
            kf(0.3333f, 0.0f, 0.0f, 15.0f),
            kf(1.125f, 12.3885f, -7.0708f, -22.9757f),
            kf(1.4583f, -1.6991f, -9.1017f, -6.6042f),
            kf(1.75f, -15.5911f, -10.4038f, -14.8719f),
            kf(2.4167f, 8.8115f, -4.7382f, -12.6246f),
            kf(2.75f, 4.7563f, -1.0398f, -1.1831f),
        ))
        .addAnimation("tent_3", AnimationChannel(ROT,
            kf(0.0f, 7.1429f, -1.5219f, -1.8062f),
            kf(0.3333f, 4.0f, 0.0f, 10.0f),
            kf(0.5f, -0.8172f, 11.7795f, 19.9602f),
            kf(1.2083f, 7.2868f, -8.179f, -20.7857f),
            kf(1.5f, -4.5372f, -12.7658f, -4.4448f),
            kf(1.75f, -22.4868f, -19.5047f, -3.0881f),
            kf(2.0f, -34.5623f, -12.8438f, 2.0815f),
            kf(2.4167f, 10.3368f, -5.2017f, -10.7311f),
            kf(2.75f, 7.1429f, -1.5219f, -1.8062f),
        ))
        .addAnimation("tent_4", AnimationChannel(ROT,
            kf(0.0f, 11.9332f, -2.4061f, -3.1104f),
            kf(0.375f, -5.0f, 0.0f, 10.0f),
            kf(0.5833f, -5.7907f, 6.1681f, 14.8147f),
            kf(1.125f, 10.5318f, -10.2099f, -22.221f),
            kf(1.3333f, 7.0525f, -17.7819f, -27.1538f),
            kf(1.5417f, 6.4358f, -25.7625f, 9.3821f),
            kf(1.7083f, -41.3101f, -33.0273f, 21.8008f),
            kf(1.875f, -61.0486f, -14.4489f, 44.7038f),
            kf(2.125f, -51.1916f, 14.9639f, 42.6297f),
            kf(2.4167f, 17.2531f, -10.4267f, -6.4978f),
            kf(2.75f, 11.9332f, -2.4061f, -3.1104f),
        ))
        .addAnimation("tent_5", AnimationChannel(ROT,
            kf(0.0f, 14.3368f, -2.8065f, -3.7894f),
            kf(0.2083f, 1.0f, 0.0f, 37.5f),
            kf(0.4167f, 10.0739f, 8.3308f, 44.0538f),
            kf(1.0417f, 4.275f, -22.1802f, -22.2446f),
            kf(1.25f, 8.2574f, -22.2549f, -36.9809f),
            kf(1.7917f, -19.1299f, 0.5801f, -1.0599f),
            kf(2.1667f, -24.054f, 0.7622f, 2.9065f),
            kf(2.4167f, 29.3157f, -2.8494f, 2.1395f),
            kf(2.75f, 14.3368f, -2.8065f, -3.7894f),
        ))
        .build()
}
