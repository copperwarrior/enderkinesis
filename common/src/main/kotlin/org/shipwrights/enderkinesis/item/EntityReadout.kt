package org.shipwrights.enderkinesis.item

import net.minecraft.core.Holder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.StringRepresentable
import net.minecraft.world.entity.AgeableMob
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Saddleable
import net.minecraft.world.entity.TamableAnimal
import net.minecraft.world.entity.VariantHolder
import net.minecraft.world.entity.animal.Cat
import net.minecraft.world.entity.animal.Sheep
import net.minecraft.world.entity.animal.Wolf
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import org.shipwrights.enderkinesis.item.StrikeReadout.Companion.line
import org.shipwrights.enderkinesis.item.StrikeReadout.Companion.round

/** Only what an onlooker can see; [look] is the entity's saved data for the sketch. */
class EntityReadout(
    val type: EntityType<*>,
    override val title: Component,
    val look: CompoundTag?,
    override val owner: String,
    override val sketchSeed: Int,
    val width: Float,
    val height: Float,
    private val details: List<Component>,
) : StrikeReadout {

    override val sketch: SketchSpec?
        get() = look?.let { SketchSpec(SketchSubject.Entity(type, it), sketchSeed, StrikeReadout.SKETCH_SIZE, notes = true) }

    override fun rounded(): Component = Component.translatable(
        "message.enderkinesis.silverpoint.rounded_entity",
        title,
        round(width.toDouble(), 2),
        round(height.toDouble(), 2),
    )

    override fun lines(): List<Component> =
        listOf(line("width", round(width.toDouble(), 3)), line("height", round(height.toDouble(), 3))) + details

    override fun save(): CompoundTag = CompoundTag().apply {
        putString(StrikeReadout.KEY_KIND, KIND)
        putString(KEY_TYPE, BuiltInRegistries.ENTITY_TYPE.getKey(this@EntityReadout.type).toString())
        putString(KEY_TITLE, Component.Serializer.toJson(title))
        look?.let { put(KEY_LOOK, it) }
        putString(StrikeReadout.KEY_OWNER, owner)
        putInt(StrikeReadout.KEY_SKETCH_SEED, sketchSeed)
        putFloat(KEY_WIDTH, width)
        putFloat(KEY_HEIGHT, height)
        put(KEY_DETAILS, ListTag().apply { details.forEach { add(StringTag.valueOf(Component.Serializer.toJson(it))) } })
    }

    companion object {
        const val KIND = "entity"
        private const val KEY_TYPE = "type"
        private const val KEY_TITLE = "title"
        private const val KEY_LOOK = "look"
        private const val KEY_WIDTH = "width"
        private const val KEY_HEIGHT = "height"
        private const val KEY_DETAILS = "details"

        // Where it is, who it is, what it carries out of sight, and how it thinks: none of it shows in a sketch.
        private val UNSEEN = listOf(
            "Pos", "Motion", "Rotation", "UUID", "FallDistance", "Air", "OnGround", "PortalCooldown", "Invulnerable",
            "Passengers", "Brain", "Attributes", "ActiveEffects", "Inventory", "Items", "Offers", "Gossips", "Leash",
            "HurtTime", "HurtByTimestamp", "DeathTime", "AbsorptionAmount", "LastPoiTime",
        )

        fun of(entity: Entity, owner: String, sketchSeed: Int): EntityReadout {
            // A player cannot be made from saved data, so it has no sketch.
            val look = if (entity.type.canSummon()) {
                entity.saveWithoutId(CompoundTag()).apply { UNSEEN.forEach(::remove) }
            } else {
                null
            }
            return EntityReadout(
                type = entity.type,
                title = entity.name,
                look = look,
                owner = owner,
                sketchSeed = sketchSeed,
                width = entity.bbWidth,
                height = entity.bbHeight,
                details = details(entity),
            )
        }

        fun load(tag: CompoundTag): EntityReadout? {
            val id = ResourceLocation.tryParse(tag.getString(KEY_TYPE)) ?: return null
            val type = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null) ?: return null
            val details = tag.getList(KEY_DETAILS, Tag.TAG_STRING.toInt()).mapNotNull { Component.Serializer.fromJson(it.asString) }
            return EntityReadout(
                type = type,
                title = Component.Serializer.fromJson(tag.getString(KEY_TITLE)) ?: type.description,
                look = if (tag.contains(KEY_LOOK, Tag.TAG_COMPOUND.toInt())) tag.getCompound(KEY_LOOK) else null,
                owner = tag.getString(StrikeReadout.KEY_OWNER),
                sketchSeed = tag.getInt(StrikeReadout.KEY_SKETCH_SEED),
                width = tag.getFloat(KEY_WIDTH),
                height = tag.getFloat(KEY_HEIGHT),
                details = details,
            )
        }

        private fun details(entity: Entity): List<Component> {
            val out = ArrayList<Component>()
            if (entity.hasCustomName()) out.add(line("kind", entity.type.description))
            if (entity is AgeableMob || entity is LivingEntity && entity.isBaby) {
                val baby = (entity as LivingEntity).isBaby
                out.add(line("age", Component.translatable("message.enderkinesis.strike_readout.age.${if (baby) "young" else "adult"}")))
            }
            if (entity is VariantHolder<*>) variantName(entity.variant)?.let { out.add(line("variant", it)) }
            if (entity is Sheep) {
                out.add(line("wool", if (entity.isSheared) Component.translatable("message.enderkinesis.strike_readout.wool.sheared") else colour(entity.color)))
            }
            collar(entity)?.let { out.add(line("collar", colour(it))) }
            if (entity is Saddleable && entity.isSaddleable) {
                out.add(line("saddled", Component.translatable(if (entity.isSaddled) "gui.yes" else "gui.no")))
            }
            if (entity.isOnFire) out.add(line("burning"))
            if (entity is LivingEntity) {
                items(entity.armorSlots)?.let { out.add(line("wearing", it)) }
                items(entity.handSlots)?.let { out.add(line("holding", it)) }
            }
            return out
        }

        private fun collar(entity: Entity): DyeColor? = when {
            entity !is TamableAnimal || !entity.isTame -> null
            entity is Wolf -> entity.collarColor
            entity is Cat -> entity.collarColor
            else -> null
        }

        private fun colour(colour: DyeColor): Component = Component.translatable("color.minecraft.${colour.getName()}")

        private fun variantName(variant: Any?): String? {
            val name = when (variant) {
                is StringRepresentable -> variant.serializedName
                is Holder<*> -> variant.unwrapKey().map { it.location().path }.orElse(null)
                is Enum<*> -> variant.name.lowercase()
                else -> null
            }
            return name?.substringAfter(':')?.replace('_', ' ')
        }

        private fun items(slots: Iterable<ItemStack>): Component? {
            val names = slots.filterNot { it.isEmpty }.map { it.hoverName }
            if (names.isEmpty()) return null
            val list = Component.empty()
            names.forEachIndexed { i, name ->
                if (i > 0) list.append(", ")
                list.append(name)
            }
            return list
        }
    }
}
