package org.shipwrights.enderkinesis.client.model

import net.minecraft.client.animation.AnimationChannel
import net.minecraft.client.animation.AnimationDefinition
import net.minecraft.client.animation.Keyframe
import net.minecraft.client.animation.KeyframeAnimations

/**
 * Sselith Moth animations. Keyframe values translated from the
 * Blockbench-exported `sselith_moth.animation.json` — two 5-second looping
 * cycles: [FLY] (in flight) and [IDLE] (landed). The model cross-fades them
 * by calling both with complementary scales (see [SselithMothModel.setupAnim]).
 *
 * Every keyframe uses CATMULLROM: the animation was authored with bezier
 * easing, which the vanilla-Java exporter can't represent and downgrades
 * to LINEAR — catmull-rom is vanilla's closest smooth-curve equivalent.
 */
internal object SselithMothAnimations {

    private val ROT = AnimationChannel.Targets.ROTATION
    private val POS = AnimationChannel.Targets.POSITION
    private val CATM = AnimationChannel.Interpolations.CATMULLROM

    private fun kfC(time: Float, x: Float, y: Float, z: Float) =
        Keyframe(time, KeyframeAnimations.degreeVec(x, y, z), CATM)

    private fun kfPosC(time: Float, x: Float, y: Float, z: Float) =
        Keyframe(time, KeyframeAnimations.posVec(x, y, z), CATM)

    val FLY: AnimationDefinition = AnimationDefinition.Builder.withLength(5.0f).looping()
        .addAnimation("right_wing_upper", AnimationChannel(ROT,
            kfC(0.0f,     0.0f, 0.0f, 0.0f),
            kfC(1.75f,   -5.0f, 0.0f, 24.8688f),
            kfC(3.5417f,  2.5f, 0.0f, -30.0f),
            kfC(5.0f,     0.0f, 0.0f, 0.0f),
        ))
        .addAnimation("left_wing_upper", AnimationChannel(ROT,
            kfC(0.0f,     0.0f, 0.0f, 0.0f),
            kfC(1.75f,   -5.0f, 0.0f, -24.8688f),
            kfC(3.5417f,  2.5f, 0.0f, 30.0f),
            kfC(5.0f,     0.0f, 0.0f, 0.0f),
        ))
        .addAnimation("leg_b_r", AnimationChannel(ROT,
            kfC(0.0f,  0.0f, 0.0f, -30.0f),
            kfC(2.5f, 50.0f, 0.0f, -30.0f),
            kfC(5.0f,  0.0f, 0.0f, -30.0f),
        ))
        .addAnimation("leg_b_l", AnimationChannel(ROT,
            kfC(0.0f,  0.0f, 0.0f, 30.0f),
            kfC(2.5f, 50.0f, 0.0f, 30.0f),
            kfC(5.0f,  0.0f, 0.0f, 30.0f),
        ))
        .addAnimation("leg_f_l", AnimationChannel(ROT,
            kfC(0.0f,  0.0f, 0.0f, 30.0f),
            kfC(2.5f, 30.0f, 0.0f, 30.0f),
            kfC(5.0f,  0.0f, 0.0f, 30.0f),
        ))
        .addAnimation("leg_f_r", AnimationChannel(ROT,
            kfC(0.0f,  0.0f, 0.0f, -30.0f),
            kfC(2.5f, 30.0f, 0.0f, -30.0f),
            kfC(5.0f,  0.0f, 0.0f, -30.0f),
        ))
        .addAnimation("head", AnimationChannel(ROT,
            kfC(0.0f, 10.0f, 0.0f, 0.0f),
            kfC(3.0f, 15.0f, 0.0f, 0.0f),
            kfC(5.0f, 10.0f, 0.0f, 0.0f),
        ))
        .addAnimation("body", AnimationChannel(ROT,
            kfC(0.0f,   0.0f, 0.0f, 0.0f),
            kfC(3.25f, -2.5f, 0.0f, 0.0f),
            kfC(5.0f,   0.0f, 0.0f, 0.0f),
        ))
        .addAnimation("left_wing_lower", AnimationChannel(ROT,
            kfC(0.0f,     0.0f, 0.0f, 0.0f),
            kfC(2.0f,    -5.0f, 0.0f, -24.8688f),
            kfC(3.5417f,  2.5f, 0.0f, 30.0f),
            kfC(5.0f,     0.0f, 0.0f, 0.0f),
        ))
        .addAnimation("left_wing_3", AnimationChannel(ROT,
            kfC(0.0f,     0.0f, 0.0f, 0.0f),
            kfC(1.4167f,  7.5f, 0.0f, 0.0f),
            kfC(4.0f,    -7.5f, 0.0f, 0.0f),
            kfC(5.0f,     0.0f, 0.0f, 0.0f),
        ))
        .addAnimation("antenna_r", AnimationChannel(ROT,
            kfC(0.0f,     0.0f, 0.0f, 0.0f),
            kfC(0.9583f, -7.7898f, 2.2703f, -6.4405f),
            kfC(2.0f,    -5.3878f, 4.0058f, -17.1986f),
            kfC(3.0833f, -10.4403f, -3.5283f, -14.8689f),
            kfC(3.9167f, -5.5782f, 7.0696f, -0.5017f),
            kfC(5.0f,     0.0f, 0.0f, 0.0f),
        ))
        .addAnimation("antenna_l", AnimationChannel(ROT,
            kfC(0.0f,     0.0f, 0.0f, 0.0f),
            kfC(0.9583f, -7.7898f, -2.2703f, 6.4405f),
            kfC(2.0f,    -5.3878f, -4.0058f, 17.1986f),
            kfC(3.0833f, -10.4403f, 3.5283f, 14.8689f),
            kfC(3.9167f, -5.5782f, -7.0696f, 0.5017f),
            kfC(5.0f,     0.0f, 0.0f, 0.0f),
        ))
        .addAnimation("right_wing_lower", AnimationChannel(ROT,
            kfC(0.0f,     0.0f, 0.0f, 0.0f),
            kfC(2.0f,    -5.0f, 0.0f, 24.8688f),
            kfC(3.5417f,  2.5f, 0.0f, -30.0f),
            kfC(5.0f,     0.0f, 0.0f, 0.0f),
        ))
        .addAnimation("right_wing_5", AnimationChannel(ROT,
            kfC(0.0f,     0.0f, 0.0f, 0.0f),
            kfC(1.4167f,  7.5f, 0.0f, 0.0f),
            kfC(4.0f,    -7.5f, 0.0f, 0.0f),
            kfC(5.0f,     0.0f, 0.0f, 0.0f),
        ))
        .build()

    val IDLE: AnimationDefinition = AnimationDefinition.Builder.withLength(5.0f).looping()
        .addAnimation("right_wing_upper", AnimationChannel(ROT,
            kfC(0.0f,     0.0f, 17.5f, 16.0f),
            kfC(1.75f,   -5.0f, 17.5f, 9.0f),
            kfC(3.5417f,  0.0f, 17.5f, 21.0f),
            kfC(5.0f,     0.0f, 17.5f, 16.0f),
        ))
        .addAnimation("left_wing_upper", AnimationChannel(ROT,
            kfC(0.0f,     0.0f, -17.5f, -16.0f),
            kfC(1.75f,   -5.0f, -17.5f, -9.0f),
            kfC(3.5417f,  0.0f, -17.5f, -21.0f),
            kfC(5.0f,     0.0f, -17.5f, -16.0f),
        ))
        .addAnimation("leg_b_r", AnimationChannel(ROT,
            kfC(0.0f,  0.0f, 0.0f, 22.5f),
            kfC(2.0f, 12.5209f, -1.0563f, 20.234f),
            kfC(5.0f,  0.0f, 0.0f, 22.5f),
        ))
        .addAnimation("leg_b_l", AnimationChannel(ROT,
            kfC(0.0f,  0.0f, 0.0f, -22.5f),
            kfC(2.0f, 12.5209f, 1.0563f, -20.234f),
            kfC(5.0f,  0.0f, 0.0f, -22.5f),
        ))
        .addAnimation("leg_f_l", AnimationChannel(ROT,
            kfC(0.0f, -41.6066f, 7.9936f, 12.7342f),
            kfC(2.0f, -46.9119f, 3.9343f, 9.7983f),
            kfC(5.0f, -41.6066f, 7.9936f, 12.7342f),
        ))
        .addAnimation("leg_f_r", AnimationChannel(ROT,
            kfC(0.0f, -41.6066f, 7.9936f, -12.7342f),
            kfC(2.0f, -46.9119f, -3.9343f, -9.7983f),
            kfC(5.0f, -41.6066f, 7.9936f, -12.7342f),
        ))
        .addAnimation("head", AnimationChannel(ROT,
            kfC(0.0f, 10.0f, 0.0f, 0.0f),
            kfC(3.0f,  0.0f, 0.0f, 0.0f),
            kfC(5.0f, 10.0f, 0.0f, 0.0f),
        ))
        .addAnimation("body", AnimationChannel(ROT,
            kfC(0.0f, -5.0f, 0.0f, 0.0f),
            kfC(2.0f, -2.5f, 0.0f, 0.0f),
            kfC(5.0f, -5.0f, 0.0f, 0.0f),
        ))
        .addAnimation("body", AnimationChannel(POS,
            kfPosC(0.0f, 0.0f, 1.0f, -1.0f),
        ))
        .addAnimation("left_wing_lower", AnimationChannel(ROT,
            kfC(0.0417f,  0.0f, 0.0f, -15.0f),
            kfC(2.0f,    -5.0f, 0.0f, 0.0f),
            kfC(3.5417f,  2.5f, 0.0f, -10.0f),
            kfC(5.0f,     0.0f, 0.0f, -15.0f),
        ))
        .addAnimation("left_wing_3", AnimationChannel(ROT,
            kfC(0.0f,     7.5f, 0.0f, 0.0f),
            kfC(2.0f,     7.5f, 10.0f, 0.0f),
            kfC(3.5417f,  5.0f, -2.5f, 0.0f),
            kfC(5.0f,     7.5f, 0.0f, 0.0f),
        ))
        .addAnimation("left_wing_3", AnimationChannel(POS,
            kfPosC(0.0f,    0.0f, 0.0f, 0.0f),
            kfPosC(2.0f,    0.0f, 0.0f, 0.0f),
            kfPosC(3.5417f, 0.0f, 0.0f, -0.5f),
            kfPosC(5.0f,    0.0f, 0.0f, 0.0f),
        ))
        .addAnimation("antenna_r", AnimationChannel(ROT,
            kfC(0.0f,     0.0f, 0.0f, 0.0f),
            kfC(0.9583f, -7.7898f, 2.2703f, -6.4405f),
            kfC(2.0f,    -5.3878f, 4.0058f, -17.1986f),
            kfC(3.0833f, -10.4403f, -3.5283f, -14.8689f),
            kfC(3.9167f, -5.5782f, 7.0696f, -0.5017f),
            kfC(5.0f,     0.0f, 0.0f, 0.0f),
        ))
        .addAnimation("antenna_l", AnimationChannel(ROT,
            kfC(0.0f,     0.0f, 0.0f, 0.0f),
            kfC(0.9583f, -7.7898f, -2.2703f, 6.4405f),
            kfC(2.0f,    -5.3878f, -4.0058f, 17.1986f),
            kfC(3.0833f, -10.4403f, 3.5283f, 14.8689f),
            kfC(3.9167f, -5.5782f, -7.0696f, 0.5017f),
            kfC(5.0f,     0.0f, 0.0f, 0.0f),
        ))
        .addAnimation("right_wing_lower", AnimationChannel(ROT,
            kfC(0.0417f,  0.0f, 0.0f, 15.0f),
            kfC(2.0f,    -5.0f, 0.0f, 0.0f),
            kfC(3.5417f,  2.5f, 0.0f, 10.0f),
            kfC(5.0f,     0.0f, 0.0f, 15.0f),
        ))
        .addAnimation("right_wing_5", AnimationChannel(ROT,
            kfC(0.0f,     7.5f, 0.0f, 0.0f),
            kfC(2.0f,     7.5f, -10.0f, 0.0f),
            kfC(3.5417f,  5.0f, 2.5f, 0.0f),
            kfC(5.0f,     7.5f, 0.0f, 0.0f),
        ))
        .addAnimation("right_wing_5", AnimationChannel(POS,
            kfPosC(0.0f,    0.0f, 0.0f, 0.0f),
            kfPosC(2.0f,    0.0f, 0.0f, 0.0f),
            kfPosC(3.5417f, 0.0f, 0.0f, -0.5f),
            kfPosC(5.0f,    0.0f, 0.0f, 0.0f),
        ))
        .build()
}
