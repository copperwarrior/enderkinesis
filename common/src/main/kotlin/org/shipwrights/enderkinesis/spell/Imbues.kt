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
import org.shipwrights.enderkinesis.duck.ImbuedProjectile
import org.shipwrights.enderkinesis.item.MysticScrollItem
import org.shipwrights.enderkinesis.mixin.ThrownTridentItemAccessor
import java.util.IdentityHashMap
import java.util.UUID

/**
 * Imbuing: right-click with a Mystic Scroll in the off hand and any item in the main hand,
 * and the scroll is used up writing its spell into that item instead of the item's own
 * right-click. The imbue lasts until it triggers, and imbuing again overwrites it.
 *
 * - **Melee:** the item's next regular attack. A sweep splits the magnitude across the
 *   primary and swept targets (a Bolt instead detonates once, at full magnitude, on the
 *   primary).
 * - **Ranged:** a bow, crossbow or trident hands the imbue to what it fires, which sets it
 *   off on its first impact, entity or block. Multishot splits it evenly across the shots.
 *
 * Against duplication: the scroll is taken before the item is written, and the imbue
 * records the item's stack count; if the count has changed when it triggers, it fizzles. A
 * weapon that breaks on the hit or shot that triggers it is the exception: an empty stack
 * can't be split, so its imbue still goes off. Grindstone and anvil merges drop it, and so
 * does every crafting, smithing, stonecutting and cartography result (see the mixins).
 *
 * Whoever wields the item is the one casting, for claim checks too: the attacker for a melee
 * hit, the shooter for a shot. Imbuing shares the scrolls' cooldown with Area casts and runes
 * (see [MysticScrollItem.startCooldown]).
 */
object Imbues {
    /** Item and projectile NBT key. */
    const val NBT = "EnderkinesisImbue"
    private const val NBT_OWNER = "Owner"
    private const val NBT_COUNT = "Count"
    private const val NBT_SHARE = "Share"

    const val TOOLTIP_KEY = "tooltip.enderkinesis.imbued"
    const val OVERWRITTEN_KEY = "message.enderkinesis.imbue.overwritten"
    const val FIZZLED_KEY = "message.enderkinesis.imbue.fizzled"

    /** A stored imbue. [share] is the part of the magnitude a Multishot shot carries. */
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
                val share = if (tag.contains(NBT_SHARE, Tag.TAG_FLOAT.toInt())) tag.getFloat(NBT_SHARE) else 1f
                return Imbue(spell, owner, tag.getInt(NBT_COUNT), share)
            }
        }
    }

    private class Melee(val weapon: ItemStack, val targets: MutableList<Entity> = ArrayList())

    // Game thread only, cleared at the end of each server tick.
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

    /** The imbued-item tooltip line: "Imbued: <spell name>". */
    fun tooltip(stack: ItemStack): Component? {
        val name = of(stack)?.spell?.name() ?: return null
        return Component.translatable(TOOLTIP_KEY, name.toComponent()).withStyle(ChatFormatting.GRAY)
    }

    // -----------------------------------------------------------------------
    // Imbuing
    // -----------------------------------------------------------------------

    /** Imbues the main-hand item from an off-hand scroll, using up the main hand's
     *  right-click. True when it applies, on both sides; the work is server-side. While the
     *  scrolls are cooling down the click is still used up, but nothing is written and the
     *  arm doesn't move. */
    private fun tryImbue(player: Player, hand: InteractionHand): Boolean {
        if (hand != InteractionHand.MAIN_HAND) return false
        val target = player.mainHandItem
        val scroll = player.offhandItem
        val spell = MysticScrollItem.spellOf(scroll) ?: return false
        if (target.isEmpty || target.item is MysticScrollItem) return false
        if (MysticScrollItem.coolingDown(player)) return true
        if (player.level().isClientSide) {
            MysticScrollItem.clientUsed?.invoke(player, MysticScrollItem.Companion.OtherUse.IMBUE, InteractionHand.OFF_HAND)
            return true
        }
        // The scroll's use goes first, so nothing can copy the item mid-write with it unspent.
        MysticScrollItem.spend(player.level() as ServerLevel, player, scroll)
        val overwritten = of(target) != null
        target.orCreateTag.put(NBT, Imbue(spell, player.uuid, target.count).save())
        MysticScrollItem.startCooldown(player)
        if (overwritten) player.displayClientMessage(Component.translatable(OVERWRITTEN_KEY), true)
        (player as? ServerPlayer)?.let { SpellNetwork.broadcastUsed(it.serverLevel(), it, MysticScrollItem.Companion.OtherUse.IMBUE, InteractionHand.OFF_HAND) }
        SpellVfx.imbue(player.level() as ServerLevel, player, spell)
        return true
    }

    /** Removes and returns [stack]'s imbue. Null, after telling [holder], if the stack's count
     *  changed since it was imbued. A stack that's empty broke on this very hit or shot (its
     *  tag is still there): it can't have been split, so the imbue goes off. */
    private fun take(stack: ItemStack, holder: LivingEntity): Imbue? {
        val imbue = of(stack) ?: return null
        stack.removeTagKey(NBT)
        if (imbue.count != stack.count && !stack.isEmpty) {
            (holder as? Player)?.displayClientMessage(Component.translatable(FIZZLED_KEY), true)
            (holder.level() as? ServerLevel)?.let { SpellVfx.fizzle(it, SpellCasting.handOrigin(holder, InteractionHand.MAIN_HAND)) }
            return null
        }
        return imbue
    }

    // -----------------------------------------------------------------------
    // Melee
    // -----------------------------------------------------------------------

    /** Collects every target one attack hurts this tick (the primary, then any swept ones). */
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
        // Copied as pairs: IdentityHashMap's entries are views that clear() empties.
        val attacks = melee.map { it.key to it.value }
        melee.clear()
        for ((attacker, attack) in attacks) {
            val level = attacker.level() as? ServerLevel ?: continue
            val imbue = take(attack.weapon, attacker) ?: continue
            // The wielder casts, as the shooter does for a shot (see [context]).
            val ctx = SpellContext(level, attacker, attacker.position(), use = SpellUse.IMBUE)
            val primary = attack.targets.first()
            // The spell's glyphs burst out where the blade lands.
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

    // -----------------------------------------------------------------------
    // Ranged
    // -----------------------------------------------------------------------

    /** Moves the imbue from the weapon mid-shot onto [projectile]: a bow or trident being
     *  released, or a charged crossbow firing. Multishot's shots share it. */
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
        // A thrown trident carries a copy of the trident, which it gives back on pickup.
        if (projectile is ThrownTrident) {
            val trident = (projectile as ThrownTridentItemAccessor).`enderkinesis$getTridentItem`()
            trident.removeTagKey(NBT)
            // Its glint is synced from the item when thrown: now it's the bare trident's.
            projectile.entityData.set(ThrownTridentItemAccessor.`enderkinesis$idFoil`(), trident.hasFoil())
        }
    }

    /** The weapon firing right now. A bow or crossbow that broke on this shot is already an
     *  empty stack (its item reads as air), but it's still the one in use or in hand and
     *  still carries its tags, imbue included. */
    private fun firingWeapon(shooter: LivingEntity): ItemStack? {
        val using = shooter.useItem
        if (shooter.isUsingItem && (using.item is ProjectileWeaponItem || using.item is TridentItem || brokenImbued(using))) return using
        return listOf(shooter.mainHandItem, shooter.offhandItem).firstOrNull {
            (it.item is CrossbowItem || brokenImbued(it)) && CrossbowItem.isCharged(it)
        }
    }

    private fun brokenImbued(stack: ItemStack): Boolean = stack.isEmpty && stack.tag?.contains(NBT, Tag.TAG_COMPOUND.toInt()) == true

    /** Multishot read from the enchantment tags directly, which a broken crossbow still has. */
    private fun shotsPerFire(weapon: ItemStack): Int =
        if ((EnchantmentHelper.getEnchantments(weapon)[Enchantments.MULTISHOT] ?: 0) > 0) 3 else 1

    /**
     * [SpellCasting.strikeEntity], except that a Chain striking something that isn't alive
     * (a boat, a minecart, an end crystal) isn't lost: it jumps from there to the nearest
     * living thing, as from a struck block.
     */
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

    /** The shooter casts. A Staff of Aegis reflection makes its holder the projectile's owner,
     *  and the imbue, claims included, belongs to them from then on. */
    private fun context(projectile: Projectile, level: ServerLevel, imbue: Imbue): SpellContext {
        val shooter = projectile.owner as? LivingEntity
        return SpellContext(level, shooter, shooter?.position() ?: projectile.position(), shooter?.uuid ?: imbue.owner, use = SpellUse.IMBUE)
    }

    /** An imbued projectile's first impact, entity or block, sets the spell off. Loyalty
     *  bringing a trident back doesn't bring the spell. */
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

    /** Cold counts fluids as hits: a Cold-imbued projectile entering a fluid strikes it. */
    @JvmStatic
    fun onProjectileTick(projectile: Projectile) {
        val level = projectile.level() as? ServerLevel ?: return
        val tag = (projectile as ImbuedProjectile).`enderkinesis$getImbue`() ?: return
        val spell = Spell.load(tag, Spell.NBT_GLYPHS) ?: return
        if (spell.magnitude(Element.COLD) <= 0f) return
        val from = projectile.position()
        val to = from.add(projectile.deltaMovement)
        val hit = level.clip(ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, projectile))
        if (hit.type != HitResult.Type.BLOCK || level.getBlockState(hit.blockPos).block !is LiquidBlock) return
        val imbue = takeFrom(projectile) ?: return
        SpellCasting.strikeBlock(context(projectile, level, imbue), imbue.spell, hit.blockPos, hit.direction, hit.location, imbue.share)
    }
}
