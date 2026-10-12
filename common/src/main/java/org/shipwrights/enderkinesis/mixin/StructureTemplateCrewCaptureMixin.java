package org.shipwrights.enderkinesis.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.shipwrights.enderkinesis.worldgen.ShipCrew;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// placeInWorld, not placeEntities: Forge replaces placeEntities with its own addEntitiesToWorld.
@Mixin(StructureTemplate.class)
public class StructureTemplateCrewCaptureMixin {

    @Inject(method = "placeInWorld", at = @At("HEAD"))
    private void enderkinesis$beginCrewCapture(ServerLevelAccessor level, BlockPos pos, BlockPos pivot,
                                               StructurePlaceSettings settings, RandomSource random, int flags,
                                               CallbackInfoReturnable<Boolean> cir) {
        ShipCrew.beginCapture(settings, pos);
    }

    @Inject(method = "placeInWorld", at = @At("RETURN"))
    private void enderkinesis$endCrewCapture(ServerLevelAccessor level, BlockPos pos, BlockPos pivot,
                                             StructurePlaceSettings settings, RandomSource random, int flags,
                                             CallbackInfoReturnable<Boolean> cir) {
        ShipCrew.endCapture();
    }
}
