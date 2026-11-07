package org.shipwrights.enderkinesis.ritual

import java.util.UUID
import kotlin.math.sqrt
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import org.shipwrights.enderkinesis.mixin.MobDropChancesAccessor

/**
 * A player body with a creature's mind in it is that vanilla creature, so its AI and brain run as they always do.
 * These hooks take away what belongs to the creature's body (its size, health, speed, loot, and traits such as gills,
 * fire immunity, flight and sun burn) and give it the player's body in their place.
 */
object SelvesBodies {

    private const val OFFHAND_SLOT = 150
    private val ARMOR_SLOTS = 100..103
    // A dropped chance above 1 marks an item the mob picked up itself; vanilla always drops those.
    private const val PICKED_UP = 1f
    private val PLAYER_WALK = sqrt(Selves.PLAYER_SPEED * Selves.PLAYER_INPUT)
    private const val PLAYER_HEALTH = 20.0

    @JvmStatic
    fun ownerOf(entity: Entity): UUID? {
        if (entity !is Mob) return null
        return if (entity.level().isClientSide) Selves.clientBodies[entity.uuid] else Selves.serverData?.bodies?.get(entity.uuid)?.owner
    }

    @JvmStatic
    fun isBody(entity: Entity): Boolean = ownerOf(entity) != null

    /** Gives [mob] the player body's health, walk and equipment. [items] is a saved player inventory. */
    fun wear(mob: Mob, items: CompoundTag) {
        mob.setPersistenceRequired()
        mob.getAttribute(Attributes.MAX_HEALTH)?.let {
            it.removeModifier(Selves.HEALTH_ID)
            it.addPermanentModifier(
                AttributeModifier(Selves.HEALTH_ID, "Ritual of Selves", PLAYER_HEALTH - it.baseValue, AttributeModifier.Operation.ADDITION),
            )
        }
        mob.getAttribute(Attributes.MOVEMENT_SPEED)?.let {
            it.removeModifier(Selves.SPEED_ID)
            if (it.baseValue > 0.0) {
                it.addPermanentModifier(
                    AttributeModifier(Selves.SPEED_ID, "Ritual of Selves", PLAYER_WALK / it.baseValue - 1.0, AttributeModifier.Operation.MULTIPLY_TOTAL),
                )
            }
        }
        dress(mob, items)
    }

    // Copies of the hands and armour: the real items stay in Selves, so the copies never drop.
    private fun dress(mob: Mob, items: CompoundTag) {
        for (slot in EquipmentSlot.entries) mob.setItemSlot(slot, ItemStack.EMPTY)
        val selected = items.getInt(Selves.TAG_SELECTED)
        for (entry in items.getList(Selves.TAG_ITEMS, Tag.TAG_COMPOUND.toInt())) {
            val tag = entry as CompoundTag
            val slot = when (val index = tag.getByte("Slot").toInt() and 0xFF) {
                selected -> EquipmentSlot.MAINHAND
                OFFHAND_SLOT -> EquipmentSlot.OFFHAND
                in ARMOR_SLOTS -> EquipmentSlot.byTypeAndIndex(EquipmentSlot.Type.ARMOR, index - ARMOR_SLOTS.first)
                else -> null
            } ?: continue
            mob.setItemSlot(slot, ItemStack.of(tag))
        }
        val chances = mob as MobDropChancesAccessor
        chances.`enderkinesis$getHandDropChances`().fill(0f)
        chances.`enderkinesis$getArmorDropChances`().fill(0f)
    }

    @JvmStatic
    fun dimensions(entity: Entity, vanilla: EntityDimensions): EntityDimensions =
        if (isBody(entity)) Player.STANDING_DIMENSIONS else vanilla

    @JvmStatic
    fun eyeHeight(entity: Entity, vanilla: Float): Float = if (isBody(entity)) Player.DEFAULT_EYE_HEIGHT else vanilla

    /** True when [entity] is a body, which keeps none of its kind's body traits. */
    @JvmStatic
    fun losesTrait(entity: Entity): Boolean = isBody(entity)

    /** Drops a killed body's items in place of the creature's loot. True when [entity] was a body. */
    @JvmStatic
    fun dropLoot(entity: Entity): Boolean {
        if (entity !is Mob || entity.level().isClientSide) return false
        val items = release(entity) ?: return false
        dropItems(entity, items)
        val chances = entity as MobDropChancesAccessor
        for (slot in EquipmentSlot.entries) {
            val chance = if (slot.type == EquipmentSlot.Type.HAND) chances.`enderkinesis$getHandDropChances`()[slot.index]
            else chances.`enderkinesis$getArmorDropChances`()[slot.index]
            if (chance > PICKED_UP) entity.spawnAtLocation(entity.getItemBySlot(slot))
        }
        return true
    }

    /** A body that goes without a death, such as a creeper that explodes, still drops its items. */
    @JvmStatic
    fun onRemoved(entity: Entity, reason: Entity.RemovalReason) {
        if (!reason.shouldDestroy() || entity !is Mob || entity.level().isClientSide) return
        release(entity)?.let { dropItems(entity, it) }
    }

    /** A converted body (a pig struck by lightning, a drowned zombie) stays the same player body. */
    @JvmStatic
    fun onConverting(old: Mob, converted: Entity) {
        val data = Selves.serverData ?: return
        if (converted !is Mob) return
        val body = data.bodies.remove(old.uuid) ?: return
        data.bodies[converted.uuid] = body
        wear(converted, body.items)
        converted.refreshDimensions()
        data.setDirty()
        Selves.broadcast(converted.server ?: return)
    }

    /** True when [mob]'s owner took the body back while it was not loaded; it goes now. */
    @JvmStatic
    fun reclaimed(mob: Mob): Boolean {
        val data = Selves.serverData ?: return false
        if (data.reclaimed.isEmpty() || !data.reclaimed.remove(mob.uuid)) return false
        data.setDirty()
        mob.discard()
        return true
    }

    @JvmStatic
    fun onLoad(entity: Entity) {
        if (isBody(entity)) entity.refreshDimensions()
    }

    private fun release(mob: Mob): CompoundTag? {
        val data = Selves.serverData ?: return null
        val body = data.bodies.remove(mob.uuid) ?: return null
        data.setDirty()
        (mob.level() as? ServerLevel)?.let { Selves.broadcast(it.server) }
        return body.items
    }

    private fun dropItems(mob: Mob, items: CompoundTag) {
        for (entry in items.getList(Selves.TAG_ITEMS, Tag.TAG_COMPOUND.toInt())) {
            val stack = ItemStack.of(entry as CompoundTag)
            if (!stack.isEmpty) mob.spawnAtLocation(stack)
        }
    }
}
