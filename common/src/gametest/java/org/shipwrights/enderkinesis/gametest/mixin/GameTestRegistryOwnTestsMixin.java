package org.shipwrights.enderkinesis.gametest.mixin;

import java.util.Collection;
import java.util.stream.Collectors;
import net.minecraft.gametest.framework.GameTestRegistry;
import net.minecraft.gametest.framework.TestFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The headless run takes every test on the classpath, and dev mods such as Create ship their own. Only tests whose
 * template is in this mod's namespace run, so a foreign test cannot fail the run. Every test here must use such a template.
 */
@Mixin(GameTestRegistry.class)
public abstract class GameTestRegistryOwnTestsMixin {
    private static final String OWN_NAMESPACE = "enderkinesis_gametest:";

    @Inject(method = "getAllTestFunctions", at = @At("RETURN"), cancellable = true)
    private static void enderkinesis$onlyOwnTests(CallbackInfoReturnable<Collection<TestFunction>> cir) {
        cir.setReturnValue(cir.getReturnValue().stream()
            .filter(test -> test.getStructureName().startsWith(OWN_NAMESPACE))
            .collect(Collectors.toList()));
    }
}
