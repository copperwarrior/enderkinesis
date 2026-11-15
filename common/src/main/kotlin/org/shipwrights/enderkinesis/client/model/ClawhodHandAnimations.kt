package org.shipwrights.enderkinesis.client.model

import net.minecraft.client.animation.AnimationChannel
import net.minecraft.client.animation.AnimationDefinition
import net.minecraft.client.animation.Keyframe
import net.minecraft.client.animation.KeyframeAnimations

/**
 * The Clawhod hand clips, from the user's Blockbench files (clawhod_left/right.bbmodel). The left file mirrors
 * the right one on the thumb and on the fist palm only, so one table serves both hands ([left] mirrors those).
 * Bezier keys of the palm are sampled into extra catmull-rom keys, as vanilla has no bezier.
 */
internal object ClawhodHandAnimations {

    private val ROT = AnimationChannel.Targets.ROTATION
    private val POS = AnimationChannel.Targets.POSITION
    private val CAT = AnimationChannel.Interpolations.CATMULLROM

    // The export mirrors X and Y of the Blockbench space (see ClawhodHandModel), so a Blockbench rotation
    // turns into (-x, -y, z) and a position into (-x, y, z); posVec negates Y itself.
    private fun r(t: Float, x: Float, y: Float, z: Float) = Keyframe(t, modelRotation(x, y, z).let { (mx, my, mz) -> KeyframeAnimations.degreeVec(mx, my, mz) }, CAT)

    private fun p(t: Float, x: Float, y: Float, z: Float) = Keyframe(t, KeyframeAnimations.posVec(-x, y, z), CAT)

    private fun rm(left: Boolean, t: Float, x: Float, y: Float, z: Float) =
        if (left) r(t, x, -y, -z) else r(t, x, y, z)

    private fun pm(left: Boolean, t: Float, x: Float, y: Float, z: Float) =
        if (left) p(t, -x, y, z) else p(t, x, y, z)

    /** Not moving, or flying empty. */
    val IDLE_LEFT = idle(true)
    val IDLE_RIGHT = idle(false)

    /** Breaking a block. */
    val FIST_LEFT = fist(true)
    val FIST_RIGHT = fist(false)

    /** Holding items. */
    val HOLDING_LEFT = holding(true)
    val HOLDING_RIGHT = holding(false)

    /** Placing a block, once. */
    val PLACE_LEFT = place(true)
    val PLACE_RIGHT = place(false)

    private fun idle(left: Boolean): AnimationDefinition = AnimationDefinition.Builder.withLength(5.3333f).looping()
        .addAnimation("palm", AnimationChannel(ROT,
            r(0f, -17.5f, 0f, 0f), r(1.625f, -12.5f, 0f, 0f), r(2.5833f, -12.5f, 0f, 0f),
            r(4.4167f, -17.5f, 0f, 0f), r(5.3333f, -17.5f, 0f, 0f),
        ))
        .addAnimation("palm", AnimationChannel(POS,
            p(0f, 0f, 2f, 0f), p(1.625f, 0f, 1f, 0f), p(2.5833f, 0f, 1f, 0f),
            p(4.4167f, 0f, 2f, 0f), p(5.3333f, 0f, 2f, 0f),
        ))
        .addAnimation("finger", AnimationChannel(ROT,
            r(0f, -7.5f, -15f, 0f), r(1.625f, 1.817f, -19.9207f, -5.3191f), r(2.5833f, 1.817f, -19.9207f, -5.3191f),
            r(4.4167f, -7.5f, -15f, 0f), r(5.3333f, -7.5f, -15f, 0f),
        ))
        .addAnimation("finger", AnimationChannel(POS,
            p(0f, -1f, 0f, 0.5f), p(4.4167f, -1f, 0f, 0.5f), p(5.3333f, -1f, 0f, 0.5f),
        ))
        .addAnimation("finger2", AnimationChannel(ROT,
            r(0f, -5f, 0f, 0f), r(1.625f, 2.5f, 0f, 0f), r(2.5833f, 2.5f, 0f, 0f),
            r(4.4167f, -5f, 0f, 0f), r(5.3333f, -5f, 0f, 0f),
        ))
        .addAnimation("finger3", AnimationChannel(ROT,
            r(0f, -5f, 15f, 0f), r(1.625f, 4.3721f, 19.6922f, 5.5379f), r(2.5833f, 4.3721f, 19.6922f, 5.5379f),
            r(4.4167f, -5f, 15f, 0f), r(5.3333f, -5f, 15f, 0f),
        ))
        .addAnimation("finger3", AnimationChannel(POS,
            p(0f, 1f, 0f, 0.25f), p(4.4167f, 1f, 0f, 0.25f), p(5.3333f, 1f, 0f, 0.25f),
        ))
        .addAnimation("finger4", AnimationChannel(ROT,
            rm(left, 0f, -61.4124f, 29.0057f, 27.2088f), rm(left, 1.625f, -100.9438f, 0.9376f, 20.5982f), rm(left, 2.5833f, -100.9438f, 0.9376f, 20.5982f),
            rm(left, 4.4167f, -61.4124f, 29.0057f, 27.2088f), rm(left, 5.3333f, -61.4124f, 29.0057f, 27.2088f),
        ))
        .addAnimation("finger4", AnimationChannel(POS,
            pm(left, 0f, 0f, 0f, -0.25f), pm(left, 1.625f, -0.75f, 0.75f, -1.25f), pm(left, 2.5833f, -0.75f, 0.75f, -1.25f),
            pm(left, 4.4167f, 0f, 0f, -0.25f), pm(left, 5.3333f, 0f, 0f, -0.25f),
        ))
        .build()

    private fun fist(left: Boolean): AnimationDefinition = AnimationDefinition.Builder.withLength(5.3333f).looping()
        .addAnimation("palm", AnimationChannel(ROT,
            rm(left, 0f, 4.4966f, -10.9172f, 75.1224f), rm(left, 1.625f, 15.2536f, -4.9893f, 67.8348f), rm(left, 2.875f, 7.2414f, -15.8421f, 69.7605f),
            rm(left, 4.125f, 15.2536f, -4.9893f, 70.8348f), rm(left, 5.3333f, 4.4966f, -10.9172f, 75.1224f),
        ))
        .addAnimation("palm", AnimationChannel(POS,
            pm(left, 0f, -0.5f, 2.5f, 0f), pm(left, 1.0417f, -0.5f, 2.5f, 0f), pm(left, 2.5833f, -0.5f, 2.75f, 0f),
            pm(left, 3.9167f, -0.5f, 2.5f, 0f), pm(left, 5.3333f, -0.5f, 2.5f, 0f),
        ))
        .addAnimation("finger", AnimationChannel(ROT,
            r(0f, -164.9933f, -12.4508f, -0.3278f), r(1.9167f, -164.9933f, -12.4508f, -0.3278f), r(2.25f, -169.9933f, -12.4508f, -0.3278f),
            r(4.2917f, -169.9933f, -12.4508f, -0.3278f), r(4.5417f, -164.9933f, -12.4508f, -0.3278f), r(5.3333f, -164.9933f, -12.4508f, -0.3278f),
        ))
        .addAnimation("finger", AnimationChannel(POS,
            p(0f, -1f, -1f, -0.5f), p(1.9167f, -1f, -1f, -0.5f), p(4.5417f, -1f, -1f, -0.5f),
            p(5.3333f, -1f, -1f, -0.5f),
        ))
        .addAnimation("finger2", AnimationChannel(ROT,
            r(0f, -162.5f, 0f, 0f), r(1.8333f, -162.5f, 0f, 0f), r(2.1667f, -167.5f, 0f, 0f),
            r(4.2083f, -167.5f, 0f, 0f), r(4.4583f, -162.5f, 0f, 0f), r(5.3333f, -162.5f, 0f, 0f),
        ))
        .addAnimation("finger2", AnimationChannel(POS,
            p(0f, 0f, -1.25f, -0.75f), p(1.8333f, 0f, -1.25f, -0.75f), p(4.4583f, 0f, -1.25f, -0.75f),
            p(5.3333f, 0f, -1.25f, -0.75f),
        ))
        .addAnimation("finger3", AnimationChannel(ROT,
            r(0f, -159.7596f, 11.3201f, 3.2061f), r(1.5f, -159.7596f, 11.3201f, 3.2061f), r(1.7083f, -164.7516f, 11.3201f, 3.2061f),
            r(3.75f, -164.7516f, 11.3201f, 3.2061f), r(4.125f, -159.7596f, 11.3201f, 3.2061f), r(5.3333f, -159.7596f, 11.3201f, 3.2061f),
        ))
        .addAnimation("finger3", AnimationChannel(POS,
            p(0f, 1f, -1f, -0.75f), p(5.3333f, 1f, -1f, -0.75f),
        ))
        .addAnimation("finger4", AnimationChannel(ROT,
            rm(left, 0f, -90.5698f, 14.0855f, -74.585f), rm(left, 1.625f, -90.5698f, 14.0855f, -74.585f), rm(left, 1.875f, -91.8201f, 13.9812f, -79.7378f),
            rm(left, 3.9167f, -91.8201f, 13.9812f, -79.7378f), rm(left, 4.25f, -90.5698f, 14.0855f, -74.585f), rm(left, 5.3333f, -90.5698f, 14.0855f, -74.585f),
        ))
        .addAnimation("finger4", AnimationChannel(POS,
            pm(left, 0f, 0.5f, 0f, -1f), pm(left, 1.7917f, 0.5f, 0f, -1f), pm(left, 2f, 0.25f, 0f, -1f),
            pm(left, 4.0417f, 0.25f, 0f, -1f), pm(left, 4.4167f, 0.5f, 0f, -1f), pm(left, 5.3333f, 0.5f, 0f, -1f),
        ))
        .build()

    private fun holding(left: Boolean): AnimationDefinition = AnimationDefinition.Builder.withLength(2.125f).looping()
        .addAnimation("palm", AnimationChannel(ROT,
            r(0f, 30f, 0f, 0f), r(0.0774f, 30.5917f, 0f, 0f), r(0.1548f, 31.7137f, 0f, 0f),
            r(0.2321f, 32.9823f, 0f, 0f), r(0.3095f, 34.2554f, 0f, 0f), r(0.3869f, 35.4603f, 0f, 0f),
            r(0.4643f, 36.5523f, 0f, 0f), r(0.5417f, 37.5f, 0f, 0f), r(0.6607f, 38.633f, 0f, 0f),
            r(0.7798f, 39.4123f, 0f, 0f), r(0.8988f, 39.8646f, 0f, 0f), r(1.0179f, 39.9775f, 0f, 0f),
            r(1.1369f, 39.703f, 0f, 0f), r(1.256f, 38.9429f, 0f, 0f), r(1.375f, 37.5f, 0f, 0f),
            r(1.4286f, 36.5821f, 0f, 0f), r(1.4821f, 35.5141f, 0f, 0f), r(1.5357f, 34.2557f, 0f, 0f),
            r(1.5893f, 32.7885f, 0f, 0f), r(1.6429f, 31.2827f, 0f, 0f), r(1.6964f, 30.2788f, 0f, 0f),
            r(1.75f, 30f, 0f, 0f), r(1.8036f, 30f, 0f, 0f), r(1.8571f, 30f, 0f, 0f),
            r(1.9107f, 30f, 0f, 0f), r(1.9643f, 30f, 0f, 0f), r(2.0179f, 30f, 0f, 0f),
            r(2.0714f, 30f, 0f, 0f), r(2.125f, 30f, 0f, 0f),
        ))
        .addAnimation("palm", AnimationChannel(POS,
            p(0.0833f, 0f, 5f, 0f), p(0.5833f, 0f, 5.5f, 0f), p(1.375f, 0f, 5.5f, 0f),
            p(2.125f, 0f, 5f, 0f),
        ))
        .addAnimation("finger", AnimationChannel(ROT,
            r(0f, -72.5f, -15f, 0f), r(2f, -72.5f, -15f, 0f),
        ))
        .addAnimation("finger", AnimationChannel(POS,
            p(0f, -1f, 0f, 0.5f), p(2f, -1f, 0f, 0.5f),
        ))
        .addAnimation("finger2", AnimationChannel(ROT,
            r(0f, -72.5f, 0f, 0f),
        ))
        .addAnimation("finger3", AnimationChannel(ROT,
            r(0f, -75f, 15f, 0f), r(2f, -75f, 15f, 0f),
        ))
        .addAnimation("finger3", AnimationChannel(POS,
            p(0f, 1f, 0f, 0.25f), p(2f, 1f, 0f, 0.25f),
        ))
        .addAnimation("finger4", AnimationChannel(ROT,
            rm(left, 0f, -70.5859f, 19.7827f, -3.9752f),
        ))
        .addAnimation("finger4", AnimationChannel(POS,
            pm(left, 0f, 0f, 0f, -0.75f),
        ))
        .build()

    private fun place(left: Boolean): AnimationDefinition = AnimationDefinition.Builder.withLength(1.375f)
        .addAnimation("palm", AnimationChannel(ROT,
            r(0f, 30f, 0f, 0f), r(0.0595f, 32.0083f, 0f, 0f), r(0.119f, 37.0786f, 0f, 0f),
            r(0.1786f, 44.325f, 0f, 0f), r(0.2381f, 52.2794f, 0f, 0f), r(0.2976f, 59.2309f, 0f, 0f),
            r(0.3571f, 63.7478f, 0f, 0f), r(0.4167f, 65f, 0f, 0f), r(0.4583f, 63.3405f, 0f, 0f),
            r(0.5f, 57.7649f, 0f, 0f), r(0.5417f, 45.7879f, 0f, 0f), r(0.5833f, 31.6464f, 0f, 0f),
            r(0.625f, 23.3993f, 0f, 0f), r(0.6667f, 19.8508f, 0f, 0f), r(0.7083f, 19f, 0f, 0f),
            r(0.7679f, 19.8718f, 0f, 0f), r(0.8274f, 22.0692f, 0f, 0f), r(0.8869f, 25.0565f, 0f, 0f),
            r(0.9464f, 27.6877f, 0f, 0f), r(1.006f, 28.4017f, 0f, 0f), r(1.0655f, 25.9603f, 0f, 0f),
            r(1.125f, 20f, 0f, 0f), r(1.1607f, 15.1255f, 0f, 0f), r(1.1964f, 9.6053f, 0f, 0f),
            r(1.2321f, 3.3527f, 0f, 0f), r(1.2679f, -3.5855f, 0f, 0f), r(1.3036f, -10.6051f, 0f, 0f),
            r(1.3393f, -15.8714f, 0f, 0f), r(1.375f, -17.5f, 0f, 0f),
        ))
        .addAnimation("palm", AnimationChannel(POS,
            p(0f, 0f, 5f, 0f), p(0.4167f, 0f, 5.5f, -0.5f), p(0.7083f, 0f, 3.5f, -4.5f),
            p(1.125f, 0f, 5.5f, -0.5f), p(1.3333f, 0f, 2f, 0f),
        ))
        .addAnimation("finger", AnimationChannel(ROT,
            r(0f, -72.5f, -15f, 0f), r(0.3333f, -80f, -15f, 0f), r(0.7083f, -37.1783f, -15f, 0f),
            r(1.3333f, -7.5f, -15f, 0f),
        ))
        .addAnimation("finger", AnimationChannel(POS,
            p(0f, -1f, 0f, 0.5f), p(0.3333f, -1f, 0f, 0.5f), p(1.3333f, -1f, 0f, 0.5f),
        ))
        .addAnimation("finger2", AnimationChannel(ROT,
            r(0f, -72.5f, 0f, 0f), r(0.3333f, -82.5f, 0f, 0f), r(0.7083f, -35f, 0f, 0f),
            r(1.3333f, -5f, 0f, 0f),
        ))
        .addAnimation("finger3", AnimationChannel(ROT,
            r(0f, -75f, 15f, 0f), r(0.3333f, -82.5f, 15f, 0f), r(0.7083f, -41.8932f, 15f, 0f),
            r(1.3333f, -5f, 15f, 0f),
        ))
        .addAnimation("finger3", AnimationChannel(POS,
            p(0f, 1f, 0f, 0.25f), p(0.3333f, 1f, 0f, 0.25f), p(1.3333f, 1f, 0f, 0.25f),
        ))
        .addAnimation("finger4", AnimationChannel(ROT,
            rm(left, 0f, -70.5859f, 19.7827f, -3.9752f), rm(left, 0.3333f, -70.5859f, 19.7827f, -3.9752f), rm(left, 0.7083f, -63.1176f, 4.395f, 31.1665f),
            rm(left, 1.3333f, -61.4124f, 29.0057f, 27.2088f),
        ))
        .addAnimation("finger4", AnimationChannel(POS,
            pm(left, 0f, 0f, 0f, -0.75f), pm(left, 0.3333f, 0f, 0f, -0.75f), pm(left, 1.3333f, 0f, 0f, -0.25f),
        ))
        .build()
}
