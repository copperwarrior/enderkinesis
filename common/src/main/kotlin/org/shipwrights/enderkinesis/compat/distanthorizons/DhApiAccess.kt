package org.shipwrights.enderkinesis.compat.distanthorizons

import com.mojang.blaze3d.systems.RenderSystem
import com.seibel.distanthorizons.api.DhApi
import com.seibel.distanthorizons.api.enums.rendering.EDhApiFogFalloff
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeDeferredRenderEvent
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeFogRenderEvent
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderEvent
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam
import net.minecraft.client.Minecraft
import org.shipwrights.enderkinesis.client.IchaericInkVisuals
import org.shipwrights.enderkinesis.dimension.SselithRepertory
import org.shipwrights.enderkinesis.dimension.Sureibjin
import org.shipwrights.enderkinesis.dimension.WeheloPocket
import org.shipwrights.enderkinesis.dimension.Wohlonnogondonia
import org.shipwrights.enderkinesis.dimension.YgannAbyss
import java.awt.Color

// Loads DH API classes, so only DistantHorizonsCompat may reach it, after its isModLoaded check.
internal object DhApiAccess {

    fun isDhThread(): Boolean = DhApi.isDhThread()

    fun registerRenderGate() {
        DhApi.events.bind(DhApiBeforeRenderEvent::class.java, LodRenderGate())
        // DH fires the deferred transparent pass as its own event class.
        DhApi.events.bind(DhApiBeforeDeferredRenderEvent::class.java, DeferredLodRenderGate())
        DhApi.events.bind(DhApiBeforeFogRenderEvent::class.java, LodFogMatch())
    }

    private fun suppressLods(): Boolean {
        val dimension = Minecraft.getInstance().level?.dimension() ?: return false
        // Fog in these ends inside vanilla range, so LODs could only show as fog-coloured shapes.
        return dimension == WeheloPocket.LEVEL_KEY || dimension == YgannAbyss.LEVEL_KEY ||
            dimension == SselithRepertory.LEVEL_KEY || dimension == Wohlonnogondonia.LEVEL_KEY
    }

    private class LodRenderGate : DhApiBeforeRenderEvent() {
        override fun beforeRender(event: DhApiCancelableEventParam<DhApiRenderParam>) {
            if (suppressLods()) event.cancelEvent()
        }
    }

    private class DeferredLodRenderGate : DhApiBeforeDeferredRenderEvent() {
        override fun beforeRender(event: DhApiCancelableEventParam<DhApiRenderParam>) {
            if (suppressLods()) event.cancelEvent()
        }
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
    private const val BLOCKS_PER_CHUNK = 16
}
