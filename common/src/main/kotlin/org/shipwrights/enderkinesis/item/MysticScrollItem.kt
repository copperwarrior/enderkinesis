package org.shipwrights.enderkinesis.item

import net.minecraft.ChatFormatting
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
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

/**
 * Mystic Scroll, a finished spell from the Altar of Illumination. Every scroll is this one
 * item, always named "Mystic Scroll"; its spell name is generated from its glyphs and shown
 * in the tooltip, with the exact glyphs while Shift is held. Never stacks.
 *
 * Cast modes, chosen when it's used:
 * - **Sneak + right-click — Area:** a burst around the caster; used up at once.
 * - **Right-click and hold — Forward:** the spell's shape, held up to
 *   [SpellCasting.holdLimitTicks]. A Bolt charges and flies on release; a Beam, Chain or
 *   Spray re-applies every [SpellCasting.PULSE_TICKS] while held. Used up on release, or
 *   when the hold limit releases it.
 */
class MysticScrollItem(properties: Properties) : Item(properties) {

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        val spell = spellOf(stack) ?: return InteractionResultHolder.pass(stack)
        if (player.isSecondaryUseActive) {
            if (level is ServerLevel) {
                SpellCasting.burst(SpellContext(level, player, player.position()), spell, player.position(), area = true)
                useUp(level, player, stack)
            }
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
        val spell = spellOf(stack) ?: return
        val shapeElement = spell.shapeElement ?: return
        val held = getUseDuration(stack) - remainingUseDuration
        val origin = entity.eyePosition
        val look = entity.lookAngle
        if (shapeElement.shape == Shape.BOLT) {
            if (held % BOLT_CHARGE_FX_TICKS == 0) {
                val p = origin.add(look)
                level.sendParticles(SpellCasting.dust(shapeElement), p.x, p.y, p.z, 4, 0.15, 0.15, 0.15, 0.0)
            }
            return
        }
        val ctx = SpellContext(level, entity, origin, pulsed = true)
        val pulse = held % SpellCasting.PULSE_TICKS == 0
        if (shapeElement.shape == Shape.BEAM) {
            // Every tick, so the beam stays registered for crossings while held.
            SpellCasting.beamTick(ctx, spell, entity, origin, look, pulse, held % BEAM_TRAIL_TICKS == 0)
        } else if (pulse) {
            SpellCasting.pulse(ctx, spell, origin, look)
        }
    }

    override fun releaseUsing(stack: ItemStack, level: Level, entity: LivingEntity, timeLeft: Int) {
        release(stack, level, entity, getUseDuration(stack) - timeLeft)
    }

    /** The hold limit was reached: release as if let go. */
    override fun finishUsingItem(stack: ItemStack, level: Level, entity: LivingEntity): ItemStack {
        release(stack, level, entity, getUseDuration(stack))
        return stack
    }

    /** A cast abandoned without a release (the scroll was switched away from mid-hold) fizzles
     *  but still uses the scroll up, so a hold can't be cut short to keep it. */
    override fun inventoryTick(stack: ItemStack, level: Level, entity: Entity, slot: Int, selected: Boolean) {
        if (level !is ServerLevel || stack.tag?.getBoolean(NBT_CASTING) != true) return
        if (entity is LivingEntity && entity.isUsingItem && entity.useItem === stack) return
        stack.removeTagKey(NBT_CASTING)
        if (entity is LivingEntity) useUp(level, entity, stack)
    }

    private fun release(stack: ItemStack, level: Level, entity: LivingEntity, held: Int) {
        if (level !is ServerLevel || stack.tag?.getBoolean(NBT_CASTING) != true) return
        stack.removeTagKey(NBT_CASTING)
        val spell = spellOf(stack) ?: return
        val shapeElement = spell.shapeElement ?: return
        if (shapeElement.shape == Shape.BOLT) {
            val charge = (held.toFloat() / BOLT_FULL_CHARGE_TICKS).coerceIn(BOLT_MIN_CHARGE, 1f)
            val bolt = SpellBoltEntity(level, entity, spell)
            bolt.shootFromRotation(
                entity, entity.xRot, entity.yRot, 0f,
                SpellBoltEntity.maxSpeed(spell.magnitude(shapeElement)) * charge, BOLT_INACCURACY,
            )
            level.addFreshEntity(bolt)
            level.playSound(null, entity.x, entity.y, entity.z, SoundEvents.ENDER_PEARL_THROW, SoundSource.PLAYERS, 1f, 0.6f)
        } else if (held == 0) {
            // Let go before the first tick: still one pulse, so a tap is never wasted.
            SpellCasting.pulse(SpellContext(level, entity, entity.eyePosition, pulsed = true), spell, entity.eyePosition, entity.lookAngle)
        }
        useUp(level, entity, stack)
    }

    private fun useUp(level: ServerLevel, entity: LivingEntity, stack: ItemStack) {
        if (entity !is Player || !entity.abilities.instabuild) stack.shrink(1)
        level.playSound(null, entity.x, entity.y, entity.z, SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 1f, 0.8f)
    }

    override fun appendHoverText(stack: ItemStack, level: Level?, tooltip: MutableList<Component>, flag: TooltipFlag) {
        val spell = spellOf(stack) ?: return
        val name = spell.name() ?: return
        tooltip += name.toComponent().withStyle(ChatFormatting.ITALIC)
        // Tooltips are only built on the client, so the client-only Screen class is safe here.
        if (Screen.hasShiftDown()) tooltip += spell.glyphLine()
    }

    companion object {
        /** Wrap colour for a scroll with no spell. */
        const val BLANK_WRAP_COLOR = 0xFFFFFF

        /** Set while a Forward cast is held, so a cast that ends without a release still
         *  uses the scroll up. */
        private const val NBT_CASTING = "Casting"

        private const val BOLT_FULL_CHARGE_TICKS = 30
        private const val BOLT_MIN_CHARGE = 0.25f
        private const val BOLT_INACCURACY = 0.5f
        private const val BOLT_CHARGE_FX_TICKS = 4
        private const val BEAM_TRAIL_TICKS = 2

        fun spellOf(stack: ItemStack): Spell? =
            stack.tag?.let { Spell.load(it, Spell.NBT_GLYPHS) }?.takeIf { it.glyphs.isNotEmpty() }

        /** The wrap layer's tint; see [Spell.tint]. */
        fun wrapColor(stack: ItemStack): Int = spellOf(stack)?.tint() ?: BLANK_WRAP_COLOR
    }
}
