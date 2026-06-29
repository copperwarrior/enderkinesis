package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@link LocalPlayer} overrides {@code isCrouching()} to return its own
 * private {@code crouching} field rather than reading
 * {@code Entity.hasPose(Pose.CROUCHING)} like every other entity. That
 * means {@code player.setPose(Pose.CROUCHING)} is invisible to
 * {@code PlayerRenderer.setModelProperties}, which calls
 * {@code entity.isCrouching()} when wiring up {@code PlayerModel.crouching}.
 *
 * <p>This accessor exposes the field directly so the Sureibjin silhouette
 * renderer can momentarily flip the local player into a crouched pose
 * before drawing each silhouette and restore it afterwards.
 */
@Mixin(LocalPlayer.class)
public interface LocalPlayerCrouchingAccessor {

    @Accessor("crouching")
    boolean enderkinesis$getCrouching();

    @Accessor("crouching")
    void enderkinesis$setCrouching(boolean value);
}
