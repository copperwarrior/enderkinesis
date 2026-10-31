package org.shipwrights.enderkinesis.mixin;

import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.IControlContraption;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = ControlledContraptionEntity.class, remap = false)
public interface ControlledContraptionEntityAccessor {
    @Invoker("getController") IControlContraption enderkinesis$getController();
}
