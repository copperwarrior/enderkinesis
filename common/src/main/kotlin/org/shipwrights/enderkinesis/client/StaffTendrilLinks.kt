package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import org.joml.Matrix4f
import org.joml.Vector3d
import java.util.IdentityHashMap

/** Tendrils from a held grand staff's head to each entity its aura takes. Client thread only. */
class StaffTendrilLinks(
    staff: Class<out Item>,
    private val isCasting: (LivingEntity) -> Boolean,
    private val targetsOf: (Level, LivingEntity) -> List<LivingEntity>,
    private val mainStyle: TendrilStyle,
    private val thinStyle: TendrilStyle,
    private val density: Float,
) {

    private class Link(val target: LivingEntity) {
        var caster: LivingEntity? = null
        var live = false
        var age = 0
        var fade = 0f
        var fadeO = 0f
        val p0 = Vector3d()
        val p1 = Vector3d()
        val p2 = Vector3d()
        val frame = TendrilFrame(Vector3d())
        val main = TendrilCurve(p0, p1, p2, MAIN_WRITHE, target.id * 1.7)
        val writhing = arrayOf(
            TendrilCurve(p0, p1, p2, THIN_WRITHE, target.id * 1.7 + 2.9),
            TendrilCurve(p0, p1, p2, THIN_WRITHE, target.id * 1.7 + 5.3),
        )
    }

    private val heads = StaffHeadTracker(staff)
    private val links = IdentityHashMap<LivingEntity, Link>()
    // The nearest links only: each tendril volume costs per frame.
    private val active = ArrayList<Link>(MAX_ACTIVE_LINKS)
    private val live = ArrayList<LivingEntity>(MAX_ACTIVE_LINKS)

    /** The targets of the drawn links that are still live, as of the last [tick]. */
    val liveTargets: List<LivingEntity> get() = live

    fun clear() {
        links.clear()
        active.clear()
        live.clear()
        heads.clear()
    }

    fun record(stack: ItemStack, pose: Matrix4f, centerX: Float, centerY: Float, centerZ: Float) =
        heads.record(stack, pose, centerX, centerY, centerZ)

    fun headPosition(caster: LivingEntity, partialTick: Float, out: Vector3d) = heads.position(caster, partialTick, out)

    fun tick(level: ClientLevel) {
        for (link in links.values) link.live = false
        for (caster in level.players()) {
            if (!isCasting(caster)) continue
            for (target in targetsOf(level, caster)) {
                val link = links.getOrPut(target) { Link(target) }
                val current = link.caster
                if (!link.live || current == null || caster.distanceToSqr(target) < current.distanceToSqr(target)) {
                    link.caster = caster
                }
                link.live = true
            }
        }
        val paused = Minecraft.getInstance().isPaused
        val iterator = links.values.iterator()
        while (iterator.hasNext()) {
            val link = iterator.next()
            if (paused) continue
            link.fadeO = link.fade
            link.fade = (link.fade + (if (link.live) 1f else -1f) / FADE_TICKS).coerceIn(0f, 1f)
            if (link.live) link.age++
            if (link.fade <= 0f || link.target.isRemoved || link.target.level() !== level) iterator.remove()
        }
        val camera = Minecraft.getInstance().gameRenderer.mainCamera.position
        active.clear()
        active.addAll(links.values)
        active.sortBy { it.target.distanceToSqr(camera) }
        while (active.size > MAX_ACTIVE_LINKS) active.removeAt(active.size - 1)
        live.clear()
        if (!paused) for (link in active) if (link.live) live.add(link.target)
    }

    /** False when the link has no caster on this client. */
    private fun update(link: Link, partialTick: Float): Boolean {
        val caster = link.caster ?: return false
        heads.position(caster, partialTick, link.p0)
        val t = link.target
        val self = t === Minecraft.getInstance().cameraEntity
        // Not Entity.getX(double): that is x + width × scale, not an interpolation.
        val x = Mth.lerp(partialTick.toDouble(), t.xo, t.x)
        val z = Mth.lerp(partialTick.toDouble(), t.zo, t.z)
        // In first person a tendril to the chest fills the view; the feet keep it in sight.
        val y = Mth.lerp(partialTick.toDouble(), t.yo, t.y) + if (self) SELF_LIFT else t.bbHeight * BODY_HEIGHT
        link.p2.set(x, y, z)
        val dx = link.p2.x - link.p0.x
        val dz = link.p2.z - link.p0.z
        val lift = ARC_LIFT_MIN + Math.sqrt(dx * dx + dz * dz) * ARC_LIFT_PER_BLOCK
        link.p1.set(link.p0.x + dx * 0.5, maxOf(link.p0.y, link.p2.y) + lift, link.p0.z + dz * 0.5)
        link.frame.origin.set(link.p0)
        return true
    }

    fun render(poseStack: PoseStack, projection: Matrix4f, camera: Camera) {
        if (active.isEmpty() || !TendrilVolume.begin(poseStack, projection, camera)) return
        val partialTick = Minecraft.getInstance().frameTime
        for (link in active) {
            if (!update(link, partialTick)) continue
            val reach = ((link.age + partialTick) / GROW_TICKS).coerceIn(0.0, 1.0)
            val strength = (link.fadeO + (link.fade - link.fadeO) * partialTick) * density
            TendrilVolume.draw(link.frame, link.main, reach, strength, mainStyle)
            for (curve in link.writhing) TendrilVolume.draw(link.frame, curve, reach, strength, thinStyle)
        }
        TendrilVolume.end()
    }

    private companion object {
        const val GROW_TICKS = 8.0
        const val FADE_TICKS = 8f
        const val MAX_ACTIVE_LINKS = 6
        const val MAIN_WRITHE = 0.7
        const val THIN_WRITHE = 1.3
        const val ARC_LIFT_MIN = 0.5
        const val ARC_LIFT_PER_BLOCK = 0.35
        const val BODY_HEIGHT = 0.5
        const val SELF_LIFT = 0.1
    }
}
