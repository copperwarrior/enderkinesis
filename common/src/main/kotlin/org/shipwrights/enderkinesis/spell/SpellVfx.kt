package org.shipwrights.enderkinesis.spell

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3

/**
 * Short-lived spell visuals, drawn by clients (see `SpellVfxClient` and `SpellImpacts`). The
 * server says what and where; the client draws, animates and fades it.
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
        /** Jagged, flickering lightning from [a] to [b], with branches, thinning to its tip. */
        ARC,
        /** Dark strands writhing from [a] to [b], drops of blood running along them. */
        STREAM,
        /** A quick bright flash at [a], [size] across. */
        FLASH,
        /** Obsidian cubes nested round [a], [size] across, crushing inward as they turn. */
        CRUSH,
        /** An element's burst where it lands, at [a], [size] about the radius of what was hit.
         *  The colour sent is the element's ordinal; the life sent is how many ticks to wait. */
        SIGNATURE,
        /** A Chain's lightning from [a] to [b], the rest of the spell riding it. The colour sent
         *  is the spell's elements, one bit per ordinal. */
        CHAIN,
        /** An element's Area wave going out from [a] (the caster's feet, or a rune) to [size]
         *  blocks, over the ground and, from [b], through the air. The colour sent is the
         *  element's ordinal. */
        AREA,
        /** The rune circle under an Area cast's caster at [a], in the colour sent. */
        RUNE_CIRCLE,
        /** A spell written into an item: its glyphs stream from the off-hand scroll into the
         *  main-hand item, and the scroll breaks. The seed sent is the imbuer's entity id. */
        IMBUE,
        /** An imbued weapon striking at [a]: the spell's glyphs burst out, in the colour sent. */
        GLYPH_BURST,
        /** An imbue fizzling at [a]: a puff of smoke, the glyphs falling grey. */
        FIZZLE,
        /** An imbued projectile setting off: it trails its elements. The seed sent is its entity
         *  id; the colour, the spell's elements, one bit per ordinal. */
        IMBUED_SHOT,
        /** A rune being written at the block [a] is in: the glyphs stream from the scroll to the
         *  face and the rune writes itself in. The seed sent is the writer's entity id; the size,
         *  the hand they wrote with. */
        INSCRIBE,
        /** A rune going off at the block [a] is in: it flares and its pixels burst off the face.
         *  The size sent is the face's direction. */
        RUNE_BURST,
        /** A block at [b] (its centre) changing into another: its old textures wipe away from
         *  [a], where the spell came from. The colour sent is the old block state's id; the
         *  size, the element's ordinal (or -1). */
        BLOCK_SHIFT,
        /** A block struck by Gravity at [a] (its centre): a dark frame pressing on it. The seed
         *  is the falling block it became, which puffs dust where it lands. */
        PRESS,
        /** A spell projectile turned by an Aegis at [a], leaving along [b] (a direction): glitter
         *  and its elements' sparks off the face, and a short white streak. The colour sent is
         *  its elements, one bit per ordinal. */
        AEGIS_DEFLECT,
        /** Lightning striking an Aegis face at [a], [b] the face's outward normal: it crawls over
         *  the face. */
        AEGIS_STRIKE,
        /** A mob dying to an element: it dies that element's way. The seed sent is its entity id;
         *  the colour, the element's ordinal; [a] where it was. */
        DEATH,
        /** Two beams with opposites between them exploding at [a], [size] in radius: explosion
         *  sprites, a flash the size of its force, a ring racing over the ground. */
        BEAM_EXPLOSION,
        /** A light shockwave at [a], [size] in radius: a swelling shell of white light, a ring
         *  over the ground, glitter thrown out. */
        SHOCKWAVE,
        /** Cleanse taking an effect off the entity whose id is the seed: the effect's swirls, in
         *  its colour (the colour sent), drain down the body into the ground, and glints puff
         *  up where they went. [a] is where it stood; the size, its height. */
        CLEANSE_DRAIN,
        /** Shuffle moving an effect, in the colour sent, from the entity whose id is the seed to
         *  the one whose id is the size (exactly): its swirls circle the first once, are flung
         *  across, and circle the second as they settle. [a] and [b] are where they stood. */
        SHUFFLE_SLING,
        /** A Singularity drawing in the entity whose id is the seed toward its core at [a]: two
         *  thin strands of void dust waver between them. Sent again while the pull lasts; the
         *  life is how long this one holds. [b] is where the entity was. */
        VOID_TETHER,
        /** A self-cast on the entity whose id is the seed, at [a]: the size is the element's
         *  ordinal, the life how long it plays. See `SelfCastVisuals`. */
        SELF_CAST,
        /** A Void Cloak's tentacle whipping out of the cloak round the entity whose id is the seed
         *  (standing at [a] as it does) at [b], then slowing and fading away. */
        LASH,

        // Parts of signatures, made by the client itself; never sent.
        /** Packed-ice shards flung out point first. */
        SHARDS,
        /** A white cube flashing and bursting into small tumbling cubes. */
        SHATTER,
        /** A hole into the Abyss, Ygann tentacles reaching out of it one by one. */
        RIFT,
        /** Green gas puffing out and drifting up. */
        PUFFS,
        /** A flash of sunlight. */
        SUN_FLASH,
        /** Two opposed beams about to explode where they cross, at [a]: the glyphs that will
         *  annihilate fly together in pairs along the line from [a] to [b], with sparks of both
         *  beams' colours. The colour sent is the first beam's, the size (exactly) the second's;
         *  the seed carries the pairs ([chargePairs]); the life is the ticks until it goes. */
        BEAM_CHARGE,
        /** Two beams meeting head-on at [a], pressing into a flattening disc of light facing
         *  along [a] to [b], until the shockwave. The life is the ticks until it goes. */
        BEAM_PRESS,
        /** Chaos swapping the blocks at [a] and [b] (their centres): both shrink away to points
         *  and grow back in each other's places. The colour sent is the block state at [a]
         *  before, the size (exactly) the one at [b]. */
        BLOCK_SWAP,
    }

    /** [pairs] (each the stronger beam's element of an annihilating pair; the other is its
     *  opposite), packed for [Kind.BEAM_CHARGE]'s seed: a count, then 3 bits for each. */
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

    /** How long a Cleanse's drain and a Shuffle's sling take, in ticks. */
    const val DRAIN_TICKS = 12
    const val SLING_TICKS = 30

    fun send(level: ServerLevel, kind: Kind, a: Vec3, b: Vec3, size: Float, rgb: Int, life: Int, seed: Int = level.random.nextInt()) {
        SpellNetwork.broadcastVfx(level, kind.ordinal, a, b, size, rgb, life, seed)
    }

    /** [entity] writing [spell] into the item in its main hand. */
    fun imbue(level: ServerLevel, entity: Entity, spell: Spell) =
        send(level, Kind.IMBUE, entity.position(), entity.position(), 1f, spell.markColour(), IMBUE_LIFE, entity.id)

    fun glyphBurst(level: ServerLevel, at: Vec3, spell: Spell) = send(level, Kind.GLYPH_BURST, at, at, 1f, spell.markColour(), GLYPH_BURST_LIFE)

    fun fizzle(level: ServerLevel, at: Vec3) = send(level, Kind.FIZZLE, at, at, 1f, 0x8A8A8A, 1)

    /** [entity] writing [spell] as a rune at [pos] with the scroll in [hand]. */
    fun inscribe(level: ServerLevel, pos: BlockPos, entity: Entity, hand: InteractionHand, spell: Spell) =
        send(level, Kind.INSCRIBE, Vec3.atCenterOf(pos), Vec3.atCenterOf(pos), hand.ordinal.toFloat(), spell.markColour(), INSCRIBE_LIFE, entity.id)

    /** The rune at [pos], written on the face toward [facing], going off. */
    fun runeBurst(level: ServerLevel, pos: BlockPos, facing: Direction, spell: Spell) =
        send(level, Kind.RUNE_BURST, Vec3.atCenterOf(pos), Vec3.atCenterOf(pos), facing.ordinal.toFloat(), spell.markColour(), RUNE_BURST_LIFE)

    /** The block at [pos] changed from [old], by [element], cast from [from]. */
    fun blockShift(level: ServerLevel, pos: BlockPos, old: BlockState, from: Vec3, element: Element?) =
        send(level, Kind.BLOCK_SHIFT, from, Vec3.atCenterOf(pos), (element?.ordinal ?: -1).toFloat(), Block.getId(old), BLOCK_SHIFT_LIFE)

    fun press(level: ServerLevel, pos: BlockPos, falling: Int) = send(level, Kind.PRESS, Vec3.atCenterOf(pos), Vec3.atCenterOf(pos), 1f, Element.GRAVITY.color, 6, falling)

    fun blockSwap(level: ServerLevel, a: BlockPos, b: BlockPos, oldA: BlockState, oldB: BlockState) =
        send(level, Kind.BLOCK_SWAP, Vec3.atCenterOf(a), Vec3.atCenterOf(b), Block.getId(oldB).toFloat(), Block.getId(oldA), 6)

    /** A block struck directly by a spell (an imbued shot): its glyphs burst where it lands. */
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

    /** [projectile] carrying [spell]: it trails the spell's elements until it lands. */
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

    /** A Chain's arc carrying the [payload] elements' particles along it. */
    fun chainArc(level: ServerLevel, from: Vec3, to: Vec3, payload: Collection<Element>, life: Int) =
        send(level, Kind.CHAIN, from, to, 1f, elementBits(payload), life)

    fun stream(level: ServerLevel, from: Vec3, to: Vec3, rgb: Int, life: Int = 16) =
        send(level, Kind.STREAM, from, to, 1f, rgb, life)

    fun crush(level: ServerLevel, at: Vec3, size: Double, rgb: Int, life: Int = 18) =
        send(level, Kind.CRUSH, at, at, size.toFloat(), rgb, life)

    fun flash(level: ServerLevel, at: Vec3, size: Double, rgb: Int, life: Int = 5) =
        send(level, Kind.FLASH, at, at, size.toFloat(), rgb, life)

    /** [element]'s burst at [at], [delay] ticks from now. */
    fun signature(level: ServerLevel, element: Element, at: Vec3, size: Double, delay: Int = 0) =
        send(level, Kind.SIGNATURE, at, at, size.toFloat(), element.ordinal, delay)

    /** The rune circle under an Area cast. */
    fun runeCircle(level: ServerLevel, at: Vec3, rgb: Int) = send(level, Kind.RUNE_CIRCLE, at, at, 1f, rgb, RUNE_CIRCLE_LIFE)

    fun elementBits(elements: Collection<Element>): Int = elements.fold(0) { bits, e -> bits or (1 shl e.ordinal) }

    fun elementsOf(bits: Int): List<Element> = Element.entries.filter { bits and (1 shl it.ordinal) != 0 }

    private const val RUNE_CIRCLE_LIFE = 16
    private const val LASH_TICKS = 16
    private const val IMBUE_LIFE = 32
    private const val GLYPH_BURST_LIFE = 12
    private const val BLOCK_SHIFT_LIFE = 24
    private const val INSCRIBE_LIFE = 24
    private const val RUNE_BURST_LIFE = 18

    /** An Area wave goes off a tick after the cast and takes [WAVE_TICKS] to reach its edge. */
    const val WAVE_START_TICKS = 1
    const val WAVE_TICKS = 12
    /** How long an Area wave's record lasts: its ground effects linger after it passes. */
    const val AREA_LIFE = 100

    /** Ticks from the cast until [element]'s wave of [radius] reaches something [distance]
     *  out. Gravity's closes in from the edge; the rest go out from the caster. */
    fun waveDelay(element: Element, distance: Double, radius: Double): Int {
        val f = (distance / radius).coerceIn(0.0, 1.0)
        return WAVE_START_TICKS + Math.round(WAVE_TICKS * if (element == Element.GRAVITY) 1 - f else f).toInt()
    }

    /** The burst each element leaves where it lands: on each thing it hits, or where a Bolt
     *  detonates; for an Area cast, its wave, and its burst on each thing as the wave reaches
     *  it. Held shapes (beams, sprays) are drawn by their own renderers instead. */
    fun element(ctx: SpellContext, delivery: Delivery, element: Element, magnitude: Float) {
        val level = ctx.level
        when (delivery) {
            is Delivery.SingleEntity -> signature(level, element, delivery.anchor, (delivery.entity.bbHeight * 0.75).toDouble())
            is Delivery.SingleBlock -> signature(level, element, delivery.anchor, 0.9)
            is Delivery.Group -> for (target in delivery.targets) {
                signature(level, element, target.position().add(0.0, target.bbHeight * 0.5, 0.0), (target.bbHeight * 0.75).toDouble())
            }
            is Delivery.Sphere -> if (delivery.waves) {
                // Through the air from a block above the caster's feet, or from a rune itself.
                val air = if (delivery.area) delivery.center.add(0.0, 1.0, 0.0) else delivery.center
                send(level, Kind.AREA, delivery.center, air, delivery.radius.toFloat(), element.ordinal, AREA_LIFE)
                // Each thing caught in the sphere takes the element's burst as the wave reaches it.
                for (target in delivery.living(ctx)) {
                    val at = target.position().add(0.0, target.bbHeight * 0.5, 0.0)
                    signature(level, element, at, (target.bbHeight * 0.55).toDouble(), waveDelay(element, at.distanceTo(delivery.center), delivery.radius))
                }
            } else signature(level, element, delivery.anchor, BOLT_SIGNATURE_SIZE)
            else -> {}
        }
    }

    /** About the burst a Bolt makes on a player-sized target. */
    private const val BOLT_SIGNATURE_SIZE = 1.5
}
