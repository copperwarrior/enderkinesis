package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexSorting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.util.FastColor
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.Random
import kotlin.math.tan

/**
 * The Sselith sky without the sun, seen through the satchel's opening. In the dimension the
 * horizon glow comes from height fog; here the fog colour fills the view and the dark dome
 * fades over it, so the colour follows elevation the same way.
 */
class SatchelOfShelvingBackdrop {

    private val projection = Matrix4f()
    private val savedProjection = Matrix4f()
    private val skyPose = PoseStack()
    private val pitch = Quaternionf()

    /** Turns view-space directions into the drifting star-sphere frame the shooting stars live in. */
    private val viewToSky = Quaternionf()
    private var tanHalfHeight = 0f
    private var tanHalfWidth = 0f

    /** The mouse pan as a lens shift in normalized device coordinates. */
    private var panX = 0f
    private var panY = 0f

    // The world sky spawns over the whole sphere, so few streaks would cross this small view; each crossing is short, so they also come more often.
    private val shootingStars = SselithRepertorySky.ShootingStars(::startInView, SHOOTING_STAR_RATE)

    /**
     * [shelfScroll] is the shelf offset in GUI pixels; the sky follows it at [SCROLL_PARALLAX].
     * The sky also slides with the mouse, relative to the centre of the opening, at [MOUSE_PARALLAX].
     */
    fun render(
        graphics: GuiGraphics, x: Int, y: Int, width: Int, height: Int,
        shelfScroll: Float, mouseX: Int, mouseY: Int, partialTick: Float,
    ) {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return
        graphics.fill(x, y, x + width, y + height, HORIZON_COLOR_DIMMED)
        // The sky draws straight to GL, so the GUI batch must go out first.
        graphics.flush()

        val scale = minecraft.window.guiScale
        val target = minecraft.mainRenderTarget
        val left = (x * scale).toInt()
        val top = (y * scale).toInt()
        val pixelWidth = ((x + width) * scale).toInt() - left
        val pixelHeight = ((y + height) * scale).toInt() - top
        // GL viewports count from the bottom of the framebuffer.
        RenderSystem.viewport(left, target.height - top - pixelHeight, pixelWidth, pixelHeight)

        val aspect = pixelWidth.toFloat() / pixelHeight
        // A lens shift slides the image without turning the view, as the sky has no parallax of its own.
        panX = 2f * (mouseX - (x + width / 2f)) * MOUSE_PARALLAX / width
        panY = -2f * (mouseY - (y + height / 2f)) * MOUSE_PARALLAX / height
        savedProjection.set(RenderSystem.getProjectionMatrix())
        val savedSorting = RenderSystem.getVertexSorting()
        projection.translation(panX, panY, 0f).perspective(Math.toRadians(FOV_DEGREES.toDouble()).toFloat(), aspect, NEAR, FAR)
        RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN)
        val modelView = RenderSystem.getModelViewStack()
        modelView.pushPose()
        modelView.setIdentity()
        RenderSystem.applyModelViewMatrix()

        RenderSystem.disableDepthTest()
        RenderSystem.depthMask(false)
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.disableCull()
        RenderSystem.setShaderColor(BRIGHTNESS, BRIGHTNESS, BRIGHTNESS, 1f)

        // Scrolling down slides the sky up; past the horizon it keeps turning round the sphere.
        val elevation = BASE_ELEVATION_DEGREES - shelfScroll * SCROLL_PARALLAX * FOV_DEGREES / height
        pitch.rotationX(Math.toRadians(-elevation.toDouble()).toFloat())
        skyPose.setIdentity()
        skyPose.mulPose(pitch)

        val gameTime = level.gameTime + partialTick.toDouble()
        val driftRadians = Math.toRadians(SselithRepertorySky.driftDegrees(gameTime).toDouble()).toFloat()
        viewToSky.set(pitch).rotateY(driftRadians).conjugate()
        tanHalfHeight = tan(Math.toRadians(FOV_DEGREES / 2.0).toFloat())
        tanHalfWidth = tanHalfHeight * aspect

        SselithRepertorySky.renderHorizonFade(skyPose)
        SselithRepertorySky.renderStarfield(skyPose, gameTime, shootingStars)

        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
        RenderSystem.enableCull()
        RenderSystem.depthMask(true)
        RenderSystem.enableDepthTest()
        modelView.popPose()
        RenderSystem.applyModelViewMatrix()
        RenderSystem.setProjectionMatrix(savedProjection, savedSorting)
        RenderSystem.viewport(0, 0, target.width, target.height)
    }

    /** A start point in the upper half of the opening, so the downward streak crosses it. */
    private fun startInView(random: Random, out: Vector3f) {
        val viewX = (random.nextFloat() * 2f - 1f - panX) * tanHalfWidth
        val viewY = (random.nextFloat() - panY) * tanHalfHeight
        viewToSky.transform(out.set(viewX, viewY, -1f).normalize())
    }

    private companion object {
        /** Scales the whole sky; the full fog colour is too bright behind the shelf. */
        const val BRIGHTNESS = 0.6f

        val HORIZON_COLOR_DIMMED = FastColor.ARGB32.color(
            255,
            (SselithFog.red() * BRIGHTNESS * 255f).toInt(),
            (SselithFog.green() * BRIGHTNESS * 255f).toInt(),
            (SselithFog.blue() * BRIGHTNESS * 255f).toInt(),
        )

        /** Vertical field of view. Narrower makes fewer, larger stars; the world sky is about 70°. */
        const val FOV_DEGREES = 30f

        /** Elevation of the view centre at the top of the shelf. */
        const val BASE_ELEVATION_DEGREES = 35f

        const val SCROLL_PARALLAX = 0.5f
        const val MOUSE_PARALLAX = 1f / 16f
        const val SHOOTING_STAR_RATE = 3f

        const val NEAR = 0.05f
        const val FAR = 400f
    }
}
