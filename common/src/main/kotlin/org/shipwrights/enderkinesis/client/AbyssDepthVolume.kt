package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.shaders.AbstractUniform
import com.mojang.blaze3d.vertex.PoseStack
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import net.minecraft.Util
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.EffectInstance
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.client.volume.VolumeComposite
import org.shipwrights.enderkinesis.client.volume.VolumeEffect
import org.shipwrights.enderkinesis.client.volume.VolumeProxy
import org.shipwrights.enderkinesis.client.volume.VolumeView
import org.shipwrights.enderkinesis.item.IncantationOfTheAbyssItem
import kotlin.math.exp

/** Ink sphere of the Incantation of the Abyss at each caster's focus. Render thread only. */
object AbyssDepthVolume {
    private const val MAX_SPHERES_PER_FRAME = 8
    private const val MAX_VIEW_DISTANCE_SQR = 128.0 * 128.0

    /** Per-tick strength change; the rise matches the cast-pose ramp. */
    private const val RAMP_IN = 0.1f
    private const val FADE_OUT = 0.15f

    /** Time constant of the centre's lag behind the focus, so the sphere glides instead of jumping between walls. */
    private const val FOLLOW_SECONDS = 0.12

    private val RADIUS = IncantationOfTheAbyssItem.RADIUS.toFloat()

    private class Sphere(val startMillis: Long) {
        var casting = false
        var strength = 0f
        var prevStrength = 0f
        var x = 0.0
        var y = 0.0
        var z = 0.0
        var lastMillis = startMillis
    }

    private val spheres = Int2ObjectOpenHashMap<Sphere>()
    private var lastLevel: ClientLevel? = null

    private val view = VolumeView()
    private val effect = VolumeEffect("abyss_depth_volume")
    private var uniforms: Uniforms? = null
    private val localToView = Matrix4f()

    fun tick(level: ClientLevel) {
        if (level !== lastLevel) {
            spheres.clear()
            lastLevel = level
        }
        for (sphere in spheres.values) sphere.casting = false
        for (player in level.players()) {
            if (!isCastingAbyss(player)) continue
            val isNew = !spheres.containsKey(player.id)
            val sphere = spheres.computeIfAbsent(player.id) { Sphere(Util.getMillis()) }
            sphere.casting = true
            if (isNew) {
                val focus = IncantationOfTheAbyssItem.focus(level, player, 1f)
                sphere.x = focus.x
                sphere.y = focus.y
                sphere.z = focus.z
            }
        }
        val it = spheres.values.iterator()
        while (it.hasNext()) {
            val sphere = it.next()
            sphere.prevStrength = sphere.strength
            sphere.strength = if (sphere.casting) {
                (sphere.strength + RAMP_IN).coerceAtMost(1f)
            } else {
                (sphere.strength - FADE_OUT).coerceAtLeast(0f)
            }
            if (sphere.strength <= 0f && sphere.prevStrength <= 0f) it.remove()
        }
    }

    /** The drawn, smoothed cloud centre of a caster, or null when there is no cloud. */
    fun centreOf(playerId: Int): Vec3? = spheres.get(playerId)?.let { Vec3(it.x, it.y, it.z) }

    fun isCastingAbyss(player: Player): Boolean {
        val hand = player.usedItemHand
        return IncantationOfWindsClient.isCastingIncantation(player, hand) &&
            player.getItemInHand(hand).item is IncantationOfTheAbyssItem
    }

    // Called at renderLevel TAIL, with the other volumes: the volume writes no depth.
    @JvmStatic
    fun render(poseStack: PoseStack, projection: Matrix4f, camera: Camera, partialTick: Float) {
        if (spheres.isEmpty() || IrisCompat.isShaderPackInUse()) return
        val level = Minecraft.getInstance().level ?: return
        val eff = effect.get() ?: return
        val u = uniforms ?: Uniforms(eff).also { uniforms = it }
        val main = Minecraft.getInstance().mainRenderTarget
        val cam = camera.position
        view.update(projection, poseStack.last().pose(), cam.x, cam.y, cam.z)
        u.pushFrame(main.width, main.height)
        val now = Util.getMillis()

        var drawn = 0
        for (entry in spheres.int2ObjectEntrySet()) {
            if (drawn >= MAX_SPHERES_PER_FRAME) break
            val sphere = entry.value
            val strength = Mth.lerp(partialTick, sphere.prevStrength, sphere.strength)
            if (strength <= 0f) continue
            val player = level.getEntity(entry.intKey) as? Player
            if (player != null && sphere.casting) follow(sphere, player, partialTick, now)
            sphere.lastMillis = now
            val sx = sphere.x - cam.x
            val sy = sphere.y - cam.y
            val sz = sphere.z - cam.z
            if (sx * sx + sy * sy + sz * sz > MAX_VIEW_DISTANCE_SQR) continue
            // Local axes are world axes, so the box does not turn.
            u.localToView.set(localToView.translation(sx.toFloat(), sy.toFloat(), sz.toFloat()))
            u.camLocal.set(-sx.toFloat(), -sy.toFloat(), -sz.toFloat())
            u.strength.set(strength)
            u.time.set((now - sphere.startMillis) / MILLIS_PER_SECOND)
            if (drawn == 0) VolumeProxy.begin(main, VolumeComposite.Blend.PREMULTIPLIED)
            VolumeProxy.draw(eff)
            drawn++
        }
        if (drawn > 0) VolumeProxy.end(main, eff)
    }

    /** Eases the centre toward the focus, frame-rate independent. */
    private fun follow(sphere: Sphere, player: Player, partialTick: Float, now: Long) {
        val focus = IncantationOfTheAbyssItem.focus(player.level(), player, partialTick)
        val k = 1.0 - exp(-(now - sphere.lastMillis) / MILLIS_PER_SECOND / FOLLOW_SECONDS)
        sphere.x += (focus.x - sphere.x) * k
        sphere.y += (focus.y - sphere.y) * k
        sphere.z += (focus.z - sphere.z) * k
    }

    private const val MILLIS_PER_SECOND = 1000f

    private class Uniforms(private val eff: EffectInstance) {
        val localToView: AbstractUniform = eff.safeGetUniform("LocalToView")
        val camLocal: AbstractUniform = eff.safeGetUniform("CamLocal")
        val strength: AbstractUniform = eff.safeGetUniform("Strength")
        val time: AbstractUniform = eff.safeGetUniform("Time")

        fun pushFrame(width: Int, height: Int) {
            view.pushViewProj(eff)
            view.pushInvViewProj(eff)
            eff.safeGetUniform("ScreenSize").set(width.toFloat(), height.toFloat())
            eff.safeGetUniform("Radius").set(RADIUS)
            eff.safeGetUniform("BoxMin").set(-RADIUS, -RADIUS, -RADIUS)
            eff.safeGetUniform("BoxMax").set(RADIUS, RADIUS, RADIUS)
        }
    }
}
