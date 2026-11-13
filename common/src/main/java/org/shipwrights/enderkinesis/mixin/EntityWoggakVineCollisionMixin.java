package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3d;
import org.shipwrights.enderkinesis.vine.VineCarried;
import org.shipwrights.enderkinesis.vine.VineColliderKt;
import org.shipwrights.enderkinesis.vine.VineHoldsKt;
import org.shipwrights.enderkinesis.vine.VineSupport;
import org.shipwrights.enderkinesis.vine.VinePushesKt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityWoggakVineCollisionMixin implements VineCarried {

    @Unique private List<VoxelShape> enderkinesis$base;
    @Unique private List<VoxelShape> enderkinesis$withVines;
    @Unique private Vector3d enderkinesis$vineCarry;
    @Unique private VineSupport enderkinesis$vineSupport;

    @Override
    public Vector3d enderkinesisVineCarry() {
        return enderkinesis$vineCarry;
    }

    @Override
    public void enderkinesisSetVineCarry(Vector3d carry) {
        enderkinesis$vineCarry = carry;
    }

    @Override
    public VineSupport enderkinesisVineSupport() {
        if (enderkinesis$vineSupport == null) enderkinesis$vineSupport = new VineSupport();
        return enderkinesis$vineSupport;
    }

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
        List<VoxelShape> base = original.call(level, entity, box);
        List<VoxelShape> withVines = VineColliderKt.withVineShapes(entity, box, base);
        enderkinesis$base = base;
        enderkinesis$withVines = withVines;
        return withVines;
    }

    @Inject(method = "move", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$rideVines(MoverType type, Vec3 movement, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (type == MoverType.SELF && VineHoldsKt.hangFromVine(self)) {
            VineColliderKt.dropVineCarry(self);
            ci.cancel();
            return;
        }
        VineColliderKt.rideVines(self, type);
    }

    @Inject(method = "collide", at = @At("RETURN"))
    private void enderkinesis$pushVines(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
        List<VoxelShape> base = enderkinesis$base;
        List<VoxelShape> withVines = enderkinesis$withVines;
        enderkinesis$base = null;
        enderkinesis$withVines = null;
        VinePushesKt.pushVinesAfterCollide((Entity) (Object) this, movement, cir.getReturnValue(), base, withVines);
    }
}
