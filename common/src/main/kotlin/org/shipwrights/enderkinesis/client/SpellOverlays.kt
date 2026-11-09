package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import dev.architectury.event.events.client.ClientGuiEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.world.effect.MobEffect
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.registry.EKEffects
import org.shipwrights.enderkinesis.spell.Element
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import org.shipwrights.enderkinesis.util.smoothstep

/**
 * As with the vanilla vignette, overlays draw under the HUD ([renderUnderHud], from `GuiSpellOverlayMixin`).
 * Only the hollowed hearts draw over the vanilla hearts after the HUD ([renderHearts]).
 */
object SpellOverlays {
    private val since = HashMap<MobEffect, Long>()
    private val OVERLAY_EFFECTS = arrayOf(
        EKEffects.FROZEN, EKEffects.CRYSTALLIZED, EKEffects.ENTANGLED, EKEffects.WEIGHTED,
        EKEffects.BLEEDING, EKEffects.ROT, EKEffects.DISCORD, EKEffects.HOLLOWED,
    )
    private const val FROZEN = 0
    private const val CRYSTALLIZED = 1
    private const val ENTANGLED = 2
    private const val WEIGHTED = 3
    private const val BLEEDING = 4
    private const val ROT = 5
    private const val DISCORD = 6
    private const val HOLLOWED = 7
    /** NaN when the effect is off. Render thread only. */
    private val ages = DoubleArray(OVERLAY_EFFECTS.size)
    private val GUI_ICONS = ResourceLocation("textures/gui/icons.png")
    private val POWDER_SNOW = ResourceLocation("textures/misc/powder_snow_outline.png")
    /** Seeded as the vanilla HUD seeds its heart shake, so the shakes match. */
    private val heartJiggle = net.minecraft.util.RandomSource.create()

    fun init() {
        ClientGuiEvent.RENDER_HUD.register(::renderHearts)
    }

    private fun hash2(a: Int, b: Int): Double { val x = sin(a * 127.1 + b * 311.7) * 43758.5453; return x - floor(x) }

    @JvmStatic
    fun renderUnderHud(gfx: GuiGraphics, partialTick: Float) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        if (mc.options.hideGui) return
        if (player.activeEffectsMap.isEmpty()) {
            since.clear()
            return
        }
        val now = System.currentTimeMillis()
        var any = false
        for (i in OVERLAY_EFFECTS.indices) {
            val e = OVERLAY_EFFECTS[i].get()
            if (player.hasEffect(e)) {
                ages[i] = (now - since.getOrPut(e) { now }) / 1000.0
                any = true
            } else {
                ages[i] = Double.NaN
                since.remove(e)
            }
        }
        if (!any) return
        val w = gfx.guiWidth().toDouble(); val h = gfx.guiHeight().toDouble()
        val m = gfx.pose().last().pose()
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.disableDepthTest()
        if (!ages[FROZEN].isNaN()) frozen(gfx, m, w, h, ages[FROZEN], player.ticksFrozen > 0)
        if (!ages[CRYSTALLIZED].isNaN()) crystallized(m, w, h, ages[CRYSTALLIZED])
        if (!ages[ENTANGLED].isNaN()) entangled(m, w, h, ages[ENTANGLED])
        if (!ages[WEIGHTED].isNaN()) weighted(gfx, m, w, h, ages[WEIGHTED])
        if (!ages[BLEEDING].isNaN()) bleeding(m, w, h, ages[BLEEDING])
        if (!ages[ROT].isNaN()) rot(m, w, h, ages[ROT])
        if (!ages[DISCORD].isNaN()) discord(m, w, h, ages[DISCORD])
        if (!ages[HOLLOWED].isNaN()) vignette(m, w, h, 0x1E0A2D, 0.6)
        RenderSystem.enableDepthTest()
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
    }

    private fun renderHearts(gfx: GuiGraphics, partialTick: Float) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        if (mc.options.hideGui || !player.hasEffect(EKEffects.HOLLOWED.get())) return
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.disableDepthTest()
        hollowedHearts(gfx, gfx.guiWidth(), gfx.guiHeight(), player, System.currentTimeMillis() / 1000.0)
        RenderSystem.enableDepthTest()
    }

    private inline fun textured(texture: ResourceLocation, draw: (BufferBuilder) -> Unit) {
        RenderSystem.setShader { GameRenderer.getPositionTexColorShader() }
        RenderSystem.setShaderTexture(0, texture)
        val b = Tesselator.getInstance().builder
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR)
        draw(b)
        BufferUploader.drawWithShader(b.end())
    }

    /** UVs are fractions of the sprite; [x], [y] is the centre. */
    private fun quad(b: BufferBuilder, m: Matrix4f, s: Sprite, x: Double, y: Double, w: Double, h: Double, rgb: Int, alpha: Double, u0: Float = 0f, v0: Float = 0f, u1: Float = 1f, v1: Float = 1f, rot: Double = 0.0) {
        if (alpha <= 0.004) return
        val c = cos(rot); val sn = sin(rot)
        val r = (rgb shr 16) and 0xFF; val g = (rgb shr 8) and 0xFF; val bl = rgb and 0xFF; val a = (alpha * 255).roundToInt().coerceIn(0, 255)
        fun put(dx: Double, dy: Double, u: Float, v: Float) {
            b.vertex(m, (x + dx * c - dy * sn).toFloat(), (y + dx * sn + dy * c).toFloat(), 0f).uv(s.u(u), s.v(v)).color(r, g, bl, a).endVertex()
        }
        put(-w / 2, -h / 2, u0, v0); put(-w / 2, h / 2, u0, v1); put(w / 2, h / 2, u1, v1); put(w / 2, -h / 2, u1, v0)
    }

    private fun piece(b: BufferBuilder, m: Matrix4f, s: Sprite, u: Int, v: Int, px: Int, x: Double, y: Double, size: Double, rgb: Int, alpha: Double = 1.0, rot: Double = 0.0) =
        quad(b, m, s, x + size / 2, y + size / 2, size, size, rgb, alpha, u / 16f, v / 16f, (u + px) / 16f, (v + px) / 16f, rot)

    /** Clear inside [inner] of the shorter side, [alpha] at [outer] of the longer side. */
    private fun vignette(m: Matrix4f, w: Double, h: Double, rgb: Int, alpha: Double, inner: Double = 0.3, outer: Double = 0.75) {
        RenderSystem.setShader { GameRenderer.getPositionColorShader() }
        val b = Tesselator.getInstance().builder
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
        val r0 = min(w, h) * inner; val r1 = max(w, h) * outer
        val r = (rgb shr 16) and 0xFF; val g = (rgb shr 8) and 0xFF; val bl = rgb and 0xFF
        fun a(x: Double, y: Double): Int = (alpha * smoothstep((hypot(x - w / 2, y - h / 2) - r0) / (r1 - r0)) * 255).roundToInt().coerceIn(0, 255)
        val nx = 24; val ny = 14
        for (i in 0 until nx) for (j in 0 until ny) {
            val x0 = w * i / nx; val x1 = w * (i + 1) / nx; val y0 = h * j / ny; val y1 = h * (j + 1) / ny
            b.vertex(m, x0.toFloat(), y0.toFloat(), 0f).color(r, g, bl, a(x0, y0)).endVertex()
            b.vertex(m, x0.toFloat(), y1.toFloat(), 0f).color(r, g, bl, a(x0, y1)).endVertex()
            b.vertex(m, x1.toFloat(), y1.toFloat(), 0f).color(r, g, bl, a(x1, y1)).endVertex()
            b.vertex(m, x1.toFloat(), y0.toFloat(), 0f).color(r, g, bl, a(x1, y0)).endVertex()
        }
        BufferUploader.drawWithShader(b.end())
    }

    private fun argb(rgb: Int, alpha: Double): Int = ((alpha * 255).roundToInt().coerceIn(0, 255) shl 24) or (rgb and 0xFFFFFF)

    private val blockAtlas get() = BLOCK_ATLAS
    private val particleAtlas get() = PARTICLE_ATLAS

    private fun frozen(gfx: GuiGraphics, m: Matrix4f, w: Double, h: Double, t: Double, vanillaShows: Boolean) {
        val a = smoothstep(t / 0.4)
        gfx.fill(0, 0, w.toInt(), h.toInt(), argb(0x96C3E6, 0.1 * a))
        if (!vanillaShows) textured(POWDER_SNOW) { b -> quad(b, m, Sprite(POWDER_SNOW, 0f, 0f, 1f, 1f), w / 2, h / 2, w, h, 0xFFFFFF, 0.95 * a) }
    }

    private fun crystallized(m: Matrix4f, w: Double, h: Double, t: Double) {
        val grow = smoothstep(t / 2.0)
        val s = max(8.0, (h / 20).roundToInt().toDouble())
        val calcite = SpellArt.block("calcite")
        val buds = ArrayList<Triple<Double, Double, Double>>()
        textured(blockAtlas) { b ->
            var cx = 0
            while (cx * s < w) {
                var cy = 0
                while (cy * s < h) {
                    val x = cx * s; val y = cy * s
                    val edge = min(min(x, y), min(w - x - s, h - y - s)) / min(w, h)
                    val band = grow * (0.08 + 0.09 * hash2(cx / 2, cy / 2))
                    if (edge <= band) {
                        piece(b, m, calcite, (cx * 4) % 16, (cy * 4) % 16, 4, x, y, s, 0xFFFFFF, 0.95)
                        if (edge > band - 0.02 && hash2(cx, cy) < 0.18) buds += Triple(x + s / 2, y + s / 2, atan2(h / 2 - y, w / 2 - x) + Math.PI / 2)
                    }
                    cy++
                }
                cx++
            }
        }
        val gray = SpellVfxRenderTypes.grayShader()
        if (gray != null && buds.isNotEmpty()) {
            val lights = Minecraft.getInstance().gameRenderer.lightTexture()
            lights.turnOnLightLayer()
            RenderSystem.setShader { gray }
            RenderSystem.setShaderTexture(0, blockAtlas)
            val fogStart = RenderSystem.getShaderFogStart()
            RenderSystem.setShaderFogStart(Float.MAX_VALUE)
            val b = Tesselator.getInstance().builder
            b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE)
            val bud = SpellArt.block("amethyst_cluster")
            for ((x, y, rot) in buds) {
                val c = cos(rot); val sn = sin(rot)
                fun put(dx: Double, dy: Double, u: Float, v: Float) =
                    b.vertex(m, (x + dx * c - dy * sn).toFloat(), (y + dx * sn + dy * c).toFloat(), 0f).uv(bud.u(u), bud.v(v)).color(246, 242, 255, 242).uv2(SpellShapes.FULL_BRIGHT).endVertex()
                put(-s, -2 * s, 0f, 0f); put(-s, 0.0, 0f, 1f); put(s, 0.0, 1f, 1f); put(s, -2 * s, 1f, 0f)
            }
            BufferUploader.drawWithShader(b.end())
            RenderSystem.setShaderFogStart(fogStart)
            lights.turnOffLightLayer()
        }
        textured(particleAtlas) { b ->
            for (i in 0 until 6) {
                val k = (t * 0.7 + hash2(i, 9)) % 1
                val x = w * hash2(i, 5); val y = h * hash2(i, 6)
                if (min(min(x, y), min(w - x, h - y)) / min(w, h) < grow * 0.14) {
                    quad(b, m, SpellArt.glitter((k * 8).toInt().coerceIn(0, 7)), x, y, s, s, 0xFFFFFF, sin(Math.PI * k))
                }
            }
        }
    }

    /** Each stem has a fixed seed, so its shape stays the same and only shows more from the base as it grows. */
    private fun entangled(m: Matrix4f, w: Double, h: Double, t: Double) {
        val grow = smoothstep(t / 1.4)
        val k = max(2.0, (h / 130).roundToInt().toDouble())
        val step = 2 * k
        val vine = SpellArt.block("vine")
        val root = SpellArt.block("mangrove_roots_side")
        textured(blockAtlas) { b ->
            fun put(sprite: Sprite, x: Double, y: Double, px: Int, u: Int, v: Int, rgb: Int) =
                piece(b, m, sprite, u, v, px, Math.round(x / k) * k - px * k / 2, Math.round(y / k) * k - px * k / 2, px * k, rgb)
            fun stem(x0: Double, y0: Double, a0: Double, length: Double, px0: Int, isRoot: Boolean, depth: Int, d0: Double, reveal: Double, r: Random) {
                val sprite = if (isRoot) root else vine
                val rgb = if (isRoot) 0xFFFFFF else 0x48B518
                var x = x0; var y = y0; var a = a0
                var d = 0.0
                var node = (3 + r.nextDouble() * 5) * step
                var side = if (r.nextBoolean()) -1 else 1
                while (d < length) {
                    x += cos(a) * step; y += sin(a) * step; d += step
                    val px = max(2, (px0 * (1 - 0.45 * d / length)).roundToInt())
                    if (d0 + d < reveal) put(sprite, x, y, px, ((d / step * 2).toInt()) % (16 - px), (depth * 5 + 3) % (16 - px), rgb)
                    if (d < node) continue
                    node += (3 + r.nextDouble() * 5) * step
                    a += (r.nextDouble() - 0.5) * (if (isRoot) 1.0 else 0.7)
                    val fork = depth < 2 && r.nextDouble() < (if (isRoot) 0.5 else 0.35)
                    val fa = a + (if (r.nextBoolean()) -1 else 1) * (0.5 + 0.5 * r.nextDouble())
                    val fl = (length - d) * (0.35 + 0.35 * r.nextDouble())
                    if (fork) stem(x, y, fa, fl, max(2, px - 1), isRoot, depth + 1, d0 + d, reveal, r)
                    if (!isRoot) {
                        side = -side
                        val lx = x + cos(a + side * 1.4) * 3 * k; val ly = y + sin(a + side * 1.4) * 3 * k
                        val stir = Math.round(sin(t * 2.2 + x * 0.05 + y * 0.03)) * k
                        if (d0 + d + 4 * step < reveal) put(vine, lx + stir, ly, 6, (floor(x).toInt() % 3) * 4, (floor(y).toInt() % 3) * 4, 0x55B827)
                    }
                }
            }
            val maxLen = h * 0.45
            val reveal = grow * maxLen * 1.2
            for (i in 0 until 20) {
                val r = Random(i * 91 + 7)
                val e = hash2(i, 1)
                when {
                    i < 10 -> {
                        val x = w * (0.03 + 0.94 * e)
                        val lean = (if (x < w / 2) 1 else -1) * (0.1 + 0.4 * hash2(i, 2))
                        stem(x, h + 3 * k, -Math.PI / 2 + lean, maxLen * (0.45 + 0.55 * hash2(i, 3)), if (i % 2 != 0) 6 else 5, i % 2 == 1, 0, 0.0, reveal, r)
                    }
                    i < 15 -> {
                        val right = i % 2 == 1
                        val y = h * (0.5 + 0.5 * e)
                        stem(if (right) w + 3 * k else -3 * k, y, if (right) Math.PI + 0.3 + 0.4 * hash2(i, 2) else -0.3 - 0.4 * hash2(i, 2), maxLen * (0.4 + 0.4 * hash2(i, 3)), 5, false, 0, 0.0, reveal, r)
                    }
                    else -> stem(w * (0.05 + 0.9 * e), -3 * k, Math.PI / 2 + (hash2(i, 2) - 0.5) * 0.3, maxLen * (0.3 + 0.35 * hash2(i, 3)), 4, false, 1, 0.0, reveal, r)
                }
            }
        }
    }

    private fun weighted(gfx: GuiGraphics, m: Matrix4f, w: Double, h: Double, t: Double) {
        gfx.fillGradient(0, 0, w.toInt(), (h * 0.55).toInt(), argb(0x0A0610, 0.75), argb(0x0A0610, 0.0))
        for (i in 0 until 3) {
            val u = (t * 0.4 + i / 3.0) % 1
            val size = min(w, h) * (1.4 - 0.6 * u)
            val a = sin(Math.PI * u) * 0.55
            val lw = max(4.0, h / 40)
            val hw = size / 2 * w / h; val hh = size / 2
            val x0 = (w / 2 - hw).toInt(); val x1 = (w / 2 + hw).toInt(); val y0 = (h / 2 - hh).toInt(); val y1 = (h / 2 + hh).toInt(); val l = lw.toInt()
            val c = argb(0x140E1E, a)
            gfx.fill(x0, y0, x1, y0 + l, c); gfx.fill(x0, y1 - l, x1, y1, c)
            gfx.fill(x0, y0 + l, x0 + l, y1 - l, c); gfx.fill(x1 - l, y0 + l, x1, y1 - l, c)
        }
    }

    private fun bleeding(m: Matrix4f, w: Double, h: Double, t: Double) {
        val ph = (t % 1.2) / 1.2
        val beat = max(exp(-(ph - 0.05) * (ph - 0.05) / 0.002), 0.7 * exp(-(ph - 0.22) * (ph - 0.22) / 0.002))
        vignette(m, w, h, 0x78000A, 0.35 + 0.35 * beat)
        val s = max(10.0, (h / 22).roundToInt().toDouble())
        val drip = SpellArt.particle("drip_fall")
        RenderSystem.setShader { GameRenderer.getPositionColorShader() }
        val streaks = Tesselator.getInstance().builder
        streaks.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
        val drops = ArrayList<Triple<Double, Double, Double>>()
        for (i in 0 until 6) {
            val k = (t * 0.35 + hash2(i, 7)) % 1
            val x = w * (0.08 + 0.84 * hash2(i, 8)); val y = -s + k * k * h * 0.5
            val a = (0.5 * (1 - k) * 255).toInt()
            val sx0 = (x + s * 0.35).toFloat(); val sx1 = (x + s * 0.65).toFloat(); val sy = max(0.0, y).toFloat()
            streaks.vertex(m, sx0, 0f, 0f).color(110, 8, 16, a).endVertex(); streaks.vertex(m, sx0, sy, 0f).color(110, 8, 16, a).endVertex()
            streaks.vertex(m, sx1, sy, 0f).color(110, 8, 16, a).endVertex(); streaks.vertex(m, sx1, 0f, 0f).color(110, 8, 16, a).endVertex()
            drops += Triple(x, y, 1 - k)
        }
        BufferUploader.drawWithShader(streaks.end())
        textured(particleAtlas) { b -> for ((x, y, a) in drops) quad(b, m, drip, x + s / 2, y + s / 2, s, s, 0x8A0F18, a) }
    }

    private fun rot(m: Matrix4f, w: Double, h: Double, t: Double) {
        vignette(m, w, h, 0x3C5A1E, 0.45, 0.35)
        val s = max(8.0, (h / 30).roundToInt().toDouble())
        textured(particleAtlas) { b ->
            for (i in 0 until 14) {
                val k = (t * 0.15 + hash2(i, 3)) % 1
                quad(b, m, SpellArt.generic((k * 8).toInt().coerceIn(0, 7)), w * hash2(i, 4) + sin(t + i) * 20, h * (1 - k), s, s, Element.CORRUPTION.color, sin(Math.PI * k) * 0.9)
            }
        }
    }

    private fun discord(m: Matrix4f, w: Double, h: Double, t: Double) {
        val s = max(12.0, (h / 18).roundToInt().toDouble())
        val slot = (t * 7).toInt()
        textured(particleAtlas) { b ->
            for (i in 0 until 14) {
                if (hash2(i, slot) < 0.45) continue
                val side = i % 4
                val f = hash2(i, slot + 1)
                val x = when (side) { 0, 2 -> f * w; 1 -> w - s * 1.5; else -> s * 0.5 }
                val y = when (side) { 0 -> s * 0.5; 1, 3 -> f * h; else -> h - s * 1.5 }
                quad(b, m, SpellArt.glyph(i + slot), x + s / 2, y + s / 2, s, s, if (i % 2 != 0) 0xFFD0E4 else Element.CHAOS.color, 0.85)
            }
        }
    }

    private fun hollowedHearts(gfx: GuiGraphics, w: Int, h: Int, player: net.minecraft.world.entity.player.Player, t: Double) {
        val mc = Minecraft.getInstance()
        if (mc.gameMode?.canHurtPlayer() != true) return
        // Copies the layout and shake of `Gui.renderPlayerHealth` and `renderHearts`, so each hollow sits on a vanilla heart.
        val ticks = mc.gui.guiTicks
        val health = Mth.ceil(player.health)
        val max = maxOf(player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).toFloat(), health.toFloat())
        val absorption = Mth.ceil(player.absorptionAmount)
        val containers = Mth.ceil(max / 2.0)
        val rows = Mth.ceil((max + absorption) / 2f / 10f)
        val rowHeight = max(10 - (rows - 2), 3)
        val left = w / 2 - 91
        val bottom = h - 39
        val regen = if (player.hasEffect(net.minecraft.world.effect.MobEffects.REGENERATION)) ticks % Mth.ceil(max + 5f) else -1
        val hardcore = if (player.level().levelData.isHardcore) 45 else 0
        heartJiggle.setSeed(ticks * 312871L)
        RenderSystem.enableBlend()
        // Keep the vanilla order (last heart first, absorption included) so the random shakes match.
        for (i in containers + Mth.ceil(absorption / 2.0) - 1 downTo 0) {
            var y = bottom - i / 10 * rowHeight
            if (health + absorption <= 4) y += heartJiggle.nextInt(2)
            if (i >= containers) continue
            if (i == regen) y -= 2
            val x = left + i % 10 * 8
            gfx.blit(GUI_ICONS, x, y, 16, hardcore, 9, 9)
            gfx.fill(x + 3, y + 3, x + 6, y + 6, argb(0x783CAA, 0.35 + 0.15 * sin(t * 2 + i)))
        }
    }
}
