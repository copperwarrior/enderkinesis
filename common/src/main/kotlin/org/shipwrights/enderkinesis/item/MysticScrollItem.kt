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
        // Vanilla carries a use over when the hand switches to another scroll. That scroll never
        // started, so it must not cast.
        if (stack.tag?.getBoolean(NBT_CASTING) != true) {
            entity.stopUsingItem()
            return
        }
        val spell = spellOf(stack) ?: return
        val shapeElement = spell.shapeElement ?: return
        val held = getUseDuration(stack) - remainingUseDuration
        if (spent(stack) + forwardCost(spell, held) >= USES) {
            release(stack, level, entity, held, limit = true)
            entity.stopUsingItem()
            return
        }
        spell.signature?.let { Signatures.useTick(level, entity, entity.usedItemHand, spell, it, held); return }
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
        // A Beam or Spray applies a share of a pulse at a time, so it reaches all that it sweeps.
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

    /** Vanilla calls this at the hold limit. */
    override fun finishUsingItem(stack: ItemStack, level: Level, entity: LivingEntity): ItemStack {
        release(stack, level, entity, getUseDuration(stack), limit = true)
        return stack
    }

    /** A cast abandoned mid-hold still uses the scroll up, so a hold cannot stop early to keep it. */
    override fun inventoryTick(stack: ItemStack, level: Level, entity: Entity, slot: Int, selected: Boolean) {
        if (level !is ServerLevel || stack.tag?.getBoolean(NBT_CASTING) != true) return
        if (entity is LivingEntity && entity.isUsingItem && entity.useItem === stack) return
        stack.removeTagKey(NBT_CASTING)
        if (entity is LivingEntity) {
            Signatures.abandon(entity)
            useUp(level, entity, stack)
        }
    }

    /** [limit] is true when the hold limit let go. The client of the caster did not see that release. */
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
            val bolt = SpellBoltEntity(level, entity, spell, origin, SpellCasting.boltPower(charge))
            bolt.shoot(aim.x, aim.y, aim.z, SpellBoltEntity.maxSpeed(spell.magnitude(shapeElement)) * charge, BOLT_INACCURACY)
            val carried = entity.deltaMovement
            bolt.deltaMovement = bolt.deltaMovement.add(carried.x, if (entity.onGround()) 0.0 else carried.y, carried.z)
            level.addFreshEntity(bolt)
        } else if (held == 0) {
            // A release before the first tick still gives one pulse.
            val origin = SpellCasting.handOrigin(entity, entity.usedItemHand)
            SpellCasting.pulse(SpellContext(level, entity, origin, pulsed = true), spell, origin, SpellCasting.handAim(entity, origin))
        }
        spend(level, entity, stack, forwardCost(spell, held))
    }

    private fun useUp(level: ServerLevel, entity: LivingEntity, stack: ItemStack) = spend(level, entity, stack)

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
        enum class OtherUse { RUNE, IMBUE, AREA }

        /** Client only: the first-person arm plays these. */
        var clientUsed: ((Player, OtherUse, InteractionHand) -> Unit)? = null

        var clientReleased: ((Player) -> Unit)? = null

        /** Stops a hotbar of scrolls from emptying in a few ticks. All scrolls share the item cooldown. */
        const val OTHER_USE_COOLDOWN_TICKS = 10

        /** A held cast spends a use, so a drop cannot save one after a long hold. */
        fun dropped(player: Player, stack: ItemStack) {
            val level = player.level() as? ServerLevel ?: return
            if (stack.item !is MysticScrollItem || stack.tag?.getBoolean(NBT_CASTING) != true) return
            stack.removeTagKey(NBT_CASTING)
            Signatures.abandon(player)
            spend(level, player, stack)
        }

        /** One use is an Area cast, a rune, an imbue, or a Forward cast held to its limit. */
        const val USES = 3f

        /** The components of a fusion glyph are the two shards that made it. */
        const val SHARD_RETURN = 0.345

        private const val NBT_SPENT = "Spent"

        fun spent(stack: ItemStack): Float = stack.tag?.getFloat(NBT_SPENT) ?: 0f

        fun recharge(stack: ItemStack, amount: Float) {
            val spent = spent(stack)
            if (spent <= 0f) return
            val left = spent - amount
            if (left <= CHARGE_EPSILON) stack.removeTagKey(NBT_SPENT) else stack.orCreateTag.putFloat(NBT_SPENT, left)
        }

        /** Each Beam, Chain or Spray start costs [CAST_START], so quick taps cost more than one long hold. */
        fun forwardCost(spell: Spell, held: Int): Float =
            if (spell.castShape == Shape.BOLT) (held.toFloat() / BOLT_FULL_CHARGE_TICKS).coerceIn(BOLT_MIN_CHARGE, 1f)
            else CAST_START + (1f - CAST_START) * maxOf(held, SpellCasting.PULSE_TICKS).toFloat() / SpellCasting.holdLimitTicks(spell).coerceAtLeast(1)

        const val CAST_START = 0.1f

        /** The shard count rounds at random, so on average exactly [SHARD_RETURN] comes back. */
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

        const val BLANK_WRAP_COLOR = 0xFFFFFF

        /** So a cast that ends without a release still uses the scroll up. */
        private const val NBT_CASTING = "Casting"

        private const val BOLT_FULL_CHARGE_TICKS = 30
        private const val BOLT_MIN_CHARGE = 0.25f
        private const val BOLT_INACCURACY = 0.5f
        /** Float sums of shares must not leave a sliver of charge. */
        private const val CHARGE_EPSILON = 1.0e-3f

        fun spellOf(stack: ItemStack): Spell? {
            val tag = stack.tag ?: return null
            if (!tag.contains(Spell.NBT_GLYPHS, Tag.TAG_LIST.toInt())) return null
            val glyphs = tag.getList(Spell.NBT_GLYPHS, Tag.TAG_STRING.toInt())
            return PARSED.get(glyphs) { Optional.ofNullable(Spell.load(glyphs).takeIf { it.glyphs.isNotEmpty() }) }.orElse(null)
        }

        /** Weak identity keys: spellOf runs every pulse and frame, and written glyphs never change. */
        private val PARSED: Cache<ListTag, Optional<Spell>> = CacheBuilder.newBuilder().weakKeys().build()

        fun wrapColor(stack: ItemStack): Int = spellOf(stack)?.tint() ?: BLANK_WRAP_COLOR
    }
}
