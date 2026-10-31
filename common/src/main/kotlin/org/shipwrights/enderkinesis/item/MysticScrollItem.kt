package org.shipwrights.enderkinesis.item

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import java.util.Optional
import net.minecraft.ChatFormatting
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.UseAnim
import net.minecraft.world.level.Level
import org.shipwrights.enderkinesis.entity.SpellBoltEntity
import org.shipwrights.enderkinesis.spell.Shape
import org.shipwrights.enderkinesis.spell.Spell
import org.shipwrights.enderkinesis.spell.SpellCasting
import org.shipwrights.enderkinesis.spell.SpellContext
import org.shipwrights.enderkinesis.spell.SpellUse
import org.shipwrights.enderkinesis.spell.SpellNetwork
import org.shipwrights.enderkinesis.spell.signature.Signatures
import org.shipwrights.enderkinesis.registry.EKItems

/**
 * Mystic Scroll, a finished spell from the Altar of Illumination. Every scroll is this one
 * item, always named "Mystic Scroll"; its spell name is generated from its glyphs and shown
 * in the tooltip, with the exact glyphs while Shift is held. Never stacks.
 *
 * A scroll holds a charge of [USES] uses' worth, shown as a durability bar. An Area cast, a
 * rune or an imbue spends one use; a Forward cast spends as much as it was held for (see
 * [forwardCost]). When the charge runs out the scroll breaks, giving back a share of the shards
 * its glyphs were written from ([spend]); a cast held as it runs dry lets go there.
 *
 * Cast modes, chosen when it's used:
 * - **Sneak + right-click — Area:** a burst around the caster; one use.
 * - **Right-click and hold — Forward:** the spell's shape, held up to
 *   [SpellCasting.holdLimitTicks]. A Bolt charges and flies on release; a Beam, Chain or
 *   Spray re-applies every [SpellCasting.PULSE_TICKS] while held (a Beam or Spray a share of
 *   that every [SpellCasting.STREAM_TICKS]). Spent on release, or when
 *   the hold limit or the charge running out releases it.
 */
class MysticScrollItem(properties: Properties) : Item(properties) {

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        val spell = spellOf(stack) ?: return InteractionResultHolder.pass(stack)
        if (player.isSecondaryUseActive) {
            if (level is ServerLevel) {
                (player as? ServerPlayer)?.let { SpellNetwork.broadcastUsed(level, it, OtherUse.AREA, hand) }
                SpellCasting.burst(SpellContext(level, player, player.position(), use = SpellUse.AREA), spell, player.position(), area = true)
                useUp(level, player, stack)
                startCooldown(player)
            } else clientUsed?.invoke(player, OtherUse.AREA, hand)
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide)
        }
        if (!level.isClientSide) stack.orCreateTag.putBoolean(NBT_CASTING, true)
        player.startUsingItem(hand)
        return InteractionResultHolder.consume(stack)
    }

    override fun getUseDuration(stack: ItemStack): Int = spellOf(stack)?.let(SpellCasting::holdLimitTicks) ?: 0

    override fun getUseAnimation(stack: ItemStack): UseAnim = UseAnim.BOW

    override fun onUseTick(level: Level, entity: LivingEntity, stack: ItemStack, remainingUseDuration: Int) {
        if (level !is ServerLevel) return
        // Vanilla carries a use over when the hand switches to another scroll; that one was
        // never started, so it doesn't cast (the one switched away from fizzles).
        if (stack.tag?.getBoolean(NBT_CASTING) != true) {
            entity.stopUsingItem()
            return
        }
        val spell = spellOf(stack) ?: return
        val shapeElement = spell.shapeElement ?: return
        val held = getUseDuration(stack) - remainingUseDuration
        // The charge runs dry: the cast lets go, as at the hold limit.
        if (spent(stack) + forwardCost(spell, held) >= USES) {
            release(stack, level, entity, held, limit = true)
            entity.stopUsingItem()
            return
        }
        // A signature casts as itself, not as its shape.
        spell.signature?.let { Signatures.useTick(level, entity, entity.usedItemHand, spell, it, held); return }
        // Every cast leaves the hand holding the scroll, aimed where the caster looks.
        val origin = SpellCasting.handOrigin(entity, entity.usedItemHand)
        val look = SpellCasting.handAim(entity, origin)
        if (shapeElement.shape == Shape.BOLT) {
            return
        }
        val pulse = held % SpellCasting.PULSE_TICKS == 0
        if (shapeElement.shape == Shape.CHAIN) {
            if (pulse) SpellCasting.pulse(SpellContext(level, entity, origin, pulsed = true), spell, origin, look)
            return
        }
        // A Beam or Spray applies a share of a pulse at a time, so it reaches all it sweeps over.
        val ctx = SpellContext(level, entity, origin, pulsed = true, share = SpellCasting.STREAM_SHARE, beat = pulse)
        val apply = held % SpellCasting.STREAM_TICKS == 0
        if (shapeElement.shape == Shape.BEAM) {
            // Every tick, so the beam stays registered for crossings while held.
            SpellCasting.beamTick(ctx, spell, entity, origin, look, apply)
        } else if (apply) {
            SpellCasting.pulse(ctx, spell, origin, look)
        }
    }

    override fun releaseUsing(stack: ItemStack, level: Level, entity: LivingEntity, timeLeft: Int) {
        if (level.isClientSide && entity is Player) clientReleased?.invoke(entity)
        release(stack, level, entity, getUseDuration(stack) - timeLeft, limit = false)
    }

    /** The hold limit was reached: release as if let go. Server only. */
    override fun finishUsingItem(stack: ItemStack, level: Level, entity: LivingEntity): ItemStack {
        release(stack, level, entity, getUseDuration(stack), limit = true)
        return stack
    }

    /** A cast abandoned without a release (the scroll was switched away from mid-hold) fizzles
     *  but still uses the scroll up, so a hold can't be cut short to keep it. */
    override fun inventoryTick(stack: ItemStack, level: Level, entity: Entity, slot: Int, selected: Boolean) {
        if (level !is ServerLevel || stack.tag?.getBoolean(NBT_CASTING) != true) return
        if (entity is LivingEntity && entity.isUsingItem && entity.useItem === stack) return
        stack.removeTagKey(NBT_CASTING)
        if (entity is LivingEntity) {
            Signatures.abandon(entity)
            useUp(level, entity, stack)
        }
    }

    /** [limit]: the hold limit let go, not the caster, whose own client then didn't see it. */
    private fun release(stack: ItemStack, level: Level, entity: LivingEntity, held: Int, limit: Boolean) {
        if (level !is ServerLevel || stack.tag?.getBoolean(NBT_CASTING) != true) return
        stack.removeTagKey(NBT_CASTING)
        (entity as? ServerPlayer)?.let { SpellNetwork.broadcastReleased(level, it, toSelf = limit) }
        val spell = spellOf(stack) ?: return
        val shapeElement = spell.shapeElement ?: return
        val signature = spell.signature
        if (signature != null) {
            Signatures.release(level, entity, entity.usedItemHand, spell, signature, held)
        } else if (shapeElement.shape == Shape.BOLT) {
            val charge = (held.toFloat() / BOLT_FULL_CHARGE_TICKS).coerceIn(BOLT_MIN_CHARGE, 1f)
            val origin = SpellCasting.handOrigin(entity, entity.usedItemHand)
            val aim = SpellCasting.handAim(entity, origin)
            // Its burst hits harder the further it was drawn.
            val bolt = SpellBoltEntity(level, entity, spell, origin, SpellCasting.boltPower(charge))
            bolt.shoot(aim.x, aim.y, aim.z, SpellBoltEntity.maxSpeed(spell.magnitude(shapeElement)) * charge, BOLT_INACCURACY)
            // The caster's own motion carries into the throw, as for any projectile.
            val carried = entity.deltaMovement
            bolt.deltaMovement = bolt.deltaMovement.add(carried.x, if (entity.onGround()) 0.0 else carried.y, carried.z)
            level.addFreshEntity(bolt)
        } else if (held == 0) {
            // Let go before the first tick: still one pulse, so a tap is never wasted.
            val origin = SpellCasting.handOrigin(entity, entity.usedItemHand)
            SpellCasting.pulse(SpellContext(level, entity, origin, pulsed = true), spell, origin, SpellCasting.handAim(entity, origin))
        }
        spend(level, entity, stack, forwardCost(spell, held))
    }

    private fun useUp(level: ServerLevel, entity: LivingEntity, stack: ItemStack) = spend(level, entity, stack)

    /** The charge left, as a share of a full scroll's. */
    private fun left(stack: ItemStack): Float = ((USES - spent(stack)) / USES).coerceIn(0f, 1f)

    override fun isBarVisible(stack: ItemStack): Boolean = spent(stack) > 0f

    override fun getBarWidth(stack: ItemStack): Int = Math.round(13f * left(stack))

    override fun getBarColor(stack: ItemStack): Int = Mth.hsvToRgb(left(stack) / 3f, 1f, 1f)

    override fun appendHoverText(stack: ItemStack, level: Level?, tooltip: MutableList<Component>, flag: TooltipFlag) {
        val spell = spellOf(stack) ?: return
        val signature = spell.signature
        if (signature != null) tooltip += signature.displayName().withStyle(ChatFormatting.GOLD, ChatFormatting.ITALIC)
        else tooltip += (spell.name() ?: return).toComponent().withStyle(ChatFormatting.ITALIC)
        // Tooltips are only built on the client, so the client-only Screen class is safe here.
        if (Screen.hasShiftDown()) tooltip += spell.glyphLine()
    }

    companion object {
        /** The scroll's uses besides a held cast: writing a rune, imbuing an item, an Area cast. */
        enum class OtherUse { RUNE, IMBUE, AREA }

        /** Told, on the client, when its own player uses a scroll one of those ways (the
         *  first-person arm plays it). */
        var clientUsed: ((Player, OtherUse, InteractionHand) -> Unit)? = null

        /** Told, on the client, when its own player lets go of a held cast. */
        var clientReleased: ((Player) -> Unit)? = null

        /** The shared cooldown after an Area cast, a rune or an imbue, so a hotbar of scrolls
         *  can't be emptied in a few ticks. It's the item's own cooldown, so every scroll shares
         *  it and the hotbar shows it. */
        const val OTHER_USE_COOLDOWN_TICKS = 10

        /** [stack] is being dropped by [player]: a cast still held on it fizzles and spends a
         *  use, so dropping a scroll can't save one after a long hold. */
        fun dropped(player: Player, stack: ItemStack) {
            val level = player.level() as? ServerLevel ?: return
            if (stack.item !is MysticScrollItem || stack.tag?.getBoolean(NBT_CASTING) != true) return
            stack.removeTagKey(NBT_CASTING)
            Signatures.abandon(player)
            spend(level, player, stack)
        }

        /** A scroll's full charge, in uses: one is an Area cast, a rune, an imbue, or a Forward
         *  cast held to its limit. */
        const val USES = 3f

        /** The share of a broken scroll's component shards that come back: a fusion glyph's
         *  components are the two shards it was fused from. */
        const val SHARD_RETURN = 0.345

        private const val NBT_SPENT = "Spent"

        /** How much of the charge has been spent, in uses. */
        fun spent(stack: ItemStack): Float = stack.tag?.getFloat(NBT_SPENT) ?: 0f

        /** Gives [amount] uses of charge back to [stack], up to full. */
        fun recharge(stack: ItemStack, amount: Float) {
            val spent = spent(stack)
            if (spent <= 0f) return
            val left = spent - amount
            if (left <= CHARGE_EPSILON) stack.removeTagKey(NBT_SPENT) else stack.orCreateTag.putFloat(NBT_SPENT, left)
        }

        /**
         * What a Forward cast held [held] ticks spends. A Bolt spends its charge: a full throw
         * is one use, the weakest a quarter. A Beam, Chain or Spray spends [CAST_START] to begin
         * and the rest of a use over its hold limit (counting at least one pulse), so held to
         * the limit it's exactly one use, while the same time spent in quick pulses costs a
         * start for every one: ten taps are a use before what they were held for.
         */
        fun forwardCost(spell: Spell, held: Int): Float =
            if (spell.castShape == Shape.BOLT) (held.toFloat() / BOLT_FULL_CHARGE_TICKS).coerceIn(BOLT_MIN_CHARGE, 1f)
            else CAST_START + (1f - CAST_START) * maxOf(held, SpellCasting.PULSE_TICKS).toFloat() / SpellCasting.holdLimitTicks(spell).coerceAtLeast(1)

        /** What starting a Beam, Chain or Spray costs, in uses, however briefly it's held. */
        const val CAST_START = 0.1f

        /**
         * Spends [amount] uses of [stack]'s charge, for [entity]. A creative player never wears
         * a scroll out. When the charge is gone it breaks into [SHARD_RETURN] of its component
         * shards, picked at random, the count rounded up or down at random so that on average
         * exactly that share comes back. The shards go into a player's inventory, or drop
         * where there's no room (or where the user isn't a player).
         */
        fun spend(level: ServerLevel, entity: LivingEntity, stack: ItemStack, amount: Float = 1f) {
            if (entity is Player && entity.abilities.instabuild) return
            val spent = spent(stack) + amount
            if (spent < USES - CHARGE_EPSILON) {
                stack.orCreateTag.putFloat(NBT_SPENT, spent)
                return
            }
            val spell = spellOf(stack)
            stack.shrink(1)
            spell ?: return
            val components = spell.glyphs.flatMap { it.components }.toMutableList()
            val exact = components.size * SHARD_RETURN
            val count = Mth.floor(exact) + if (level.random.nextDouble() < exact - Mth.floor(exact)) 1 else 0
            components.shuffle(java.util.Random(level.random.nextLong()))
            for (element in components.take(count)) {
                val shard = ItemStack(EKItems.shardOf(element) ?: continue)
                if (entity is Player) entity.inventory.add(shard)
                if (shard.isEmpty) continue
                val drop = ItemEntity(level, entity.x, entity.y, entity.z, shard)
                drop.setDefaultPickUpDelay()
                level.addFreshEntity(drop)
            }
        }

        fun coolingDown(player: Player): Boolean = player.cooldowns.isOnCooldown(EKItems.MYSTIC_SCROLL.get())

        fun startCooldown(player: Player) {
            if (!player.abilities.instabuild) player.cooldowns.addCooldown(EKItems.MYSTIC_SCROLL.get(), OTHER_USE_COOLDOWN_TICKS)
        }

        /** Wrap colour for a scroll with no spell. */
        const val BLANK_WRAP_COLOR = 0xFFFFFF

        /** Set while a Forward cast is held, so a cast that ends without a release still
         *  uses the scroll up. */
        private const val NBT_CASTING = "Casting"

        private const val BOLT_FULL_CHARGE_TICKS = 30
        private const val BOLT_MIN_CHARGE = 0.25f
        private const val BOLT_INACCURACY = 0.5f
        /** Charge left below this counts as none, so float sums of shares don't leave a sliver. */
        private const val CHARGE_EPSILON = 1.0e-3f

        fun spellOf(stack: ItemStack): Spell? {
            val tag = stack.tag ?: return null
            if (!tag.contains(Spell.NBT_GLYPHS, Tag.TAG_LIST.toInt())) return null
            val glyphs = tag.getList(Spell.NBT_GLYPHS, Tag.TAG_STRING.toInt())
            return PARSED.get(glyphs) { Optional.ofNullable(Spell.load(glyphs).takeIf { it.glyphs.isNotEmpty() }) }.orElse(null)
        }

        /** Spells already read, by their glyph list (by identity, held weakly): spellOf is asked
         *  every pulse and every frame, and a scroll's glyphs never change once written. */
        private val PARSED: Cache<ListTag, Optional<Spell>> = CacheBuilder.newBuilder().weakKeys().build()

        /** The wrap layer's tint; see [Spell.tint]. */
        fun wrapColor(stack: ItemStack): Int = spellOf(stack)?.tint() ?: BLANK_WRAP_COLOR
    }
}
