package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.Entity;
import org.shipwrights.enderkinesis.entity.ConstriliarEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.util.EntityShipCollisionUtils;

/**
 * A Constriliar must never collide with its own back ship. The body target pose derives
 * from the entity position, so any push from the carried ship feeds straight back into
 * the ship's target — every uncovered contact became a runaway (the climb ladder, the
 * cargo elevator, the step-up scoop where a 1-block step sweeps the spring-lagged cargo
 * through the body). Filtering the own ship out of VS2's per-entity ship gathering
 * severs the loop at the root; collision with terrain, other ships, and other entities
 * is untouched.
 */
@Mixin(value = EntityShipCollisionUtils.class, remap = false)
public class EntityShipCollisionOwnShipMixin {

    @WrapOperation(
        method = "getShipPolygonsCollidingWithEntity",
        at = @At(
            value = "INVOKE",
            target = "Lorg/valkyrienskies/core/internal/ships/VsiQueryableShipData;getIntersecting(Lorg/joml/primitives/AABBdc;Ljava/lang/String;)Ljava/lang/Iterable;"
        )
    )
    private Iterable<?> enderkinesis$skipConstriliarOwnShip(
        org.valkyrienskies.core.internal.ships.VsiQueryableShipData<?> instance,
        org.joml.primitives.AABBdc aabb,
        String dimensionId,
        Operation<Iterable<?>> original,
        @Local(argsOnly = true) Entity entity
    ) {
        final Iterable<?> ships = original.call(instance, aabb, dimensionId);
        if (!(entity instanceof ConstriliarEntity constriliar)) return ships;
        final Long ownShipId = constriliar.ownShipIdForCollision();
        if (ownShipId == null) return ships;
        final List<Object> filtered = new ArrayList<>();
        for (Object ship : ships) {
            if (!(ship instanceof Ship s) || s.getId() != ownShipId) filtered.add(ship);
        }
        return filtered;
    }
}
