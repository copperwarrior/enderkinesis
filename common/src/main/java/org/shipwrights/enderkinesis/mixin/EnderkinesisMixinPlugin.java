package org.shipwrights.enderkinesis.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Mixin config plugin for {@code enderkinesis-common.mixins.json}.
 *
 * <p>Single job right now: gate Sodium-targeting mixins on Sodium's
 * actual presence. Without this, the Sodium-cloud-fade mixin would
 * fail to find its target class on Mixin's pre-apply pass when the
 * user doesn't have Sodium installed, crashing the launch.
 *
 * <p>The check is done once in {@link #onLoad(String)} via a single
 * {@link Class#forName} probe for Sodium's {@code CloudRenderer}
 * class (introduced in Sodium 0.5+). All non-Sodium mixins still
 * apply unconditionally — {@link #shouldApplyMixin(String, String)}
 * only filters the explicitly-named Sodium-bridge classes.
 */
public class EnderkinesisMixinPlugin implements IMixinConfigPlugin {

    /** Probe Sodium's JSON config (not {@code CloudRenderer.class}): Fabric's
     *  {@code KnotClassLoader.getResource(".class")} routes through class-define machinery,
     *  which predefines {@code CloudRenderer} and triggers
     *  {@code MixinTargetAlreadyLoadedException} at PREPARE. JSON has no such side effect. */
    private static final String SODIUM_PRESENCE_MARKER = "sodium.mixins.json";

    // Embeddium 0.3.x keeps the Sodium package names but ships its own config name.
    private static final String EMBEDDIUM_PRESENCE_MARKER = "embeddium.mixins.json";

    private boolean sodiumPresent = false;
    private boolean embeddiumPresent = false;

    @Override
    public void onLoad(String mixinPackage) {
        sodiumPresent = getClass().getClassLoader().getResource(SODIUM_PRESENCE_MARKER) != null;
        embeddiumPresent = getClass().getClassLoader().getResource(EMBEDDIUM_PRESENCE_MARKER) != null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // Only filter the explicit Sodium-bridge mixins. Everything
        // else applies normally.
        if (mixinClassName.endsWith(".SodiumLightPipelineSselithMixin")) {
            return sodiumPresent || embeddiumPresent;
        }
        if (mixinClassName.endsWith(".SodiumCloudRendererFadeMixin")) {
            return sodiumPresent || embeddiumPresent;
        }
        // Sodium's WorldSlice doesn't exist without Sodium — gate the
        // ambient-floor mirror so a non-Sodium runtime doesn't fail PREPARE.
        if (mixinClassName.endsWith(".WorldSliceWohlonAmbientMixin")) {
            return sodiumPresent || embeddiumPresent;
        }
        // Same WorldSlice target as Wohlon — the orb dynamic-light
        // mirror exists because Sodium's mesh build reads light from
        // its cloned `DataLayer` arrays, bypassing the vanilla
        // `LightEngine.getLightValue` hook.
        if (mixinClassName.endsWith(".WorldSliceOrbDynamicLightMixin")) {
            return sodiumPresent || embeddiumPresent;
        }
        // Sodium overwrites `LevelRenderer.setupRender` with an
        // implementation that no longer calls `LocalPlayer.getX/Y/Z()`,
        // so the scrying-camera @WrapOperations have nothing to bind
        // to — Mixin would fail with "0 target(s) scanned". Skip the
        // mixin entirely under Sodium. The scrying feature itself
        // degrades (the camera moves but the chunk-grid stays at the
        // player), which is a known limitation with Sodium installed
        // — but the game boots.
        if (mixinClassName.endsWith(".LevelRendererScryingMixin")) {
            // Embeddium overwrites setupRender too (checked in 0.3.31).
            return !sodiumPresent && !embeddiumPresent;
        }
        return true;
    }

    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
