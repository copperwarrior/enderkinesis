package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Accessor mixin exposing the private static {@link SpawnPlacements#register} so the
 * Cataloger (and any future mod entity) can register a spawn placement rule from
 * common code. Vanilla 1.20.1 left this method private; Forge wraps it in
 * SpawnPlacementRegisterEvent and Fabric exposes it via an accessor — we use the same
 * pattern here so the common module doesn't depend on either loader.
 */
@Mixin(SpawnPlacements.class)
public interface SpawnPlacementsInvoker {

    @Invoker("register")
    static <T extends Mob> void enderkinesis$register(
            EntityType<T> entityType,
            SpawnPlacements.Type placementType,
            Heightmap.Types heightmap,
            SpawnPlacements.SpawnPredicate<T> spawnPredicate
    ) {
        throw new AssertionError("Mixin failed to apply");
    }
}
