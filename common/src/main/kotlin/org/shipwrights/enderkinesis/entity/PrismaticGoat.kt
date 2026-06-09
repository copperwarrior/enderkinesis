package org.shipwrights.enderkinesis.entity

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.AgeableMob
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.animal.goat.Goat
import net.minecraft.world.item.InstrumentItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.registry.EKEntities
import org.shipwrights.enderkinesis.registry.EKItems

/**
 * Vanilla [Goat] subclass with two overrides: [getBreedOffspring] spawns a prismatic child
 * instead of a vanilla one, and [createHorn] picks from [EKItems.PRISMATIC_GOAT_HORNS_TAG]
 * (cave-ambience instruments) and wraps in [EKItems.PRISMATIC_GOAT_HORN].
 */
class PrismaticGoat(type: EntityType<out PrismaticGoat>, level: Level) : Goat(type, level) {

    override fun getBreedOffspring(level: ServerLevel, otherParent: AgeableMob): Goat? {
        val child: PrismaticGoat = EKEntities.PRISMATIC_GOAT.get().create(level) ?: return null
        val random = level.random
        var screaming = false
        if (otherParent is Goat) {
            screaming = if (random.nextBoolean()) this.isScreamingGoat else otherParent.isScreamingGoat
        }
        if (random.nextInt(50) == 0) {
            screaming = !screaming
        }
        child.isScreamingGoat = screaming
        return child
    }

    /** UUID-seeded random so a goat always drops the same horn variant. No screaming-vs-normal
     *  fork — prismatic pool isn't split by variant. */
    override fun createHorn(): ItemStack {
        val random = RandomSource.create(this.uuid.hashCode().toLong())
        val holderSet = BuiltInRegistries.INSTRUMENT.getOrCreateTag(EKItems.PRISMATIC_GOAT_HORNS_TAG)
        val holder = holderSet.getRandomElement(random).orElseThrow {
            IllegalStateException(
                "Prismatic-goat-horn instrument tag is empty — check " +
                    "data/enderkinesis/tags/instrument/prismatic_goat_horns.json " +
                    "and the cave_*.json instrument records under it.",
            )
        }
        return InstrumentItem.create(EKItems.PRISMATIC_GOAT_HORN.get(), holder)
    }

    companion object {
        const val ID_PATH: String = "prismatic_goat"
        val ID: ResourceLocation = EnderkinesisMod.id(ID_PATH)
        // No createAttributes wrapper: a same-signature companion method would shadow Goat.createAttributes.
    }
}
