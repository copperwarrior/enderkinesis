package org.shipwrights.enderkinesis.compat.distanthorizons

import com.mojang.blaze3d.systems.RenderSystem
import com.seibel.distanthorizons.api.DhApi
import com.seibel.distanthorizons.api.enums.rendering.EDhApiFogFalloff
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeFogRenderEvent
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam
import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.client.Minecraft
import org.shipwrights.enderkinesis.client.IchaericInkVisuals
import org.shipwrights.enderkinesis.dimension.SselithRepertory
import org.shipwrights.enderkinesis.dimension.Sureibjin
import org.shipwrights.enderkinesis.dimension.WeheloPocket
import org.shipwrights.enderkinesis.dimension.Wohlonnogondonia
import org.shipwrights.enderkinesis.dimension.YgannAbyss
import java.awt.Color

// Client only, and loads DH API classes: only DistantHorizonsCompat.initClient may reach it, after its isModLoaded check.
internal object DhApiAccess {

    fun initClient() {
        DhApi.events.bind(DhApiBeforeFogRenderEvent::class.java, LodFogMatch())
        ClientTickEvent.CLIENT_POST.register { _ -> updateRenderGate() }
    }

    /**
     * Cancelling DH's render events skips the LODs but not its vanilla fade, which then fades far chunks
     * into an empty LOD buffer. The API override sets rendererMode DISABLED, which gates both, and is not saved.
     */
    private fun updateRenderGate() {
        val configs = DhApi.Delayed.configs ?: return
        val enabled = configs.graphics().renderingEnabled()
        if (suppressLods()) {
            if (enabled.apiValue != false) enabled.setValue(false, API_CALLER)
        } else if (enabled.apiValue != null) {
            enabled.clearValue()
        }
    }

    private fun suppressLods(): Boolean {
        val dimension = Minecraft.getInstance().level?.dimension() ?: return false
        // Fog in these ends inside vanilla range, so LODs could only show as fog-coloured shapes.
        return dimension == WeheloPocket.LEVEL_KEY || dimension == YgannAbyss.LEVEL_KEY ||
            dimension == SselithRepertory.LEVEL_KEY || dimension == Wohlonnogondonia.LEVEL_KEY
    }

    private fun ourFogShapesLods(): Boolean {
        val dimension = Minecraft.getInstance().level?.dimension() ?: return false
        return dimension == Sureibjin.LEVEL_KEY || IchaericInkVisuals.cameraInInk()
    }

    /** DH fogs LODs over its own LOD range; copy our terrain fog onto that scale so the far terrain fades out with it. */
    private class LodFogMatch : DhApiBeforeFogRenderEvent() {
        override fun beforeRender(event: DhApiCancelableEventParam<DhApiBeforeFogRenderEvent.EventParam>) {
            if (!ourFogShapesLods()) return
            val end = TerrainFogSnapshot.end
            // Past this, the fog is DH's pushed-out value: none of our fogs set it this frame.
            if (end > PUSHED_OUT_FOG) return
            // No `?.` chain: the dev-time Architectury transformer merges DH types at its joins and cannot load them.
            val configs = DhApi.Delayed.configs ?: return
            val lodChunks: Int = configs.graphics().chunkRenderDistance().value
            val lodBlocks = (lodChunks * BLOCKS_PER_CHUNK).toFloat()
            val fog = event.value.fogRenderParam
            fog.setFarFogFalloff(EDhApiFogFalloff.LINEAR)
            fog.setFarFogStartPercent(TerrainFogSnapshot.start / lodBlocks)
            fog.setFarFogEndPercent(end / lodBlocks)
            fog.setFarFogMinThickness(0f)
            fog.setFarFogMaxThickness(1f)
            val color = RenderSystem.getShaderFogColor()
            fog.setFogColor(Color(color[0], color[1], color[2], 1f))
        }
    }

    private const val PUSHED_OUT_FOG = 1.0e6f

    // DH shows this name to players as the mod that changed the setting.
    private const val API_CALLER = "Enderkinesis"
    private const val BLOCKS_PER_CHUNK = 16
}
