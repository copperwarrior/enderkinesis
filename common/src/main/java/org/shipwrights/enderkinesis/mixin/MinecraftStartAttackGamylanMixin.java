package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import org.shipwrights.enderkinesis.client.GamylanCageClientRegistry;
import org.shipwrights.enderkinesis.client.GamylanCageNetworkClient;
import org.shipwrights.enderkinesis.client.GamylanCageRaytrace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Routes a left-click attack to a Ga'Mylan cage body when the player's
 * look ray hits one. Mirrors the Skyriders pattern
 * ({@code SkyridersModClient.onInteractionKey} →
 * {@code SkyridersNetwork.sendVehicleMeleeAttack} →
 * {@code VehicleDamageEvents.handleClientMeleeAttack}). Because
 * Architectury doesn't surface Forge's
 * {@code InputEvent.InteractionKeyMappingTriggered}, we intercept
 * inside {@code Minecraft.startAttack()} — the single point both Forge
 * and Fabric drive the click through.
 *
 * <p>On a cage hit:
 *   <ol>
 *     <li>Send the {@code CAGE_ATTACK} C2S packet to the server.</li>
 *     <li>Trigger the local swing animation manually so the client
 *         still gets visible attack feedback.</li>
 *     <li>Return {@code false} from {@code startAttack} so vanilla
 *         skips its own block-break / entity-attack resolution.</li>
 *   </ol>
 */
@Mixin(Minecraft.class)
public abstract class MinecraftStartAttackGamylanMixin {

    /** Max range (blocks) the client raycast extends from the player's
     *  eye. Slightly larger than vanilla reach to give a comfortable
     *  hit window on the crystal even at melee edge. */
    private static final double MAX_REACH = 5.0;

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$attackCageBody(CallbackInfoReturnable<Boolean> cir) {
        Minecraft self = (Minecraft) (Object) this;
        LocalPlayer player = self.player;
        if (player == null || self.level == null) return;
        if (GamylanCageClientRegistry.INSTANCE.currentLevelEntries().isEmpty()) return;

        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 look = player.getLookAngle();
        GamylanCageClientRegistry.Entry hit =
            GamylanCageRaytrace.INSTANCE.findCageOnRay(eye, look, MAX_REACH);
        if (hit == null) return;
        // A cage behind a wall or a mob must not take the click from it.
        if (GamylanCageRaytrace.vanillaTargetIsNearer(eye, self.hitResult)) return;

        // Confirm-hit packet + visible local swing, then short-circuit
        // vanilla's startAttack to keep block-break / entity-attack out
        // of the picture for this click.
        GamylanCageNetworkClient.INSTANCE.sendAttack(hit.getBodyId());
        player.swing(InteractionHand.MAIN_HAND);
        cir.setReturnValue(false);
    }
}
