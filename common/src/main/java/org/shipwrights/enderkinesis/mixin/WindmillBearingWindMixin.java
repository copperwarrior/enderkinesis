package org.shipwrights.enderkinesis.mixin;

import com.simibubi.create.content.contraptions.bearing.WindmillBearingBlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import org.shipwrights.enderkinesis.duck.WindmillWindTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = WindmillBearingBlockEntity.class, remap = false)
public abstract class WindmillBearingWindMixin implements WindmillWindTarget {

    @Unique private static final String WIND_BOOST_KEY = "EnderkinesisWindBoost";

    @Unique private float enderkinesis$windBoost = 1f;
    @Unique private long enderkinesis$windUntil = 0L;

    // Only the sail-count return: updateGeneratedRotation stores the result in lastGeneratedSpeed,
    // which the other returns hand back, so boosting those would compound.
    @Inject(method = "getGeneratedSpeed", at = @At(value = "RETURN", ordinal = 2), cancellable = true)
    private void enderkinesis$boostSailSpeed(CallbackInfoReturnable<Float> cir) {
        if (enderkinesis$windBoost != 1f) cir.setReturnValue(cir.getReturnValueF() * enderkinesis$windBoost);
    }

    // HEAD: the windmill tick returns early on most ticks, so TAIL almost never runs.
    @Inject(method = "tick", at = @At("HEAD"))
    private void enderkinesis$expireWind(CallbackInfo ci) {
        WindmillBearingBlockEntity self = (WindmillBearingBlockEntity) (Object) this;
        Level level = self.getLevel();
        if (level == null || level.isClientSide || enderkinesis$windBoost == 1f) return;
        if (level.getGameTime() > enderkinesis$windUntil) {
            enderkinesis$windBoost = 1f;
            self.updateGeneratedRotation();
        }
    }

    // MechanicalBearingBlockEntity spins windmill sails from getGeneratedSpeed on the client too,
    // so the client needs the boost; updateGeneratedRotation sends it through sendData.
    @Inject(method = "write", at = @At("TAIL"))
    private void enderkinesis$writeWind(CompoundTag tag, boolean clientPacket, CallbackInfo ci) {
        if (clientPacket) tag.putFloat(WIND_BOOST_KEY, enderkinesis$windBoost);
    }

    @Inject(method = "read", at = @At("TAIL"))
    private void enderkinesis$readWind(CompoundTag tag, boolean clientPacket, CallbackInfo ci) {
        if (clientPacket) {
            enderkinesis$windBoost = tag.contains(WIND_BOOST_KEY) ? tag.getFloat(WIND_BOOST_KEY) : 1f;
        }
    }

    @Override
    public void enderkinesis$blow(float boost, long untilGameTime) {
        enderkinesis$windUntil = untilGameTime;
        if (boost == enderkinesis$windBoost) return;
        enderkinesis$windBoost = boost;
        ((WindmillBearingBlockEntity) (Object) this).updateGeneratedRotation();
    }
}
