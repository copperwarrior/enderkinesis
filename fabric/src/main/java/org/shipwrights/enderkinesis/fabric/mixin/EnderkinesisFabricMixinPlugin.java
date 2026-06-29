package org.shipwrights.enderkinesis.fabric.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Mixin config plugin for {@code enderkinesis.mixins.json} (Fabric). Gates Sodium-
 * targeting mixins on Sodium's actual presence — without it, the Sodium block-
 * concealment mixin would crash launches that don't have Sodium installed when Mixin
 * tries to load its target class.
 *
 * <p>The same probe-via-JSON-resource trick the common plugin uses
 * ({@code sodium.mixins.json}) — JSON resource lookup doesn't trigger the
 * class-define machinery that {@code Class.forName(...)} would, so it's safe at this
 * stage in the Fabric class load pipeline.
 */
public class EnderkinesisFabricMixinPlugin implements IMixinConfigPlugin {

    private static final String SODIUM_PRESENCE_MARKER = "sodium.mixins.json";

    private boolean sodiumPresent = false;

    @Override
    public void onLoad(String mixinPackage) {
        sodiumPresent = getClass().getClassLoader().getResource(SODIUM_PRESENCE_MARKER) != null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith(".SodiumBlockRendererCloakingMixin")) {
            return sodiumPresent;
        }
        return true;
    }

    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
