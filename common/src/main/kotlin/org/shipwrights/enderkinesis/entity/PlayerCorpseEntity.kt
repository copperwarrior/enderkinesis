package org.shipwrights.enderkinesis.entity

import java.util.Optional
import java.util.UUID
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvent
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.level.Level
import org.shipwrights.enderkinesis.EnderkinesisMod

/**
 * The "abandoned body" left behind when a player's death is intercepted by
 * the Wik-Lak host pipeline (see [WikLakDeathRedirect]). Renders as the
 * player (skin resolved client-side from the synced [playerUuid] via the
 * tab-list `PlayerInfo`), with no AI — spawned, immediately killed with
 * `Float.MAX_VALUE` generic damage, and vanilla [Mob.tickDeath] tilts it
 * over and broadcasts the death event for 20 ticks before self-removal.
 *
 * Loot, XP, equipment and sound paths are all suppressed: the corpse must
 * not duplicate the player's inventory or pop a player-death sound.
 */
class PlayerCorpseEntity(type: EntityType<out PlayerCorpseEntity>, level: Level) : Mob(type, level) {

    init {
        // Don't despawn mid death-animation if the corpse's tracker
        // wanders to the despawn radius (very unlikely in 1 s, but cheap).
        setPersistenceRequired()
    }

    /** UUID of the player this corpse represents. The renderer reads this
     *  to look up the player's `PlayerInfo` on the client and use its
     *  skin texture. `null` means "no link" → renderer falls back to the
     *  default Steve skin. */
    var playerUuid: UUID?
        get() = entityData.get(DATA_PLAYER_UUID).orElse(null)
        set(value) {
            entityData.set(DATA_PLAYER_UUID, Optional.ofNullable(value))
        }

    override fun defineSynchedData() {
        super.defineSynchedData()
        entityData.define(DATA_PLAYER_UUID, Optional.empty())
    }

    /** No goals — the corpse is killed on the same tick it's spawned and
     *  vanilla [Mob.tickDeath] handles the death animation without any AI. */
    override fun registerGoals() = Unit

    /** Suppress every drop path. The corpse is a visual stand-in, not the
     *  player's death — the player kept their inventory + XP because the
     *  death was intercepted. */
    override fun dropAllDeathLoot(source: DamageSource) = Unit

    override fun getHurtSound(source: DamageSource): SoundEvent? = null
    override fun getDeathSound(): SoundEvent? = null
    override fun getAmbientSound(): SoundEvent? = null

    /** Persist [playerUuid] so a corpse loaded from disk (chunk unload
     *  during the 1-second death window, server save tick) still renders
     *  with the right skin on the next load. */
    override fun addAdditionalSaveData(tag: CompoundTag) {
        super.addAdditionalSaveData(tag)
        playerUuid?.let { tag.putUUID(TAG_PLAYER, it) }
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)
        playerUuid = if (tag.hasUUID(TAG_PLAYER)) tag.getUUID(TAG_PLAYER) else null
    }

    companion object {
        const val ID_PATH: String = "player_corpse"
        val ID: ResourceLocation = EnderkinesisMod.id(ID_PATH)

        private const val TAG_PLAYER: String = "PlayerUUID"

        @JvmField
        val DATA_PLAYER_UUID: EntityDataAccessor<Optional<UUID>> =
            SynchedEntityData.defineId(
                PlayerCorpseEntity::class.java,
                EntityDataSerializers.OPTIONAL_UUID,
            )

        /** Minimal stats — health is set to 1 so the immediate-kill
         *  damage call in [WikLakDeathRedirect.spawnDyingCorpse] always
         *  resolves into the death sequence on the first hit. */
        fun createAttributes(): AttributeSupplier.Builder =
            createMobAttributes()
                .add(Attributes.MAX_HEALTH, 1.0)
                .add(Attributes.MOVEMENT_SPEED, 0.0)
    }
}
