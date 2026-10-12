package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PersistentEntitySectionManager.class)
public interface PersistentEntitySectionManagerStorageAccessor {
    @Accessor("permanentStorage")
    EntityPersistentStorage<?> enderkinesis$getPermanentStorage();
}
