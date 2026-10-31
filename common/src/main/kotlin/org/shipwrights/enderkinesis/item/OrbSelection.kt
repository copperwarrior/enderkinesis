package org.shipwrights.enderkinesis.item

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack

/** An orb position and its dimension, kept on an item stack under an NBT key. */
data class OrbSelection(val pos: BlockPos, val dim: ResourceLocation) {

    companion object {
        fun read(stack: ItemStack, key: String): OrbSelection? {
            val tag = stack.tag ?: return null
            if (!tag.contains(key, Tag.TAG_COMPOUND.toInt())) return null
            val sel = tag.getCompound(key)
            val dim = ResourceLocation.tryParse(sel.getString("Dim")) ?: return null
            return OrbSelection(BlockPos(sel.getInt("X"), sel.getInt("Y"), sel.getInt("Z")), dim)
        }

        /** The selection, or null when it is in a different dimension from [level]. */
        fun readIn(level: ServerLevel, stack: ItemStack, key: String): OrbSelection? =
            read(stack, key)?.takeIf { it.dim == level.dimension().location() }

        fun write(stack: ItemStack, key: String, pos: BlockPos, dim: ResourceLocation) {
            val sub = CompoundTag()
            sub.putInt("X", pos.x); sub.putInt("Y", pos.y); sub.putInt("Z", pos.z)
            sub.putString("Dim", dim.toString())
            stack.orCreateTag.put(key, sub)
        }

        fun clear(stack: ItemStack, key: String) {
            stack.tag?.remove(key)
        }
    }
}
