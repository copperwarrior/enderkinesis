package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.shipwrights.enderkinesis.registry.EKSounds;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// NoteBlockInstrument is an enum that the two loaders cannot extend in the same way, so the sound is swapped at play time.
@Mixin(NoteBlock.class)
public abstract class NoteBlockCrepusculiteMixin {

    @ModifyExpressionValue(
        method = "triggerEvent",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/properties/NoteBlockInstrument;getSoundEvent()Lnet/minecraft/core/Holder;"
        )
    )
    private Holder<SoundEvent> enderkinesis$crepusculiteInstrument(
        Holder<SoundEvent> original, BlockState state, Level level, BlockPos pos, int id, int param
    ) {
        // A mob head above the note block keeps its own sound, as in vanilla.
        if (state.getValue(NoteBlock.INSTRUMENT).worksAboveNoteBlock()) return original;
        if (level.getBlockState(pos.below()).getSoundType() != EKSounds.INSTANCE.getCREPUSCULITE_SOUND_TYPE()) return original;
        return BuiltInRegistries.SOUND_EVENT.wrapAsHolder(EKSounds.INSTANCE.getNOTE_BLOCK_CREPUSCULITE());
    }
}
