package org.shipwrights.enderkinesis.body

import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtUtils
import net.minecraft.nbt.Tag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.saveddata.SavedData
import org.joml.Vector3d

/**
 * Per-dimension SavedData for [GamylanCageData]. Cages are pure VS
 * bodies with no backing [net.minecraft.world.entity.Entity], so
 * gameplay state (HP, capture pose, break-time drops, etc.) doesn't
 * ride any vanilla persistence — it lives here.
 *
 * VS Core persists the underlying bodies with stable ids, so restore is
 * a lookup by [GamylanCageData.bodyId]: hydrate our records, and the
 * force applier + damage pipeline pick up where they left off. Records
 * whose bodies didn't survive the VS side are swept on delayed post-
 * load tick by [org.shipwrights.enderkinesis.item.GamylanCageManager].
 */
class GamylanCagePersistence : SavedData() {

    val records: MutableMap<Long, GamylanCageData> = mutableMapOf()

    override fun save(tag: CompoundTag): CompoundTag {
        val list = ListTag()
        for (data in records.values) list.add(writeCage(data))
        tag.put("cages", list)
        return tag
    }

    companion object {
        private const val NAME = "enderkinesis_gamylan_cages"

        fun forLevel(level: ServerLevel): GamylanCagePersistence =
            level.dataStorage.computeIfAbsent(::load, ::GamylanCagePersistence, NAME)

        fun markDirty(level: ServerLevel) {
            forLevel(level).setDirty()
        }

        fun load(tag: CompoundTag): GamylanCagePersistence {
            val out = GamylanCagePersistence()
            val list = tag.getList("cages", Tag.TAG_COMPOUND.toInt())
            for (i in 0 until list.size) {
                val data = readCage(list.getCompound(i)) ?: continue
                out.records[data.bodyId] = data
            }
            return out
        }

        private fun writeCage(d: GamylanCageData): CompoundTag {
            val t = CompoundTag()
            t.putLong("bodyId", d.bodyId)
            t.put("captured", d.capturedTag)
            t.put("animation", d.animationTag)
            t.putBoolean("isTribulla", d.isTribulla)
            putVec3d(t, "origin", d.captureOrigin)
            putVec3d(t, "hover", d.hoverPos)
            t.putLong("addedAt", d.addedAtGameTime)
            t.putInt("maxHp", d.maxHp)
            t.putInt("hp", d.hp)
            putVec3d(t, "shape", d.shapeDims)
            putVec3d(t, "cull", d.entityCullSize)
            t.putString("dimId", d.dimId)
            t.putLong("fadeEnd", d.fadeEndGameTime)
            t.putBoolean("fadeDone", d.fadeComplete)
            t.putLong("lastDmg", d.lastDamageTick)
            if (d.playerProfileUuid != null) t.putUUID("uuid", d.playerProfileUuid)
            if (d.playerName != null) t.putString("name", d.playerName)
            t.putByte("custom", d.playerCustomizationByte)
            t.put("equipment", writeItems(d.playerEquipment))
            t.put("drops", writeItems(d.droppedItems))
            if (!d.displayedItem.isEmpty) t.put("displayItem", d.displayedItem.save(CompoundTag()))
            d.homePedestalPos?.let { t.put("homePedestal", NbtUtils.writeBlockPos(it)) }
            d.homePedestalShipId?.let { t.putLong("homePedestalShip", it) }
            return t
        }

        private fun readCage(t: CompoundTag): GamylanCageData? {
            if (!t.contains("bodyId", Tag.TAG_LONG.toInt())) return null
            val uuid: UUID? = if (t.hasUUID("uuid")) t.getUUID("uuid") else null
            val name: String? = if (t.contains("name", Tag.TAG_STRING.toInt())) t.getString("name") else null
            return GamylanCageData(
                bodyId = t.getLong("bodyId"),
                capturedTag = t.getCompound("captured"),
                animationTag = t.getCompound("animation"),
                isTribulla = t.getBoolean("isTribulla"),
                captureOrigin = getVec3d(t, "origin"),
                hoverPos = getVec3d(t, "hover"),
                addedAtGameTime = t.getLong("addedAt"),
                maxHp = t.getInt("maxHp"),
                hp = t.getInt("hp"),
                shapeDims = getVec3d(t, "shape"),
                entityCullSize = if (t.contains("cullx")) getVec3d(t, "cull")
                    else Vector3d(getVec3d(t, "shape").x * 2.0, getVec3d(t, "shape").y * 2.0, getVec3d(t, "shape").x * 2.0),
                dimId = t.getString("dimId"),
                fadeEndGameTime = t.getLong("fadeEnd"),
                fadeComplete = t.getBoolean("fadeDone"),
                lastDamageTick = t.getLong("lastDmg"),
                playerProfileUuid = uuid,
                playerName = name,
                playerCustomizationByte = t.getByte("custom"),
                playerEquipment = readItems(t.getList("equipment", Tag.TAG_COMPOUND.toInt())),
                displayedItem = if (t.contains("displayItem", Tag.TAG_COMPOUND.toInt()))
                    ItemStack.of(t.getCompound("displayItem")) else ItemStack.EMPTY,
                homePedestalPos = readBlockPos(t, "homePedestal"),
                homePedestalShipId = if (t.contains("homePedestalShip", Tag.TAG_LONG.toInt())) t.getLong("homePedestalShip") else null,
                droppedItems = java.util.ArrayList(readItems(t.getList("drops", Tag.TAG_COMPOUND.toInt()))),
            )
        }

        private fun readBlockPos(tag: CompoundTag, key: String): BlockPos? {
            if (!tag.contains(key, Tag.TAG_COMPOUND.toInt())) return null
            return NbtUtils.readBlockPos(tag.getCompound(key))
        }

        private fun writeItems(items: List<ItemStack>): ListTag {
            val list = ListTag()
            for (stack in items) list.add(stack.save(CompoundTag()))
            return list
        }

        private fun readItems(list: ListTag): List<ItemStack> {
            if (list.isEmpty()) return emptyList()
            return List(list.size) { ItemStack.of(list.getCompound(it)) }
        }

        private fun putVec3d(tag: CompoundTag, key: String, v: Vector3d) {
            tag.putDouble("${key}x", v.x)
            tag.putDouble("${key}y", v.y)
            tag.putDouble("${key}z", v.z)
        }

        private fun getVec3d(tag: CompoundTag, key: String): Vector3d =
            Vector3d(tag.getDouble("${key}x"), tag.getDouble("${key}y"), tag.getDouble("${key}z"))
    }
}
