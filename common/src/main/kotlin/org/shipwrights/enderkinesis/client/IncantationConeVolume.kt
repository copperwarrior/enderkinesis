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
import java.util.function.IntSupplier
import net.minecraft.core.BlockPos
import net.minecraft.world.level.LightLayer
import org.shipwrights.enderkinesis.client.volume.VolumeComposite
import org.shipwrights.enderkinesis.client.volume.VolumeEffect
import org.shipwrights.enderkinesis.client.volume.VolumeProxy
import org.shipwrights.enderkinesis.client.volume.VolumeView
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.item.IncantationOfTheRiverItem
import org.shipwrights.enderkinesis.item.IncantationOfWindsItem
import org.shipwrights.enderkinesis.mixin.ParticleVelocityAccessor
import kotlin.math.abs
import kotlin.math.sqrt
import org.joml.Vector3d

/** Volumes from each caster's hand: the Winds gust cone and the River's twisted water column. Render thread only. */
object IncantationConeVolume {
    private const val MAX_CONES_PER_FRAME = 8
    private const val MAX_VIEW_DISTANCE_SQR = 96.0 * 96.0

    /** Per-tick strength change; the rise matches the cast-pose ramp. */
    private const val RAMP_IN = 0.1f
    private const val FADE_OUT = 0.2f

    /** The box starts behind the hand, so the fade-in near the apex is not clipped. */
    private const val BOX_BEHIND = 0.5f

    /** Time constant of the tail direction's lag behind the aim. */
    private const val TAIL_LAG_SECONDS = 0.35

    /** Caps the bend (lateral offset per block at the far end), so a fast turn cannot fold the cone back. */
    private const val MAX_BEND_SLOPE = 0.8
    private const val MIN_TAIL_AXIAL = 0.5

    /** Keeps the box valid when the jet hits something at the palm. */
    private const val MIN_JET_LENGTH = 0.5

    /** [fadeInFirstPerson] is long, so no bright blob sits at the caster's own camera; [fadeInOther] is short, so the
     *  stream visibly leaves the hand. [fadeOutStart] is the share of the length where the far fade begins. */
    private class Style(
        val tanHalf: Float,
        val apexRadius: Float,
        val r: Float, val g: Float, val b: Float,
        val emission: Float,
        val absorption: Float,
        val flowSpeed: Float,
        val twist: Float,
        val spinRate: Float,
        val fadeOutStart: Float,
        val fadeInFirstPerson: Float,
        val fadeInOther: Float,
        /** The River column: its own shader, straight, refracting a scene copy taken just before it draws. */
        val column: Boolean = false,
    )

    private val WINDS = Style(
        tanHalf = IncantationOfWindsItem.TAN_HALF_ANGLE.toFloat(), apexRadius = 0.15f,
        r = 1.00f, g = 0.92f, b = 0.55f, emission = 0.35f, absorption = 0.15f,
        flowSpeed = 25f, twist = 0.15f, spinRate = 1.5f,
        fadeOutStart = 0.4f, fadeInFirstPerson = 2.5f, fadeInOther = 0.4f,
    )

    private fun red(rgb: Int) = (rgb shr 16 and 0xFF) / 255f
    private fun green(rgb: Int) = (rgb shr 8 and 0xFF) / 255f
    private fun blue(rgb: Int) = (rgb and 0xFF) / 255f

    /** Only the colour and the fades apply; the column shader holds the rest of its look. */
    private val RIVER = Style(
        tanHalf = 0f, apexRadius = 0f,
        r = red(IncantationOfTheRiverItem.WATER_COLOUR), g = green(IncantationOfTheRiverItem.WATER_COLOUR),
        b = blue(IncantationOfTheRiverItem.WATER_COLOUR), emission = 0f, absorption = 0f,
        flowSpeed = 0f, twist = 0f, spinRate = 0f,
        fadeOutStart = 1f, fadeInFirstPerson = 0f, fadeInOther = 0f, column = true,
    )

    /** BaseRadius in the shader: the scale of the mist density and of the inner volume steps, not the jet width. */
    private const val COLUMN_RADIUS = 0.2f

    /** Bounds the pencil jet's mist: 0.09 radius, times the largest swell (1.1) and the mist fill (3), plus the
     *  snake and a margin for the normal samples. */
    private const val COLUMN_BOX_RADIUS = 0.4f

    private class Cone(val startMillis: Long) {
        var style = WINDS
        var range = IncantationOfWindsItem.RANGE

        /** Cast age in ticks, the clock of the jet waves. */
        var age = 0f
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
        var ownEyes = false    }

    /** Particle push, blocks/tick² at the apex; with typical particle friction it settles near 1 block/tick. */
    private const val PARTICLE_PUSH = 0.04
    private const val PARTICLE_CONE_APEX_RADIUS = 0.5
    private const val PARTICLE_FAR_FRACTION = 0.25

    private val cones = Int2ObjectOpenHashMap<Cone>()
    private var anyWindCasting = false
    private var lastLevel: ClientLevel? = null

    private val view = VolumeView()
    private val effect = VolumeEffect("wind_cone_volume")
    private val columnEffect = VolumeEffect("river_column_volume", "SceneSampler" to IntSupplier { SceneCopy.textureId })
    private var uniforms: Uniforms? = null
    private var columnUniforms: Uniforms? = null
    private val localToView = Matrix4f()
    // Render thread only: the cone axes of the cone that pushCone fills now.
    private val axisY = Vector3d()
    private val axisZ = Vector3d()

    fun tick(level: ClientLevel) {
        if (level !== lastLevel) {
            cones.clear()
            lastLevel = level
        }
        for (cone in cones.values) cone.casting = false
        for (player in level.players()) {
            val style = styleOf(player) ?: continue
            val isNew = !cones.containsKey(player.id)
            val cone = cones.computeIfAbsent(player.id) { Cone(Util.getMillis()) }
            cone.casting = true
            cone.style = style
            // Kept for the fade-out, when the player no longer gives a pose.
            aim(cone, player, 1f)
            if (isNew) {
                cone.tailX = cone.axisX
                cone.tailY = cone.axisY
                cone.tailZ = cone.axisZ
            }
        }
        anyWindCasting = cones.values.any { it.casting && it.style === WINDS }
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

    private fun styleOf(player: Player): Style? {
        val hand = player.usedItemHand
        if (!IncantationOfWindsClient.isCastingIncantation(player, hand)) return null
        return when (player.getItemInHand(hand).item) {
            is IncantationOfWindsItem -> WINDS
            is IncantationOfTheRiverItem -> RIVER
            else -> null
        }
    }

    /** The jet runs from the palm to its impact point; the gust has a fixed length toward the crosshair. */
    private fun aim(cone: Cone, player: Player, partialTick: Float) {
        val hand = IncantationOfWindsClient.castingPalm(player, partialTick).position
        val axis: Vec3
        if (cone.style === RIVER) {
            val jet = IncantationOfTheRiverItem.trace(player.level(), player, partialTick)
            val toEnd = jet.end.subtract(hand)
            cone.range = toEnd.length().coerceAtLeast(MIN_JET_LENGTH)
            axis = toEnd.normalize()
        } else {
            cone.range = IncantationOfWindsItem.RANGE
            axis = IncantationOfWindsClient.streamAxis(player, hand, partialTick)
        }
        cone.apexX = hand.x
        cone.apexY = hand.y
        cone.apexZ = hand.z
        cone.axisX = axis.x
        cone.axisY = axis.y
        cone.axisZ = axis.z
    }

    /** Adds the wind of every cast cone that holds the particle to its velocity. The stream's own particles
     *  and the lattice ocean particles keep their scripted motion. */
    @JvmStatic
    fun pushParticle(particle: Particle) {
        if (!anyWindCasting) return
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
            if (!cone.casting || cone.style !== WINDS) continue
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
        val cam = camera.position
        view.update(projection, poseStack.last().pose(), cam.x, cam.y, cam.z)
        val now = Util.getMillis()
        for (entry in cones.int2ObjectEntrySet()) {
            val cone = entry.value
            val player = level.getEntity(entry.intKey) as? Player
            // The tick pose lags the camera at high frame rates, so a live caster is posed at the partial tick.
            if (player != null && cone.casting) {
                aim(cone, player, partialTick)
                cone.age = player.ticksUsingItem + partialTick
            }
            lagTail(cone, now)
            cone.ownEyes = player != null && IncantationOfWindsClient.isViewedFromOwnEyes(player)
        }
        renderGusts(partialTick, now)
        renderColumns(level, partialTick, now)
    }

    private fun renderGusts(partialTick: Float, now: Long) {
        val eff = effect.get() ?: return
        val u = uniforms ?: Uniforms(eff).also { uniforms = it }
        val main = Minecraft.getInstance().mainRenderTarget
        u.pushFrame(main.width, main.height)
        var drawn = 0
        for (cone in cones.values) {
            if (drawn >= MAX_CONES_PER_FRAME) break
            if (cone.style.column) continue
            val strength = Mth.lerp(partialTick, cone.prevStrength, cone.strength)
            if (strength <= 0f || !pushCone(u, cone)) continue
            val style = cone.style
            u.fadeIn.set(if (cone.ownEyes) style.fadeInFirstPerson else style.fadeInOther)
            u.pushStyle(style, cone.range.toFloat())
            u.strength.set(strength)
            u.time.set((now - cone.startMillis) / MILLIS_PER_SECOND)
            if (drawn == 0) VolumeProxy.begin(main, VolumeComposite.Blend.PREMULTIPLIED)
            VolumeProxy.draw(eff)
            drawn++
        }
        if (drawn > 0) VolumeProxy.end(main, eff)
    }

    /** Each column refracts the scene as drawn so far, so it takes a fresh copy, as the Black Sun does. */
    private fun renderColumns(level: ClientLevel, partialTick: Float, now: Long) {
        if (cones.values.none { it.style.column }) return
        val eff = columnEffect.get() ?: return
        val u = columnUniforms ?: Uniforms(eff).also { columnUniforms = it }
        val main = Minecraft.getInstance().mainRenderTarget
        u.pushFrame(main.width, main.height)
        eff.safeGetUniform("BaseRadius").set(COLUMN_RADIUS)
        val skyDarken = level.getSkyDarken(partialTick)
        var drawn = 0
        for (cone in cones.values) {
            if (drawn >= MAX_CONES_PER_FRAME) break
            if (!cone.style.column) continue
            val strength = Mth.lerp(partialTick, cone.prevStrength, cone.strength)
            if (strength <= 0f || !pushCone(u, cone)) continue
            if (!SceneCopy.copy()) return
            u.pushStyle(cone.style, cone.range.toFloat())
            u.light.set(lightAt(level, cone, skyDarken))
            u.age.set(cone.age)
            u.strength.set(strength)
            u.time.set((now - cone.startMillis) / MILLIS_PER_SECOND)
            VolumeProxy.begin(main, VolumeComposite.Blend.PREMULTIPLIED)
            VolumeProxy.draw(eff)
            VolumeProxy.end(main, eff)
            drawn++
        }
    }

    private const val MILLIS_PER_SECOND = 1000f

    /** Floor, so the water never goes fully black in a dark cave. */
    private const val MIN_LIGHT = 0.2f

    /** Light at the middle of the jet, as a block there would get it: block light, or sky light dimmed at night. */
    private fun lightAt(level: ClientLevel, cone: Cone, skyDarken: Float): Float {
        val half = cone.range * 0.5
        val pos = BlockPos.containing(cone.apexX + cone.axisX * half, cone.apexY + cone.axisY * half, cone.apexZ + cone.axisZ * half)
        val block = level.getBrightness(LightLayer.BLOCK, pos) / MAX_LIGHT
        val sky = level.getBrightness(LightLayer.SKY, pos) / MAX_LIGHT * skyDarken
        return maxOf(block, sky, MIN_LIGHT)
    }

    private const val MAX_LIGHT = 15f

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

    /** Sets [axisY] = X × up (or X × east when X is near vertical), normalised, and [axisZ] = X × Y. */
    private fun crossAxes(xx: Double, xy: Double, xz: Double) {
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
        axisY.set(yx, yy, yz)
        axisZ.set(xy * yz - xz * yy, xz * yx - xx * yz, xx * yy - xy * yx)
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
        crossAxes(xx, xy, xz)
        val yx = axisY.x; val yy = axisY.y; val yz = axisY.z
        val zx = axisZ.x; val zy = axisZ.y; val zz = axisZ.z

        // The tail direction in cone axes, as a slope across the aim.
        val tailAxial = maxOf(cone.tailX * xx + cone.tailY * xy + cone.tailZ * xz, MIN_TAIL_AXIAL)
        var bendY = (cone.tailX * yx + cone.tailY * yy + cone.tailZ * yz) / tailAxial
        var bendZ = (cone.tailX * zx + cone.tailY * zy + cone.tailZ * zz) / tailAxial
        val bendLen = sqrt(bendY * bendY + bendZ * bendZ)
        if (bendLen > MAX_BEND_SLOPE) {
            bendY *= MAX_BEND_SLOPE / bendLen
            bendZ *= MAX_BEND_SLOPE / bendLen
        }
        if (cone.style.column) {
            // The column runs straight to the impact point the server pushes at.
            bendY = 0.0
            bendZ = 0.0
        }
        u.bend.set(bendY.toFloat(), bendZ.toFloat())

        val range = cone.range.toFloat()
        val radius = if (cone.style.column) COLUMN_BOX_RADIUS else range * cone.style.tanHalf + cone.style.apexRadius
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

        val range: AbstractUniform = eff.safeGetUniform("Range")
        val tanHalf: AbstractUniform = eff.safeGetUniform("TanHalf")
        val apexRadius: AbstractUniform = eff.safeGetUniform("ApexRadius")
        val colour: AbstractUniform = eff.safeGetUniform("Colour")
        val emission: AbstractUniform = eff.safeGetUniform("Emission")
        val absorption: AbstractUniform = eff.safeGetUniform("Absorption")
        val flowSpeed: AbstractUniform = eff.safeGetUniform("FlowSpeed")
        val twist: AbstractUniform = eff.safeGetUniform("Twist")
        val spinRate: AbstractUniform = eff.safeGetUniform("SpinRate")
        val fadeOutStart: AbstractUniform = eff.safeGetUniform("FadeOutStart")
        val age: AbstractUniform = eff.safeGetUniform("Age")
        val light: AbstractUniform = eff.safeGetUniform("Light")

        fun pushFrame(width: Int, height: Int) {
            view.pushViewProj(eff)
            view.pushInvViewProj(eff)
            eff.safeGetUniform("ScreenSize").set(width.toFloat(), height.toFloat())
        }

        fun pushStyle(style: Style, length: Float) {
            range.set(length)
            tanHalf.set(style.tanHalf)
            apexRadius.set(style.apexRadius)
            colour.set(style.r, style.g, style.b)
            emission.set(style.emission)
            absorption.set(style.absorption)
            flowSpeed.set(style.flowSpeed)
            twist.set(style.twist)
            spinRate.set(style.spinRate)
            fadeOutStart.set(style.fadeOutStart)
        }
    }
}
