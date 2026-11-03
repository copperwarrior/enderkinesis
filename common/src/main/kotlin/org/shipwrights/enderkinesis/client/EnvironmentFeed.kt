package org.shipwrights.enderkinesis.client

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector3d
import org.shipwrights.enderkinesis.item.UlderScrollItem
import org.shipwrights.enderkinesis.spell.Element
import org.shipwrights.enderkinesis.spell.ElementalEnvironment
import org.shipwrights.enderkinesis.spell.SpellCasting
import org.valkyrienskies.mod.common.getShipsIntersecting
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

// Only the local player's held scrolls, so the view of other players does not fill with wisps.
object EnvironmentFeed {
    private class Wisp(val from: Vec3, val hand: InteractionHand, val life: Int, val lift: Double, val sway: Double, val rgb: Int, val glows: Boolean) {
        var age = 0
    }

    private class Glint(val hand: InteractionHand, val rgb: Int) {
        var age = 0
    }

    private val wisps = ArrayList<Wisp>()
    private val glints = ArrayList<Glint>()

    fun isEmpty(): Boolean = wisps.isEmpty() && glints.isEmpty()

    fun reset() {
        wisps.clear()
        glints.clear()
    }

    fun tick(level: ClientLevel) {
        val player = Minecraft.getInstance().player
        val held = if (player == null || player.level() !== level || player.isSpectator) emptyList() else InteractionHand.entries.mapNotNull { hand ->
            val spell = UlderScrollItem.spellOf(player.getItemInHand(hand)) ?: return@mapNotNull null
            val elements = spell.glyphs.filter { it != Element.SUNDERING }.distinct()
            if (elements.isEmpty()) null else hand to elements
        }
        val holding = held.map { it.first }.toSet()
        wisps.removeIf { ++it.age >= it.life || it.hand !in holding }
        glints.removeIf { ++it.age >= GLINT_TICKS || it.hand !in holding }
        for (w in wisps) if (w.age == w.life - 1) glints.add(Glint(w.hand, whiten(w.rgb, 0.5f)))
        if (player == null || held.isEmpty()) return

        val centre = player.position().add(0.0, player.bbHeight * 0.5, 0.0)
        val origin = BlockPos.containing(centre)
        val ships = level.getShipsIntersecting(AABB(centre, centre).inflate(ElementalEnvironment.RADIUS + 1.0)).toList()
        val pos = BlockPos.MutableBlockPos()
        val shipPoint = Vector3d()
        val random = level.random
        val r = ElementalEnvironment.RADIUS
        for (dx in -r..r) for (dy in -r..r) for (dz in -r..r) {
            val weight = ElementalEnvironment.weight(dx, dy, dz)
            if (weight <= 0.0) continue
            pos.set(origin.x + dx, origin.y + dy, origin.z + dz)
            val worldState = level.getBlockState(pos)
            for ((hand, elements) in held) for (element in elements) {
                val chance = FEED_CHANCE * weight * ElementalEnvironment.share(worldState, element)
                if (chance > 0.0 && random.nextDouble() < chance) spawn(player, hand, element, onFace(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble(), random))
            }
            // Ship blocks also feed it: map each world cell into shipyard space, then the wisp back to world.
            for (ship in ships) {
                ship.transform.worldToShip.transformPosition(origin.x + dx + 0.5, origin.y + dy + 0.5, origin.z + dz + 0.5, shipPoint)
                pos.set(shipPoint.x, shipPoint.y, shipPoint.z)
                val state = level.getBlockState(pos)
                if (state.isAir) continue
                for ((hand, elements) in held) for (element in elements) {
                    val chance = FEED_CHANCE * weight * ElementalEnvironment.share(state, element)
                    if (chance <= 0.0 || random.nextDouble() >= chance) continue
                    val p = onFace(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble(), random)
                    ship.transform.shipToWorld.transformPosition(p.x, p.y, p.z, shipPoint)
                    spawn(player, hand, element, Vec3(shipPoint.x, shipPoint.y, shipPoint.z))
                }
            }
        }
    }

    private fun spawn(player: Player, hand: InteractionHand, element: Element, from: Vec3) {
        if (wisps.size >= MAX_WISPS) return
        val dist = from.distanceTo(SpellCasting.handOrigin(player, hand))
        val glows = element !in DARK
        val random = player.random
        // Whiten bright elements, so the wisp shows over its own bright blocks.
        wisps.add(Wisp(from, hand, Math.round(22 + dist * 7).toInt(), 0.4 + dist * 0.15, random.nextDouble() * 2 * Math.PI, whiten(element.color, if (glows) 0.45f else 0.1f), glows))
    }

    private fun onFace(x: Double, y: Double, z: Double, random: net.minecraft.util.RandomSource): Vec3 {
        val a = random.nextDouble(); val c = random.nextDouble()
        return when (random.nextInt(5)) {
            0 -> Vec3(x + a, y + 1.02, z + c)
            1 -> Vec3(x - 0.02, y + a, z + c)
            2 -> Vec3(x + 1.02, y + a, z + c)
            3 -> Vec3(x + a, y + c, z - 0.02)
            else -> Vec3(x + a, y + c, z + 1.02)
        }
    }

    private fun scrollAt(player: Player, hand: InteractionHand, partialTick: Float): Vec3 {
        val arm = if (hand == InteractionHand.MAIN_HAND) player.mainArm else player.mainArm.opposite
        if (SpellHand.firstPerson(player)) return SpellHand.palmInWorld(arm)
        return SpellCasting.handOrigin(player, hand, partialTick)
    }

    private fun at(w: Wisp, f: Double, end: Vec3): Vec3 {
        val u = f.coerceIn(0.0, 1.0).pow(1.5)
        val mid = w.from.lerp(end, 0.5).add(sin(w.sway) * 0.25, w.lift, cos(w.sway) * 0.25)
        return w.from.scale((1 - u) * (1 - u)).add(mid.scale(2 * u * (1 - u))).add(end.scale(u * u))
    }

    fun render(m: Matrix4f, buffers: MultiBufferSource, view: SpellView, partialTick: Float) {
        val player = Minecraft.getInstance().player ?: return
        val ends = HashMap<InteractionHand, Vec3>()
        fun end(hand: InteractionHand) = ends.getOrPut(hand) { scrollAt(player, hand, partialTick) }
        // Dark elements shade; the others glow. Each needs its own render type.
        for (glowing in listOf(false, true)) {
            if (wisps.none { it.glows == glowing } && (!glowing || glints.isEmpty())) continue
            val c = buffers.getBuffer(if (glowing) AegisRenderType.FACE_GLOW else SpellVfxRenderTypes.SHADE)
            for (w in wisps) {
                if (w.glows != glowing) continue
                val f = (w.age + partialTick) / w.life.toDouble()
                val fade = minOf(1.0, f / 0.15) * (if (f > 0.9) maxOf(0.0, (1 - f) / 0.1) else 1.0)
                val strength = if (glowing) WISP_GLOW_ALPHA else WISP_DARK_ALPHA
                for (k in 0 until WISP_PUFFS) {
                    val g = f - k * WISP_SPACING
                    if (g <= 0.0 || g >= 1.0) continue
                    val alpha = (strength * fade * (1.0 - k.toDouble() / WISP_PUFFS)).toFloat()
                    SpellShapes.disc(c, m, view, at(w, g, end(w.hand)), (0.12 + 0.03 * k) / 2, w.rgb, alpha)
                }
            }
            if (glowing) for (gl in glints) {
                val t = (gl.age + partialTick) / GLINT_TICKS
                SpellShapes.disc(c, m, view, end(gl.hand), GLINT_SIZE / 2, gl.rgb, GLINT_ALPHA * (1f - t))
            }
        }
    }

    private fun whiten(rgb: Int, f: Float): Int = SpellGeom.whiten(rgb, f)

    // Chance per tick for a full-weight block made only of one element.
    private const val FEED_CHANCE = 0.015
    private const val MAX_WISPS = 64
    private const val WISP_PUFFS = 5
    // Fraction of the path between puffs.
    private const val WISP_SPACING = 0.05
    private const val WISP_GLOW_ALPHA = 0.28
    private const val WISP_DARK_ALPHA = 0.22
    private const val GLINT_TICKS = 6
    private const val GLINT_SIZE = 0.07
    private const val GLINT_ALPHA = 0.5f
    private val DARK = setOf(Element.GENESIS, Element.GRAVITY, Element.BLOOD, Element.CORRUPTION)
}
