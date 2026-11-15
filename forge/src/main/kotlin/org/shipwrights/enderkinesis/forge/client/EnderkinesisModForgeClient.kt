package org.shipwrights.enderkinesis.forge.client

import net.minecraft.network.chat.Component
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.repository.Pack
import net.minecraft.server.packs.repository.PackSource
import net.minecraft.client.Minecraft
import net.minecraftforge.client.event.EntityRenderersEvent
import net.minecraftforge.client.event.ModelEvent
import net.minecraftforge.client.event.RegisterDimensionSpecialEffectsEvent
import net.minecraftforge.client.event.RegisterShadersEvent
import net.minecraftforge.client.event.RenderGuiEvent
import net.minecraftforge.client.event.RenderLevelStageEvent
import net.minecraft.client.renderer.ShaderInstance
import net.minecraftforge.event.AddPackFindersEvent
import net.minecraftforge.fml.ModList
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent
import net.minecraftforge.resource.PathPackResources
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.client.EnderkinesisModClient
import org.shipwrights.enderkinesis.client.ScryingClient
import org.shipwrights.enderkinesis.client.TomeSummonOverlay
import org.shipwrights.enderkinesis.client.registerDimensionEffects as registerCommonDimensionEffects
import org.shipwrights.enderkinesis.client.additionalModels
import org.shipwrights.enderkinesis.client.captureAdditionalModel
import org.shipwrights.enderkinesis.client.registerCoreShaders
import org.shipwrights.enderkinesis.client.registerModelLayers
import org.shipwrights.enderkinesis.client.renderAfterEntities
import org.shipwrights.enderkinesis.client.renderAfterTranslucent

fun clientInit(event: FMLClientSetupEvent) {
    // The event runs on a worker thread; the registrations are for the main thread.
    event.enqueueWork { EnderkinesisModClient.initClient() }
    org.shipwrights.enderkinesis.forge.compat.curios.CuriosClientCompat.init()
}

fun registerParticleProviders(event: net.minecraftforge.client.event.RegisterParticleProvidersEvent) {
    EnderkinesisModClient.registerParticleProviders(object : EnderkinesisModClient.ParticleRegistrar {
        override fun <T : net.minecraft.core.particles.ParticleOptions> register(
            type: net.minecraft.core.particles.ParticleType<T>,
            factory: (net.minecraft.client.particle.SpriteSet) -> net.minecraft.client.particle.ParticleProvider<T>,
        ) {
            event.registerSpriteSet(type) { sprites -> factory(sprites) }
        }
    })
}

fun registerLayerDefinitions(event: EntityRenderersEvent.RegisterLayerDefinitions) {
    registerModelLayers { layer, definition -> event.registerLayerDefinition(layer) { definition() } }
}

fun registerAdditionalModels(event: ModelEvent.RegisterAdditional) {
    additionalModels().forEach(event::register)
}

fun registerShaders(event: RegisterShadersEvent) {
    registerCoreShaders { id, format, onLoad ->
        event.registerShader(ShaderInstance(event.resourceProvider, id, format)) { onLoad(it) }
    }
}

fun captureWyllandTomeBakes(event: ModelEvent.ModifyBakingResult) {
    for (id in additionalModels()) captureAdditionalModel(id, event.models[id])
}

fun onAddPackFinders(event: AddPackFindersEvent) {
    if (event.packType != PackType.CLIENT_RESOURCES) return
    val modFile = ModList.get()
        .getModFileById(EnderkinesisMod.MOD_ID)
        ?.file ?: return
    val packPath = modFile.findResource("resourcepacks/sselith")
    val pack = Pack.readMetaAndCreate(
        EnderkinesisMod.MOD_ID + ":sselith",
        Component.literal("Sselith"),
        true,  // alwaysEnabled — pack stays on without user toggle
        { pid -> PathPackResources(pid, true, packPath) },
        PackType.CLIENT_RESOURCES,
        Pack.Position.TOP,
        PackSource.BUILT_IN,
    )
    event.addRepositorySource { consumer -> consumer.accept(pack) }
}

fun onRenderLevelStage(event: RenderLevelStageEvent) {
    val bufferSource = Minecraft.getInstance().renderBuffers().bufferSource()
    when (event.stage) {
        RenderLevelStageEvent.Stage.AFTER_ENTITIES ->
            renderAfterEntities(event.poseStack, bufferSource, event.camera.position, event.partialTick)
        RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS ->
            renderAfterTranslucent(event.poseStack, bufferSource, event.camera.position, event.partialTick)
        else -> {}
    }
}

// `ForgeGui.render` replaces `Gui.render` with no super call, so `GuiScryingMixin`
// never runs on Forge. These two listeners do its work.
fun onRenderGuiPre(event: RenderGuiEvent.Pre) {
    val graphics = event.guiGraphics
    ScryingClient.renderFadeOverlay(graphics)
    ScryingClient.renderBackground(graphics, event.partialTick)
    TomeSummonOverlay.renderBackground(graphics, event.partialTick)
    if (!ScryingClient.isActive()) return
    // The mixin hides the HUD during a scry. A cancel here does that, and it also
    // stops the Post event, so the foreground is drawn now.
    ScryingClient.renderForeground(graphics, event.partialTick)
    TomeSummonOverlay.renderForeground(graphics, event.partialTick)
    event.isCanceled = true
}

fun onRenderGuiPost(event: RenderGuiEvent.Post) {
    ScryingClient.renderForeground(event.guiGraphics, event.partialTick)
    TomeSummonOverlay.renderForeground(event.guiGraphics, event.partialTick)
}

fun registerDimensionEffects(event: RegisterDimensionSpecialEffectsEvent) {
    registerCommonDimensionEffects(event::register)
}
