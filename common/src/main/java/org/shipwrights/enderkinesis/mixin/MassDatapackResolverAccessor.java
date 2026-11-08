package org.shipwrights.enderkinesis.mixin;

import java.util.HashMap;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.valkyrienskies.mod.common.config.MassDatapackResolver;

// Values are VS2's package-private VSBlockStateInfo; read them through VSBlockStateInfoAccessor.
@Mixin(value = MassDatapackResolver.class, remap = false)
public interface MassDatapackResolverAccessor {
    @Accessor("map")
    static HashMap<ResourceLocation, Object> enderkinesis$getMap() {
        throw new AssertionError();
    }
}
