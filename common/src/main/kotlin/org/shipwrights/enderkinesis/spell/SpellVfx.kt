package org.shipwrights.enderkinesis.spell

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3

/**
 * Short-lived spell visuals drawn by clients as geometry (see `SpellVfxClient`), so spells
 * read as volumes and light rather than clouds of particles. The server says what and where;
 * the client draws, animates and fades it.
 */
object SpellVfx {

    /** What to draw. [a] and [b] are its points (b only where noted), [size] its scale. */
    enum class Kind {
        /** An expanding shell of light, rim-lit, around [a], [size] in radius. */
        SHELL,
        /** A dark sphere collapsing onto [a], with rings spinning into it. */
        IMPLODE,
        /** A Staff of Aegis-style cube around [a], [size] across, growing and turning. */
        AEGIS,
        /** Jagged, flickering lightning from [a] to [b], with branches. */
        ARC,
        /** Glowing beads flowing along a curve from [a] to [b]. */
        STREAM,
        /** A quick bright flash at [a], [size] across. */
        FLASH,
    }

    fun send(level: ServerLevel, kind: Kind, a: Vec3, b: Vec3, size: Float, rgb: Int, life: Int) {
        SpellNetwork.broadcastVfx(level, kind.ordinal, a, b, size, rgb, life, level.random.nextInt())
    }

    fun shell(level: ServerLevel, at: Vec3, radius: Double, rgb: Int, life: Int = 14) =
        send(level, Kind.SHELL, at, at, radius.toFloat(), rgb, life)

    fun implode(level: ServerLevel, at: Vec3, radius: Double, rgb: Int, life: Int = 16) =
        send(level, Kind.IMPLODE, at, at, radius.toFloat(), rgb, life)

    fun aegis(level: ServerLevel, at: Vec3, size: Double, rgb: Int, life: Int = 20) =
        send(level, Kind.AEGIS, at, at, size.toFloat(), rgb, life)

    fun arc(level: ServerLevel, from: Vec3, to: Vec3, rgb: Int, life: Int = 6) =
        send(level, Kind.ARC, from, to, 1f, rgb, life)

    fun stream(level: ServerLevel, from: Vec3, to: Vec3, rgb: Int, life: Int = 16) =
        send(level, Kind.STREAM, from, to, 1f, rgb, life)

    fun flash(level: ServerLevel, at: Vec3, size: Double, rgb: Int, life: Int = 5) =
        send(level, Kind.FLASH, at, at, size.toFloat(), rgb, life)

    /** The one-shot visual each element leaves where it lands: its volume, rather than a
     *  particle cloud. Held shapes (lines, cones) are drawn by their own renderers instead. */
    fun element(ctx: SpellContext, delivery: Delivery, element: Element, magnitude: Float) {
        val level = ctx.level
        val (centre, radius) = when (delivery) {
            is Delivery.Sphere -> delivery.anchor to delivery.radius
            is Delivery.SingleEntity -> delivery.anchor to (delivery.entity.bbHeight * 0.75).toDouble()
            is Delivery.SingleBlock -> delivery.anchor to 0.9
            else -> return
        }
        val rgb = element.color
        when (element) {
            Element.GRAVITY, Element.VOID -> implode(level, centre, radius, rgb)
            Element.PURITY -> aegis(level, centre, radius * 1.4, rgb)
            Element.CHAOS -> {
                // Lightning out to the edge of the effect even where nothing stands.
                repeat(3 + magnitude.toInt()) {
                    val dir = Vec3(level.random.nextGaussian(), level.random.nextGaussian() * 0.5, level.random.nextGaussian()).normalize()
                    arc(level, centre, centre.add(dir.scale(radius * (0.5 + 0.5 * level.random.nextDouble()))), rgb)
                }
            }
            else -> shell(level, centre, radius, rgb)
        }
    }
}
