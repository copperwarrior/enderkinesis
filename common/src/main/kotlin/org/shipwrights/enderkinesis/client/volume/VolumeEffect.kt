package org.shipwrights.enderkinesis.client.volume

import com.mojang.logging.LogUtils
import java.util.function.IntSupplier
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.EffectInstance

/** A full-screen volume program, with `DepthSampler` bound to [VolumeDepth]. Render thread only. */
class VolumeEffect(private val name: String, private vararg val samplers: Pair<String, IntSupplier>) {
    private var effect: EffectInstance? = null

    // A program that does not compile fails again on each attempt, so try one time only.
    private var loadFailed = false

    /** The loaded program, or null when it did not compile. */
    fun get(): EffectInstance? {
        effect?.let { return it }
        if (loadFailed) return null
        val loaded = load()
        if (loaded == null) loadFailed = true else effect = loaded
        return loaded
    }

    /** Points `DepthSampler` at [textureId] until [useSceneDepth], for a pass over a target that is not main. */
    fun useDepth(textureId: Int) {
        effect?.setSampler("DepthSampler", IntSupplier { textureId })
    }

    fun useSceneDepth() {
        effect?.setSampler("DepthSampler", SCENE_DEPTH)
    }

    // Samplers a program does not declare are ignored, so passes can share one sampler set.
    private fun load(): EffectInstance? = try {
        EffectInstance(Minecraft.getInstance().resourceManager, name).also {
            it.setSampler("DepthSampler", SCENE_DEPTH)
            for ((sampler, texture) in samplers) it.setSampler(sampler, texture)
        }
    } catch (t: Throwable) {
        LOG.warn("Failed to load volume shader program {}", name, t)
        null
    }

    private companion object {
        val LOG = LogUtils.getLogger()
        val SCENE_DEPTH = IntSupplier { VolumeDepth.textureId(Minecraft.getInstance().mainRenderTarget) }
    }
}
