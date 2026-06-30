package org.shipwrights.enderkinesis.entity

import java.util.Optional
import java.util.UUID
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.FloatGoal
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal
import net.minecraft.world.InteractionHand
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.AxeItem
import net.minecraft.world.level.Level
import org.shipwrights.enderkinesis.EnderkinesisMod

/**
 * A player-shaped construct summoned by stacking two mud blocks topped with a
 * skeleton or wither-skeleton skull inside a Wohlonnogondonia biome (see
 * [WikLakConstruction]). Tied to its [creatorUuid] for life — hostile to every
 * other living thing on the level, including other players, but indifferent
 * to the creator and to siblings owned by the same creator. Damage is purely
 * fist-based (no item drop, no ranged behaviour).
 *
 * The host's purpose is to absorb a fatal hit for its creator: when the
 * creator would die anywhere on the server, [WikLakDeathRedirect] picks one
 * of their living hosts at random, teleports the player onto it, applies
 * slowness / weakness / mining-fatigue, and consumes the host. The player
 * keeps all items and XP because the death never resolves.
 */
class WikLakHostEntity(type: EntityType<out WikLakHostEntity>, level: Level) : PathfinderMob(type, level) {

    init {
        // Built constructs aren't ambient mob spawns, so the random-despawn
        // path doesn't apply. Mirrors iron-golem / snow-golem convention.
        setPersistenceRequired()
    }

    /** UUID of the player who built this host, or `null` if the host was
     *  spawned through a command / cheat / debug path without a creator. A
     *  null creator means the host attacks every living entity (no exempt
     *  player) and is invisible to the death-redirect lookup. Round-tripped
     *  through NBT and synced to clients so future client-side affordances
     *  (own-host highlight, name plate, …) have it without a server fetch. */
    var creatorUuid: UUID?
        get() = entityData.get(DATA_CREATOR_UUID).orElse(null)
        set(value) {
            entityData.set(DATA_CREATOR_UUID, Optional.ofNullable(value))
        }

    override fun defineSynchedData() {
        super.defineSynchedData()
        entityData.define(DATA_CREATOR_UUID, Optional.empty())
        entityData.define(DATA_SWING_PULSE, 0)
    }

    override fun registerGoals() {
        goalSelector.addGoal(0, FloatGoal(this))
        goalSelector.addGoal(1, MeleeAttackGoal(this, 1.0, false))
        goalSelector.addGoal(2, WaterAvoidingRandomStrollGoal(this, 1.0))
        goalSelector.addGoal(3, LookAtPlayerGoal(this, Player::class.java, 8.0f))
        goalSelector.addGoal(4, RandomLookAroundGoal(this))

        // Retaliate when hit — but only against attackers that aren't our
        // creator / sibling hosts (HurtByTargetGoal's default predicate is
        // unaware of our ownership rules; we filter on the explicit
        // target-acquisition goal below, and [hurt] swallows any hit from
        // an exempt source before HurtByTargetGoal can latch onto it).
        targetSelector.addGoal(1, HurtByTargetGoal(this))
        // Hostile to every nearby LivingEntity that isn't the creator or
        // a sibling host. Includes other players — the host is your
        // standing army, not their friend.
        targetSelector.addGoal(
            2,
            NearestAttackableTargetGoal(
                this,
                LivingEntity::class.java,
                10,
                /* mustSee = */ true,
                /* mustReach = */ false,
                /* selector = */ ::isValidTarget,
            ),
        )
    }

    /** True iff [candidate] is a legitimate target for this host.
     *  Rejects:
     *   - null / self / dead candidates
     *   - non-attackable entities ([LivingEntity.attackable] covers
     *     armour stands and similar decoration entities — they're
     *     LivingEntities by inheritance but not combat targets)
     *   - entities with no health pool (defensive — vanilla returns
     *     `> 0` for everything, but a modded mob could expose a
     *     0-HP "display dummy")
     *   - the creator player
     *   - any Wik-Lak host (cross-creator inter-host combat would let
     *     two players chain-deplete each other's pool with zero
     *     player input — keep them mutually peaceful). */
    private fun isValidTarget(candidate: LivingEntity?): Boolean {
        if (candidate == null) return false
        if (candidate === this) return false
        if (!candidate.isAlive) return false
        if (!candidate.attackable()) return false
        if (candidate.maxHealth <= 0f) return false
        val creator = creatorUuid
        if (candidate is Player && creator != null && candidate.uuid == creator) return false
        if (candidate is WikLakHostEntity) return false
        return true
    }

    /** Right-handed for the renderer's swing animation — vanilla default,
     *  surfaced explicitly so the choice is self-documenting. The model
     *  ([org.shipwrights.enderkinesis.client.WikLakHostModel]) mirrors
     *  the main-arm attack swing onto the off-arm so visually both arms
     *  pump on every hit. */
    override fun getMainArm(): HumanoidArm = HumanoidArm.RIGHT

    /** Axes shred the construct's wet-mud body — double-damage when the
     *  attacker's main hand holds an [AxeItem]. Doubling on the *direct*
     *  entity covers melee hits and dispenser arrows wielding axes; ranged
     *  axe-throw mods would land via `source.entity` instead but we don't
     *  ship a thrown axe in this codebase. */
    override fun hurt(source: DamageSource, amount: Float): Boolean {
        val attacker = source.directEntity
        val scaled =
            if (attacker is LivingEntity && attacker.mainHandItem.item is AxeItem) amount * 2f
            else amount
        return super.hurt(source, scaled)
    }

    /** Client-side: tickCount at which the most recent [DATA_SWING_PULSE]
     *  change was observed. Drives the model's self-owned swing animation
     *  (see [org.shipwrights.enderkinesis.client.WikLakHostModel]). Negative
     *  default means "no swing yet — don't animate." */
    @Volatile
    var clientSwingStartTick: Int = NO_SWING
        private set

    override fun onSyncedDataUpdated(key: net.minecraft.network.syncher.EntityDataAccessor<*>) {
        super.onSyncedDataUpdated(key)
        // Client-side capture of the swing pulse. Only fires on actual hits
        // (pulse starts at 0; we increment from 1 upward, so the initial-
        // sync-of-defaults case is skipped by the > 0 guard).
        if (level().isClientSide && key == DATA_SWING_PULSE && entityData.get(DATA_SWING_PULSE) > 0) {
            clientSwingStartTick = tickCount
        }
    }

    /** Drive both the vanilla swing path AND a redundant self-owned pulse on
     *  every successful melee hit. The vanilla call with
     *  `overrideSwingTime = true` bypasses [LivingEntity.swing]'s in-progress
     *  gate (which otherwise silently drops a swing if the previous one
     *  hasn't reached its halfway point). The synced [DATA_SWING_PULSE]
     *  increment is the model's actual animation trigger — independent of
     *  the vanilla `attackAnim`-derived `attackTime` chain, so even if some
     *  layer in that chain is being suppressed the swing visual still fires
     *  (see [org.shipwrights.enderkinesis.client.WikLakHostModel]). */
    override fun doHurtTarget(target: Entity): Boolean {
        val landed = super.doHurtTarget(target)
        if (landed) {
            this.swing(InteractionHand.MAIN_HAND, true)
            entityData.set(DATA_SWING_PULSE, entityData.get(DATA_SWING_PULSE) + 1)
        }
        return landed
    }

    /** Don't drop the mud or the skull on death — the host *was* those
     *  blocks, and rematerialising them on death would let a player farm
     *  infinite mud + skulls by repeatedly summoning and killing hosts. */
    override fun dropAllDeathLoot(source: net.minecraft.world.damagesource.DamageSource) {
        // Intentionally empty.
    }

    /** Persistent NBT round-trip for [creatorUuid]. The synced-data path
     *  handles client visibility; this handles disk survival across
     *  world reload. */
    override fun addAdditionalSaveData(tag: CompoundTag) {
        super.addAdditionalSaveData(tag)
        creatorUuid?.let { tag.putUUID(TAG_CREATOR, it) }
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)
        creatorUuid = if (tag.hasUUID(TAG_CREATOR)) tag.getUUID(TAG_CREATOR) else null
    }

    companion object {
        const val ID_PATH: String = "wik_lak_host"
        val ID: ResourceLocation = EnderkinesisMod.id(ID_PATH)

        private const val TAG_CREATOR: String = "Creator"

        @JvmField
        val DATA_CREATOR_UUID: EntityDataAccessor<Optional<UUID>> =
            SynchedEntityData.defineId(
                WikLakHostEntity::class.java,
                EntityDataSerializers.OPTIONAL_UUID,
            )

        /** Monotonically incremented on every successful [doHurtTarget].
         *  The client uses the change-event of this field — not its value —
         *  to capture [clientSwingStartTick]; the integer value is just an
         *  always-different sentinel so [SynchedEntityData]'s "skip unchanged
         *  values" diff doesn't suppress the sync. */
        @JvmField
        val DATA_SWING_PULSE: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(
                WikLakHostEntity::class.java,
                EntityDataSerializers.INT,
            )

        /** Length of one self-driven swing animation, in ticks. Matches
         *  vanilla's default `getCurrentSwingDuration` so the visual cadence
         *  reads identical to any other vanilla mob. */
        const val SWING_DURATION_TICKS: Int = 6

        /** Sentinel for "no swing has happened on this client yet". Negative
         *  so the model's elapsed-tick math reads as out-of-range. */
        const val NO_SWING: Int = -100

        /** Health: enough to soak a few hits while protecting the creator,
         *  not so much that one is effectively immortal. Movement speed at
         *  vanilla-player baseline (0.1) keeps wandering pace sane. Attack
         *  damage at 2.0 (= 1 heart) matches "fist damage" — same as a
         *  bare-handed punch with no weapon. Follow range mirrors the
         *  10-tick target-acquisition interval's effective sight radius. */
        fun createAttributes(): AttributeSupplier.Builder =
            Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.30)
                .add(Attributes.FOLLOW_RANGE, 24.0)
                .add(Attributes.ATTACK_DAMAGE, 2.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.2)
                .add(Attributes.ATTACK_KNOCKBACK, 0.5)
    }
}
