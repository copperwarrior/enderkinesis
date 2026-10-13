package org.shipwrights.enderkinesis.client

import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.EffectInstance
import org.shipwrights.enderkinesis.dimension.Sureibjin

object SureibjinDreamVignette {

    private val LOG: org.slf4j.Logger =
        org.slf4j.LoggerFactory.getLogger("EnderkinesisSureibjinDreamVignette")

    @Volatile private var effect: EffectInstance? = null
    // A broken shader must not log one warning each frame.
    @Volatile private var loadFailed: Boolean = false

    private fun ensureLoaded(mc: Minecraft): EffectInstance? {
        effect?.let { return it }
        if (loadFailed) return null
        return try {
            EffectInstance(mc.resourceManager, "sureibjin_dream_vignette").also { effect = it }
        } catch (t: Throwable) {
            loadFailed = true
            LOG.warn("Failed to load Sureibjin dream vignette shader program", t)
            null
        }
    }

    fun close() {
        effect?.close()
        effect = null
        loadFailed = false
    }

    /** Called from `LevelRendererBubblePassMixin` at the TAIL of `LevelRenderer.renderLevel`. */
    @JvmStatic
    fun render() {
        val mc = Minecraft.getInstance()
        if (mc.level?.dimension() != Sureibjin.LEVEL_KEY) return
        val loaded = ensureLoaded(mc) ?: return
        val tSec = (Util.getMillis() / 1000.0).toFloat()
        RefractionPass.run(loaded) { eff ->
            eff.safeGetUniform("SessionTime").set(tSec)
        }
    }
}
