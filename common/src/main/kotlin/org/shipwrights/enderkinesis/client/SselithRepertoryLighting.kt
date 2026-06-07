package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.core.Direction
import net.minecraft.util.Mth
import org.joml.Matrix4f
import org.joml.Vector3f
import org.shipwrights.enderkinesis.dimension.SselithRepertory
import kotlin.math.cos
import kotlin.math.sin

/**
 * Lighting overrides for [org.shipwrights.enderkinesis.dimension.SselithRepertory].
 *
 * Three coordinated effects, all driven from the same fixed-sun direction:
 *
 *  1. **Custom lightmap** ([writeLightmap]) — re-tints the 16×16 lightmap so sky-light
 *     cells are yellow (matching the visible sun) and block-light cells stay warm-white
 *     (vanilla-like torch colour). Sky and block contributions are *added*, not max'd,
 *     so mixed cells blend naturally and a torch in a dark room keeps its warm identity.
 *
 *  2. **Per-face sky-light attenuation** ([attenuateSkyLight]) — only the sky-light
 *     nibble of the per-face packed lightmap UV is reduced based on `dot(face_normal,
 *     sunDirection)`. Block-light is *untouched*, so torches stay full brightness on
 *     shadow faces. This is the actual directional shading, applied by
 *     `ModelBlockRendererFlatSselithMixin` (flat lighting) and `ModelBlockRendererAOSselithMixin`
 *     (smooth/AO lighting).
 *
 *  3. **Sun-aimed entity diffuse** ([fillShaderLights]) — view-space light vectors for
 *     `RenderSystem.setShaderLights`, so entities/items shade against the sun direction.
 *
 * The sun graphic itself is drawn by [SselithRepertorySky]; this file derives the
 * matching direction by applying the same XYZ rotation sequence the sky quad uses.
 */
object SselithRepertoryLighting {

    // ---- lightmap tints ----

    /** Pure-sky cell tint — warm yellow, matching the visible Sselith sun. */
    private const val SKY_TINT_R = 1.00f
    private const val SKY_TINT_G = 0.92f
    private const val SKY_TINT_B = 0.55f

    /** Pure-block cell tint — vanilla torch colour so block lights stay recognisable. */
    private const val BLOCK_TINT_R = 1.00f
    private const val BLOCK_TINT_G = 0.85f
    private const val BLOCK_TINT_B = 0.60f

    /** Floor on the sky-light gradient at level 0 so totally-shadowed cells aren't black. */
    private const val SKY_FLOOR = 0.05f

    /** Opaque pure-white ABGR pixel value used for the FULL_BRIGHT slot. */
    private const val FULL_BRIGHT_WHITE = 0xFFFFFFFF.toInt()

    // ---- per-face sky-light attenuation ----

    /** Minimum sky-light multiplier for a face pointing directly away from the sun.
     *  Range [SKY_ATTEN_MIN, 1.0] is applied multiplicatively to the sky-light *nibble*
     *  of the per-face packed UV, leaving block-light untouched. Smaller → harsher
     *  directional cast; larger → gentler. */
    private const val SKY_ATTEN_MIN = 0.30f

    // ---- entity diffuse lights ----

    private const val SUN_LIGHT_STRENGTH = 1.0f
    private const val FILL_LIGHT_STRENGTH = 0.25f

    // ---- sun direction ----

    /** World-space unit vector pointing from the player to the sun. Cached. */
    val sunDirection: Vector3f by lazy { computeSunDirection() }

    /**
     * Reconstruct the world-space direction of the sun graphic.
     *
     * `SselithRepertorySky.renderSun` applies, in source order:
     * `poseStack.mulPose(XP); mulPose(YP); mulPose(ZP)`. `PoseStack.mulPose` *left*-
     * multiplies, so when those rotations are applied to vertex coordinates the order
     * reverses: vertices undergo **Z first, then Y, then X**. The earlier
     * implementation applied X-then-Y-then-Z to (0, 1, 0), which produced the wrong
     * direction (sun appeared to come from "up" instead of from the sun's actual
     * south-west-ish-up sky position).
     */
    private fun computeSunDirection(): Vector3f {
        val rad = Math.toRadians(SselithRepertorySky.SUN_ROTATION_DEG.toDouble()).toFloat()
        val c = cos(rad)
        val s = sin(rad)

        var x = 0f; var y = 1f; var z = 0f

        // ZP rotation: x' = x·c − y·s, y' = x·s + y·c
        var nx = x * c - y * s
        var ny = x * s + y * c
        x = nx; y = ny

        // YP rotation: x' = x·c + z·s, z' = −x·s + z·c
        nx = x * c + z * s
        var nz = -x * s + z * c
        x = nx; z = nz

        // XP rotation: y' = y·c − z·s, z' = y·s + z·c
        ny = y * c - z * s
        nz = y * s + z * c
        y = ny; z = nz

        return Vector3f(x, y, z).normalize()
    }

    fun isInRepertory(): Boolean {
        val level = Minecraft.getInstance().level ?: return false
        return level.dimension() == SselithRepertory.LEVEL_KEY
    }

    // ---- lightmap writer ----

    /**
     * Fill the 16×16 lightmap so sky-light contributions read as yellow and block-light
     * contributions stay warm-torch. The two are combined additively per channel,
     * clamped at 1.0.
     *
     * (15, 15) → pure white because that slot is sampled by GUI text / HUD / held-item
     * overlays as `LightTexture.FULL_BRIGHT`.
     */
    fun writeLightmap(image: NativeImage) {
        for (skyLight in 0..15) {
            for (blockLight in 0..15) {
                if (blockLight == 15 && skyLight == 15) {
                    image.setPixelRGBA(blockLight, skyLight, FULL_BRIGHT_WHITE)
                    continue
                }

                val sky = skyLight / 15f
                val block = blockLight / 15f
                val skyB = SKY_FLOOR + (1f - SKY_FLOOR) * sky
                val blockB = block * block * 0.96f + block * 0.04f

                val r = Mth.clamp(skyB * SKY_TINT_R + blockB * BLOCK_TINT_R, 0f, 1f)
                val g = Mth.clamp(skyB * SKY_TINT_G + blockB * BLOCK_TINT_G, 0f, 1f)
                val b = Mth.clamp(skyB * SKY_TINT_B + blockB * BLOCK_TINT_B, 0f, 1f)

                val ri = (r * 255f).toInt()
                val gi = (g * 255f).toInt()
                val bi = (b * 255f).toInt()
                val packed = (0xFF shl 24) or (bi shl 16) or (gi shl 8) or ri
                image.setPixelRGBA(blockLight, skyLight, packed)
            }
        }
    }

    // ---- per-face sky-light attenuation ----

    /**
     * Reduce the **sky-light** portion of a packed `(block_light, sky_light)` UV based
     * on how aligned the face's normal is with the sun. Block-light is untouched, so
     * this affects only sky-driven lighting.
     *
     * Packed layout (from `LightTexture.pack`): block_light_level in bits 4–7,
     * sky_light_level in bits 20–23.
     *
     * Curve: hemispheric wrap `0.5 + 0.5 · dot(normal, sunDir)` mapped into
     * `[SKY_ATTEN_MIN, 1.0]`. Sun-aligned face → unattenuated. Sun-opposite face →
     * sky-light scaled to `SKY_ATTEN_MIN`. Mid-faces → smooth interpolation.
     */
    @JvmStatic
    fun attenuateSkyLight(packedLight: Int, direction: Direction): Int {
        val sd = sunDirection
        val dot = direction.stepX * sd.x + direction.stepY * sd.y + direction.stepZ * sd.z
        val wrap = 0.5f + 0.5f * dot
        val factor = SKY_ATTEN_MIN + (1f - SKY_ATTEN_MIN) * wrap

        val skyLightLevel = (packedLight ushr 20) and 0xF
        val newSkyLevel = (skyLightLevel * factor).toInt().coerceIn(0, 15)

        // Clear bits 20–23, then OR in the new sky-light nibble at that position.
        val cleared = packedLight and (0xF shl 20).inv()
        return cleared or (newSkyLevel shl 20)
    }

    // ---- entity diffuse lights ----

    /** Two view-space unit vectors written into the shader light slots. */
    fun fillShaderLights(viewMatrix: Matrix4f, outSun: Vector3f, outFill: Vector3f) {
        outSun.set(sunDirection).mul(SUN_LIGHT_STRENGTH)
        outFill.set(-sunDirection.x, -sunDirection.y * 0.5f, -sunDirection.z).normalize()
            .mul(FILL_LIGHT_STRENGTH)
        viewMatrix.transformDirection(outSun)
        viewMatrix.transformDirection(outFill)
    }
}
