package org.shipwrights.enderkinesis.spell

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3

object SpellVfx {

    // Many kinds reuse the size, colour, life and seed fields to carry other data; each such kind notes it.
    enum class Kind {
        SHELL,
        IMPLODE,
        AEGIS,
        ARC,
        STREAM,
        FLASH,
        CRUSH,
        /** Colour is the element ordinal. Life is the delay in ticks. */
        SIGNATURE,
        /** Colour is the spell elements, one bit per ordinal. */
        CHAIN,
        /** Colour is the element ordinal. [b] is the start of the air wave. */
        AREA,
        RUNE_CIRCLE,
        /** Seed is the imbuer entity id. */
        IMBUE,
        GLYPH_BURST,
        FIZZLE,
        /** Seed is the projectile entity id. Colour is the spell elements, one bit per ordinal. */
        IMBUED_SHOT,
        /** Seed is the writer entity id. Size is the hand ordinal. */
        INSCRIBE,
        /** Size is the face direction ordinal. */
        RUNE_BURST,
        /** [b] is the block center, [a] the cast point. Colour is the old state id. Size is the element ordinal or -1. */
        BLOCK_SHIFT,
        /** Seed is the falling block entity id. */
        PRESS,
        /** [b] is a direction. Colour is the elements, one bit per ordinal. */
        AEGIS_DEFLECT,
        /** [b] is the outward face normal. */
        AEGIS_STRIKE,
        /** Seed is the entity id. Colour is the element ordinal. */
        DEATH,
        BEAM_EXPLOSION,
        SHOCKWAVE,
        /** Seed is the entity id. Size is its height. */
        CLEANSE_DRAIN,
        /** Seed is the source entity id. Size is the exact target entity id. */
        SHUFFLE_SLING,
        /** Seed is the pulled entity id. The server sends it again while the pull continues. */
        VOID_TETHER,
        /** Seed is the entity id. Size is the element ordinal. */
        SELF_CAST,
        /** Seed is the wearer entity id. */
        LASH,

        // The client makes these kinds itself as parts of signatures; the server never sends them.
        SHARDS,
        SHATTER,
        RIFT,
        PUFFS,
        SUN_FLASH,
        /** Colour is beam A, size is exactly beam B. Seed holds the pairs ([chargePairs]). */
        BEAM_CHARGE,
        BEAM_PRESS,
        /** Colour is the old state id at [a]. Size is exactly the old state id at [b]. */
        BLOCK_SWAP,
    }

    /** Each pair is the element of the stronger beam; the other is its opposite. Packs a count, then 3 bits for each. */
    fun chargePairs(pairs: List<Element>): Int {
        val shown = pairs.take(8)
        var packed = shown.size
        shown.forEachIndexed { i, e -> packed = packed or (e.ordinal shl (4 + 3 * i)) }
        return packed
    }

    fun chargePairsOf(seed: Int): List<Element> =
        (0 until (seed and 0xF)).mapNotNull { Element.entries.getOrNull((seed shr (4 + 3 * it)) and 7) }

    fun beamCharge(level: ServerLevel, at: Vec3, side: Vec3, rgbA: Int, rgbB: Int, pairs: List<Element>, ticks: Int) =
        send(level, Kind.BEAM_CHARGE, at, at.add(side), rgbB.toFloat(), rgbA, ticks, chargePairs(pairs))

    fun beamPress(level: ServerLevel, at: Vec3, along: Vec3, ticks: Int) =
        send(level, Kind.BEAM_PRESS, at, at.add(along), 1f, 0xFFFFFF, ticks)

    const val DRAIN_TICKS = 12
    const val SLING_TICKS = 30

    fun send(level: ServerLevel, kind: Kind, a: Vec3, b: Vec3, size: Float, rgb: Int, life: Int, seed: Int = level.random.nextInt()) {
        SpellNetwork.broadcastVfx(level, kind.ordinal, a, b, size, rgb, life, seed)
    }

    fun imbue(level: ServerLevel, entity: Entity, spell: Spell) =
        send(level, Kind.IMBUE, entity.position(), entity.position(), 1f, spell.markColour(), IMBUE_LIFE, entity.id)

    fun glyphBurst(level: ServerLevel, at: Vec3, spell: Spell) = send(level, Kind.GLYPH_BURST, at, at, 1f, spell.markColour(), GLYPH_BURST_LIFE)

    fun fizzle(level: ServerLevel, at: Vec3) = send(level, Kind.FIZZLE, at, at, 1f, 0x8A8A8A, 1)

    fun inscribe(level: ServerLevel, pos: BlockPos, entity: Entity, hand: InteractionHand, spell: Spell) =
        send(level, Kind.INSCRIBE, Vec3.atCenterOf(pos), Vec3.atCenterOf(pos), hand.ordinal.toFloat(), spell.markColour(), INSCRIBE_LIFE, entity.id)

    fun runeBurst(level: ServerLevel, pos: BlockPos, facing: Direction, spell: Spell) =
        send(level, Kind.RUNE_BURST, Vec3.atCenterOf(pos), Vec3.atCenterOf(pos), facing.ordinal.toFloat(), spell.markColour(), RUNE_BURST_LIFE)

    fun press(level: ServerLevel, pos: BlockPos, falling: Int) = send(level, Kind.PRESS, Vec3.atCenterOf(pos), Vec3.atCenterOf(pos), 1f, Element.GRAVITY.color, 6, falling)

    fun blockSwap(level: ServerLevel, a: BlockPos, b: BlockPos, oldA: BlockState, oldB: BlockState) =
        send(level, Kind.BLOCK_SWAP, Vec3.atCenterOf(a), Vec3.atCenterOf(b), Block.getId(oldB).toFloat(), Block.getId(oldA), 6)

    fun blockStrike(level: ServerLevel, at: Vec3, spell: Spell) = glyphBurst(level, at, spell)

    fun deflect(level: ServerLevel, at: Vec3, along: Vec3, elements: Collection<Element>) =
        send(level, Kind.AEGIS_DEFLECT, at, along, 1f, elementBits(elements), 12)

    fun aegisStrike(level: ServerLevel, at: Vec3, normal: Vec3) = send(level, Kind.AEGIS_STRIKE, at, normal, 1f, Element.CHAOS.color, 10)

    fun death(level: ServerLevel, entity: Entity, element: Element) =
        send(level, Kind.DEATH, entity.position(), entity.position(), entity.bbHeight, element.ordinal, SpellDeaths.bodyTicks(element), entity.id)

    fun cleanseDrain(level: ServerLevel, entity: Entity, rgb: Int) =
        send(level, Kind.CLEANSE_DRAIN, entity.position(), entity.position(), entity.bbHeight, rgb, DRAIN_TICKS, entity.id)

    fun shuffleSling(level: ServerLevel, from: Entity, to: Entity, rgb: Int) =
        send(level, Kind.SHUFFLE_SLING, from.position(), to.position(), to.id.toFloat(), rgb, SLING_TICKS, from.id)

    fun voidTether(level: ServerLevel, core: Vec3, entity: Entity, life: Int) =
        send(level, Kind.VOID_TETHER, core, entity.position(), 1f, Element.VOID.color, life, entity.id)

    fun selfCast(level: ServerLevel, entity: Entity, element: Element, life: Int) =
        send(level, Kind.SELF_CAST, entity.position(), entity.position(), element.ordinal.toFloat(), element.color, life, entity.id)

    fun lash(level: ServerLevel, wearer: Entity, at: Vec3) =
        send(level, Kind.LASH, wearer.position(), at, wearer.bbHeight, Element.VOID.color, LASH_TICKS, wearer.id)

    fun beamExplosion(level: ServerLevel, at: Vec3, radius: Double) = send(level, Kind.BEAM_EXPLOSION, at, at, radius.toFloat(), 0xFFE9C0, 20)

    fun shockwave(level: ServerLevel, at: Vec3, radius: Double) = send(level, Kind.SHOCKWAVE, at, at, radius.toFloat(), 0xF4F0FF, 16)

    fun imbuedShot(level: ServerLevel, projectile: Entity, spell: Spell) =
        send(level, Kind.IMBUED_SHOT, projectile.position(), projectile.position(), 1f, elementBits(spell.magnitudes.keys), 1, projectile.id)

    fun shell(level: ServerLevel, at: Vec3, radius: Double, rgb: Int, life: Int = 14) =
        send(level, Kind.SHELL, at, at, radius.toFloat(), rgb, life)

    fun implode(level: ServerLevel, at: Vec3, radius: Double, rgb: Int, life: Int = 16) =
        send(level, Kind.IMPLODE, at, at, radius.toFloat(), rgb, life)

    fun aegis(level: ServerLevel, at: Vec3, size: Double, rgb: Int, life: Int = 20) =
        send(level, Kind.AEGIS, at, at, size.toFloat(), rgb, life)

    fun arc(level: ServerLevel, from: Vec3, to: Vec3, rgb: Int, life: Int = 6) =
        send(level, Kind.ARC, from, to, 1f, rgb, life)

    fun chainArc(level: ServerLevel, from: Vec3, to: Vec3, payload: Collection<Element>, life: Int) =
        send(level, Kind.CHAIN, from, to, 1f, elementBits(payload), life)

    fun stream(level: ServerLevel, from: Vec3, to: Vec3, rgb: Int, life: Int = 16) =
        send(level, Kind.STREAM, from, to, 1f, rgb, life)

    fun crush(level: ServerLevel, at: Vec3, size: Double, rgb: Int, life: Int = 18) =
        send(level, Kind.CRUSH, at, at, size.toFloat(), rgb, life)

    fun flash(level: ServerLevel, at: Vec3, size: Double, rgb: Int, life: Int = 5) =
        send(level, Kind.FLASH, at, at, size.toFloat(), rgb, life)

    fun signature(level: ServerLevel, element: Element, at: Vec3, size: Double, delay: Int = 0) =
        send(level, Kind.SIGNATURE, at, at, size.toFloat(), element.ordinal, delay)

    fun runeCircle(level: ServerLevel, at: Vec3, rgb: Int) = send(level, Kind.RUNE_CIRCLE, at, at, 1f, rgb, RUNE_CIRCLE_LIFE)

    fun elementBits(elements: Collection<Element>): Int = elements.fold(0) { bits, e -> bits or (1 shl e.ordinal) }

    fun elementsOf(bits: Int): List<Element> = Element.entries.filter { bits and (1 shl it.ordinal) != 0 }

    private const val RUNE_CIRCLE_LIFE = 16
    private const val LASH_TICKS = 16
    private const val IMBUE_LIFE = 32
    private const val GLYPH_BURST_LIFE = 12
    private const val INSCRIBE_LIFE = 24
    private const val RUNE_BURST_LIFE = 18

    const val WAVE_START_TICKS = 1
    const val WAVE_TICKS = 12
    /** Longer than the wave, because its ground effects stay after it passes. */
    const val AREA_LIFE = 100

    /** The Gravity wave closes in from the edge; all other waves go out from the caster. */
    fun waveDelay(element: Element, distance: Double, radius: Double): Int {
        val f = (distance / radius).coerceIn(0.0, 1.0)
        return WAVE_START_TICKS + Math.round(WAVE_TICKS * if (element == Element.GRAVITY) 1 - f else f).toInt()
    }

    /** Held shapes (beams, sprays) have their own renderers, so this skips them. */
    fun element(ctx: SpellContext, delivery: Delivery, element: Element, magnitude: Float) {
        val level = ctx.level
        when (delivery) {
            is Delivery.SingleEntity -> signature(level, element, delivery.anchor, (delivery.entity.bbHeight * 0.75).toDouble())
            is Delivery.SingleBlock -> signature(level, element, delivery.anchor, 0.9)
            is Delivery.Group -> for (target in delivery.targets) {
                signature(level, element, target.position().add(0.0, target.bbHeight * 0.5, 0.0), (target.bbHeight * 0.75).toDouble())
            }
            is Delivery.Sphere -> if (delivery.waves) {
                val air = if (delivery.area) delivery.center.add(0.0, 1.0, 0.0) else delivery.center
                send(level, Kind.AREA, delivery.center, air, delivery.radius.toFloat(), element.ordinal, AREA_LIFE)
                for (target in delivery.living(ctx)) {
                    val at = target.position().add(0.0, target.bbHeight * 0.5, 0.0)
                    signature(level, element, at, (target.bbHeight * 0.55).toDouble(), waveDelay(element, at.distanceTo(delivery.center), delivery.radius))
                }
            } else signature(level, element, delivery.anchor, BOLT_SIGNATURE_SIZE)
            else -> {}
        }
    }

    private const val BOLT_SIGNATURE_SIZE = 1.5
}
