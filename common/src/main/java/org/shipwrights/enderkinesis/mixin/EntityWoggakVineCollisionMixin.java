package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.shipwrights.enderkinesis.vine.VineColliderKt;
import org.shipwrights.enderkinesis.vine.VinePushesKt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityWoggakVineCollisionMixin {

    // Wraps the entity-shape list, so the vines collide on the server and in client prediction alike.
    @WrapOperation(
        method = "collide",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getEntityCollisions(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"
        )
    )
    private List<VoxelShape> enderkinesis$addVineShapes(
        Level level, Entity entity, AABB box, Operation<List<VoxelShape>> original
    ) {
        return VineColliderKt.withVineShapes(level, box, original.call(level, entity, box));
    }

    @Inject(method = "collide", at = @At("RETURN"))
    private void enderkinesis$pushVines(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
        VinePushesKt.pushVinesAfterCollide((Entity) (Object) this, movement);
    }
}
