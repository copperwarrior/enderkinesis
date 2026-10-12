package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.shipwrights.enderkinesis.body.GamylanCageEntityCollision;
import org.shipwrights.enderkinesis.body.OrbEntityCollision;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Skyriders-pattern entity-vs-orb collision.
 *
 *  VS2's standalone (non-ship) `VsBody`s don't push entities by
 *  default — the physics engine knows about the body's collision
 *  shape, but vanilla {@link Entity#collide(Vec3)} doesn't consult
 *  anything outside terrain + shipyard voxels. This mixin wraps
 *  {@code collide} to first adjust the requested movement against
 *  every orb body in the entity's dimension via
 *  {@link OrbEntityCollision#adjustMovement}.
 *
 *  Long sweeps are subdivided into 0.5 m steps so a fast-moving entity
 *  can't tunnel through a single-orb sphere collision in one frame —
 *  same fix Skyriders' {@code MixinEntity} applies to its bike
 *  collisions.
 */
@Mixin(Entity.class)
public abstract class EntityOrbCollisionMixin {

    /**
     * Pre-adjust the movement vector for orb collisions BEFORE the
     * vanilla collide call sees it. Wrapping `Entity.move`'s call to
     * `Entity.collide` lets us intercept the moving entity's collision
     * query without rewriting vanilla's stair-step / step-up logic
     * (which lives outside this call site).
     */
    private static final int ENDERKINESIS$MAX_SUBDIVISIONS = 64;

    @WrapOperation(
        method = "move",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"
        )
    )
    private Vec3 enderkinesis$collideWithOrbBodies(
        Entity self, Vec3 movement, Operation<Vec3> collide
    ) {
        // Most worlds have no orb and no cage; each entity move goes through here on the two sides.
        if (!OrbEntityCollision.mayCollide(self.level()) && !GamylanCageEntityCollision.mayCollide(self.level())) {
            return collide.call(self, movement);
        }
        final AABB box = self.getBoundingBox();
        // Subdivide long sweeps so a high-speed entity can't tunnel
        // through the orb sphere in one tick. 0.5 m per substep is a
        // good balance — finer than the orb radius (1.5) so a single
        // sphere can't be skipped.
        // Capped: a very large or infinite movement gave up to 2^31 passes.
        final int subdivisions = Math.min((int) (movement.length() / 0.5) + 1, ENDERKINESIS$MAX_SUBDIVISIONS);
        final Vec3 substep = movement.scale(1.0 / subdivisions);

        Vec3 finalMovement = movement;
        for (int i = 0; i < subdivisions; i++) {
            final Vec3 previousMovement = substep.scale(i);
            final AABB sweptBox = box.move(previousMovement);
            // Chain orb collision → Ga'Mylan cage collision. Both
            // helpers use VS2's primitive-shape adjustment internally
            // and are no-ops when no body of their kind is near.
            Vec3 partial = OrbEntityCollision.adjustMovement(
                self, substep, sweptBox, self.level()
            );
            partial = GamylanCageEntityCollision.adjustMovement(
                self, partial, sweptBox, self.level()
            );
            if (partial.distanceToSqr(substep) > 1.0e-12) {
                finalMovement = previousMovement.add(partial);
                break;
            }
        }

        return collide.call(self, finalMovement);
    }
}
