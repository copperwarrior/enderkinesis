package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.shaders.AbstractUniform
import com.mojang.blaze3d.vertex.PoseStack
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import net.minecraft.Util
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.client.renderer.EffectInstance
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Player
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.client.volume.VolumeComposite
import org.shipwrights.enderkinesis.client.volume.VolumeEffect
import org.shipwrights.enderkinesis.client.volume.VolumeProxy
import org.shipwrights.enderkinesis.client.volume.VolumeView
import org.shipwrights.enderkinesis.item.IncantationOfWindsItem
import org.shipwrights.enderkinesis.mixin.ParticleVelocityAccessor
import kotlin.math.abs
import kotlin.math.sqrt

/** Glowing gust volume in the Incantation of Winds cone, from each caster's hand. Render thread only. */
object WindConeVolume {
    private const val MAX_CONES_PER_FRAME = 8
    private const val MAX_VIEW_DISTANCE_SQR = 96.0 * 96.0

    /** Per-tick strength change; the rise matches the cast-pose ramp. */
    private const val RAMP_IN = 0.1f
    private const val FADE_OUT = 0.2f

    private const val APEX_RADIUS = 0.15f

    /** The box starts behind the hand, so the fade-in near the apex is not clipped. */
    private const val BOX_BEHIND = 0.5f

    private const val COLOUR_R = 1.00f
    private const val COLOUR_G = 0.92f
    private const val COLOUR_B = 0.55f
    private const val EMISSION = 0.35f
    private const val ABSORPTION = 0.15f
    private const val FLOW_SPEED = 25f
    private const val TWIST = 0.15f
    private const val SPIN_RATE = 1.5f

    /** Time constant of the tail direction's lag behind the aim. */
    private const val TAIL_LAG_SECONDS = 0.35

    /** Caps the bend (lateral offset per block at the far end), so a fast turn cannot fold the cone back. */
    private const val MAX_BEND_SLOPE = 0.8
    private const val MIN_TAIL_AXIAL = 0.5

    /** Distance from the palm over which the volume fades in. Long for the caster's own first-person view,
     *  so no bright blob sits at the camera; short for other views, so the stream visibly leaves the hand. */
    private const val FADE_IN_FIRST_PERSON = 2.5f
    private const val FADE_IN_OTHER = 0.4f

    private class Cone(val startMillis: Long) {
        var casting = false
        var strength = 0f
        var prevStrength = 0f
        var apexX = 0.0
        var apexY = 0.0
        var apexZ = 0.0
        var axisX = 1.0
        var axisY = 0.0
        var axisZ = 0.0
        var tailX = 0.0
        var tailY = 0.0
        var tailZ = 0.0
        var lastMillis = startMillis
    }

    /** Particle push, blocks/tick² at the apex; with typical particle friction it settles near 1 block/tick. */
    private const val PARTICLE_PUSH = 0.04
    private const val PARTICLE_CONE_APEX_RADIUS = 0.5
    private const val PARTICLE_FAR_FRACTION = 0.25

    private val cones = Int2ObjectOpenHashMap<Cone>()
    private var anyCasting = false
    private var lastLevel: ClientLevel? = null

    private val view = VolumeView()
    private val effect = VolumeEffect("wind_cone_volume")
    private var uniforms: Uniforms? = null
    private val localToView = Matrix4f()

    fun tick(level: ClientLevel) {
        if (level !== lastLevel) {
            cones.clear()
            lastLevel = level
        }
        for (cone in cones.values) cone.casting = false
        for (player in level.players()) {
            if (!IncantationOfWindsClient.isCastingWinds(player, player.usedItemHand)) continue
            val isNew = !cones.containsKey(player.id)
            val cone = cones.computeIfAbsent(player.id) { Cone(Util.getMillis()) }
            cone.casting = true
            // Kept for the fade-out, when the player no longer gives a pose.
            val hand = IncantationOfWindsClient.castingPalm(player, 1f).position
            val axis = IncantationOfWindsClient.streamAxis(player, hand, 1f)
            if (isNew) {
                cone.tailX = axis.x
                cone.tailY = axis.y
                cone.tailZ = axis.z
            }
            cone.apexX = hand.x
            cone.apexY = hand.y
            cone.apexZ = hand.z
            cone.axisX = axis.x
            cone.axisY = axis.y
            cone.axisZ = axis.z
        }
        anyCasting = cones.values.any { it.casting }
        val it = cones.values.iterator()
        while (it.hasNext()) {
            val cone = it.next()
            cone.prevStrength = cone.strength
            cone.strength = if (cone.casting) {
                (cone.strength + RAMP_IN).coerceAtMost(1f)
            } else {
                (cone.strength - FADE_OUT).coerceAtLeast(0f)
            }
            if (cone.strength <= 0f && cone.prevStrength <= 0f) it.remove()
        }
    }

    /** Adds the wind of every cast cone that holds the particle to its velocity. The stream's own particles
     *  and the lattice ocean particles keep their scripted motion. */
    @JvmStatic
    fun pushParticle(particle: Particle) {
        if (!anyCasting) return
        if (particle is WindConeParticle || particle is WindDustParticle || particle is OceanParticle) return
        val pos = particle.boundingBox
        val px = (pos.minX + pos.maxX) * 0.5
        val py = (pos.minY + pos.maxY) * 0.5
        val pz = (pos.minZ + pos.maxZ) * 0.5
        val range = IncantationOfWindsItem.RANGE
        val tanHalf = IncantationOfWindsItem.TAN_HALF_ANGLE
        var ax = 0.0
        var ay = 0.0
        var az = 0.0
        for (cone in cones.values) {
            if (!cone.casting) continue
            val vx = px - cone.apexX
            val vy = py - cone.apexY
            val vz = pz - cone.apexZ
            val along = vx * cone.axisX + vy * cone.axisY + vz * cone.axisZ
            if (along < 0.0 || along > range) continue
            val distSq = vx * vx + vy * vy + vz * vz
            val radius = along * tanHalf + PARTICLE_CONE_APEX_RADIUS
            if (distSq - along * along > radius * radius) continue
            val dist = sqrt(distSq)
            if (dist < 1.0e-3) continue
            val push = PARTICLE_PUSH * cone.strength * (1.0 - along / range * (1.0 - PARTICLE_FAR_FRACTION)) / dist
            ax += vx * push
            ay += vy * push
            az += vz * push
        }
        if (ax == 0.0 && ay == 0.0 && az == 0.0) return
        val v = particle as ParticleVelocityAccessor
        v.`enderkinesis$setXd`(v.`enderkinesis$getXd`() + ax)
        v.`enderkinesis$setYd`(v.`enderkinesis$getYd`() + ay)
        v.`enderkinesis$setZd`(v.`enderkinesis$getZd`() + az)
    }

    // Called at renderLevel TAIL, with the other volumes: the volume writes no depth.
    @JvmStatic
    fun render(poseStack: PoseStack, projection: Matrix4f, camera: Camera, partialTick: Float) {
        IncantationOfWindsClient.noteWorldProjection(projection)
        if (cones.isEmpty() || IrisCompat.isShaderPackInUse()) return
        val level = Minecraft.getInstance().level ?: return
        val eff = effect.get() ?: return
        val u = uniforms ?: Uniforms(eff).also { uniforms = it }
        val main = Minecraft.getInstance().mainRenderTarget
        val cam = camera.position
        view.update(projection, poseStack.last().pose(), cam.x, cam.y, cam.z)
        u.pushFrame(main.width, main.height)
        val now = Util.getMillis()

        var drawn = 0
        for (entry in cones.int2ObjectEntrySet()) {
            if (drawn >= MAX_CONES_PER_FRAME) break
            val cone = entry.value
            val strength = Mth.lerp(partialTick, cone.prevStrength, cone.strength)
            if (strength <= 0f) continue
            val player = level.getEntity(entry.intKey) as? Player
            if (player != null && cone.casting) followHand(cone, player, partialTick)
            lagTail(cone, now)
            if (!pushCone(u, cone)) continue
            val ownEyes = player != null && IncantationOfWindsClient.isViewedFromOwnEyes(player)
            u.fadeIn.set(if (ownEyes) FADE_IN_FIRST_PERSON else FADE_IN_OTHER)
            u.strength.set(strength)
            u.time.set((now - cone.startMillis) / 1000f)
            if (drawn == 0) VolumeProxy.begin(main, VolumeComposite.Blend.PREMULTIPLIED)
            VolumeProxy.draw(eff)
            drawn++
        }
        if (drawn > 0) VolumeProxy.end(main, eff)
    }

    /** The tick pose lags the camera at high frame rates, so a live caster is posed at the partial tick. */
    private fun followHand(cone: Cone, player: Player, partialTick: Float) {
        val hand = IncantationOfWindsClient.castingPalm(player, partialTick).position
        val axis = IncantationOfWindsClient.streamAxis(player, hand, partialTick)
        cone.apexX = hand.x
        cone.apexY = hand.y
        cone.apexZ = hand.z
        cone.axisX = axis.x
        cone.axisY = axis.y
        cone.axisZ = axis.z
    }

    /** Eases the tail direction toward the aim, frame-rate independent. */
    private fun lagTail(cone: Cone, now: Long) {
        val dt = (now - cone.lastMillis) / 1000.0
        cone.lastMillis = now
        val k = 1.0 - kotlin.math.exp(-dt / TAIL_LAG_SECONDS)
        val x = cone.tailX + (cone.axisX - cone.tailX) * k
        val y = cone.tailY + (cone.axisY - cone.tailY) * k
        val z = cone.tailZ + (cone.axisZ - cone.tailZ) * k
        val len = sqrt(x * x + y * y + z * z)
        // A lerp across a full reversal passes through zero; keep the aim then.
        if (len < 1.0e-3) {
            cone.tailX = cone.axisX
            cone.tailY = cone.axisY
            cone.tailZ = cone.axisZ
        } else {
            cone.tailX = x / len
            cone.tailY = y / len
            cone.tailZ = z / len
        }
    }

    /** False when the cone is out of range. */
    private fun pushCone(u: Uniforms, cone: Cone): Boolean {
        val sx = cone.apexX - view.camX
        val sy = cone.apexY - view.camY
        val sz = cone.apexZ - view.camZ
        if (sx * sx + sy * sy + sz * sz > MAX_VIEW_DISTANCE_SQR) return false

        // Cone axes: X along the stream, Y and Z across it.
        val xx = cone.axisX
        val xy = cone.axisY
        val xz = cone.axisZ
        // Y = X × up, or X × east when the stream is near vertical.
        var yx: Double
        var yy: Double
        var yz: Double
        if (abs(xy) > 0.99) {
            yx = 0.0
            yy = xz
            yz = -xy
        } else {
            yx = -xz
            yy = 0.0
            yz = xx
        }
        val yLen = sqrt(yx * yx + yy * yy + yz * yz)
        yx /= yLen
        yy /= yLen
        yz /= yLen
        val zx = xy * yz - xz * yy
        val zy = xz * yx - xx * yz
        val zz = xx * yy - xy * yx

        // The tail direction in cone axes, as a slope across the aim.
        val tailAxial = maxOf(cone.tailX * xx + cone.tailY * xy + cone.tailZ * xz, MIN_TAIL_AXIAL)
        var bendY = (cone.tailX * yx + cone.tailY * yy + cone.tailZ * yz) / tailAxial
        var bendZ = (cone.tailX * zx + cone.tailY * zy + cone.tailZ * zz) / tailAxial
        val bendLen = sqrt(bendY * bendY + bendZ * bendZ)
        if (bendLen > MAX_BEND_SLOPE) {
            bendY *= MAX_BEND_SLOPE / bendLen
            bendZ *= MAX_BEND_SLOPE / bendLen
        }
        u.bend.set(bendY.toFloat(), bendZ.toFloat())

        val range = IncantationOfWindsItem.RANGE.toFloat()
        val radius = range * IncantationOfWindsItem.TAN_HALF_ANGLE.toFloat() + APEX_RADIUS
        val endY = (bendY * range).toFloat()
        val endZ = (bendZ * range).toFloat()
        u.boxMin.set(-BOX_BEHIND, -radius + minOf(0f, endY), -radius + minOf(0f, endZ))
        u.boxMax.set(range, radius + maxOf(0f, endY), radius + maxOf(0f, endZ))
        localToView.set(
            xx.toFloat(), xy.toFloat(), xz.toFloat(), 0f,
            yx.toFloat(), yy.toFloat(), yz.toFloat(), 0f,
            zx.toFloat(), zy.toFloat(), zz.toFloat(), 0f,
            sx.toFloat(), sy.toFloat(), sz.toFloat(), 1f,
        )
        u.localToView.set(localToView)
        u.camLocal.set(
            -(sx * xx + sy * xy + sz * xz).toFloat(),
            -(sx * yx + sy * yy + sz * yz).toFloat(),
            -(sx * zx + sy * zy + sz * zz).toFloat(),
        )
        return true
    }

    private class Uniforms(private val eff: EffectInstance) {
        val boxMin: AbstractUniform = eff.safeGetUniform("BoxMin")
        val boxMax: AbstractUniform = eff.safeGetUniform("BoxMax")
        val localToView: AbstractUniform = eff.safeGetUniform("LocalToView")
        val camLocal: AbstractUniform = eff.safeGetUniform("CamLocal")
        val strength: AbstractUniform = eff.safeGetUniform("Strength")
        val time: AbstractUniform = eff.safeGetUniform("Time")
        val bend: AbstractUniform = eff.safeGetUniform("Bend")
        val fadeIn: AbstractUniform = eff.safeGetUniform("FadeIn")

        fun pushFrame(width: Int, height: Int) {
            view.pushViewProj(eff)
            view.pushInvViewProj(eff)
            eff.safeGetUniform("ScreenSize").set(width.toFloat(), height.toFloat())
            eff.safeGetUniform("Range").set(IncantationOfWindsItem.RANGE.toFloat())
            eff.safeGetUniform("TanHalf").set(IncantationOfWindsItem.TAN_HALF_ANGLE.toFloat())
            eff.safeGetUniform("ApexRadius").set(APEX_RADIUS)
            eff.safeGetUniform("Colour").set(COLOUR_R, COLOUR_G, COLOUR_B)
            eff.safeGetUniform("Emission").set(EMISSION)
            eff.safeGetUniform("Absorption").set(ABSORPTION)
            eff.safeGetUniform("FlowSpeed").set(FLOW_SPEED)
            eff.safeGetUniform("Twist").set(TWIST)
            eff.safeGetUniform("SpinRate").set(SPIN_RATE)
        }
    }
}
