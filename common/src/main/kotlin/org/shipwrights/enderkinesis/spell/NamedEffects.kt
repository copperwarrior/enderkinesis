package org.shipwrights.enderkinesis.spell

import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.tags.TagKey
import net.minecraft.world.SimpleContainer
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.server.level.ServerLevel
import org.shipwrights.enderkinesis.item.ScalesManager
import org.valkyrienskies.mod.api.isBlockInShipyard
import net.minecraft.world.entity.animal.Animal
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.item.PrimedTnt
import net.minecraft.world.entity.monster.Creeper
import net.minecraft.world.entity.monster.Ghast
import net.minecraft.world.entity.monster.Phantom
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.HoneycombItem
import net.minecraft.world.item.crafting.RecipeType
import net.minecraft.world.level.block.AbstractCandleBlock
import net.minecraft.world.level.block.BaseFireBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CampfireBlock
import net.minecraft.world.level.block.WeatheringCopper
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.item.ShipCloakingTracker
import org.shipwrights.enderkinesis.registry.EKEffects
import org.shipwrights.enderkinesis.registry.EKSounds
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.mod.common.ValkyrienSkiesMod
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider
import org.valkyrienskies.mod.common.toWorldCoordinates
import org.valkyrienskies.mod.common.config.VSGameConfig
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.pow
import org.valkyrienskies.core.api.util.GameTickOnly

@OptIn(GameTickOnly::class)
object NamedEffects {

    fun heat(ctx: SpellContext, delivery: Delivery) {
        val level = ctx.level
        for (item in delivery.entities(ctx).filterIsInstance<ItemEntity>()) {
            val stack = item.item
            val recipe = level.recipeManager.getRecipeFor(RecipeType.SMELTING, SimpleContainer(stack), level).orElse(null) ?: continue
            val result = recipe.getResultItem(level.registryAccess()).copy()
            if (result.isEmpty) continue
            result.count = (result.count * stack.count).coerceAtMost(result.maxStackSize)
            item.item = result
        }
        for (creeper in delivery.living(ctx).filterIsInstance<Creeper>()) creeper.ignite()
        for (pos in delivery.blocks(level)) {
            val state = level.getBlockState(pos)
            when {
                // As vanilla does, ice melts to air where water cannot be (the Nether).
                state.`is`(Blocks.ICE) || state.`is`(Blocks.FROSTED_ICE) ->
                    ctx.setBlock(pos, if (level.dimensionType().ultraWarm()) Blocks.AIR.defaultBlockState() else Blocks.WATER.defaultBlockState())
                state.`is`(Blocks.SNOW) || state.`is`(Blocks.POWDER_SNOW) -> ctx.setBlock(pos, Blocks.AIR.defaultBlockState())
                state.`is`(Blocks.TNT) -> if (ctx.mayChange(pos, Blocks.AIR.defaultBlockState())) {
                    level.removeBlock(pos, false)
                    val tnt = PrimedTnt(level, pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5, ctx.caster)
                    level.addFreshEntity(tnt)
                }
                state.block is CampfireBlock && CampfireBlock.canLight(state) -> ctx.setBlock(pos, state.setValue(CampfireBlock.LIT, true))
            }
        }
    }

    // Lava freeze is data: `fluid_freezing/lava.json`.
    fun cold(ctx: SpellContext, delivery: Delivery) {
        val level = ctx.level
        for (entity in delivery.entities(ctx)) if (entity.isOnFire) entity.clearFire()
        for (pos in delivery.blocks(level)) {
            val state = level.getBlockState(pos)
            when {
                state.block is BaseFireBlock -> ctx.setBlock(pos, Blocks.AIR.defaultBlockState())
                state.block is CampfireBlock && state.getValue(CampfireBlock.LIT) -> ctx.setBlock(pos, state.setValue(CampfireBlock.LIT, false))
                state.block is AbstractCandleBlock && state.getValue(AbstractCandleBlock.LIT) ->
                    ctx.setBlock(pos, state.setValue(AbstractCandleBlock.LIT, false))
            }
        }
    }

    private val OVERGROWN: Map<Block, Block> by lazy {
        mapOf(
            Blocks.DIRT to Blocks.GRASS_BLOCK,
            Blocks.COBBLESTONE to Blocks.MOSSY_COBBLESTONE,
            Blocks.COBBLESTONE_STAIRS to Blocks.MOSSY_COBBLESTONE_STAIRS,
            Blocks.COBBLESTONE_SLAB to Blocks.MOSSY_COBBLESTONE_SLAB,
            Blocks.COBBLESTONE_WALL to Blocks.MOSSY_COBBLESTONE_WALL,
            Blocks.STONE_BRICKS to Blocks.MOSSY_STONE_BRICKS,
            Blocks.STONE_BRICK_STAIRS to Blocks.MOSSY_STONE_BRICK_STAIRS,
            Blocks.STONE_BRICK_SLAB to Blocks.MOSSY_STONE_BRICK_SLAB,
            Blocks.STONE_BRICK_WALL to Blocks.MOSSY_STONE_BRICK_WALL,
        )
    }

    private const val GROWTH_HEAL = 1f
    /** Kept apart from [SpellContext.PULSE_DAMAGE], so that a held Growth beam heals gently. */
    private const val GROWTH_HEAL_PULSE = 0.25f

    /** Entangled comes from one blow or trap, not a held beam, so it lasts longer. */
    private const val ENTANGLE_SECONDS = 2.5f
    private const val ENTANGLE_PER_MAGNITUDE = 0.75f

    fun growth(ctx: SpellContext, delivery: Delivery, magnitude: Float) {
        val level = ctx.level
        val entangled = EKEffects.ENTANGLED.get()
        for (target in delivery.living(ctx)) {
            if (ctx.use.hostile) {
                target.addEffect(MobEffectInstance(entangled, SpellCurves.ticks(ENTANGLE_SECONDS, ENTANGLE_PER_MAGNITUDE, magnitude)), ctx.caster)
                continue
            }
            // Forward and Area casts also reach friends, so they heal and never root.
            if (ElementEffects.growthBurns(ctx, target)) continue
            if (target is Animal) {
                if (target.isBaby) target.setAge(0)
                else if (target.canFallInLove()) target.setInLove(ctx.caster as? Player)
            }
            target.heal(SpellCurves.damage(GROWTH_HEAL, magnitude) * (if (ctx.pulsed) GROWTH_HEAL_PULSE else 1f) * ctx.share)
        }
        val overgrow = SpellSounds.FirstChange(ctx, EKSounds.SPELL_GENESIS_OVERGROW, SpellSounds.magnitudeVolume(magnitude))
        for (pos in delivery.blocks(level)) {
            val state = level.getBlockState(pos)
            val grown = OVERGROWN[state.block] ?: continue
            if (ctx.setBlock(pos, grown.withPropertiesOf(state))) overgrow.at(pos)
        }
    }

    /** The most drain visuals that one Cleanse shows on one target. */
    private const val MAX_DRAINS = 4

    val CLEANSE_EXCLUSIONS: TagKey<MobEffect> = TagKey.create(Registries.MOB_EFFECT, EnderkinesisMod.id("cleanse_exclusions"))

    private val RESTORED: Map<Block, Block> by lazy {
        mapOf(
            Blocks.MOSSY_COBBLESTONE to Blocks.COBBLESTONE,
            Blocks.MOSSY_COBBLESTONE_STAIRS to Blocks.COBBLESTONE_STAIRS,
            Blocks.MOSSY_COBBLESTONE_SLAB to Blocks.COBBLESTONE_SLAB,
            Blocks.MOSSY_COBBLESTONE_WALL to Blocks.COBBLESTONE_WALL,
            Blocks.MOSSY_STONE_BRICKS to Blocks.STONE_BRICKS,
            Blocks.MOSSY_STONE_BRICK_STAIRS to Blocks.STONE_BRICK_STAIRS,
            Blocks.MOSSY_STONE_BRICK_SLAB to Blocks.STONE_BRICK_SLAB,
            Blocks.MOSSY_STONE_BRICK_WALL to Blocks.STONE_BRICK_WALL,
            Blocks.CRACKED_STONE_BRICKS to Blocks.STONE_BRICKS,
            Blocks.CRACKED_DEEPSLATE_BRICKS to Blocks.DEEPSLATE_BRICKS,
            Blocks.CRACKED_DEEPSLATE_TILES to Blocks.DEEPSLATE_TILES,
            Blocks.CRACKED_NETHER_BRICKS to Blocks.NETHER_BRICKS,
            Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS to Blocks.POLISHED_BLACKSTONE_BRICKS,
        )
    }

    /** Effects that the spell applies stay. If not, a held Purity beam removes and shatters its
     *  own Crystallized on each pulse. */
    fun cleanse(ctx: SpellContext, targets: List<LivingEntity>) {
        val own = ctx.elements.flatMap { appliedBy(it) }.toSet()
        for (target in targets) {
            val removable = target.activeEffects.map { it.effect }.filter {
                it !in own && !BuiltInRegistries.MOB_EFFECT.wrapAsHolder(it).`is`(CLEANSE_EXCLUSIONS)
            }
            for (effect in removable) target.removeEffect(effect)
            if (removable.isNotEmpty()) SpellSounds.play(ctx.level, target.position(), EKSounds.SPELL_QUINTESSENCE_CLEANSE)
            for (effect in removable.take(MAX_DRAINS)) SpellVfx.cleanseDrain(ctx.level, target, effect.color)
        }
    }

    private fun appliedBy(element: Element): List<MobEffect> = when (element) {
        Element.WINTER -> listOf(EKEffects.FROZEN.get())
        Element.GENESIS -> listOf(EKEffects.ENTANGLED.get())
        Element.QUINTESSENCE -> listOf(EKEffects.CRYSTALLIZED.get())
        Element.GRAVITY -> listOf(EKEffects.WEIGHTED.get())
        Element.CHAOS -> listOf(EKEffects.DISCORD.get())
        Element.BLOOD -> listOf(
            EKEffects.BLEEDING.get(), EKEffects.ENTHRALLED.get(), EKEffects.BLOODBOUND.get(),
            EKEffects.BLOOD_SURGE.get(), MobEffects.REGENERATION,
        )
        Element.CORRUPTION -> listOf(EKEffects.ROT.get(), MobEffects.HUNGER, MobEffects.WEAKNESS)
        Element.VOID -> listOf(EKEffects.HOLLOWED.get(), EKEffects.ABSENT.get())
        Element.PYRE, Element.SUNDERING, Element.SUNFIRE -> emptyList()
    }

    fun purity(ctx: SpellContext, delivery: Delivery, magnitude: Float) {
        val level = ctx.level
        for (target in delivery.living(ctx)) target.removeEffect(MobEffects.INVISIBILITY)
        reveal(ctx, delivery, magnitude)
        for (pos in delivery.blocks(level)) {
            val state = level.getBlockState(pos)
            val restored = restored(state) ?: continue
            ctx.setBlock(pos, restored)
        }
    }

    private fun reveal(ctx: SpellContext, delivery: Delivery, magnitude: Float) {
        val level = ctx.level
        val bounds = delivery.bounds()
        val ticks = SpellCurves.ticks(3f, 1f, magnitude)
        for (ship in level.shipObjectWorld.loadedShips) {
            if (!ShipCloakingTracker.isCloaking(ship.id) || ship.chunkClaimDimension != level.dimensionId) continue
            val box = ship.worldAABB
            val shipBox = AABB(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())
            val touches = if (delivery is Delivery.Line) {
                shipBox.contains(delivery.from) || shipBox.clip(delivery.from, delivery.to).isPresent
            } else shipBox.intersects(bounds)
            if (touches) SpellNetwork.broadcastStutter(level, ship.id, ticks)
        }
    }

    private fun restored(state: BlockState): BlockState? {
        HoneycombItem.WAX_OFF_BY_BLOCK.get()[state.block]?.let { return it.withPropertiesOf(state) }
        WeatheringCopper.getPrevious(state).orElse(null)?.let { return it }
        return RESTORED[state.block]?.withPropertiesOf(state)
    }

    private const val GROUND_PULL = 1.0
    /** VS2 TNT sends one ray to each block within 4 (9 by 9 by 9, less the middle). */
    private const val TNT_RAYS = 728.0
    /** In m/s. Stops a light ship from going through the ground. */
    private const val TETHER_MAX_SPEED = 3.0
    /** A queued VS2 force lasts one physics tick. */
    private const val PHYSICS_TPS = 60.0

    fun gravity(ctx: SpellContext, delivery: Delivery, magnitude: Float) {
        val weighted = EKEffects.WEIGHTED.get()
        for (target in delivery.living(ctx)) {
            if (target is Player && target.isFallFlying) target.stopFallFlying()
            target.removeEffect(MobEffects.LEVITATION)
            if (target is Phantom || target is Ghast) {
                target.deltaMovement = target.deltaMovement.add(0.0, -GROUND_PULL * magnitude * ctx.share, 0.0)
                target.hurtMarked = true
            }
            target.addEffect(MobEffectInstance(weighted, SpellCurves.ticks(4f, 2f, magnitude)), ctx.caster)
        }
        if (delivery is Delivery.Sphere) tether(ctx, delivery, magnitude)
    }

    /** Approximates the VS2 TNT push without rays: each ship gets the share of rays that its
     *  bounds catch, turned inward. The pull does not grow with the sphere. */
    private fun tether(
        ctx: SpellContext,
        sphere: Delivery.Sphere,
        @Suppress("UNUSED_PARAMETER") magnitude: Float, // By design, the pull does not scale with magnitude.
    ) {
        val level = ctx.level
        val gtpa = ValkyrienSkiesMod.getOrCreateGTPA(level.dimensionId)
        // VS2 keeps lastShipStoodOn for some time after the caster leaves the ship.
        val dragging = (ctx.caster as? IEntityDraggingInformationProvider)?.draggingInformation
        val own = dragging?.takeIf { it.isEntityBeingDraggedByAShip() }?.lastShipStoodOn
        val excluded = own?.let { setOf(it) + gtpa.getAllConnectedShips(it) } ?: emptySet()
        // World frame, also when a Bolt lands on a ship.
        val c = level.toWorldCoordinates(sphere.center)
        val radius = sphere.radius
        val blast = VSGameConfig.SERVER.explosionBlastForce * TNT_RAYS
        for (ship in level.shipObjectWorld.loadedShips) {
            val srv = ship as? LoadedServerShip ?: continue
            if (srv.id in excluded || srv.chunkClaimDimension != level.dimensionId) continue
            val box = srv.worldAABB
            val nx = c.x.coerceIn(box.minX(), box.maxX())
            val ny = c.y.coerceIn(box.minY(), box.maxY())
            val nz = c.z.coerceIn(box.minZ(), box.maxZ())
            val near = Vec3(nx, ny, nz).distanceTo(c)
            if (near > radius) continue
            val middle = Vec3((box.minX() + box.maxX()) * 0.5, (box.minY() + box.maxY()) * 0.5, (box.minZ() + box.maxZ()) * 0.5)
            val toward = c.subtract(middle)
            if (toward.lengthSqr() < 1.0e-6) continue
            val dir = toward.normalize()
            // Never more than half of all directions.
            val sx = box.maxX() - box.minX(); val sy = box.maxY() - box.minY(); val sz = box.maxZ() - box.minZ()
            val facing = abs(dir.x) * sy * sz + abs(dir.y) * sx * sz + abs(dir.z) * sx * sy
            val share = (facing / near.coerceAtLeast(1.0).pow(2) / (4 * PI)).coerceAtMost(0.5)
            val falloff = 1 - near / (radius * 2)
            val mostForMass = srv.inertiaData.mass * TETHER_MAX_SPEED * PHYSICS_TPS
            val mag = minOf(blast * share * falloff, mostForMass)
            if (!mag.isFinite() || mag <= 0) continue
            // The replacement takes a body-frame point whose origin is not checked yet; changing it needs a playtest.
            @Suppress("DEPRECATION")
            gtpa.applyWorldForceToBodyPos(srv.id, Vector3d(dir.x * mag, dir.y * mag, dir.z * mag))
        }
    }

    /** A whitelist keeps the effects of other mods safe. */
    val SHUFFLE_WHITELIST: TagKey<MobEffect> = TagKey.create(Registries.MOB_EFFECT, EnderkinesisMod.id("shuffle_whitelist"))

    /** The Area cast's chaos effect is the position cycle in [ElementEffects]. */
    fun chaos(ctx: SpellContext, delivery: Delivery, magnitude: Float) {
        val targets = delivery.living(ctx)
        when (ctx.use) {
            SpellUse.FORWARD -> {
                val discord = EKEffects.DISCORD.get()
                for (target in targets) target.addEffect(MobEffectInstance(discord, SpellCurves.ticks(3f, 1f, magnitude)), ctx.caster)
            }
            SpellUse.AREA -> {}
            SpellUse.RUNE -> if (ctx.once()) for (target in targets) scatter(ctx, target, magnitude)
            SpellUse.IMBUE -> if (ctx.once()) shuffle(ctx, listOfNotNull(ctx.caster) + targets, SpellSounds.magnitudeVolume(magnitude))
        }
    }

    private fun shuffle(ctx: SpellContext, targets: List<LivingEntity>, volume: Float) {
        if (targets.size < 2) return
        val pool = ArrayList<Pair<LivingEntity, MobEffectInstance>>()
        for (target in targets) {
            val movable = target.activeEffects.filter { BuiltInRegistries.MOB_EFFECT.wrapAsHolder(it.effect).`is`(SHUFFLE_WHITELIST) }
            for (instance in movable) {
                pool += target to MobEffectInstance(instance)
                target.removeEffect(instance.effect)
            }
        }
        var moved = false
        for ((from, instance) in pool) {
            val to = targets[ctx.level.random.nextInt(targets.size)]
            to.addEffect(instance, ctx.caster)
            if (to === from) continue
            SpellVfx.shuffleSling(ctx.level, from, to, instance.effect.color)
            moved = true
        }
        if (moved) SpellSounds.play(ctx.level, targets[0].position(), EKSounds.SPELL_CHAOS_SWAP, volume)
    }

    private const val SCATTER_TRIES = 16

    private fun scatter(ctx: SpellContext, target: LivingEntity, magnitude: Float) {
        if (!mayTeleport(ctx.level, target)) return
        val range = 5.0 + magnitude
        val random = ctx.level.random
        repeat(SCATTER_TRIES) {
            val x = target.x + (random.nextDouble() * 2 - 1) * range
            val y = target.y + (random.nextDouble() * 2 - 1) * range * 0.5
            val z = target.z + (random.nextDouble() * 2 - 1) * range
            if (ctx.level.isBlockInShipyard(BlockPos.containing(x, y, z))) return@repeat
            if (target.randomTeleport(x, y, z, true)) {
                SpellSounds.play(ctx.level, target.position(), EKSounds.SPELL_CHAOS_SWAP, SpellSounds.magnitudeVolume(magnitude))
                return
            }
        }
    }

    /** The Scales blacklist marks dimensions where another mod owns the ships. */
    fun mayTeleport(level: ServerLevel, entity: LivingEntity): Boolean =
        !ScalesManager.isDimensionBlacklisted(level) && !SpellContext.isBoss(entity) && !level.isBlockInShipyard(entity.blockPosition()) && !anchored(entity)

    fun anchored(entity: Entity): Boolean = entity is LivingEntity && entity.hasEffect(EKEffects.ANCHORED.get())

    private const val ENTHRALL_HEALTH = 0.4f

    /** A thrall gets no other effect. */
    fun blood(ctx: SpellContext, drained: List<LivingEntity>, master: LivingEntity?, magnitude: Float) {
        val bleeding = EKEffects.BLEEDING.get()
        for (target in drained) {
            if (!target.isAlive) continue
            if (ctx.use == SpellUse.IMBUE) {
                master?.let { BloodPacts.bind(target, it, SpellCurves.ticks(3f, 1f, magnitude)) }
            } else if (master != null && target is Mob && target.health <= target.maxHealth * ENTHRALL_HEALTH) {
                BloodPacts.enthrall(ctx.level, target, master, magnitude, SpellCurves.ticks(15f, 5f, magnitude))
                continue
            }
            target.addEffect(MobEffectInstance(bleeding, SpellCurves.ticks(4f, 2f, magnitude)), ctx.caster)
        }
    }

    fun find(level: ServerLevel, box: AABB, block: Block): List<BlockPos> {
        val out = ArrayList<BlockPos>()
        val minX = Mth.floor(box.minX); val minY = Mth.floor(box.minY); val minZ = Mth.floor(box.minZ)
        val maxX = Mth.floor(box.maxX); val maxY = Mth.floor(box.maxY); val maxZ = Mth.floor(box.maxZ)
        for (cx in (minX shr 4)..(maxX shr 4)) for (cz in (minZ shr 4)..(maxZ shr 4)) {
            val chunk = level.chunkSource.getChunkNow(cx, cz) ?: continue
            for (sy in (maxOf(minY, level.minBuildHeight) shr 4)..(minOf(maxY, level.maxBuildHeight - 1) shr 4)) {
                val section = chunk.getSection(chunk.getSectionIndexFromSectionY(sy))
                if (section.hasOnlyAir() || !section.maybeHas { it.`is`(block) }) continue
                for (y in maxOf(minY, sy shl 4)..minOf(maxY, (sy shl 4) + 15))
                    for (x in maxOf(minX, cx shl 4)..minOf(maxX, (cx shl 4) + 15))
                        for (z in maxOf(minZ, cz shl 4)..minOf(maxZ, (cz shl 4) + 15)) {
                            if (section.getBlockState(x and 15, y and 15, z and 15).`is`(block)) out += BlockPos(x, y, z)
                        }
            }
        }
        return out
    }
}
