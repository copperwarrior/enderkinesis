package org.shipwrights.enderkinesis.item

import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import org.shipwrights.enderkinesis.sselith.SselithBookScore

/**
 * Wylland Tome — the Sselith-side "gravity gun" / "physics gun".
 *
 *  - **Left-click and hold** to grab the ship or entity under the
 *    crosshair, then drag it around at the current grab distance.
 *  - **Hold shift** while grabbing to rotate the grabbed target
 *    around the world Y axis using look direction.
 *  - Visible **enchantment-particle beam** runs from the player's view
 *    centre to the grab point while a target is held.
 *
 * The item itself carries no per-stack state — all grab bookkeeping
 * lives in [org.shipwrights.enderkinesis.item.WyllandTomeManager],
 * keyed by player UUID. The item only needs to exist in the player's
 * hand for the client-side input handler to engage; the model is the
 * generic vanilla book for now.
 */
class WyllandTomeItem(properties: Properties) : TomeItem(properties) {

    /** Placeholder lore until proper Wylland Tome pages are written: a single blank page on the
     *  lectern, just enough for the book view to render. The tome's actual function (grab + repair)
     *  lives in [use] and [WyllandTomeManager], not in any inscribed text. */
    override fun writtenBook(stack: ItemStack): ItemStack =
        TomeLore.blank("The Wylland Tome", "Sselith")

    /** TomeItem's base [isFoil] returns true for every tome so the
     *  shelf of generic tome textures gets a consistent enchanted-book
     *  shimmer. The Wylland Tome is its own asset with bespoke covers
     *  and the flap animation, and the foil glint reads as noise on
     *  top of that, so override the base back to no-foil for this
     *  one tome. */
    override fun isFoil(stack: ItemStack): Boolean = false

    /** The tome's power is intrinsic — it refuses every enchantment.
     *  Both halves matter: [getEnchantmentValue] = 0 keeps it out of the
     *  enchanting table, and `isEnchantable = false` overrides the
     *  default (which would return true purely because the item is now
     *  damageable) to also block the anvil's enchanted-book path. */
    override fun isEnchantable(stack: ItemStack): Boolean = false

    override fun getEnchantmentValue(): Int = 0

    /** Belt-and-braces enchantment block. [isEnchantable] / [getEnchantmentValue]
     *  keep the tome out of the enchanting *table*, but adding durability
     *  makes it match the `BREAKABLE` / `VANISHABLE` enchantment
     *  categories, so an **anvil + enchanted book** (Unbreaking, Mending,
     *  Curse of Vanishing…) would otherwise stick — that path checks the
     *  category, not [isEnchantable]. Rather than per-loader anvil mixins,
     *  we simply scrub any enchantment off the tome the tick after it
     *  lands. Cheap (a tag presence check) and works identically on
     *  Fabric and Forge. */
    override fun inventoryTick(
        stack: ItemStack, level: Level, entity: Entity, slotId: Int, isSelected: Boolean,
    ) {
        if (!level.isClientSide && stack.isEnchanted) {
            stack.removeTagKey("Enchantments")
        }
    }

    /** Right-click to repair: consume one written book held in the
     *  *other* hand and restore durability proportional to the book's
     *  writing quality. Works whether the tome is in the main or off
     *  hand — we always look at the opposite hand for the book.
     *
     *  The repair amount comes from [SselithBookScore]: a long, well-
     *  written book *in Sselith* approaches a full repair, plain English
     *  earns partial credit, and an empty book restores ≈ nothing.
     *  Only the author's signed original counts in full — duplicated
     *  books (generation ≥ 1) are scaled down by
     *  [SselithBookScore.COPY_PENALTY] so the player can't just craft-
     *  copy one masterwork into infinite repair. We pass through
     *  (consuming nothing) when the tome is already pristine, the other
     *  hand isn't a written book, or the book is worthless — so a blank
     *  book or a heavily-penalised copy is never eaten for no gain.
     *
     *  Scoring is deterministic and runs on both sides, so the client's
     *  predicted swing matches the server's actual repair. */
    override fun use(
        level: Level, player: Player, hand: InteractionHand,
    ): InteractionResultHolder<ItemStack> {
        val tome = player.getItemInHand(hand)
        if (tome.damageValue <= 0) return InteractionResultHolder.pass(tome)
        val otherHand =
            if (hand == InteractionHand.MAIN_HAND) InteractionHand.OFF_HAND
            else InteractionHand.MAIN_HAND
        val fuel = player.getItemInHand(otherHand)
        if (!fuel.`is`(Items.WRITTEN_BOOK)) return InteractionResultHolder.pass(tome)

        val restore = SselithBookScore.repairPoints(fuel, MAX_REPAIR_PER_BOOK)
        if (restore <= 0) return InteractionResultHolder.pass(tome)

        if (!level.isClientSide) {
            tome.damageValue = (tome.damageValue - restore).coerceAtLeast(0)
            if (!player.abilities.instabuild) fuel.shrink(1)
            level.playSound(
                null, player.x, player.y, player.z,
                SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.PLAYERS,
                0.8f, 0.9f + level.random.nextFloat() * 0.2f,
            )
        }
        // Intentionally NO `player.swing(hand)` here. The tome is a
        // grab-and-rotate tool, not a melee weapon — every left-click
        // is the start of a grab gesture and every right-click is a
        // repair gesture; neither should produce a hit-swing animation.
        // The left-click attack swing is cancelled by
        // [MinecraftWyllandTomeSwingMixin]; this is the matching no-op
        // for the right-click repair side.
        return InteractionResultHolder.sidedSuccess(tome, level.isClientSide)
    }

    /** Vanilla calls this before letting the player attack-and-break a
     *  block. Returning false stops the break action entirely — no block
     *  damage, no swing animation. Left-click is the grab gesture; it
     *  must never destroy blocks. */
    override fun canAttackBlock(
        state: BlockState, level: Level, pos: BlockPos, player: Player,
    ): Boolean = false

    override fun appendHoverText(
        stack: ItemStack,
        level: Level?,
        tooltip: MutableList<Component>,
        flag: TooltipFlag,
    ) {
        tooltip.add(
            Component.translatable("item.enderkinesis.wylland_tome.tooltip.grab")
                .withStyle(ChatFormatting.GRAY)
        )
        tooltip.add(
            Component.translatable("item.enderkinesis.wylland_tome.tooltip.rotate")
                .withStyle(ChatFormatting.DARK_GRAY)
        )
        tooltip.add(
            Component.translatable("item.enderkinesis.wylland_tome.tooltip.repair")
                .withStyle(ChatFormatting.DARK_GRAY)
        )
    }

    companion object {
        /** Maximum durability of the tome. Drains while a target is held
         *  (see [DURABILITY_DRAIN_PER_TICK]); the tome never shatters —
         *  at the bottom it simply refuses to grab until repaired with
         *  written books. */
        const val MAX_DURABILITY = 1000

        /** Durability spent per server tick while actively holding a
         *  ship or entity. At 20 tps a full tome lasts ~50 s of
         *  continuous use. Idle holding of the tome costs nothing. */
        const val DURABILITY_DRAIN_PER_TICK = 1

        /** Upper bound on durability a single written book can restore —
         *  the repair a *perfect* book yields. The actual amount scales
         *  with the book's [org.shipwrights.enderkinesis.sselith.SselithBookScore]
         *  quality, so an empty book restores ≈ nothing and a long, well-
         *  written Sselith book approaches a full repair. Equal to
         *  [MAX_DURABILITY] so one flawless tome of Sselith can fully heal
         *  the Wylland Tome. */
        const val MAX_REPAIR_PER_BOOK = MAX_DURABILITY

        /** A damageable tome counts as "broken" one point short of its
         *  max damage — the item is never destroyed (it can't reach the
         *  max-damage shatter threshold), it just stops grabbing here. */
        fun isBroken(stack: ItemStack): Boolean =
            stack.maxDamage > 0 && stack.damageValue >= stack.maxDamage - 1

        /** Spend [DURABILITY_DRAIN_PER_TICK] of the tome's durability,
         *  clamped so it can never reach the shatter threshold. */
        fun drain(stack: ItemStack) {
            if (stack.maxDamage <= 0) return
            stack.damageValue =
                (stack.damageValue + DURABILITY_DRAIN_PER_TICK)
                    .coerceAtMost(stack.maxDamage - 1)
        }

        /** Initial grab distance set when a target is first grabbed (blocks). */
        const val DEFAULT_GRAB_DISTANCE = 6.0
        /** Hard cap on grab distance — beyond this, the target slips. */
        const val MAX_GRAB_DISTANCE = 32.0
        /** Minimum grab distance — prevents pulling target into the camera. */
        const val MIN_GRAB_DISTANCE = 2.5

        /** Maximum distance the grab raycast reaches when first picking up. */
        const val PICKUP_REACH = 24.0

        /** Hard cap on player-to-anchor distance while held. If the
         *  grabbed object drifts further than this (collision, lag,
         *  player walked away) the grab snaps. Larger than
         *  [MAX_GRAB_DISTANCE] to leave the spring some slack. */
        const val MAX_HOLD_DISTANCE = 40.0

        /** Spring stiffness (1/tick) applied to ships and entities each
         *  tick to pull them toward the desired grab point. Damping is
         *  built into the simulation; tweak both together. */
        const val SPRING_STIFFNESS = 0.35
        /** Velocity damping per tick — fraction of the residual velocity
         *  subtracted from the spring force, prevents overshoot/oscillation. */
        const val SPRING_DAMPING = 0.30

        /** Yaw rate (degrees per pixel of mouse motion) while rotating. */
        const val ROTATE_DEG_PER_TICK = 6.0

        /** Blocks of distance adjusted per mouse-wheel notch when shift-
         *  scrolling. Positive scroll pulls the target closer; negative
         *  pushes it further away. */
        const val SCROLL_DISTANCE_STEP = 0.6

        /** Degrees of roll per mouse-wheel notch when mod-key+scrolling
         *  with a ship grabbed. Tuned so a full clockwise wheel turn
         *  (≈ 10 notches) gives roughly a 90° roll. */
        const val SCROLL_ROLL_STEP = 9.0

        /** Mouse-to-degrees multiplier when explicitly rotating with
         *  the mod key held. Vanilla LocalPlayer.turn uses 0.15 to
         *  convert post-sensitivity mouse delta to view degrees; the
         *  tome scales that down so a small wrist motion produces
         *  fine-grained ship rotation rather than a whip. */
        const val MOUSE_ROTATE_SENSITIVITY = 0.05

        /** Camera-relative drop from the eye position to the beam origin —
         *  the beam should emerge from below the chest so it reads as
         *  the player projecting the tome forward from waist height. */
        const val BEAM_ORIGIN_DROP = 0.85

        /** Number of beam particles spawned per client tick. With the
         *  short-lived flicker each particle gives, this needs to be
         *  high enough to keep the beam visually continuous. */
        const val BEAM_PARTICLES_PER_TICK = 8

        /** Bezier `t` advance per particle per tick. The particle dies
         *  when `t > 1` (reached the target end), so this determines
         *  how fast each spark flows along the beam. 0.15 → ≈ 7 ticks
         *  to traverse the full length, ≈ 0.35 s. */
        const val BEAM_FLOW_RATE = 0.15

        /** Downward droop applied to the beam's bezier control point
         *  (blocks). The midpoint of a quadratic bezier is pulled half
         *  this far, so the beam visibly sags in the middle — reads as
         *  the tome's tether having a little slack rather than a taut
         *  laser. Layered on top of the cursor bow, not instead of it. */
        const val BEAM_SAG = 0.7

        /** Radius of the beam at the player end (blocks). Linearly
         *  tapers to zero at the target along the bezier so the beam
         *  is a swept cone, not a single thread — fat where it leaves
         *  the player, precise where it touches the ship. */
        const val BEAM_RADIUS = 0.85

        /** Enchanted-book glyphs rained inside a targeted ship's local
         *  AABB per client tick. Deliberately low — the glyphs are
         *  emissive and full-bright (see [org.shipwrights.enderkinesis.client.WyllandTomeShipParticle]),
         *  so a couple per tick reads as a steady shimmer without the
         *  per-particle cost of a dense field. */
        const val SHIP_GLYPHS_PER_TICK = 2
    }
}
