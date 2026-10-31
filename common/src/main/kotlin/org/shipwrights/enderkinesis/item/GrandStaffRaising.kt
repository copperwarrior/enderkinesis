package org.shipwrights.enderkinesis.item

import com.mojang.authlib.GameProfile
import dev.architectury.event.EventResult
import dev.architectury.event.events.common.EntityEvent
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.ThrowablePotionItem
import net.minecraft.world.item.alchemy.PotionUtils
import net.minecraft.world.level.storage.loot.LootParams
import net.minecraft.world.level.storage.loot.LootTable
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets
import net.minecraft.world.level.storage.loot.parameters.LootContextParams
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.entity.PlayerCorpseEntity
import org.shipwrights.enderkinesis.entity.RaisedSkeletonEntity
import org.shipwrights.enderkinesis.registry.EKEntities

/** What the Grand Staff of the Dead does while cast: death loot, [RaisingRites] on deaths, and gather rites on items. */
object GrandStaffRaising {

    private const val RADIUS = 16.0
    private const val RADIUS_SQ = RADIUS * RADIUS
    private const val SOUL_PARTICLES = 20
    private const val GATHER_INTERVAL_TICKS = 2L
    private const val GATHER_PICKUP_LOCK_TICKS = 10
    private const val GATHER_MAX_SPEED = 0.35
    private const val GATHER_SPEED_PER_BLOCK = 0.15
    private const val GATHER_ARRIVE_SQ = 1.0
    private const val GATHER_PARTICLES = 3
    private const val MAX_GATHER_STACKS = 128
    // Clusters are ordered nearest the caster first; farther ones gather without the soul puff.
    private const val MAX_PUFFING_CLUSTERS = 4
    private const val BONE_MEAL_HEAL = 2f
    // Rotten flesh's food value.
    private const val FLESH_HEAL = 4f
    private const val HEAL_PARTICLES = 5
    private const val MIN_SHARED_TICKS = 20
    private const val POTION_PUFF_PARTICLES = 12
    private const val GATHER_LIFT = 2.5
    private const val GATHER_DESCEND_RADIUS = 1.5
    private const val GATHER_VERTICAL_GAIN = 0.3
    private const val GATHER_CLIMB_SLACK = 0.5
    private const val GATHER_CLIMB_DRIFT = 0.3
    // ItemEntity.tick: deltaMovement.y -= 0.04 each tick.
    private const val ITEM_GRAVITY = 0.04
    private const val DRESS_AIM_HEIGHT = 0.5
    private const val DRESS_ARRIVE_SQ = 1.5 * 1.5

    // Register after WikLakDeathRedirect: its interrupt stops later listeners, so a redirected
    // player death raises nothing here (its corpse dies next and raises instead).
    fun init() {
        EntityEvent.LIVING_DEATH.register(::onLivingDeath)
    }

    private fun onLivingDeath(entity: LivingEntity, source: DamageSource): EventResult {
        val level = entity.level() as? ServerLevel ?: return EventResult.pass()
        val caster = nearestCaster(level, entity) ?: return EventResult.pass()
        dropDeathLoot(level, entity, source)
        val rite = RaisingRites.forEntity(entity) ?: return EventResult.pass()
        val skin = if (!rite.wearSkin) null else when (entity) {
            is Player -> entity.gameProfile
            is PlayerCorpseEntity -> entity.playerUuid?.let { profileOf(level, it) }
            else -> null
        }
        // A death raises at once, so its flesh is taken where it lies instead of being pulled in.
        val flesh = if (rite.zombie || rite.flesh == null) null else fleshIn(itemsNear(level, caster), rite)
        if (flesh != null) {
            for (item in flesh) level.sendParticles(ParticleTypes.SOUL, item.x, item.y + 0.2, item.z, GATHER_PARTICLES, 0.1, 0.1, 0.1, 0.01)
            consume(flesh, rite.fleshCount)
        }
        raise(level, caster, entity.position(), entity.yRot, rite, entity, skin, zombie = rite.zombie || flesh != null)
        return EventResult.pass()
    }

    /** The corpse's player is usually online (the redirect keeps them alive); otherwise the server's profile cache. */
    private fun profileOf(level: ServerLevel, uuid: java.util.UUID): GameProfile {
        level.server.playerList.getPlayer(uuid)?.let { return it.gameProfile }
        return level.server.profileCache?.get(uuid)?.orElse(null) ?: GameProfile(uuid, null)
    }

    private fun dropDeathLoot(level: ServerLevel, entity: LivingEntity, source: DamageSource) {
        val typeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.type)
        val tableId = EnderkinesisMod.id("grand_staff_of_the_dead/${typeId.namespace}/${typeId.path}")
        val table = level.server.lootData.getLootTable(tableId)
        if (table === LootTable.EMPTY) return
        val params = LootParams.Builder(level)
            .withParameter(LootContextParams.THIS_ENTITY, entity)
            .withParameter(LootContextParams.ORIGIN, entity.position())
            .withParameter(LootContextParams.DAMAGE_SOURCE, source)
            .withOptionalParameter(LootContextParams.KILLER_ENTITY, source.entity)
            .withOptionalParameter(LootContextParams.DIRECT_KILLER_ENTITY, source.directEntity)
            .withOptionalParameter(LootContextParams.LAST_DAMAGE_PLAYER, source.entity as? Player)
            .create(LootContextParamSets.ENTITY)
        table.getRandomItems(params) { entity.spawnAtLocation(it) }
    }

    private fun nearestCaster(level: ServerLevel, dying: LivingEntity): Player? {
        var best: Player? = null
        var bestSq = RADIUS_SQ
        for (player in level.players()) {
            if (player === dying || !isCasting(player)) continue
            val distSq = player.distanceToSqr(dying)
            if (distSq <= bestSq) {
                best = player
                bestSq = distSq
            }
        }
        return best
    }

    private fun isCasting(player: Player): Boolean =
        player.isUsingItem && player.useItem.item is GrandStaffOfTheDeadItem

    /** Pulls items for the first gather rite with enough in range together, then raises once enough have arrived. */
    fun tickGather(level: ServerLevel, caster: Player) {
        if (level.gameTime % GATHER_INTERVAL_TICKS != 0L) return
        val nearby = itemsNear(level, caster)
        val claimed = gatherBodies(level, caster, nearby)
        dressRaised(level, caster, nearby, claimed)
        healRaised(level, caster, nearby, claimed)
    }

    /** Runs the first gather rite with enough material in range. Returns the gear and flesh its forming bodies claimed. */
    private fun gatherBodies(level: ServerLevel, caster: Player, nearby: List<ItemEntity>): Set<ItemEntity> {
        for (gather in RaisingRites.gatherRites()) {
            val bones = nearby.filter { gather.ingredient.test(it.item) }
                .sortedBy { it.distanceToSqr(caster) }
                .take(MAX_GATHER_STACKS)
            if (bones.sumOf { it.item.count } < gather.count) continue
            val rite = gather.rite
            val clusters = clusters(bones, gather.count, caster)
            val fleshIngredient = rite.flesh
            for (item in nearby) {
                if (gather.ingredient.test(item.item)) continue
                val nearest = clusters.minBy { it.anchor.distanceToSqr(item.position()) }
                if (fleshIngredient != null && fleshIngredient.test(item.item)) {
                    nearest.flesh += item
                    continue
                }
                val slot = RaisedSkeletonEntity.equipSlotFor(item.item) ?: continue
                // One piece for each slot; extra gear stays free for bodies already risen.
                if (nearest.gear.none { RaisedSkeletonEntity.equipSlotFor(it.item) == slot }) nearest.gear += item
            }
            clusters.forEachIndexed { i, cluster -> gatherCluster(level, caster, gather, cluster, puff = i < MAX_PUFFING_CLUSTERS) }
            return clusters.flatMapTo(HashSet()) { it.gear + it.flesh }
        }
        return emptySet()
    }

    /** Pulls loose gear to the caster's nearest risen body with that slot empty, and puts it on when it arrives. */
    private fun dressRaised(level: ServerLevel, caster: Player, nearby: List<ItemEntity>, claimed: Set<ItemEntity>) {
        val gear = nearby.filter { it !in claimed && RaisedSkeletonEntity.equipSlotFor(it.item) != null }
        if (gear.isEmpty()) return
        val bodies = ownedBodiesNear(level, caster).filterNot { it.isRising }
        if (bodies.isEmpty()) return
        // One item for each empty slot in a pass, so two helmets do not race for the same head.
        val reserved = HashSet<Pair<RaisedSkeletonEntity, EquipmentSlot>>()
        for (item in gear.take(MAX_GATHER_STACKS)) {
            val slot = RaisedSkeletonEntity.equipSlotFor(item.item) ?: continue
            val body = bodies
                .filter { it.getItemBySlot(slot).isEmpty && (it to slot) !in reserved }
                .minByOrNull { it.distanceToSqr(item) } ?: continue
            reserved += body to slot
            val at = body.position().add(0.0, DRESS_AIM_HEIGHT, 0.0)
            if (item.distanceToSqr(at) <= DRESS_ARRIVE_SQ) {
                body.equipKept(slot, takeOne(item))
            } else {
                pull(listOf(item), at)
            }
        }
    }

    /** [gear]: weapons and armour lying nearest this group; the body rises wearing what has arrived. */
    private class Cluster(val anchor: Vec3, val bones: List<ItemEntity>) {
        val flesh = ArrayList<ItemEntity>()
        val gear = ArrayList<ItemEntity>()
    }

    /** Groups of at least [need] bones. Each group starts at the free bone nearest the caster and takes the bones
     *  nearest that one, so separate piles become separate bodies. Leftovers short of [need] stay put. */
    private fun clusters(bones: List<ItemEntity>, need: Int, caster: Player): List<Cluster> {
        val free = ArrayList(bones)
        val out = ArrayList<Cluster>()
        // Each pass removes at least one stack, so the loop ends within bones.size passes.
        while (free.sumOf { it.item.count } >= need) {
            val anchor = free.removeAt(0)
            val members = arrayListOf(anchor)
            var total = anchor.item.count
            free.sortBy { it.distanceToSqr(anchor) }
            while (total < need) {
                val next = free.removeAt(0)
                members += next
                total += next.item.count
            }
            free.sortBy { it.distanceToSqr(caster) }
            out += Cluster(anchor.position(), members)
        }
        return out
    }

    private fun gatherCluster(
        level: ServerLevel, caster: Player, gather: RaisingRites.GatherRite, cluster: Cluster, puff: Boolean,
    ) {
        val rite = gather.rite
        val at = cluster.anchor
        val flesh = if (rite.zombie) null else cluster.flesh.takeIf { list -> list.sumOf { it.item.count } >= rite.fleshCount }
        val bonesArrived = pull(cluster.bones, at)
        val fleshArrived = if (flesh != null) pull(flesh, at) else 0
        pull(cluster.gear, at)
        if (puff) level.sendParticles(ParticleTypes.SOUL, at.x, at.y + 0.3, at.z, GATHER_PARTICLES, 0.2, 0.2, 0.2, 0.01)
        if (bonesArrived < gather.count || (flesh != null && fleshArrived < rite.fleshCount)) return
        consume(cluster.bones.filter { arrived(it, at) }, gather.count)
        if (flesh != null) consume(flesh.filter { arrived(it, at) }, rite.fleshCount)
        raise(
            level, caster, at, caster.yRot + 180f, rite, null, null,
            zombie = rite.zombie || flesh != null, gear = cluster.gear.filter { arrived(it, at) },
        )
    }

    /** The rite's flesh items in [items], or null when the rite takes none or too little lies there. */
    private fun fleshIn(items: List<ItemEntity>, rite: RaisingRites.Rite): List<ItemEntity>? {
        val ingredient = rite.flesh ?: return null
        val flesh = items.filter { ingredient.test(it.item) }
        return if (flesh.sumOf { it.item.count } >= rite.fleshCount) flesh else null
    }

    private fun arrived(item: ItemEntity, center: Vec3): Boolean = item.distanceToSqr(center) <= GATHER_ARRIVE_SQ

    /** Moves [items] toward [center] and locks their pickup. Returns how many have arrived. */
    private fun pull(items: List<ItemEntity>, center: Vec3): Int {
        var arrivedCount = 0
        for (item in items) {
            item.setPickUpDelay(GATHER_PICKUP_LOCK_TICKS)
            if (arrived(item, center)) {
                arrivedCount += item.item.count
            } else {
                item.deltaMovement = pullVelocity(item, center)
            }
        }
        return arrivedCount
    }

    /** Lift to a cruise height over [center], cross at that height, then drop in, so items clear walls and ledges. */
    private fun pullVelocity(item: ItemEntity, center: Vec3): Vec3 {
        val dx = center.x - item.x
        val dz = center.z - item.z
        val across = Math.sqrt(dx * dx + dz * dz)
        val cruising = across > GATHER_DESCEND_RADIUS
        val aimY = if (cruising) maxOf(center.y + GATHER_LIFT, item.y) else center.y
        val dy = aimY - item.y
        val vy = (dy * GATHER_VERTICAL_GAIN).coerceIn(-GATHER_MAX_SPEED, GATHER_MAX_SPEED)
        // Hold most of the sideways speed until the item is near its cruise height.
        val climbing = cruising && dy > GATHER_CLIMB_SLACK
        val speed = minOf(GATHER_MAX_SPEED, across * GATHER_SPEED_PER_BLOCK) * (if (climbing) GATHER_CLIMB_DRIFT else 1.0)
        val sideways = if (across > 1.0e-6) speed / across else 0.0
        // Item entities fall between the pulls; give back the gravity of the ticks until the next one.
        return Vec3(dx * sideways, vy + ITEM_GRAVITY * GATHER_INTERVAL_TICKS, dz * sideways)
    }

    /** Pulls loose bone meal to the caster's damaged bodies, and rotten flesh to damaged zombies, to heal them. */
    private fun healRaised(level: ServerLevel, caster: Player, nearby: List<ItemEntity>, claimed: Set<ItemEntity>) {
        val salves = nearby.filter { it !in claimed && healAmount(it.item) > 0f }
        if (salves.isEmpty()) return
        val wounded = ownedBodiesNear(level, caster).filter { !it.isRising && it.health < it.maxHealth }
        if (wounded.isEmpty()) return
        // Health already on its way to each body this pass, so a body draws no more than it is missing.
        val pending = java.util.IdentityHashMap<RaisedSkeletonEntity, Float>()
        for (item in salves.take(MAX_GATHER_STACKS)) {
            val heal = healAmount(item.item)
            val anyBody = item.item.`is`(Items.BONE_MEAL)
            val body = wounded
                .filter { (anyBody || it.isZombie) && it.maxHealth - it.health > (pending[it] ?: 0f) }
                .minByOrNull { it.distanceToSqr(item) } ?: continue
            pending[body] = (pending[body] ?: 0f) + heal * item.item.count
            val at = body.position().add(0.0, DRESS_AIM_HEIGHT, 0.0)
            if (item.distanceToSqr(at) <= DRESS_ARRIVE_SQ) {
                takeOne(item)
                body.heal(heal)
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, body.x, body.y + body.bbHeight * 0.6, body.z, HEAL_PARTICLES, 0.3, 0.4, 0.3, 0.0)
            } else {
                pull(listOf(item), at)
            }
        }
    }

    /** Bone meal mends any body; rotten flesh only a zombie's. */
    private fun healAmount(stack: ItemStack): Float = when {
        stack.`is`(Items.BONE_MEAL) -> BONE_MEAL_HEAL
        stack.`is`(Items.ROTTEN_FLESH) -> FLESH_HEAL
        else -> 0f
    }

    private fun ownedBodiesNear(level: ServerLevel, caster: Player): List<RaisedSkeletonEntity> =
        level.getEntitiesOfClass(RaisedSkeletonEntity::class.java, caster.boundingBox.inflate(RADIUS)) {
            it.isAlive && it.isOwnedBy(caster) && it.distanceToSqr(caster) <= RADIUS_SQ
        }

    /** Splits the off-hand potion evenly over the caster's bodies in range: 1/N of each duration, 1/N of each instant. */
    fun sharePotion(level: ServerLevel, caster: Player) {
        val potion = caster.offhandItem
        val bodies = ownedBodiesNear(level, caster)
        if (bodies.isEmpty()) {
            caster.displayClientMessage(Component.translatable("item.enderkinesis.grand_staff_of_the_dead.no_minions"), true)
            return
        }
        val effects = PotionUtils.getMobEffects(potion)
        val share = 1.0 / bodies.size
        val color = PotionUtils.getColor(potion)
        for (body in bodies) {
            for (effect in effects) {
                val type = effect.effect
                if (type.isInstantenous) {
                    type.applyInstantenousEffect(caster, caster, body, effect.amplifier, share)
                } else {
                    val duration = maxOf(MIN_SHARED_TICKS, (effect.duration * share).toInt())
                    body.addEffect(MobEffectInstance(type, duration, effect.amplifier, effect.isAmbient, effect.isVisible), caster)
                }
            }
            potionPuff(level, body, color)
        }
        level.playSound(null, caster.x, caster.y, caster.z, SoundEvents.BREWING_STAND_BREW, SoundSource.PLAYERS, 0.8f, 1.2f)
        if (caster.abilities.instabuild) return
        val drinkable = potion.item !is ThrowablePotionItem
        potion.shrink(1)
        if (!drinkable) return
        val bottle = ItemStack(Items.GLASS_BOTTLE)
        if (potion.isEmpty) caster.setItemInHand(InteractionHand.OFF_HAND, bottle)
        else if (!caster.inventory.add(bottle)) caster.drop(bottle, false)
    }

    // ENTITY_EFFECT reads a zero-count particle's speed fields as its colour.
    private fun potionPuff(level: ServerLevel, body: RaisedSkeletonEntity, color: Int) {
        val r = (color shr 16 and 0xFF) / 255.0
        val g = (color shr 8 and 0xFF) / 255.0
        val b = (color and 0xFF) / 255.0
        repeat(POTION_PUFF_PARTICLES) {
            level.sendParticles(
                ParticleTypes.ENTITY_EFFECT,
                body.getRandomX(0.6), body.getRandomY(), body.getRandomZ(0.6),
                0, r, g, b, 1.0,
            )
        }
    }

    private fun itemsNear(level: ServerLevel, caster: Player): List<ItemEntity> =
        level.getEntitiesOfClass(ItemEntity::class.java, caster.boundingBox.inflate(RADIUS)) {
            it.isAlive && it.distanceToSqr(caster) <= RADIUS_SQ
        }

    private fun takeOne(item: ItemEntity): ItemStack {
        val rest = item.item.copy()
        val one = rest.split(1)
        // Replace, not shrink in place: the synced stack only updates on setItem.
        if (rest.isEmpty) item.discard() else item.item = rest
        return one
    }

    private fun consume(items: List<ItemEntity>, count: Int) {
        var remaining = count
        for (item in items) {
            if (remaining <= 0) return
            val stack = item.item.copy()
            val n = minOf(remaining, stack.count)
            stack.shrink(n)
            remaining -= n
            // Replace, not shrink in place: the synced stack only updates on setItem.
            if (stack.isEmpty) item.discard() else item.item = stack
        }
    }

    private fun raise(
        level: ServerLevel,
        caster: Player,
        at: Vec3,
        yaw: Float,
        rite: RaisingRites.Rite,
        source: LivingEntity?,
        skin: GameProfile?,
        zombie: Boolean = false,
        gear: List<ItemEntity> = emptyList(),
    ) {
        val skeleton = EKEntities.RAISED_SKELETON.get().create(level) ?: return
        skeleton.moveTo(at.x, at.y, at.z, yaw, 0f)
        skeleton.raise(caster, skin?.id, skin?.name)
        skeleton.isZombie = zombie
        for ((slot, equip) in rite.equipment) {
            when (equip) {
                is RaisingRites.Equip.Give -> skeleton.equipConjured(slot, equip.stack.copy())
                RaisingRites.Equip.Inherit -> {
                    val held = source?.getItemBySlot(slot) ?: continue
                    if (held.isEmpty) continue
                    // Runs inside die(), before death drops: clearing the slot moves the item instead of duplicating it.
                    source.setItemSlot(slot, ItemStack.EMPTY)
                    skeleton.equipKept(slot, held)
                }
            }
        }
        // One item for each empty slot; the rest of the gear stays on the ground.
        for (item in gear) {
            val slot = RaisedSkeletonEntity.equipSlotFor(item.item) ?: continue
            if (!skeleton.getItemBySlot(slot).isEmpty) continue
            skeleton.equipKept(slot, takeOne(item))
        }
        level.addFreshEntity(skeleton)
        skeleton.startRising()
        level.sendParticles(ParticleTypes.SOUL, at.x, at.y + 1.0, at.z, SOUL_PARTICLES, 0.3, 0.5, 0.3, 0.02)
        level.playSound(null, at.x, at.y, at.z, SoundEvents.SOUL_ESCAPE, SoundSource.HOSTILE, 1.0f, 0.8f)
    }
}
