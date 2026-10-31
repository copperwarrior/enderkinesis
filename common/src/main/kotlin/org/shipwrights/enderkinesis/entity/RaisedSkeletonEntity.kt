package org.shipwrights.enderkinesis.entity

import java.util.EnumSet
import java.util.Optional
import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.DoubleTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.ai.util.LandRandomPos
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.util.Mth
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.AgeableMob
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MobType
import net.minecraft.world.entity.TamableAnimal
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.FollowOwnerGoal
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal
import net.minecraft.world.entity.ai.goal.SitWhenOrderedToGoal
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal
import net.minecraft.world.entity.ai.goal.target.TargetGoal
import net.minecraft.world.entity.ai.goal.target.OwnerHurtByTargetGoal
import net.minecraft.world.entity.ai.goal.target.OwnerHurtTargetGoal
import net.minecraft.world.entity.NeutralMob
import net.minecraft.world.entity.monster.Creeper
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.entity.monster.CrossbowAttackMob
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.entity.projectile.ThrownTrident
import net.minecraft.world.item.BowItem
import net.minecraft.world.item.CrossbowItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.ProjectileWeaponItem
import net.minecraft.world.item.TieredItem
import net.minecraft.world.item.TridentItem
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.item.GrandStaffOfTheDeadItem
import org.shipwrights.enderkinesis.item.StaffOfCommandItem
import org.shipwrights.enderkinesis.registry.EKParticles

// TamableAnimal, not AbstractSkeleton: the vanilla owner goals need it. The vanilla bow and
// crossbow goals need a Monster, so the ranged goal is local.
class RaisedSkeletonEntity(type: EntityType<out RaisedSkeletonEntity>, level: Level) :
    TamableAnimal(type, level), CrossbowAttackMob {

    private var commandedPost: Vec3? = null
    private var faceTarget: Vec3? = null
    private var patrol: List<Vec3> = emptyList()

    private var patrolIndex = 0

    init {
        setPersistenceRequired()
    }

    /** The player whose skin this skeleton wears, or null for plain bones. */
    var skinUuid: UUID?
        get() = entityData.get(DATA_SKIN_UUID).orElse(null)
        set(value) {
            entityData.set(DATA_SKIN_UUID, Optional.ofNullable(value))
        }

    override fun defineSynchedData() {
        super.defineSynchedData()
        entityData.define(DATA_SKIN_UUID, Optional.empty())
        entityData.define(DATA_SKIN_NAME, "")
        entityData.define(DATA_ZOMBIE, false)
        entityData.define(DATA_CHARGING_CROSSBOW, false)
        entityData.define(DATA_RISE_TICKS, 0)
    }

    val isRising: Boolean
        get() = entityData.get(DATA_RISE_TICKS) > 0

    /** 0 when the rise starts, 1 when the skeleton stands clear of the ground. */
    fun riseProgress(partialTick: Float): Float {
        val left = entityData.get(DATA_RISE_TICKS)
        if (left <= 0) return 1f
        return ((RISE_TICKS - left + partialTick) / RISE_TICKS).coerceIn(0f, 1f)
    }

    fun startRising() {
        entityData.set(DATA_RISE_TICKS, RISE_TICKS)
        val ground = groundState()
        if (!ground.isAir) {
            val sound = ground.soundType
            playSound(sound.breakSound, sound.volume, sound.pitch * RISE_SOUND_PITCH)
        }
    }

    private fun groundState(): BlockState = level().getBlockState(BlockPos.containing(x, y - GROUND_PROBE, z))

    override fun isImmobile(): Boolean = isRising || super.isImmobile()

    // Entity.push(Entity) moves both sides, so blocking both entry points frees the owner in either direction.
    override fun doPush(entity: net.minecraft.world.entity.Entity) {
        if (entity is Player && isOwnedBy(entity)) return
        super.doPush(entity)
    }

    override fun push(entity: net.minecraft.world.entity.Entity) {
        if (entity is Player && isOwnedBy(entity)) return
        super.push(entity)
    }

    override fun tick() {
        super.tick()
        val left = entityData.get(DATA_RISE_TICKS)
        if (left <= 0) {
            if (level().isClientSide && !isInvisible) eyeDust()
            return
        }
        if (level().isClientSide) riseEffects(left) else entityData.set(DATA_RISE_TICKS, left - 1)
    }

    /** Sselith dust rising from both eye sockets. */
    private fun eyeDust() {
        if (random.nextInt(EYE_DUST_CHANCE) != 0) return
        val look = getViewVector(1f)
        // Right of the face, level with the ground: the look direction turned 90 degrees about +Y.
        val rightX = -look.z
        val rightZ = look.x
        val norm = Math.sqrt(rightX * rightX + rightZ * rightZ).coerceAtLeast(1.0e-6)
        val side = if (random.nextBoolean()) EYE_SPACING else -EYE_SPACING
        val drop = if (isInSittingPose) SIT_DROP else 0.0
        level().addParticle(
            EKParticles.sselithEyeDust(),
            x + look.x * EYE_FORWARD + rightX / norm * side,
            eyeY - drop + look.y * EYE_FORWARD,
            z + look.z * EYE_FORWARD + rightZ / norm * side,
            (random.nextDouble() - 0.5) * EYE_DUST_JITTER,
            EYE_DUST_RISE + random.nextDouble() * EYE_DUST_RISE,
            (random.nextDouble() - 0.5) * EYE_DUST_JITTER,
        )
    }

    private fun riseEffects(ticksLeft: Int) {
        val ground = groundState()
        if (ground.isAir) return
        val particle = BlockParticleOption(ParticleTypes.BLOCK, ground)
        repeat(RISE_PARTICLES_PER_TICK) {
            level().addParticle(
                particle,
                x + (random.nextDouble() - 0.5) * bbWidth * 2.0, y + 0.1, z + (random.nextDouble() - 0.5) * bbWidth * 2.0,
                (random.nextDouble() - 0.5) * 0.2, random.nextDouble() * 0.3, (random.nextDouble() - 0.5) * 0.2,
            )
        }
        if (ticksLeft % RISE_DIG_SOUND_TICKS == 0) {
            val sound = ground.soundType
            level().playLocalSound(x, y, z, sound.hitSound, soundSource, sound.volume * 0.5f, sound.pitch * RISE_SOUND_PITCH, false)
        }
    }

    val isChargingCrossbow: Boolean
        get() = entityData.get(DATA_CHARGING_CROSSBOW)

    override fun setChargingCrossbow(charging: Boolean) {
        entityData.set(DATA_CHARGING_CROSSBOW, charging)
    }

    override fun registerGoals() {
        // Above the sit goal: a sitting skeleton stands to shoot, then sits again.
        goalSelector.addGoal(1, RangedWeaponGoal())
        goalSelector.addGoal(2, SitWhenOrderedToGoal(this))
        goalSelector.addGoal(
            3,
            object : MeleeAttackGoal(this, 1.2, false) {
                override fun canUse(): Boolean = rangedWeaponHand() == null && super.canUse()
            },
        )
        goalSelector.addGoal(4, CommandedPostGoal())
        goalSelector.addGoal(4, PatrolGoal())
        goalSelector.addGoal(5, FollowOwnerGoal(this, 1.0, 10.0f, 2.0f, false))
        goalSelector.addGoal(6, WaterAvoidingRandomStrollGoal(this, 1.0))
        goalSelector.addGoal(7, LookAtPlayerGoal(this, Player::class.java, 8.0f))
        goalSelector.addGoal(7, RandomLookAroundGoal(this))
        // The owner's fights take at most MAX_SHARED_TARGET bodies while other threats need them.
        targetSelector.addGoal(
            1,
            object : OwnerHurtByTargetGoal(this) {
                override fun canUse(): Boolean = super.canUse() && !shouldSpreadFrom(owner?.lastHurtByMob)
            },
        )
        targetSelector.addGoal(
            2,
            object : OwnerHurtTargetGoal(this) {
                override fun canUse(): Boolean = super.canUse() && !shouldSpreadFrom(owner?.lastHurtMob)
            },
        )
        targetSelector.addGoal(
            3,
            // Vanilla HurtByTargetGoal never asks wantsToAttack, so a stray arrow from another skeleton would start infighting.
            object : HurtByTargetGoal(this) {
                override fun canUse(): Boolean {
                    if (!super.canUse()) return false
                    val attacker = lastHurtByMob ?: return false
                    if (attacker is RaisedSkeletonEntity) return false
                    val owner = owner ?: return true
                    return wantsToAttack(attacker, owner)
                }
            },
        )
        targetSelector.addGoal(4, SpreadTargetGoal())
    }

    private fun packmates(): List<RaisedSkeletonEntity> {
        val ownerId = ownerUUID ?: return emptyList()
        return level().getEntitiesOfClass(RaisedSkeletonEntity::class.java, boundingBox.inflate(PACK_RADIUS)) {
            it !== this && it.ownerUUID == ownerId
        }
    }

    /** The visible threat with the best distance, after a penalty for each packmate already on it. */
    private fun bestThreat(pack: List<RaisedSkeletonEntity>, excluding: LivingEntity? = null): LivingEntity? {
        val range = getAttributeValue(Attributes.FOLLOW_RANGE)
        var best: LivingEntity? = null
        var bestScore = Double.MAX_VALUE
        for (candidate in level().getEntitiesOfClass(LivingEntity::class.java, boundingBox.inflate(range)) { threatensOwner(it) }) {
            if (candidate === excluding || !sensing.hasLineOfSight(candidate)) continue
            val crowd = pack.count { it.target === candidate }
            val score = distanceToSqr(candidate) + SPREAD_PENALTY_SQ * crowd
            if (score < bestScore) {
                best = candidate
                bestScore = score
            }
        }
        return best
    }

    private fun shouldSpreadFrom(shared: LivingEntity?): Boolean {
        if (shared == null) return false
        val pack = packmates()
        if (pack.count { it.target === shared } < MAX_SHARED_TARGET) return false
        return bestThreat(pack, excluding = shared) != null
    }

    /** Takes the least crowded hostile near the owner, so a pack fans out over a group instead of piling on one. */
    private inner class SpreadTargetGoal : TargetGoal(this@RaisedSkeletonEntity, true, false) {
        private var picked: LivingEntity? = null

        init {
            flags = EnumSet.of(Flag.TARGET)
        }

        override fun canUse(): Boolean {
            if (random.nextInt(GUARD_SCAN_INTERVAL) != 0) return false
            picked = bestThreat(packmates())
            return picked != null
        }

        override fun start() {
            mob.target = picked
            super.start()
        }
    }

    /** The skin player's name, or empty. A dev or offline login has no signed skin, so the client looks the skin up by name. */
    var skinName: String
        get() = entityData.get(DATA_SKIN_NAME)
        set(value) {
            entityData.set(DATA_SKIN_NAME, value)
        }

    /** A zombie body: raised with flesh. It changes only the model and the sounds. */
    var isZombie: Boolean
        get() = entityData.get(DATA_ZOMBIE)
        set(value) {
            entityData.set(DATA_ZOMBIE, value)
        }

    fun raise(owner: Player, skin: UUID?, skinName: String?) {
        isTame = true
        ownerUUID = owner.uuid
        skinUuid = skin
        this.skinName = skinName.orEmpty()
    }

    /** An item that came with the body or was handed over: it drops intact on death. */
    fun equipKept(slot: EquipmentSlot, stack: ItemStack) {
        setItemSlot(slot, stack)
        setDropChance(slot, KEPT_DROP_CHANCE)
    }

    /** An item the rite conjured: it never drops. */
    fun equipConjured(slot: EquipmentSlot, stack: ItemStack) {
        setItemSlot(slot, stack)
        setDropChance(slot, 0f)
    }

    /** Staff of Command "Go To": walk to [target] and hold it until the owner sits or recalls it. */
    fun commandMoveTo(target: Vec3) {
        orderToSit(false)
        patrol = emptyList()
        commandedPost = target
        faceTarget = null
        navigation.stop()
    }

    /** Staff of Command "Go To" at the wielder's feet: drop the post, stand, and follow the owner again. */
    fun commandFollow() {
        orderToSit(false)
        patrol = emptyList()
        commandedPost = null
        faceTarget = null
    }

    /** Walk [route] in a loop (one point: hold it) until a Staff of Command order or a new route replaces it. Sitting pauses it. */
    private fun startPatrol(route: List<Vec3>) {
        orderToSit(false)
        commandedPost = null
        faceTarget = null
        patrol = route
        patrolIndex = 0
    }

    /** Staff of Command "Aim To": shoot [victim], or turn toward [point] when no creature is aimed at. */
    fun commandAim(victim: LivingEntity?, point: Vec3) {
        val owner = owner
        if (victim != null && victim != this && (owner == null || wantsToAttack(victim, owner))) {
            target = victim
            faceTarget = null
        } else {
            faceTarget = point
        }
    }

    private fun orderToSit(sit: Boolean) {
        isOrderedToSit = sit
        isInSittingPose = sit
        jumping = false
        navigation.stop()
        target = null
    }

    override fun mobInteract(player: Player, hand: InteractionHand): InteractionResult {
        // Vanilla runs mobInteract before ItemStack.interactLivingEntity; pass so the staff's add-toggle runs.
        val held = player.getItemInHand(hand)
        if (held.item is StaffOfCommandItem) return InteractionResult.PASS
        // The Grand Staff casts even when clicked on one of its undead: a pass falls through to the staff's use().
        if (player.isHolding { it.item is GrandStaffOfTheDeadItem }) return InteractionResult.PASS
        if (!isOwnedBy(player)) return super.mobInteract(player, hand)
        if (level().isClientSide) return InteractionResult.SUCCESS
        val route = PatrolRoute.read(held)
        if (route != null) {
            if (route.isNotEmpty()) startPatrol(route)
            val message = when (route.size) {
                0 -> Component.translatable("entity.enderkinesis.raised_skeleton.no_patrol")
                // A one-point loop walks there and holds it, coming back when pushed off.
                1 -> Component.translatable(
                    "entity.enderkinesis.raised_skeleton.hold",
                    Mth.floor(route[0].x), Mth.floor(route[0].y), Mth.floor(route[0].z),
                )
                else -> Component.translatable("entity.enderkinesis.raised_skeleton.patrol", route.size)
            }
            player.displayClientMessage(message, true)
            return InteractionResult.CONSUME
        }
        val slot = equipSlotFor(held)
        if (slot != null) {
            takeFromOwner(player, held, slot)
            return InteractionResult.CONSUME
        }
        orderToSit(!isOrderedToSit)
        commandedPost = null
        faceTarget = null
        return InteractionResult.CONSUME
    }

    private fun takeFromOwner(player: Player, held: ItemStack, slot: EquipmentSlot) {
        val old = getItemBySlot(slot)
        // A conjured item vanishes on swap; returning it would let owners farm rite gear.
        val returnOld = !old.isEmpty && getEquipmentDropChance(slot) > 0f
        equipKept(slot, held.copyWithCount(1))
        if (!player.abilities.instabuild) held.shrink(1)
        if (returnOld && !player.inventory.add(old)) player.drop(old, false)
    }

    override fun customServerAiStep() {
        super.customServerAiStep()
        // Runs after the goal tick, so it wins over the idle look goals.
        val face = faceTarget
        if (face != null && target == null) lookControl.setLookAt(face.x, face.y, face.z)
    }

    /** A hostile near the owner. A calm neutral mob (enderman, zombified piglin) is left alone, and so is a creeper:
     *  the owner may send them at one, but they do not walk one into the owner on their own. */
    private fun threatensOwner(target: LivingEntity): Boolean {
        if (target !is Enemy || target is Creeper) return false
        if (target is NeutralMob && !target.isAngry) return false
        val owner = owner ?: return false
        // A patrol guards its own beat as well as the owner.
        val near = target.distanceToSqr(owner) <= GUARD_RADIUS_SQ ||
            (patrol.isNotEmpty() && target.distanceToSqr(this) <= GUARD_RADIUS_SQ)
        return near && wantsToAttack(target, owner)
    }

    // Mob.canAttackType refuses ghasts, which would block an owner's ghast target in TargetingConditions.
    override fun canAttackType(type: EntityType<*>): Boolean = true

    override fun wantsToAttack(target: LivingEntity, owner: LivingEntity): Boolean {
        if (target is RaisedSkeletonEntity) return false
        if (target is TamableAnimal && target.owner == owner) return false
        if (target is Player && owner is Player && !owner.canHarmPlayer(target)) return false
        return target != owner
    }

    override fun performRangedAttack(target: LivingEntity, power: Float) {
        val hand = rangedWeaponHand() ?: return
        val weapon = getItemInHand(hand)
        if (weapon.item is CrossbowItem) {
            performCrossbowAttack(this, ARROW_SPEED)
            return
        }
        if (weapon.item is TridentItem) {
            throwTrident(target)
            return
        }
        val arrow = ProjectileUtil.getMobArrow(this, getProjectile(weapon), power)
        val dx = target.x - x
        val dy = target.getY(1.0 / 3.0) - arrow.y
        val dz = target.z - z
        val horizontal = Math.sqrt(dx * dx + dz * dz)
        arrow.shoot(dx, dy + horizontal * 0.2, dz, ARROW_SPEED, (14 - level().difficulty.id * 4).toFloat())
        playSound(SoundEvents.SKELETON_SHOOT, 1.0f, 1.0f / (random.nextFloat() * 0.4f + 0.8f))
        level().addFreshEntity(arrow)
    }

    /** Drowned.performRangedAttack: a fresh plain trident, so the held one is never lost. */
    private fun throwTrident(target: LivingEntity) {
        val thrown = ThrownTrident(level(), this, ItemStack(Items.TRIDENT))
        val dx = target.x - x
        val dy = target.getY(1.0 / 3.0) - thrown.y
        val dz = target.z - z
        val horizontal = Math.sqrt(dx * dx + dz * dz)
        thrown.shoot(dx, dy + horizontal * 0.2, dz, ARROW_SPEED, (14 - level().difficulty.id * 4).toFloat())
        playSound(SoundEvents.DROWNED_SHOOT, 1.0f, 1.0f / (random.nextFloat() * 0.4f + 0.8f))
        level().addFreshEntity(thrown)
    }

    override fun shootCrossbowProjectile(target: LivingEntity, crossbow: ItemStack, projectile: Projectile, angle: Float) {
        shootCrossbowProjectile(this, target, projectile, angle, ARROW_SPEED)
    }

    override fun onCrossbowAttackPerformed() {
        noActionTime = 0
    }

    // LivingEntity returns EMPTY, which leaves a crossbow unable to load; Monster's version, plus a default arrow.
    override fun getProjectile(weapon: ItemStack): ItemStack {
        val item = weapon.item as? ProjectileWeaponItem ?: return ItemStack.EMPTY
        val held = ProjectileWeaponItem.getHeldProjectile(this, item.supportedHeldProjectiles)
        return if (held.isEmpty) ItemStack(Items.ARROW) else held
    }

    /** The hand holding a bow, crossbow or trident, main hand first. */
    fun rangedWeaponHand(): InteractionHand? = when {
        isRangedWeapon(mainHandItem) -> InteractionHand.MAIN_HAND
        isRangedWeapon(offhandItem) -> InteractionHand.OFF_HAND
        else -> null
    }

    private fun isRangedWeapon(stack: ItemStack): Boolean =
        stack.item is BowItem || stack.item is CrossbowItem || stack.item is TridentItem

    override fun dropCustomDeathLoot(source: DamageSource, looting: Int, recentlyHit: Boolean) {
        super.dropCustomDeathLoot(source, looting, recentlyHit)
        // Every body counts as 16 bones, and a zombie as 1 rotten flesh as well; a third comes back.
        dropThird(Items.BONE, BODY_BONES)
        if (isZombie) dropThird(Items.ROTTEN_FLESH, BODY_FLESH)
    }

    private fun dropThird(item: net.minecraft.world.item.Item, made: Int) {
        val exact = made * MATERIAL_RETURN
        val whole = exact.toInt()
        val count = whole + if (random.nextDouble() < exact - whole) 1 else 0
        if (count > 0) spawnAtLocation(ItemStack(item, count))
    }

    override fun getMobType(): MobType = MobType.UNDEAD

    override fun getBreedOffspring(level: ServerLevel, partner: AgeableMob): AgeableMob? = null

    override fun isFood(stack: ItemStack): Boolean = false

    override fun removeWhenFarAway(distanceToClosestPlayer: Double): Boolean = false

    override fun getAmbientSound(): SoundEvent = if (isZombie) SoundEvents.ZOMBIE_AMBIENT else SoundEvents.SKELETON_AMBIENT
    override fun getHurtSound(source: DamageSource): SoundEvent =
        if (isZombie) SoundEvents.ZOMBIE_HURT else SoundEvents.SKELETON_HURT
    override fun getDeathSound(): SoundEvent = if (isZombie) SoundEvents.ZOMBIE_DEATH else SoundEvents.SKELETON_DEATH

    override fun playStepSound(pos: BlockPos, state: BlockState) {
        playSound(if (isZombie) SoundEvents.ZOMBIE_STEP else SoundEvents.SKELETON_STEP, 0.15f, 1.0f)
    }

    override fun addAdditionalSaveData(tag: CompoundTag) {
        super.addAdditionalSaveData(tag)
        skinUuid?.let { tag.putUUID(TAG_SKIN, it) }
        if (skinName.isNotEmpty()) tag.putString(TAG_SKIN_NAME, skinName)
        if (isZombie) tag.putBoolean(TAG_ZOMBIE, true)
        commandedPost?.let {
            tag.putDouble(TAG_POST_X, it.x)
            tag.putDouble(TAG_POST_Y, it.y)
            tag.putDouble(TAG_POST_Z, it.z)
        }
        if (patrol.isNotEmpty()) {
            val list = ListTag()
            for (p in patrol) {
                list.add(DoubleTag.valueOf(p.x))
                list.add(DoubleTag.valueOf(p.y))
                list.add(DoubleTag.valueOf(p.z))
            }
            tag.put(TAG_PATROL, list)
            tag.putInt(TAG_PATROL_INDEX, patrolIndex)
        }
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)
        skinUuid = if (tag.hasUUID(TAG_SKIN)) tag.getUUID(TAG_SKIN) else null
        skinName = tag.getString(TAG_SKIN_NAME)
        isZombie = tag.getBoolean(TAG_ZOMBIE)
        commandedPost = if (tag.contains(TAG_POST_X)) {
            Vec3(tag.getDouble(TAG_POST_X), tag.getDouble(TAG_POST_Y), tag.getDouble(TAG_POST_Z))
        } else {
            null
        }
        val list = tag.getList(TAG_PATROL, Tag.TAG_DOUBLE.toInt())
        val points = minOf(list.size / 3, PatrolRoute.MAX_POINTS)
        patrol = List(points) { Vec3(list.getDouble(it * 3), list.getDouble(it * 3 + 1), list.getDouble(it * 3 + 2)) }
        patrolIndex = if (points == 0) 0 else tag.getInt(TAG_PATROL_INDEX).mod(points)
    }

    /** Vanilla skeleton bow, pillager crossbow and drowned trident logic without strafing. Holds still while sitting or posted. */
    private inner class RangedWeaponGoal : Goal() {
        private var seeTime = 0
        private var attackCooldown = -1
        private var crossbowState = CrossbowState.UNCHARGED

        init {
            flags = EnumSet.of(Flag.MOVE, Flag.LOOK)
        }

        override fun canUse(): Boolean = target?.isAlive == true && rangedWeaponHand() != null

        override fun canContinueToUse(): Boolean = canUse() || !navigation.isDone

        override fun start() {
            isAggressive = true
            val hand = rangedWeaponHand()
            val charged = hand != null && CrossbowItem.isCharged(getItemInHand(hand))
            crossbowState = if (charged) CrossbowState.CHARGED else CrossbowState.UNCHARGED
        }

        override fun stop() {
            isAggressive = false
            seeTime = 0
            attackCooldown = -1
            if (isUsingItem) stopUsingItem()
            setChargingCrossbow(false)
        }

        override fun requiresUpdateEveryTick(): Boolean = true

        override fun tick() {
            val target = target ?: return
            val hand = rangedWeaponHand() ?: return
            val distSq = distanceToSqr(target.x, target.y, target.z)
            val canSee = sensing.hasLineOfSight(target)
            seeTime = if (canSee) maxOf(seeTime, 0) + 1 else minOf(seeTime, 0) - 1
            val anchored = isOrderedToSit || commandedPost != null
            if (!anchored && (distSq > RANGED_RANGE_SQ || seeTime < SEE_TICKS_BEFORE_HOLD)) {
                navigation.moveTo(target, 1.0)
            } else {
                navigation.stop()
            }
            lookControl.setLookAt(target, LOOK_SPEED, LOOK_SPEED)
            val weapon = getItemInHand(hand)
            when (weapon.item) {
                is CrossbowItem -> tickCrossbow(target, hand, weapon, canSee)
                is TridentItem -> tickTrident(target, canSee)
                else -> tickBow(target, hand, canSee)
            }
        }

        // Drowned throw on a fixed 40-tick interval with no draw time.
        private fun tickTrident(target: LivingEntity, canSee: Boolean) {
            if (--attackCooldown > 0 || !canSee) return
            performRangedAttack(target, 1.0f)
            attackCooldown = TRIDENT_INTERVAL_TICKS
        }

        private fun tickBow(target: LivingEntity, hand: InteractionHand, canSee: Boolean) {
            if (isUsingItem) {
                if (!canSee && seeTime < LOST_SIGHT_TICKS) {
                    stopUsingItem()
                } else if (canSee && ticksUsingItem >= BowItem.MAX_DRAW_DURATION) {
                    stopUsingItem()
                    performRangedAttack(target, BowItem.getPowerForTime(ticksUsingItem))
                    attackCooldown = ATTACK_INTERVAL_TICKS
                }
            } else if (--attackCooldown <= 0 && seeTime >= LOST_SIGHT_TICKS) {
                startUsingItem(hand)
            }
        }

        private fun tickCrossbow(target: LivingEntity, hand: InteractionHand, crossbow: ItemStack, canSee: Boolean) {
            when (crossbowState) {
                CrossbowState.UNCHARGED -> {
                    startUsingItem(hand)
                    setChargingCrossbow(true)
                    crossbowState = CrossbowState.CHARGING
                }
                CrossbowState.CHARGING -> {
                    if (!isUsingItem) {
                        crossbowState = CrossbowState.UNCHARGED
                    } else if (ticksUsingItem >= CrossbowItem.getChargeDuration(crossbow)) {
                        // releaseUsing is what loads the crossbow (CrossbowItem.releaseUsing → tryLoadProjectiles).
                        releaseUsingItem()
                        setChargingCrossbow(false)
                        attackCooldown = ATTACK_INTERVAL_TICKS + random.nextInt(ATTACK_INTERVAL_TICKS)
                        crossbowState = CrossbowState.CHARGED
                    }
                }
                CrossbowState.CHARGED -> if (--attackCooldown <= 0) crossbowState = CrossbowState.READY
                CrossbowState.READY -> if (canSee) {
                    performRangedAttack(target, 1.0f)
                    // performShooting empties the crossbow but leaves its Charged flag; a still-charged crossbow
                    // refuses to load again. RangedCrossbowAttackGoal clears it the same way.
                    CrossbowItem.setCharged(getItemInHand(hand), false)
                    crossbowState = CrossbowState.UNCHARGED
                }
            }
        }
    }

    private enum class CrossbowState { UNCHARGED, CHARGING, CHARGED, READY }

    /** Walks the patrol loop. Far points are approached in steps: one path search reaches only about FOLLOW_RANGE. */
    private inner class PatrolGoal : Goal() {
        private var repathIn = 0

        init {
            flags = EnumSet.of(Flag.MOVE)
        }

        override fun canUse(): Boolean = patrol.isNotEmpty() && commandedPost == null && !isOrderedToSit

        override fun start() {
            repathIn = 0
        }

        override fun tick() {
            val point = patrol[patrolIndex % patrol.size]
            val dx = point.x - x
            val dz = point.z - z
            if (dx * dx + dz * dz <= POST_ARRIVE_SQ && Math.abs(point.y - y) <= PATROL_ARRIVE_DY) {
                patrolIndex = (patrolIndex + 1) % patrol.size
                repathIn = 0
                return
            }
            if (--repathIn > 0) return
            repathIn = POST_REPATH_TICKS
            val dest = if (distanceToSqr(point) > PATROL_STEP_SQ) {
                LandRandomPos.getPosTowards(this@RaisedSkeletonEntity, PATROL_STEP_XZ, PATROL_STEP_Y, point) ?: point
            } else {
                point
            }
            navigation.moveTo(dest.x, dest.y, dest.z, 1.0)
        }
    }

    /** Walks to the Staff of Command post and keeps the follow goal off while one is set. */
    private inner class CommandedPostGoal : Goal() {
        private var repathIn = 0

        init {
            flags = EnumSet.of(Flag.MOVE)
        }

        override fun canUse(): Boolean = commandedPost != null && !isOrderedToSit

        override fun tick() {
            val post = commandedPost ?: return
            if (distanceToSqr(post) <= POST_ARRIVE_SQ) {
                navigation.stop()
                return
            }
            if (--repathIn > 0) return
            repathIn = POST_REPATH_TICKS
            navigation.moveTo(post.x, post.y, post.z, 1.0)
        }
    }

    companion object {
        const val ID_PATH: String = "raised_skeleton"
        val ID = EnderkinesisMod.id(ID_PATH)

        private const val TAG_SKIN = "SkinUUID"
        private const val TAG_SKIN_NAME = "SkinName"
        private const val TAG_ZOMBIE = "Zombie"
        private const val TAG_PATROL = "Patrol"
        private const val BODY_BONES = 16
        private const val BODY_FLESH = 1
        private const val MATERIAL_RETURN = 1.0 / 3.0
        private const val TAG_PATROL_INDEX = "PatrolIndex"
        private const val PATROL_ARRIVE_DY = 2.0
        private const val PATROL_STEP_XZ = 12
        private const val PATROL_STEP_Y = 7
        private const val PATROL_STEP_SQ = 14.0 * 14.0
        private const val TAG_POST_X = "PostX"
        private const val TAG_POST_Y = "PostY"
        private const val TAG_POST_Z = "PostZ"

        private const val ARROW_SPEED = 1.6f
        const val RISE_TICKS = 40
        private const val GUARD_RADIUS_SQ = 12.0 * 12.0
        private const val GUARD_SCAN_INTERVAL = 10
        private const val PACK_RADIUS = 32.0
        private const val MAX_SHARED_TARGET = 2
        // Each packmate on a target counts as this many blocks of extra distance, squared.
        private const val SPREAD_PENALTY_SQ = 8.0 * 8.0
        /** How far a sitting body drops so its forward-reaching legs rest on the ground. */
        const val SIT_DROP = 0.6
        private const val EYE_DUST_CHANCE = 3
        // The eye sockets: 2 px each side of the face centre, on the face 4 px in front of the head axis.
        private const val EYE_SPACING = 2.0 / 16.0
        private const val EYE_FORWARD = 4.0 / 16.0
        private const val EYE_DUST_RISE = 0.02
        private const val EYE_DUST_JITTER = 0.01
        private const val RISE_PARTICLES_PER_TICK = 4
        private const val RISE_DIG_SOUND_TICKS = 6
        private const val RISE_SOUND_PITCH = 0.8f
        private const val GROUND_PROBE = 0.1
        private const val RANGED_RANGE_SQ = 15.0 * 15.0
        private const val KEPT_DROP_CHANCE = 2.0f
        private const val SEE_TICKS_BEFORE_HOLD = 20
        private const val LOST_SIGHT_TICKS = -60
        private const val ATTACK_INTERVAL_TICKS = 20
        private const val TRIDENT_INTERVAL_TICKS = 40
        private const val LOOK_SPEED = 30.0f
        private const val POST_ARRIVE_SQ = 1.5 * 1.5
        private const val POST_REPATH_TICKS = 10

        @JvmField
        val DATA_SKIN_UUID: EntityDataAccessor<Optional<UUID>> =
            SynchedEntityData.defineId(RaisedSkeletonEntity::class.java, EntityDataSerializers.OPTIONAL_UUID)

        @JvmField
        val DATA_SKIN_NAME: EntityDataAccessor<String> =
            SynchedEntityData.defineId(RaisedSkeletonEntity::class.java, EntityDataSerializers.STRING)

        @JvmField
        val DATA_ZOMBIE: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(RaisedSkeletonEntity::class.java, EntityDataSerializers.BOOLEAN)

        @JvmField
        val DATA_RISE_TICKS: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(RaisedSkeletonEntity::class.java, EntityDataSerializers.INT)

        @JvmField
        val DATA_CHARGING_CROSSBOW: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(RaisedSkeletonEntity::class.java, EntityDataSerializers.BOOLEAN)

        /** The slot a raised body would wear or wield [stack] in: armour and other wearables, shields, and weapons. */
        @JvmStatic
        fun equipSlotFor(stack: ItemStack): EquipmentSlot? {
            if (stack.isEmpty) return null
            val slot = Mob.getEquipmentSlotForItem(stack)
            val item = stack.item
            return when {
                slot != EquipmentSlot.MAINHAND -> slot
                item is TieredItem || item is ProjectileWeaponItem || item is TridentItem -> slot
                else -> null
            }
        }

        /** Raised skeletons are never hostile to each other, whoever owns them. */
        @JvmStatic
        fun isPackmate(shooter: net.minecraft.world.entity.Entity?, target: net.minecraft.world.entity.Entity): Boolean =
            shooter is RaisedSkeletonEntity && target is RaisedSkeletonEntity

        fun createAttributes(): AttributeSupplier.Builder =
            Monster.createMonsterAttributes().add(Attributes.MOVEMENT_SPEED, 0.25)
    }
}
