package org.shipwrights.enderkinesis.mixin;

import java.util.List;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** CubeListBuilder has only box UV; per-face UV cubes are added to the baked part through this. */
@Mixin(ModelPart.class)
public interface ModelPartCubesAccessor {
    @Accessor("cubes")
    List<ModelPart.Cube> enderkinesis$getCubes();

    @Mutable
    @Accessor("cubes")
    void enderkinesis$setCubes(List<ModelPart.Cube> cubes);
}
