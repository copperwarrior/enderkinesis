package org.shipwrights.enderkinesis.spell

import dev.architectury.event.CompoundEventResult
import dev.architectury.event.EventResult
import dev.architectury.event.events.common.EntityEvent
import dev.architectury.event.events.common.InteractionEvent
import dev.architectury.event.events.common.TickEvent
import net.minecraft.ChatFormatting
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.entity.projectile.ThrownTrident
import net.minecraft.world.item.CrossbowItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ProjectileWeaponItem
import net.minecraft.world.item.TridentItem
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import org.shipwrights.enderkinesis.util.pickStandaloneBody
import org.shipwrights.enderkinesis.duck.ImbuedProjectile
import org.shipwrights.enderkinesis.item.UlderScrollItem
import org.shipwrights.enderkinesis.mixin.ThrownTridentItemAccessor
import org.shipwrights.enderkinesis.registry.EKSounds
import java.util.IdentityHashMap
import java.util.UUID

object Imbues {
    const val NBT = "EnderkinesisImbue"
    private const val NBT_OWNER = "Owner"
    private const val NBT_COUNT = "Count"
    private const val NBT_SHARE = "Share"

    const val TOOLTIP_KEY = "tooltip.enderkinesis.imbued"
    const val OVERWRITTEN_KEY = "message.enderkinesis.imbue.overwritten"
    const val FIZZLED_KEY = "message.enderkinesis.imbue.fizzled"

    /** [share] is the part of the magnitude that one Multishot shot carries. */
    class Imbue(val spell: Spell, val owner: UUID?, val count: Int, val share: Float = 1f) {
        fun save(): CompoundTag = CompoundTag().apply {
            put(Spell.NBT_GLYPHS, spell.save())
            owner?.let { putUUID(NBT_OWNER, it) }
            putInt(NBT_COUNT, count)
            putFloat(NBT_SHARE, share)
        }

        companion object {
            fun load(tag: CompoundTag): Imbue? {
                val spell = Spell.load(tag, Spell.NBT_GLYPHS)?.takeIf { it.glyphs.isNotEmpty() } ?: return null
                val owner = if (tag.hasUUID(NBT_OWNER)) tag.getUUID(NBT_OWNER) else null
                // A share is 1 / shots, so it is never more than a full cast.
                val share = if (tag.contains(NBT_SHARE, Tag.TAG_FLOAT.toInt())) tag.getFloat(NBT_SHARE).takeUnless { it.isNaN() }?.coerceIn(0f, 1f) ?: 1f else 1f
                return Imbue(spell, owner, tag.getInt(NBT_COUNT), share)
            }
        }
    }

    private class Melee(val weapon: ItemStack, val targets: MutableList<Entity> = ArrayList())

    // Game thread only. The end of each server tick clears these.
    private val melee = IdentityHashMap<LivingEntity, Melee>()
    private val shots = IdentityHashMap<ItemStack, Imbue>()

    fun init() {
        InteractionEvent.RIGHT_CLICK_ITEM.register { player, hand ->
            if (tryImbue(player, hand)) CompoundEventResult.interruptTrue(player.getItemInHand(hand)) else CompoundEventResult.pass()
        }
        InteractionEvent.RIGHT_CLICK_BLOCK.register { player, hand, _, _ ->
            if (tryImbue(player, hand)) EventResult.interruptTrue() else EventResult.pass()
        }
        EntityEvent.LIVING_HURT.register { entity, source, _ ->
            onHurt(entity, source)
            EventResult.pass()
        }
        EntityEvent.ADD.register { entity, level ->
            if (entity is Projectile && level is ServerLevel) onFired(entity)
            EventResult.pass()
        }
        TickEvent.SERVER_POST.register {
            resolveMelee()
            shots.clear()
        }
    }

    fun of(stack: ItemStack): Imbue? = stack.tag?.takeIf { it.contains(NBT, Tag.TAG_COMPOUND.toInt()) }?.let { Imbue.load(it.getCompound(NBT)) }

    fun tooltip(stack: ItemStack): Component? {
        val name = of(stack)?.spell?.name() ?: return null
        return Component.translatable(TOOLTIP_KEY, name.toComponent()).withStyle(ChatFormatting.GRAY)
    }

    /** Returns true on both sides when the imbue applies. During the scroll cooldown, the click
     *  is used but nothing is written. */
    private fun tryImbue(player: Player, hand: InteractionHand): Boolean {
        if (hand != InteractionHand.MAIN_HAND) return false
        val target = player.mainHandItem
        val scroll = player.offhandItem
        val spell = UlderScrollItem.spellOf(scroll) ?: return false
        if (target.isEmpty || target.item is UlderScrollItem) return false
        if (UlderScrollItem.coolingDown(player)) return true
        if (player.level().isClientSide) {
            UlderScrollItem.clientUsed?.invoke(player, UlderScrollItem.Companion.OtherUse.IMBUE, InteractionHand.OFF_HAND)
            return true
        }
        // Spend the scroll first, so nothing can copy the item while the scroll is not spent.
        UlderScrollItem.spend(player.level() as ServerLevel, player, scroll)
        val overwritten = of(target) != null
        target.orCreateTag.put(NBT, Imbue(spell, player.uuid, target.count).save())
        UlderScrollItem.startCooldown(player)
        if (overwritten) player.displayClientMessage(Component.translatable(OVERWRITTEN_KEY), true)
        (player as? ServerPlayer)?.let { SpellNetwork.broadcastUsed(it.serverLevel(), it, UlderScrollItem.Companion.OtherUse.IMBUE, InteractionHand.OFF_HAND) }
        SpellVfx.imbue(player.level() as ServerLevel, player, spell)
        SpellSounds.play(player.level() as ServerLevel, player.position(), EKSounds.SPELL_IMBUE)
        return true
    }

    /** Returns null if the stack count changed after the imbue, to stop duplication. An empty
     *  stack broke on this hit and cannot be split, so its imbue still goes off. */
    private fun take(stack: ItemStack, holder: LivingEntity): Imbue? {
        val imbue = of(stack) ?: return null
        stack.removeTagKey(NBT)
        if (imbue.count != stack.count && !stack.isEmpty) {
            (holder as? Player)?.displayClientMessage(Component.translatable(FIZZLED_KEY), true)
            (holder.level() as? ServerLevel)?.let {
                val at = SpellCasting.handOrigin(holder, InteractionHand.MAIN_HAND)
                SpellVfx.fizzle(it, at)
                SpellSounds.play(it, at, EKSounds.SPELL_IMBUE_FIZZLE)
            }
            return null
        }
        return imbue
    }

    /** The primary target goes first, then the swept targets. */
    private fun onHurt(entity: LivingEntity, source: DamageSource) {
        if (entity.level().isClientSide) return
        val attacker = source.entity as? LivingEntity ?: return
        if (source.directEntity !== attacker) return
        if (!source.`is`(DamageTypes.PLAYER_ATTACK) && !source.`is`(DamageTypes.MOB_ATTACK)) return
        val weapon = attacker.mainHandItem
        if (of(weapon) == null) return
        val attack = melee.getOrPut(attacker) { Melee(weapon) }
        if (entity !in attack.targets) attack.targets += entity
    }

    private fun resolveMelee() {
        if (melee.isEmpty()) return
        // Copy as pairs: IdentityHashMap entries are views that clear() empties.
        val attacks = melee.map { it.key to it.value }
        melee.clear()
        for ((attacker, attack) in attacks) {
            val level = attacker.level() as? ServerLevel ?: continue
            val imbue = take(attack.weapon, attacker) ?: continue
            val ctx = SpellContext(level, attacker, attacker.position(), use = SpellUse.IMBUE)
            val primary = attack.targets.first()
            val toAttacker = attacker.position().subtract(primary.position()).multiply(1.0, 0.0, 1.0).normalize()
            SpellVfx.glyphBurst(level, primary.position().add(0.0, primary.bbHeight * 0.6, 0.0).add(toAttacker.scale(primary.bbWidth * 0.6)), imbue.spell)
            if (attack.targets.size == 1 || imbue.spell.shape == Shape.BOLT) {
                strike(ctx, imbue.spell, primary, 1f)
            } else {
                val share = 1f / attack.targets.size
                val hit = HashSet<Entity>()
                for (target in attack.targets) strike(ctx, imbue.spell, target, share, hit)
            }
        }
    }

    /** Multishot shots share one imbue. */
    private fun onFired(projectile: Projectile) {
        if ((projectile as ImbuedProjectile).`enderkinesis$getImbue`() != null) return
        val shooter = projectile.owner as? LivingEntity ?: return
        val weapon = firingWeapon(shooter) ?: return
        val imbue = shots[weapon] ?: run {
            if (of(weapon) == null) return
            val taken = take(weapon, shooter) ?: return
            Imbue(taken.spell, taken.owner, taken.count, 1f / shotsPerFire(weapon)).also { shots[weapon] = it }
        }
        projectile.`enderkinesis$setImbue`(imbue.save())
        SpellVfx.imbuedShot(projectile.level() as ServerLevel, projectile, imbue.spell)
        // A thrown trident holds a copy of the trident and gives it back on pickup.
        if (projectile is ThrownTrident) {
            val trident = (projectile as ThrownTridentItemAccessor).`enderkinesis$getTridentItem`()
            trident.removeTagKey(NBT)
            // The glint syncs from the item at the throw, so set it again from the bare trident.
            projectile.entityData.set(ThrownTridentItemAccessor.`enderkinesis$idFoil`(), trident.hasFoil())
        }
    }

    /** A bow or crossbow that broke on this shot is an empty stack, but it still has its tags
     *  and its imbue. */
    private fun firingWeapon(shooter: LivingEntity): ItemStack? {
        val using = shooter.useItem
        if (shooter.isUsingItem && (using.item is ProjectileWeaponItem || using.item is TridentItem || brokenImbued(using))) return using
        return listOf(shooter.mainHandItem, shooter.offhandItem).firstOrNull {
            (it.item is CrossbowItem || brokenImbued(it)) && CrossbowItem.isCharged(it)
        }
    }

    private fun brokenImbued(stack: ItemStack): Boolean = stack.isEmpty && stack.tag?.contains(NBT, Tag.TAG_COMPOUND.toInt()) == true

    /** Read the enchantment tags directly, because a broken crossbow still has them. */
    private fun shotsPerFire(weapon: ItemStack): Int =
        if ((EnchantmentHelper.getEnchantments(weapon)[Enchantments.MULTISHOT] ?: 0) > 0) 3 else 1

    /** A Chain that strikes a non-living entity (boat, end crystal) jumps from there to the
     *  nearest living entity, as from a block. */
    private fun strike(ctx: SpellContext, spell: Spell, target: Entity, scale: Float, hit: MutableSet<Entity> = HashSet()) {
        if (spell.shape == Shape.CHAIN && target !is LivingEntity) {
            val at = target.boundingBox.center
            SpellCasting.strikeBlock(ctx, spell, target.blockPosition(), Direction.UP, at, scale)
        } else SpellCasting.strikeEntity(ctx, spell, target, scale, hit)
    }

    private fun takeFrom(projectile: Projectile): Imbue? {
        val holder = projectile as ImbuedProjectile
        val tag = holder.`enderkinesis$getImbue`() ?: return null
        holder.`enderkinesis$setImbue`(null)
        return Imbue.load(tag)
    }

    /** An Aegis reflection makes its holder the owner, so the imbue and its claims then belong
     *  to that holder. */
    private fun context(projectile: Projectile, level: ServerLevel, imbue: Imbue): SpellContext {
        val shooter = projectile.owner as? LivingEntity
        return SpellContext(level, shooter, shooter?.position() ?: projectile.position(), shooter?.uuid ?: imbue.owner, use = SpellUse.IMBUE)
    }

    /** Only the first impact casts. Loyalty does not bring the spell back with the trident. */
    @JvmStatic
    fun onProjectileHit(projectile: Projectile, result: HitResult) {
        val level = projectile.level() as? ServerLevel ?: return
        if (result.type == HitResult.Type.MISS) return
        val imbue = takeFrom(projectile) ?: return
        val ctx = context(projectile, level, imbue)
        when (result) {
            is EntityHitResult -> strike(ctx, imbue.spell, result.entity, imbue.share)
            is BlockHitResult -> SpellCasting.strikeBlock(ctx, imbue.spell, result.blockPos, result.direction, result.location, imbue.share)
        }
    }

    /** Cold counts fluids as hits. */
    @JvmStatic
    fun onProjectileTick(projectile: Projectile) {
        val level = projectile.level() as? ServerLevel ?: return
        val tag = (projectile as ImbuedProjectile).`enderkinesis$getImbue`() ?: return
        val spell = Spell.load(tag, Spell.NBT_GLYPHS) ?: return
        if (spell.magnitude(Element.WINTER) <= 0f) return
        val from = projectile.position()
        val to = from.add(projectile.deltaMovement)
        val hit = level.clip(ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, projectile))
        if (hit.type != HitResult.Type.BLOCK || level.getBlockState(hit.blockPos).block !is LiquidBlock) return
        val step = to.subtract(from)
        if (level.pickStandaloneBody(from, step.normalize(), hit.location.distanceTo(from)) != null) return
        val imbue = takeFrom(projectile) ?: return
        SpellCasting.strikeBlock(context(projectile, level, imbue), imbue.spell, hit.blockPos, hit.direction, hit.location, imbue.share)
    }
}
