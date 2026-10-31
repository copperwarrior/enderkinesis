package org.shipwrights.enderkinesis.item

import net.minecraft.core.registries.Registries
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.TagKey
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Equipable
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.HopperBlockEntity
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.registry.EKSounds
import java.util.UUID

class SatchelOfShelvingItem(properties: Properties) : Item(properties), Equipable {

    override fun getEquipmentSlot(): EquipmentSlot = EquipmentSlot.CHEST

    override fun getEquipSound(): SoundEvent = EKSounds.SATCHEL_OF_SHELVING_EQUIP.get()

    // Equipable's usual use() swaps into the chest slot; the satchel opens instead.
    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        if (player is ServerPlayer) {
            val slot = if (hand == InteractionHand.MAIN_HAND) player.inventory.selected else Inventory.SLOT_OFFHAND
            SatchelOfShelvingMenu.open(player, stack, slot)
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide)
    }

    /** Sneak-use on a container moves as much of the contents into it as it accepts. */
    override fun useOn(context: UseOnContext): InteractionResult {
        if (!context.isSecondaryUseActive) return InteractionResult.PASS
        val level = context.level
        val container = HopperBlockEntity.getContainerAt(level, context.clickedPos) ?: return InteractionResult.PASS
        val server = level.server
        val id = satchelId(context.itemInHand)
        if (server != null && id != null) {
            val moved = SatchelOfShelvingSavedData.get(server).contents(id).moveAll { stack ->
                HopperBlockEntity.addItem(null, container, stack, context.clickedFace)
            }
            if (moved) {
                level.playSound(
                    null, context.clickedPos, EKSounds.SATCHEL_OF_SHELVING_UNLOAD.get(), SoundSource.PLAYERS, 1f, 1f,
                )
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide)
    }

    companion object {
        val STORABLE: TagKey<Item> = TagKey.create(Registries.ITEM, EnderkinesisMod.id("satchel_of_shelving_storable"))

        private const val ID_KEY = "SatchelId"

        /** Accessory mods report the worn items as new when the wearer spawns. */
        private const val ACCESSORY_SPAWN_TICKS = 20

        fun satchelId(stack: ItemStack): UUID? =
            stack.tag?.takeIf { it.hasUUID(ID_KEY) }?.getUUID(ID_KEY)

        fun getOrCreateId(stack: ItemStack): UUID =
            satchelId(stack) ?: UUID.randomUUID().also { stack.orCreateTag.putUUID(ID_KEY, it) }

        /** Armor indices from head to feet, as the inventory screen shows them. */
        private val ARMOR_SLOTS = intArrayOf(39, 38, 37, 36)

        /** Opens the first satchel found: hands, accessory slots, armor, then the inventory read like a page. */
        fun openFirst(player: ServerPlayer) {
            val inventory = player.inventory
            if (tryOpen(player, inventory.selected) || tryOpen(player, Inventory.SLOT_OFFHAND)) return
            val worn = AccessoryGatherer.gather(player).firstOrNull { it.item is SatchelOfShelvingItem }
            if (worn != null) {
                SatchelOfShelvingMenu.open(player, worn, SatchelOfShelvingMenu.NO_SLOT)
                return
            }
            for (slot in ARMOR_SLOTS) if (tryOpen(player, slot)) return
            for (slot in Inventory.getSelectionSize() until Inventory.INVENTORY_SIZE) if (tryOpen(player, slot)) return
            for (slot in 0 until Inventory.getSelectionSize()) if (tryOpen(player, slot)) return
        }

        /** For Curios and Trinkets, which play no sound when a slot takes the satchel. */
        fun playAccessoryEquip(wearer: LivingEntity, previous: ItemStack) {
            val level = wearer.level()
            if (level.isClientSide || wearer.isSpectator || wearer.tickCount < ACCESSORY_SPAWN_TICKS) return
            // Curios reports a tag or count change as an equip.
            if (previous.item is SatchelOfShelvingItem) return
            val sound = EKSounds.SATCHEL_OF_SHELVING_EQUIP.get()
            level.playSound(null, wearer.x, wearer.y, wearer.z, sound, wearer.soundSource, 1f, 1f)
        }

        private fun tryOpen(player: ServerPlayer, slot: Int): Boolean {
            val stack = player.inventory.getItem(slot)
            if (stack.item !is SatchelOfShelvingItem) return false
            SatchelOfShelvingMenu.open(player, stack, slot)
            return true
        }
    }
}
