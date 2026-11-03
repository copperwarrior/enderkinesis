package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3f
import org.shipwrights.enderkinesis.entity.RaisedSkeletonEntity
import org.shipwrights.enderkinesis.item.GrandStaffOfTheDeadItem
import org.shipwrights.enderkinesis.registry.EKParticles
import java.util.IdentityHashMap

/** Grand Staff of the Dead visuals: tendrils and glyphs from the skull to each rising skeleton, and the skull's gaze.
 *  Client thread only. */
object GrandStaffClient : StaffGemGlow.Aim {

    private const val TARGET_LIFT = 1.0
    private const val GROUND_LIFT = 0.1
    private const val ARC_LIFT_MIN = 0.5
    private const val ARC_LIFT_PER_BLOCK = 0.35
    private const val GROW_TICKS = 8f
    private const val FADE_TICKS = 8f
    private const val LOOK_EASE_TICKS = 6f
    private const val GLYPHS_PER_TICK = 2
    private const val MAX_ACTIVE_LINKS = 4
    private const val MAIN_WRITHE = 0.7
    private const val THIN_WRITHE = 1.3
    // Below 1, the volume is thinner than its default fill.
    private const val DENSITY = 0.45f

    private val SKULL_YELLOW = floatArrayOf(1.00f, 0.95f, 0.65f)
    private val GROUND_AMBER = floatArrayOf(1.00f, 0.85f, 0.45f)
    private val MAIN_STYLE = TendrilStyle(0.16f, SKULL_YELLOW, GROUND_AMBER)
    private val THIN_STYLE = TendrilStyle(0.09f, SKULL_YELLOW, GROUND_AMBER)

    private class Link(val skeleton: RaisedSkeletonEntity) {
        val p0 = Vector3d()
        val p1 = Vector3d()
        val p2 = Vector3d()
        val frame = TendrilFrame(Vector3d())
        val main = TendrilCurve(p0, p1, p2, MAIN_WRITHE, skeleton.id * 1.7)
        val writhing = arrayOf(
            TendrilCurve(p0, p1, p2, THIN_WRITHE, skeleton.id * 1.7 + 2.9),
            TendrilCurve(p0, p1, p2, THIN_WRITHE, skeleton.id * 1.7 + 5.3),
        )
    }

    private val skulls = StaffHeadTracker(GrandStaffOfTheDeadItem::class.java)
    private val links = IdentityHashMap<RaisedSkeletonEntity, Link>()
    // The newest rises only: tendril volumes and glyphs cost per body, and a mass raise would stack them.
    private val active = ArrayList<Link>(MAX_ACTIVE_LINKS)

    private val viewRotation = Quaternionf()
    private val aimRotation = Quaternionf()
    private val inversePose = Matrix4f()
    private val scratchF = Vector3f()
    private val scratchBend = Vector3d()

    fun init() {
        ClientTickEvent.CLIENT_LEVEL_POST.register(ClientTickEvent.ClientLevel { level -> tick(level) })
    }

    fun onDisconnect() {
        links.clear()
        active.clear()
        skulls.clear()
    }

    private fun tick(level: ClientLevel) {
        links.values.removeIf { !it.skeleton.isRising || it.skeleton.isRemoved || it.skeleton.level() !== level }
        for (entity in level.entitiesForRendering()) {
            if (entity is RaisedSkeletonEntity && entity.isRising && !links.containsKey(entity)) {
                links[entity] = Link(entity)
            }
        }
        active.clear()
        active.addAll(links.values)
        active.sortBy { it.skeleton.riseProgress(0f) }
        while (active.size > MAX_ACTIVE_LINKS) active.removeAt(active.size - 1)
        if (Minecraft.getInstance().isPaused) return
        for (link in active) {
            if (!update(link, 1f)) continue
            repeat(GLYPHS_PER_TICK) {
                level.addParticle(
                    EKParticles.grandStaffGlyph(), link.p0.x, link.p0.y, link.p0.z,
                    link.skeleton.id.toDouble(), 0.0, 0.0,
                )
            }
        }
    }

    /** False when the skeleton has no owner on this client. */
    private fun update(link: Link, partialTick: Float): Boolean {
        val owner = link.skeleton.owner as? LivingEntity ?: return false
        skulls.position(owner, partialTick, link.p0)
        val s = link.skeleton
        link.p2.set(s.x, s.y + GROUND_LIFT, s.z)
        // Lift the control point: the tendril leaves the skull outward and upward, then curves down to the ground.
        val dx = link.p2.x - link.p0.x
        val dz = link.p2.z - link.p0.z
        val lift = ARC_LIFT_MIN + Math.sqrt(dx * dx + dz * dz) * ARC_LIFT_PER_BLOCK
        link.p1.set(link.p0.x + dx * 0.5, maxOf(link.p0.y, link.p2.y) + lift, link.p0.z + dz * 0.5)
        link.frame.origin.set(link.p0)
        return true
    }

    /** The world point at [s] (0 at the skull, 1 at the ground) on the main tendril to [skeleton]. */
    fun pointOnTendril(skeleton: RaisedSkeletonEntity, s: Double, out: Vector3d): Boolean {
        val link = links[skeleton] ?: return false
        link.main.writhedPointAt(s, out, scratchBend)
        return true
    }

    // Called with the other world-space volumes, before the fog passes.
    @JvmStatic
    fun render(poseStack: PoseStack, projection: Matrix4f, camera: Camera) {
        if (active.isEmpty() || !TendrilVolume.begin(poseStack, projection, camera)) return
        val partialTick = Minecraft.getInstance().frameTime
        for (link in active) {
            if (!update(link, partialTick)) continue
            val elapsed = link.skeleton.riseProgress(partialTick) * RaisedSkeletonEntity.RISE_TICKS
            val reach = (elapsed / GROW_TICKS).coerceIn(0f, 1f).toDouble()
            val strength = ((RaisedSkeletonEntity.RISE_TICKS - elapsed) / FADE_TICKS).coerceIn(0f, 1f) * DENSITY
            TendrilVolume.draw(link.frame, link.main, reach, strength, MAIN_STYLE)
            for (curve in link.writhing) TendrilVolume.draw(link.frame, curve, reach, strength, THIN_STYLE)
        }
        TendrilVolume.end()
    }

    /** Records where the skull draws, and turns it to face the newest skeleton its caster is raising. */
    override fun rotation(
        stack: ItemStack, pose: Matrix4f, centerX: Float, centerY: Float, centerZ: Float, spinYaw: Float, out: Quaternionf,
    ): Quaternionf {
        out.rotationY(spinYaw)
        skulls.record(stack, pose, centerX, centerY, centerZ)
        val link = newestRaisingWith(stack) ?: return out
        val target = link.skeleton
        val camera = Minecraft.getInstance().gameRenderer.mainCamera
        StaffHeadTracker.viewRotation(camera.xRot, camera.yRot, viewRotation)
        val cam = camera.position
        scratchF.set((target.x - cam.x).toFloat(), (target.y + TARGET_LIFT - cam.y).toFloat(), (target.z - cam.z).toFloat())
        viewRotation.transform(scratchF)
        pose.invert(inversePose).transformPosition(scratchF)
        val dx = scratchF.x - centerX
        val dy = scratchF.y - centerY
        val dz = scratchF.z - centerZ
        // The skull's face is the model's west (-X) face. rotationZ(-e) tilts -X up by e, then rotationY(a)
        // turns it to (-cos a, 0, sin a) across: together they aim the face along (dx, dy, dz).
        val yaw = Mth.atan2(dz.toDouble(), -dx.toDouble()).toFloat()
        val elevation = Mth.atan2(dy.toDouble(), Math.sqrt((dx * dx + dz * dz).toDouble())).toFloat()
        aimRotation.rotationY(yaw).rotateZ(-elevation)
        val elapsed = target.riseProgress(Minecraft.getInstance().frameTime) * RaisedSkeletonEntity.RISE_TICKS
        val ease = (minOf(elapsed, RaisedSkeletonEntity.RISE_TICKS - elapsed) / LOOK_EASE_TICKS).coerceIn(0f, 1f)
        return out.slerp(aimRotation, ease * ease * (3f - 2f * ease))
    }

    private fun newestRaisingWith(stack: ItemStack): Link? {
        var best: Link? = null
        var bestProgress = Float.MAX_VALUE
        for (link in links.values) {
            val owner = link.skeleton.owner as? LivingEntity ?: continue
            if (!owner.isUsingItem || owner.useItem !== stack) continue
            val progress = link.skeleton.riseProgress(0f)
            if (progress < bestProgress) {
                best = link
                bestProgress = progress
            }
        }
        return best
    }
}
