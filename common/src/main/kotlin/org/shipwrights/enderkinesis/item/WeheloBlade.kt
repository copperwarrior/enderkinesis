package org.shipwrights.enderkinesis.item

import com.mojang.logging.LogUtils
import dev.architectury.event.EventResult
import dev.architectury.event.events.common.EntityEvent
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.TagKey
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.Tier
import net.minecraft.world.item.crafting.Ingredient
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.Level
import net.minecraft.world.level.dimension.DimensionType
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.dimension.Sureibjin
import org.shipwrights.enderkinesis.dimension.WeheloDestinations
import org.shipwrights.enderkinesis.dimension.WeheloPocket
import org.shipwrights.enderkinesis.dimension.Wohlonnogondonia
import org.shipwrights.enderkinesis.dimension.YgannAbyss
import org.shipwrights.enderkinesis.mixin.LivingEntityDeathLootInvoker
import org.shipwrights.enderkinesis.registry.EKItems
import org.shipwrights.enderkinesis.registry.EKSounds
import org.shipwrights.enderkinesis.spell.SpellSounds
import org.shipwrights.enderkinesis.spell.SpellVfx
import org.shipwrights.enderkinesis.teleport.SafeTeleporter

/** A killing blow from the blade runs the death drops, then sends the target alive to another dimension. */
object WeheloBlade {

    private val LOG = LogUtils.getLogger()

    const val ATTACK_DAMAGE_MODIFIER: Int = 6
    const val ATTACK_SPEED_MODIFIER: Float = -1.6f

    val TIER: Tier = object : Tier {
        // No durability: the blade runs on charge.
        override fun getUses(): Int = 0
        override fun getSpeed(): Float = 9.0f
        override fun getAttackDamageBonus(): Float = 4.0f
        override fun getLevel(): Int = 4
        override fun getEnchantmentValue(): Int = 15
        override fun getRepairIngredient(): Ingredient = Ingredient.EMPTY
    }

    val UNSHIFTABLE: TagKey<EntityType<*>> =
        TagKey.create(Registries.ENTITY_TYPE, EnderkinesisMod.id("wehelo_unshiftable"))

    private val FORBIDDEN = setOf(Wohlonnogondonia.LEVEL_KEY, Sureibjin.LEVEL_KEY, WeheloPocket.LEVEL_KEY)

    private class Landing(val level: ServerLevel, val pos: Vec3)

    fun init() {
        EntityEvent.LIVING_DEATH.register(::onLivingDeath)
    }

    private fun onLivingDeath(entity: LivingEntity, source: DamageSource): EventResult {
        val level = entity.level() as? ServerLevel ?: return EventResult.pass()
        if (!isBladeStrike(source)) return EventResult.pass()
        if (entity.type.`is`(UNSHIFTABLE) || !entity.canChangeDimensions()) return EventResult.pass()
        // Find the landing before the drops, so a failed search gives a normal death, not a double drop.
        val landing = pickLanding(level, entity) ?: return EventResult.pass()

        // Drop before the move: a non-player move copies the entity from NBT, so the copy would keep the gear.
        (entity as LivingEntityDeathLootInvoker).`enderkinesis$dropAllDeathLoot`(source)
        if (entity is ServerPlayer && !level.gameRules.getBoolean(GameRules.RULE_KEEPINVENTORY)) {
            entity.setExperienceLevels(0)
            entity.setExperiencePoints(0)
        }

        entity.health = entity.maxHealth
        entity.clearFire()
        entity.fallDistance = 0.0f
        rift(level, entity.position(), entity.bbHeight)
        SpellSounds.play(level, entity.position(), EKSounds.WEHELO_BANISH_DEPART)
        val moved = entity.teleportTo(
            landing.level, landing.pos.x, landing.pos.y, landing.pos.z,
            emptySet(), entity.yRot, entity.xRot,
        )
        if (moved) {
            rift(landing.level, landing.pos, entity.bbHeight)
            SpellSounds.play(landing.level, landing.pos, EKSounds.WEHELO_BANISH_ARRIVE)
        }
        if (!moved) LOG.warn("Wehelo Blade could not move {} to {}", entity, landing.level.dimension().location())
        return EventResult.interruptFalse()
    }

    private fun isBladeStrike(source: DamageSource): Boolean {
        if (!source.`is`(DamageTypes.PLAYER_ATTACK) && !source.`is`(DamageTypes.MOB_ATTACK)) return false
        val attacker = source.entity as? LivingEntity ?: return false
        val blade = attacker.mainHandItem
        return source.directEntity === attacker && blade.`is`(EKItems.WEHELO_BLADE.get()) && WeheloBladeItem.charge(blade) > 0
    }

    private fun pickLanding(from: ServerLevel, entity: LivingEntity): Landing? {
        val candidates = from.server.levelKeys()
            .filter { it != from.dimension() && it !in FORBIDDEN && WeheloDestinations.contains(it.location()) }
            .shuffled(java.util.Random(entity.random.nextLong()))
        for (key in candidates) {
            val target = from.server.getLevel(key) ?: continue
            val scale = DimensionType.getTeleportationScale(from.dimensionType(), target.dimensionType())
            val scaled = target.worldBorder.clampToBounds(entity.x * scale, entity.y, entity.z * scale)
            if (key == YgannAbyss.LEVEL_KEY) return Landing(target, SafeTeleporter.findShipDeck(target) ?: voidSpot(target, scaled))
            val anchor = if (key == Level.END) ServerLevel.END_SPAWN_POINT else target.sharedSpawnPos
            val pos = SafeTeleporter.findSafeSpot(target, scaled.x, scaled.z)
                ?: SafeTeleporter.findSafeSpot(target, anchor.x, anchor.z)
            if (pos != null) return Landing(target, Vec3.atBottomCenterOf(pos))
        }
        return null
    }

    private fun voidSpot(level: ServerLevel, scaled: BlockPos): Vec3 {
        val y = scaled.y.coerceIn(level.minBuildHeight, level.maxBuildHeight - 2)
        return Vec3(scaled.x + 0.5, y.toDouble(), scaled.z + 0.5)
    }

    private fun rift(level: ServerLevel, feet: Vec3, height: Float) {
        val tall = height + RIFT_MARGIN
        val centre = feet.add(0.0, height * 0.5, 0.0)
        SpellVfx.send(level, SpellVfx.Kind.WEHELO_RIFT, centre, centre, tall, RIFT_RGB, RIFT_TICKS)
    }

    private const val RIFT_MARGIN = 0.6f
    /** The sheen of the blade texture. */
    const val RIFT_RGB = 0x0C5E80
    private const val RIFT_TICKS = 28
}
