package org.shipwrights.enderkinesis.forge.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Mixin config plugin for {@code enderkinesis.mixins.json} (Forge). Gates Embeddium-
 * targeting mixins on Embeddium's presence on the classpath. Without the gate, the
 * Embeddium mixin would fail to find its target class on launches without Embeddium
 * installed and crash.
 *
 * <p>Probe is the {@code embeddium.mixins.json} resource — Embeddium ships it the same
 * way Sodium does, and the resource-not-class lookup avoids the class-loader side
 * effects that would crash on Forge.
 */
public class EnderkinesisForgeMixinPlugin implements IMixinConfigPlugin {

    private static final String EMBEDDIUM_PRESENCE_MARKER = "embeddium.mixins.json";

    private boolean embeddiumPresent = false;

    @Override
    public void onLoad(String mixinPackage) {
        embeddiumPresent = getClass().getClassLoader().getResource(EMBEDDIUM_PRESENCE_MARKER) != null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith(".EmbeddiumBlockRendererCloakingMixin")) {
            return embeddiumPresent;
        }
        return true;
    }

    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
