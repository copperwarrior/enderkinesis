package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import dev.architectury.event.events.client.ClientPlayerEvent
import dev.architectury.networking.NetworkManager
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.world.entity.LivingEntity
import org.shipwrights.enderkinesis.dimension.Sureibjin
import org.shipwrights.enderkinesis.item.MundaneIncubation
import java.util.function.Supplier
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object IncubationGlow {
    private const val SEGMENTS = 24
    private const val CHEST = 0.72f
    private const val MIN_RADIUS = 0.08f
    private const val MAX_RADIUS = 0.4f
    private const val CORE_ALPHA = 0.85f

    private val COS = FloatArray(SEGMENTS + 1) { cos(it * 2.0 * PI / SEGMENTS).toFloat() }
    private val SIN = FloatArray(SEGMENTS + 1) { sin(it * 2.0 * PI / SEGMENTS).toFloat() }
    private val SHADER = Supplier<ShaderInstance?> { GameRenderer.getPositionColorShader() }

    // Render thread only. Below 0 when no stone incubates.
    private var progress = -1f
    private var colour = 0

    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, MundaneIncubation.S2C_STATE) { buf, _ ->
            val p = buf.readFloat()
            val c = buf.readInt()
            Minecraft.getInstance().execute {
                progress = p
                colour = c
            }
        }
        ClientPlayerEvent.CLIENT_PLAYER_QUIT.register { progress = -1f }
    }

    @JvmStatic
    fun render(graphics: GuiGraphics, x: Int, y: Int, scale: Int, entity: LivingEntity) {
        if (progress < 0f || entity !== Minecraft.getInstance().player) return
        // The incubation pauses in the dream; the glow comes back on the wake.
        if (entity.level().dimension() == Sureibjin.LEVEL_KEY) return
        val radius = scale * (MIN_RADIUS + (MAX_RADIUS - MIN_RADIUS) * progress)
        val cx = x.toFloat()
        val cy = y - scale * entity.bbHeight * CHEST
        val r = (colour shr 16 and 0xFF) / 255f
        val g = (colour shr 8 and 0xFF) / 255f
        val b = (colour and 0xFF) / 255f
        val matrix = graphics.pose().last().pose()
        RenderSystem.enableBlend()
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE)
        RenderSystem.disableDepthTest()
        RenderSystem.setShader(SHADER)
        val buffer = Tesselator.getInstance().builder
        buffer.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR)
        buffer.vertex(matrix, cx, cy, Z).color(r, g, b, CORE_ALPHA).endVertex()
        for (i in 0..SEGMENTS) {
            buffer.vertex(matrix, cx + COS[i] * radius, cy + SIN[i] * radius, Z).color(r, g, b, 0f).endVertex()
        }
        BufferUploader.drawWithShader(buffer.end())
        RenderSystem.enableDepthTest()
        RenderSystem.defaultBlendFunc()
        RenderSystem.disableBlend()
    }

    private const val Z = 100f
}
