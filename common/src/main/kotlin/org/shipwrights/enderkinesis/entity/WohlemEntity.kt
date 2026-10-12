package org.shipwrights.enderkinesis.entity

import net.minecraft.core.registries.Registries
import net.minecraft.tags.TagKey
import net.minecraft.world.entity.AnimationState
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.FloatGoal
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ArmorItem
import net.minecraft.world.level.Level
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.item.RobeArmorMaterial

/**
 * The Wohlem — a lumbering many-eyed brute native to Wohlonnogondonia's
 * spread. Slow, hits far harder than an iron golem, and hostile to anything
 * that isn't part of the Wohlon ecosystem:
 *
 *  - **Exempt**: entity types in the `enderkinesis:wohlon_mobs` tag (other
 *    wohlems, prismatic goats — extend the tag to grow the ecosystem), and
 *    any [LivingEntity] wearing at least one piece of Blue Witch armour.
 *  - **Attack**: a 1.25 s wind-up swing ([WohlemAnimations.attack]); the
 *    damage frame lands 0.58–0.67 s into the animation, so the hurt fires
 *    once at [ATTACK_HIT_TICK] (tick 12 = 0.60 s) if the target is still in
 *    reach — side-stepping a wohlem mid-swing is a real dodge.
 *
 * Animation clocks (idle / walk cross-fade + client-side scratch flavour)
 * follow the [Cataloger] pattern; the attack is server-driven and broadcast
 * via entity event so all viewers see the same wind-up.
 */
class WohlemEntity(type: EntityType<out WohlemEntity>, level: Level) : Monster(type, level) {

    val idleAnimationState: AnimationState = AnimationState()
    val walkingAnimationState: AnimationState = AnimationState()
    val scratchAnimationState: AnimationState = AnimationState()
    val attackAnimationState: AnimationState = AnimationState()

    /** Idle↔walk cross-fade weight, updated client-side per tick. */
    var walkBlend: Float = 0f
        private set
    var walkBlendOld: Float = 0f
        private set

    /** Server swing clock: -1 idle, else ticks since the swing started.
     *  The hurt fires once when it reaches [ATTACK_HIT_TICK]. */
    private var attackWindupTicks: Int = -1

    /** Client-side stop deadlines for the one-shot states (AnimationState
     *  has no duration of its own). -1 = not running. */
    private var clientAttackEndTick: Int = -1
    private var clientScratchEndTick: Int = -1

    override fun registerGoals() {
        goalSelector.addGoal(0, FloatGoal(this))
        goalSelector.addGoal(1, WohlemAttackGoal())
        goalSelector.addGoal(3, WaterAvoidingRandomStrollGoal(this, 0.6))
        goalSelector.addGoal(4, LookAtPlayerGoal(this, Player::class.java, 12.0f))
        goalSelector.addGoal(5, RandomLookAroundGoal(this))
        targetSelector.addGoal(1, HurtByTargetGoal(this))
        targetSelector.addGoal(
            2,
            NearestAttackableTargetGoal(this, LivingEntity::class.java, 10, true, false) { candidate ->
                candidate is LivingEntity && isValidPrey(candidate)
            },
        )
    }

    /** Hostile to anything except the Wohlon ecosystem ([WOHLON_MOBS] tag)
     *  and wearers of at least one Blue Witch armour piece. */
    private fun isValidPrey(candidate: LivingEntity): Boolean {
        // An armour stand takes no melee damage, so it would hold the target with no end.
        if (candidate is ArmorStand) return false
        if (candidate.type.`is`(WOHLON_MOBS)) return false
        for (slot in EquipmentSlot.values()) {
            if (slot.type != EquipmentSlot.Type.ARMOR) continue
            val item = candidate.getItemBySlot(slot).item
            if (item is ArmorItem && item.material === RobeArmorMaterial.BLUE_WITCH) return false
        }
        return true
    }

    override fun aiStep() {
        super.aiStep()
        if (level().isClientSide) {
            tickClientAnimations()
            return
        }
        if (attackWindupTicks >= 0) {
            attackWindupTicks++
            if (attackWindupTicks == ATTACK_HIT_TICK) {
                val victim = target
                if (victim != null && distanceToSqr(victim) <= attackReachSqr(victim)) {
                    doHurtTarget(victim)
                }
            }
            if (attackWindupTicks >= ATTACK_ANIM_TICKS) attackWindupTicks = -1
        }
    }

    /** Start the wind-up: server clock + broadcast so every viewer's model
     *  plays the same swing. */
    private fun startSwing() {
        attackWindupTicks = 0
        level().broadcastEntityEvent(this, EVENT_ATTACK)
    }

    /** Same reach formula MeleeAttackGoal uses, re-checked at the damage
     *  frame so a target that stepped away mid-wind-up is missed. */
    private fun attackReachSqr(target: LivingEntity): Double =
        (bbWidth * 2.0f * bbWidth * 2.0f + target.bbWidth).toDouble()

    override fun handleEntityEvent(id: Byte) {
        if (id == EVENT_ATTACK) {
            attackAnimationState.start(tickCount)
            clientAttackEndTick = tickCount + ATTACK_ANIM_TICKS
            // A swing interrupts the scratch flavour.
            scratchAnimationState.stop()
            clientScratchEndTick = -1
        } else {
            super.handleEntityEvent(id)
        }
    }

    private fun tickClientAnimations() {
        if (!idleAnimationState.isStarted) idleAnimationState.start(tickCount)
        if (!walkingAnimationState.isStarted) walkingAnimationState.start(tickCount)

        val dx = x - xo
        val dz = z - zo
        val moving = dx * dx + dz * dz > 1.0e-6
        walkBlendOld = walkBlend
        walkBlend = (walkBlend + if (moving) BLEND_RATE else -BLEND_RATE).coerceIn(0f, 1f)

        if (clientAttackEndTick >= 0 && tickCount >= clientAttackEndTick) {
            attackAnimationState.stop()
            clientAttackEndTick = -1
        }
        if (clientScratchEndTick >= 0 && tickCount >= clientScratchEndTick) {
            scratchAnimationState.stop()
            clientScratchEndTick = -1
        } else if (
            clientScratchEndTick < 0 && clientAttackEndTick < 0 &&
            walkBlend < 0.05f && random.nextInt(SCRATCH_CHANCE) == 0
        ) {
            // Cosmetic flavour — triggered client-side so it needs no sync;
            // viewers seeing different scratches is fine for an idle fidget.
            scratchAnimationState.start(tickCount)
            clientScratchEndTick = tickCount + SCRATCH_TICKS
        }
    }

    /** Melee goal that starts the wind-up instead of hurting instantly —
     *  the damage lands from [aiStep] at the animation's impact frame. */
    private inner class WohlemAttackGoal : MeleeAttackGoal(this@WohlemEntity, 1.0, true) {
        override fun checkAndPerformAttack(enemy: LivingEntity, distToEnemySqr: Double) {
            if (distToEnemySqr <= getAttackReachSqr(enemy) && isTimeToAttack && attackWindupTicks < 0) {
                resetAttackCooldown()
                startSwing()
            }
        }
    }

    companion object {
        const val ID_PATH = "wohlem"

        /** Ecosystem-exemption tag — data-driven so new Wohlon mobs opt in
         *  by tag edit, no code change. */
        val WOHLON_MOBS: TagKey<EntityType<*>> =
            TagKey.create(Registries.ENTITY_TYPE, EnderkinesisMod.id("wohlon_mobs"))

        /** Attack animation length: 1.25 s. */
        const val ATTACK_ANIM_TICKS: Int = 25

        /** Damage frame — the artist's impact lands 0.58–0.67 s into the
         *  attack animation; tick 12 (0.60 s) sits inside that window. */
        const val ATTACK_HIT_TICK: Int = 12

        /** idle_scratch length: 6 s one-shot. */
        private const val SCRATCH_TICKS: Int = 120

        /** ~1 scratch per 30 s of standing idle. */
        private const val SCRATCH_CHANCE: Int = 600

        /** Idle↔walk cross-fade step per tick (~7 ticks per transition). */
        private const val BLEND_RATE: Float = 0.15f

        private const val EVENT_ATTACK: Byte = 61

        /** Lumbering tank: iron-golem durability, harder hit (golem averages
         *  ~15), notably slower stride, immovable by knockback.
         *
         *  Speed is fitted to the walk animation: 4 foot-plants per 6.96 s
         *  cycle with ~10° leg swings on ~1.3-block legs works out to well
         *  under 1 m/s of designed ground speed. 0.06 (~1.1 m/s) keeps the
         *  foot slide negligible while staying a credible threat in a
         *  corridor. */
        fun createAttributes(): AttributeSupplier.Builder = createMonsterAttributes()
            .add(Attributes.MAX_HEALTH, 100.0)
            .add(Attributes.MOVEMENT_SPEED, 0.06)
            .add(Attributes.ATTACK_DAMAGE, 22.0)
            .add(Attributes.ATTACK_KNOCKBACK, 2.0)
            .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
            .add(Attributes.FOLLOW_RANGE, 40.0)
    }
}
