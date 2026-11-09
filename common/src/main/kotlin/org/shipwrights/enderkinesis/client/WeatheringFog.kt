package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.shaders.AbstractUniform
import com.mojang.blaze3d.shaders.Uniform
import com.mojang.blaze3d.vertex.PoseStack
import dev.architectury.event.events.client.ClientTickEvent
import dev.architectury.networking.NetworkManager
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.EffectInstance
import net.minecraft.core.BlockPos
import net.minecraft.world.level.LightLayer
import net.minecraft.util.Mth
import org.joml.Matrix4d
import org.joml.Matrix4f
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.mod.common.getShipManagingPos
import org.shipwrights.enderkinesis.client.volume.VolumeComposite
import org.shipwrights.enderkinesis.client.volume.VolumeEffect
import org.shipwrights.enderkinesis.client.volume.VolumeProxy
import org.shipwrights.enderkinesis.client.volume.VolumeView
import org.shipwrights.enderkinesis.registry.EKParticles
import org.shipwrights.enderkinesis.ritual.RitualWeathering
import org.shipwrights.enderkinesis.util.TICKS_PER_SECOND

/**
 * The Ritual of Weathering's yellow-green ground fog: it grows out from the rite, holds, then thins away.
 * Green rain drops out of it anywhere it covers. Render thread only.
 */
object WeatheringFog {

    private const val GROW_SECONDS = 6f
    private const val FADE_SECONDS = 8f
    private const val BELOW = 4f
    private const val ABOVE = 10f
    private const val NIGHT_FLOOR = 0.15f
    private const val BLOCK_LIGHT_SHARE = 0.7f
    private const val LIGHT_LEVELS = 15f
    // Drops a tick over the whole fog at full strength; fewer while it grows and fades.
    private const val DROPS_PER_TICK = 24f

    private class Fog(val center: BlockPos, val radius: Float, val startTick: Long, val duration: Int) {
        // Eases out, so the front slows as it nears the edge.
        fun front(now: Float): Float {
            val grow = ((now - startTick) / TICKS_PER_SECOND / GROW_SECONDS).coerceIn(0f, 1f)
            return radius * (1f - (1f - grow) * (1f - grow))
        }

        fun fade(now: Float): Float = ((startTick + duration - now) / TICKS_PER_SECOND / FADE_SECONDS).coerceIn(0f, 1f)
    }

    private val fogs = ArrayList<Fog>()
    private var fogLevel: ClientLevel? = null
    private val dropAt = BlockPos.MutableBlockPos()

    private val view = VolumeView()
    private val effect = VolumeEffect("ritual_weathering_fog")
    private var uniforms: Uniforms? = null
    private val localToView = Matrix4f()
    private val localToViewD = Matrix4d()
    private val camLocal = Vector3d()

    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, RitualWeathering.S2C_FOG) { buf, _ ->
            val center = BlockPos.of(buf.readLong())
            val radius = buf.readVarInt().toFloat()
            val duration = buf.readVarInt()
            Minecraft.getInstance().execute {
                val level = Minecraft.getInstance().level ?: return@execute
                if (level !== fogLevel) fogs.clear()
                fogLevel = level
                fogs.add(Fog(center, radius, level.gameTime, duration))
            }
        }
        ClientTickEvent.CLIENT_LEVEL_POST.register(ClientTickEvent.ClientLevel { level -> rain(level) })
    }

    private fun rain(level: ClientLevel) {
        if (fogs.isEmpty() || level !== fogLevel) return
        val now = level.gameTime.toFloat()
        val random = level.random
        for (fog in fogs) {
            val front = fog.front(now)
            val share = front / fog.radius
            val drops = DROPS_PER_TICK * share * share * fog.fade(now)
            // The fraction becomes a chance, so a thin fog still drips now and then.
            val count = drops.toInt() + if (random.nextFloat() < drops - drops.toInt()) 1 else 0
            repeat(count) {
                val r = front * Mth.sqrt(random.nextFloat())
                val angle = random.nextFloat() * Mth.TWO_PI
                val x = fog.center.x + 0.5 + r * Mth.cos(angle)
                val y = fog.center.y - BELOW + random.nextFloat() * (BELOW + ABOVE).toDouble()
                val z = fog.center.z + 0.5 + r * Mth.sin(angle)
                // The volume goes into the ground; a drop there would start inside a block.
                if (!level.getBlockState(dropAt.set(x, y, z)).isAir) return@repeat
                level.addParticle(EKParticles.weatheringRain(), x, y, z, 0.0, 0.0, 0.0)
            }
        }
    }

    // Called with the other world-space volumes, before the fog passes. The render thread reads the client ship's
    // render transform, not physics state.
    @OptIn(GameTickOnly::class)
    @JvmStatic
    fun render(poseStack: PoseStack, projection: Matrix4f, camera: Camera) {
        if (fogs.isEmpty()) return
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level
        if (level == null || level !== fogLevel) {
            fogs.clear()
            return
        }
        val now = level.gameTime + minecraft.frameTime
        fogs.removeIf { now - it.startTick > it.duration }
        if (fogs.isEmpty() || IrisCompat.isShaderPackInUse()) return
        val eff = effect.get() ?: return
        val u = uniforms ?: Uniforms(eff).also { uniforms = it }
        val main = minecraft.mainRenderTarget
        val cam = camera.position
        view.update(projection, poseStack.last().pose(), cam.x, cam.y, cam.z)
        view.pushViewProj(eff)
        view.pushInvViewProj(eff)
        u.screenSize.set(main.width.toFloat(), main.height.toFloat())
        u.time.set(((System.nanoTime() * 1e-9) % 3600.0).toFloat())
        u.colour.set(COLOUR[0], COLOUR[1], COLOUR[2])
        VolumeProxy.begin(main, VolumeComposite.Blend.PREMULTIPLIED)
        var first = true
        for (fog in fogs) {
            u.front.set(fog.front(now))
            u.fade.set(fog.fade(now))
            u.radius.set(fog.radius)
            u.brightness.set(brightness(level, fog.center, minecraft.frameTime))
            // A rite on a ship has a shipyard centre, so the fog goes through the ship transform.
            val ship = level.getShipManagingPos(fog.center) as? ClientShip
            if (ship == null) {
                localToViewD.translation(fog.center.x + 0.5 - view.camX, fog.center.y - view.camY, fog.center.z + 0.5 - view.camZ)
            } else {
                localToViewD.translation(-view.camX, -view.camY, -view.camZ).mul(ship.renderTransform.shipToWorld)
                    .translate(fog.center.x + 0.5, fog.center.y.toDouble(), fog.center.z + 0.5)
            }
            localToView.set(localToViewD)
            u.localToView.set(localToView)
            localToViewD.invertAffine().getTranslation(camLocal)
            u.camLocal.set(camLocal.x.toFloat(), camLocal.y.toFloat(), camLocal.z.toFloat())
            u.boxMin.set(-fog.radius, -BELOW, -fog.radius)
            u.boxMax.set(fog.radius, ABOVE, fog.radius)
            if (first) {
                VolumeProxy.draw(eff)
                first = false
            } else {
                u.upload()
                VolumeProxy.drawApplied()
            }
        }
        VolumeProxy.end(main, eff)
    }

    // The fog takes the light it stands in, so it does not glow at night or underground.
    private fun brightness(level: ClientLevel, center: BlockPos, partialTick: Float): Float {
        val above = center.above()
        val sky = level.getBrightness(LightLayer.SKY, above) / LIGHT_LEVELS * level.getSkyDarken(partialTick)
        val block = level.getBrightness(LightLayer.BLOCK, above) / LIGHT_LEVELS * BLOCK_LIGHT_SHARE
        return maxOf(sky, block, NIGHT_FLOOR)
    }

    private val COLOUR = floatArrayOf(0.74f, 0.82f, 0.38f)

    private class Uniforms(eff: EffectInstance) {
        val screenSize: AbstractUniform = eff.safeGetUniform("ScreenSize")
        val time: AbstractUniform = eff.safeGetUniform("Time")
        val colour: AbstractUniform = eff.safeGetUniform("FogColour")
        val localToView: AbstractUniform = eff.safeGetUniform("LocalToView")
        val camLocal: AbstractUniform = eff.safeGetUniform("CamLocal")
        val boxMin: AbstractUniform = eff.safeGetUniform("BoxMin")
        val boxMax: AbstractUniform = eff.safeGetUniform("BoxMax")
        val front: AbstractUniform = eff.safeGetUniform("Front")
        val fade: AbstractUniform = eff.safeGetUniform("Fade")
        val radius: AbstractUniform = eff.safeGetUniform("Radius")
        val brightness: AbstractUniform = eff.safeGetUniform("Brightness")
        private val perFog: List<Uniform> =
            listOf(localToView, camLocal, boxMin, boxMax, front, fade, radius, brightness).filterIsInstance<Uniform>()

        fun upload() {
            for (uniform in perFog) uniform.upload()
        }
    }
}
