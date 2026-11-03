package org.shipwrights.enderkinesis.item

import net.minecraft.ChatFormatting
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.SwordItem
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.level.Level
import org.shipwrights.enderkinesis.dimension.WeheloPocket
import org.shipwrights.enderkinesis.entity.WeheloTearEntity
import org.shipwrights.enderkinesis.registry.EKSounds
import org.shipwrights.enderkinesis.spell.SpellSounds
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Charge, not durability: each hit and each tear spends one. Empty, the blade is inert until the fountain refills it. */
class WeheloBladeItem(properties: Properties) :
    SwordItem(WeheloBlade.TIER, WeheloBlade.ATTACK_DAMAGE_MODIFIER, WeheloBlade.ATTACK_SPEED_MODIFIER, properties),
    Rechargeable {

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        if (charge(stack) <= 0) return InteractionResultHolder.fail(stack)
        if (level is ServerLevel && player is ServerPlayer) {
            val tear = WeheloPocket.openTear(level, player) ?: return InteractionResultHolder.fail(stack)
            SpellSounds.play(level, tear.position(), EKSounds.WEHELO_TEAR_OPEN)
            spend(stack, player)
            // One tear at a time.
            player.cooldowns.addCooldown(this, WeheloTearEntity.LIFE_TICKS)
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide)
    }

    override fun hurtEnemy(stack: ItemStack, target: LivingEntity, attacker: LivingEntity): Boolean {
        val level = target.level()
        if (level is ServerLevel && charge(stack) > 0) SpellSounds.play(level, target.position(), EKSounds.WEHELO_HIT)
        spend(stack, attacker)
        return true
    }

    override fun recharge(stack: ItemStack, share: Double) {
        setCharge(stack, charge(stack) + ceil(share * MAX_CHARGE).toInt())
    }

    override fun isBarVisible(stack: ItemStack): Boolean = charge(stack) < MAX_CHARGE

    override fun getBarWidth(stack: ItemStack): Int = (BAR_WIDTH * charge(stack) / MAX_CHARGE.toFloat()).roundToInt()

    override fun getBarColor(stack: ItemStack): Int = WeheloBlade.RIFT_RGB

    override fun appendHoverText(stack: ItemStack, level: Level?, tooltip: MutableList<Component>, flag: TooltipFlag) {
        tooltip += Component.translatable("item.enderkinesis.wehelo_blade.charge", charge(stack), MAX_CHARGE)
            .withStyle(ChatFormatting.BLUE)
    }

    // As durability: creative use costs nothing.
    private fun spend(stack: ItemStack, user: LivingEntity) {
        if (user is Player && user.abilities.instabuild) return
        val charge = charge(stack)
        if (charge > 0) setCharge(stack, charge - 1, user)
    }

    /** [holder] is where the inert sound plays. */
    private fun setCharge(stack: ItemStack, value: Int, holder: LivingEntity? = null) {
        val before = charge(stack)
        val after = value.coerceIn(0, MAX_CHARGE)
        if (after >= MAX_CHARGE) stack.tag?.remove(CHARGE_KEY) else stack.orCreateTag.putInt(CHARGE_KEY, after)
        if ((before == 0) != (after == 0)) setInert(stack, after == 0)
        val level = holder?.level()
        if (before > 0 && after == 0 && level is ServerLevel) SpellSounds.play(level, holder.position(), EKSounds.WEHELO_INERT)
    }

    /**
     * Vanilla reads attributes from the item, not the stack, unless the stack has `AttributeModifiers`.
     * So an empty blade carries its own weak damage modifier there; the base modifier ids keep the tooltip as a sword's.
     */
    private fun setInert(stack: ItemStack, inert: Boolean) {
        val tag = stack.orCreateTag
        if (!tag.contains(ATTRIBUTES_KEY, Tag.TAG_LIST.toInt())) {
            if (!inert) return
            for ((attribute, modifier) in getDefaultAttributeModifiers(EquipmentSlot.MAINHAND).entries()) {
                stack.addAttributeModifier(attribute, modifier, EquipmentSlot.MAINHAND)
            }
            tag.putBoolean(OWNS_ATTRIBUTES_KEY, true)
        }
        val list = tag.getList(ATTRIBUTES_KEY, Tag.TAG_COMPOUND.toInt())
        list.removeIf { (it as? CompoundTag)?.let { e -> e.hasUUID("UUID") && e.getUUID("UUID") == BASE_ATTACK_DAMAGE_UUID } == true }
        val amount = if (inert) INERT_DAMAGE else damage.toDouble()
        stack.addAttributeModifier(
            Attributes.ATTACK_DAMAGE,
            AttributeModifier(BASE_ATTACK_DAMAGE_UUID, "Weapon modifier", amount, AttributeModifier.Operation.ADDITION),
            EquipmentSlot.MAINHAND,
        )
        // Drop the list again only when it holds nothing but the defaults this method wrote.
        val onlyDefaults = list.all { (it as? CompoundTag)?.let { e -> e.hasUUID("UUID") && e.getUUID("UUID") in BASE_IDS } == true }
        if (!inert && tag.getBoolean(OWNS_ATTRIBUTES_KEY) && onlyDefaults) {
            tag.remove(ATTRIBUTES_KEY)
            tag.remove(OWNS_ATTRIBUTES_KEY)
        }
    }

    companion object {
        const val MAX_CHARGE = 64
        private const val CHARGE_KEY = "WeheloCharge"
        private const val ATTRIBUTES_KEY = "AttributeModifiers"
        private const val OWNS_ATTRIBUTES_KEY = "WeheloOwnsAttributes"
        private const val BAR_WIDTH = 13
        /** Added to the base 1, so an empty blade hits as a bare hand. */
        private const val INERT_DAMAGE = 0.0
        private val BASE_IDS = setOf(BASE_ATTACK_DAMAGE_UUID, BASE_ATTACK_SPEED_UUID)

        /** A stack without the tag is full. */
        fun charge(stack: ItemStack): Int =
            stack.tag?.takeIf { it.contains(CHARGE_KEY, Tag.TAG_INT.toInt()) }?.getInt(CHARGE_KEY) ?: MAX_CHARGE
    }
}
