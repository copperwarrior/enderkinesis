package org.shipwrights.enderkinesis.client.model

import net.minecraft.client.animation.AnimationChannel
import net.minecraft.client.animation.AnimationDefinition
import net.minecraft.client.animation.Keyframe
import net.minecraft.client.animation.KeyframeAnimations

/**
 * Cataloger animations exported from Blockbench (Mojang 1.19+ keyframe format).
 *
 *  - [IDLE]: 5-second loop. Subtle head/body sway and a slight asymmetry between
 *    arms; played while the cataloger is stationary.
 *  - [WALKING]: 4-second loop. Full leg cycle, counter-swung arms, head bob, and
 *    a robe sway; played while the cataloger is moving.
 *
 * Both run as time-driven [net.minecraft.world.entity.AnimationState]s on the entity
 * — Cataloger toggles which one is active per tick based on horizontal displacement.
 * Time-driven (not limb-swing-driven) so the artist-authored 4-second cadence is
 * preserved regardless of the cataloger's actual movement speed.
 */
internal object CatalogerAnimations {

    private val ROT = AnimationChannel.Targets.ROTATION
    private val POS = AnimationChannel.Targets.POSITION
    private val LERP = AnimationChannel.Interpolations.CATMULLROM

    private fun kfRot(time: Float, x: Float, y: Float, z: Float) =
        Keyframe(time, KeyframeAnimations.degreeVec(x, y, z), LERP)

    private fun kfPos(time: Float, x: Float, y: Float, z: Float) =
        Keyframe(time, KeyframeAnimations.posVec(x, y, z), LERP)

    val IDLE: AnimationDefinition = AnimationDefinition.Builder.withLength(5.0f).looping()
        .addAnimation("head", AnimationChannel(ROT,
            kfRot(0.0f,  0.0f, 0.0f, 0.0f),
            kfRot(2.25f, -2.5f, 0.0f, 0.0f),
            kfRot(5.0f,  0.0f, 0.0f, 0.0f),
        ))
        .addAnimation("body", AnimationChannel(ROT,
            kfRot(0.0f,  0.0f, 0.0f, 0.0f),
            kfRot(2.25f, -2.5f, 0.0f, 0.0f),
            kfRot(5.0f,  0.0f, 0.0f, 0.0f),
        ))
        .addAnimation("body", AnimationChannel(POS,
            kfPos(0.0f,  0.0f, 0.0f, 0.0f),
            kfPos(2.25f, 0.0f, 0.0f, 0.25f),
            kfPos(5.0f,  0.0f, 0.0f, 0.0f),
        ))
        .addAnimation("left_arm", AnimationChannel(ROT,
            kfRot(0.0f, 0.0f, 0.0f, -5.0f),
            kfRot(5.0f, 0.0f, 0.0f, -5.0f),
        ))
        .addAnimation("left_arm", AnimationChannel(POS,
            kfPos(0.0f,  0.0f, 0.0f,  0.0f),
            kfPos(2.25f, 0.0f, 0.25f, 0.25f),
            kfPos(5.0f,  0.0f, 0.0f,  0.0f),
        ))
        .addAnimation("right_arm", AnimationChannel(ROT,
            kfRot(0.0f, 0.0f, 0.0f, 7.5f),
            kfRot(5.0f, 0.0f, 0.0f, 7.5f),
        ))
        .addAnimation("right_arm", AnimationChannel(POS,
            kfPos(0.0f,  0.0f, 0.0f,  0.0f),
            kfPos(2.25f, 0.0f, 0.25f, 0.25f),
            kfPos(5.0f,  0.0f, 0.0f,  0.0f),
        ))
        .build()

    val WALKING: AnimationDefinition = AnimationDefinition.Builder.withLength(4.0f).looping()
        .addAnimation("head", AnimationChannel(ROT,
            kfRot(0.0f, 2.5f, 0.0f, 0.0f),
            kfRot(1.0f, 0.0f, 0.0f, 0.0f),
            kfRot(2.0f, 2.5f, 0.0f, 0.0f),
            kfRot(3.0f, 0.0f, 0.0f, 0.0f),
            kfRot(4.0f, 2.5f, 0.0f, 0.0f),
        ))
        .addAnimation("head", AnimationChannel(POS,
            kfPos(0.0f, 0.0f, -0.25f, -0.5f),
            kfPos(1.0f, 0.0f, -0.25f,  0.0f),
            kfPos(2.0f, 0.0f, -0.25f, -0.5f),
            kfPos(3.0f, 0.0f, -0.25f,  0.0f),
            kfPos(4.0f, 0.0f, -0.25f, -0.5f),
        ))
        .addAnimation("body", AnimationChannel(ROT,
            kfRot(0.0f, 2.5f, 0.0f, 0.0f),
            kfRot(1.0f, 0.0f, 0.0f, 0.0f),
            kfRot(2.0f, 2.5f, 0.0f, 0.0f),
            kfRot(3.0f, 0.0f, 0.0f, 0.0f),
            kfRot(4.0f, 2.5f, 0.0f, 0.0f),
        ))
        .addAnimation("body", AnimationChannel(POS,
            kfPos(0.0f, 0.0f, -0.25f, -0.5f),
            kfPos(1.0f, 0.0f,  0.0f,   0.0f),
            kfPos(2.0f, 0.0f, -0.25f, -0.5f),
            kfPos(3.0f, 0.0f,  0.0f,   0.0f),
            kfPos(4.0f, 0.0f, -0.25f, -0.5f),
        ))
        .addAnimation("left_arm", AnimationChannel(ROT,
            kfRot(0.0f, -7.5f, 0.0f, 0.0f),
            kfRot(1.0f,  0.0f, 0.0f, 0.0f),
            kfRot(2.0f, 10.0f, 0.0f, 0.0f),
            kfRot(3.0f,  0.0f, 0.0f, 0.0f),
            kfRot(4.0f, -7.5f, 0.0f, 0.0f),
        ))
        .addAnimation("left_arm", AnimationChannel(POS,
            kfPos(0.0f, 0.0f, -0.25f, -0.5f),
            kfPos(1.0f, 0.0f,  0.0f,   0.0f),
            kfPos(2.0f, 0.0f, -0.25f, -0.5f),
            kfPos(3.0f, 0.0f,  0.0f,   0.0f),
            kfPos(4.0f, 0.0f, -0.25f, -0.5f),
        ))
        .addAnimation("right_arm", AnimationChannel(ROT,
            kfRot(0.0f, 10.0f, 0.0f, 0.0f),
            kfRot(1.0f,  0.0f, 0.0f, 0.0f),
            kfRot(2.0f, -7.5f, 0.0f, 0.0f),
            kfRot(3.0f,  0.0f, 0.0f, 0.0f),
            kfRot(4.0f, 10.0f, 0.0f, 0.0f),
        ))
        .addAnimation("right_arm", AnimationChannel(POS,
            kfPos(0.0f, 0.0f, -0.25f, -0.5f),
            kfPos(1.0f, 0.0f,  0.0f,   0.0f),
            kfPos(2.0f, 0.0f, -0.25f, -0.5f),
            kfPos(3.0f, 0.0f,  0.0f,   0.0f),
            kfPos(4.0f, 0.0f, -0.25f, -0.5f),
        ))
        .addAnimation("left_leg", AnimationChannel(ROT,
            kfRot(0.0f, -12.5f, 0.0f, 0.0f),
            kfRot(2.0f,  12.5f, 0.0f, 0.0f),
            kfRot(4.0f, -12.5f, 0.0f, 0.0f),
        ))
        .addAnimation("left_leg", AnimationChannel(POS,
            kfPos(0.0f,    0.0f, 0.29f, -0.29f),
            kfPos(0.5833f, 0.0f, 0.0f,   0.0f),
            kfPos(2.5f,    0.0f, 0.0f,   0.0f),
            kfPos(3.5417f, 0.0f, 0.5f,  -0.5f),
            kfPos(4.0f,    0.0f, 0.29f, -0.29f),
        ))
        .addAnimation("right_leg", AnimationChannel(ROT,
            kfRot(0.0f,  12.5f, 0.0f, 0.0f),
            kfRot(2.0f, -12.5f, 0.0f, 0.0f),
            kfRot(4.0f,  12.5f, 0.0f, 0.0f),
        ))
        .addAnimation("right_leg", AnimationChannel(POS,
            kfPos(0.25f,   0.0f, 0.0f,  0.0f),
            kfPos(1.2917f, 0.0f, 0.5f, -0.5f),
            kfPos(2.3333f, 0.0f, 0.0f,  0.0f),
        ))
        .addAnimation("robe", AnimationChannel(ROT,
            kfRot(0.0f,   4.46f, 0.0f, 0.0f),
            kfRot(1.125f, -7.5f, 0.0f, 0.0f),
            kfRot(2.125f,  5.0f, 0.0f, 0.0f),
            kfRot(3.125f, -7.5f, 0.0f, 0.0f),
            kfRot(4.0f,   4.46f, 0.0f, 0.0f),
        ))
        .build()
}
