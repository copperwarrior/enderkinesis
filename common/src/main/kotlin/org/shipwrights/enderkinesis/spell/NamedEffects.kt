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

/**
 * Each shard element's named effects, on top of its base effect in [ElementEffects]. Effects
 * that do nothing a second time (Smelt, Thaw, Kindle, Quench, Overgrow, Restore) need no
 * cap when a held shape re-applies them; the stacking ones are limited where they apply.
 * Block changes go through the owner's claims ([SpellContext.setBlock]).
 */
object NamedEffects {

    // -----------------------------------------------------------------------
    // Heat: Smelt, Thaw, Kindle
    // -----------------------------------------------------------------------

    fun heat(ctx: SpellContext, delivery: Delivery) {
        val level = ctx.level
        // Smelt: dropped items cook as in a furnace.
        for (item in delivery.entities(ctx).filterIsInstance<ItemEntity>()) {
            val stack = item.item
            val recipe = level.recipeManager.getRecipeFor(RecipeType.SMELTING, SimpleContainer(stack), level).orElse(null) ?: continue
            val result = recipe.getResultItem(level.registryAccess()).copy()
            if (result.isEmpty) continue
            result.count = (result.count * stack.count).coerceAtMost(result.maxStackSize)
            item.item = result
        }
        // Kindle: creepers ignite.
        for (creeper in delivery.living(ctx).filterIsInstance<Creeper>()) creeper.ignite()
        for (pos in delivery.blocks(level)) {
            val state = level.getBlockState(pos)
            when {
                // Thaw: ice melts to water, or to nothing where water can't be (the Nether), as
                // vanilla's does; snow layers and powder snow vanish.
                state.`is`(Blocks.ICE) || state.`is`(Blocks.FROSTED_ICE) ->
                    ctx.setBlock(pos, if (level.dimensionType().ultraWarm()) Blocks.AIR.defaultBlockState() else Blocks.WATER.defaultBlockState())
                state.`is`(Blocks.SNOW) || state.`is`(Blocks.POWDER_SNOW) -> ctx.setBlock(pos, Blocks.AIR.defaultBlockState())
                // Kindle: TNT primes, unlit campfires relight.
                state.`is`(Blocks.TNT) -> if (ctx.mayChange(pos, Blocks.AIR.defaultBlockState())) {
                    level.removeBlock(pos, false)
                    val tnt = PrimedTnt(level, pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5, ctx.caster)
                    level.addFreshEntity(tnt)
                }
                state.block is CampfireBlock && CampfireBlock.canLight(state) -> ctx.setBlock(pos, state.setValue(CampfireBlock.LIT, true))
            }
        }
    }

    // -----------------------------------------------------------------------
    // Cold: Quench (Lava freeze is data: `fluid_freezing/lava.json`)
    // -----------------------------------------------------------------------

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

    // -----------------------------------------------------------------------
    // Growth: Quicken, Overgrow, Entangled
    // -----------------------------------------------------------------------

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

    /** Health Growth restores per application, before the curve. */
    private const val GROWTH_HEAL = 1f
    /** A held cast's per-pulse share of that heal: kept apart from [SpellContext.PULSE_DAMAGE],
     *  so a held Growth beam's healing stays gentle. */
    private const val GROWTH_HEAL_PULSE = 0.25f

    /** Entangled comes from a single blow or trap now, not a beam held on the target, so it
     *  lasts longer: 3.25 s at magnitude 1, 7.75 s at 7. */
    private const val ENTANGLE_SECONDS = 2.5f
    private const val ENTANGLE_PER_MAGNITUDE = 0.75f

    fun growth(ctx: SpellContext, delivery: Delivery, magnitude: Float) {
        val level = ctx.level
        val entangled = EKEffects.ENTANGLED.get()
        for (target in delivery.living(ctx)) {
            // A rune or an imbued blow roots whatever it catches, and never heals it.
            if (ctx.use.hostile) {
                target.addEffect(MobEffectInstance(entangled, SpellCurves.ticks(ENTANGLE_SECONDS, ENTANGLE_PER_MAGNITUDE, magnitude)), ctx.caster)
                continue
            }
            // Forward and Area casts reach friends too: they tend, never root. What Growth
            // burns (the undead, a Forward cast's foes) isn't tended.
            if (ElementEffects.growthBurns(ctx, target)) continue
            // Quicken: babies grow up; grown animals fall in love.
            if (target is Animal) {
                if (target.isBaby) target.setAge(0)
                else if (target.canFallInLove()) target.setInLove(ctx.caster as? Player)
            }
            // Gentle healing for whatever the growth touches.
            target.heal(SpellCurves.damage(GROWTH_HEAL, magnitude) * (if (ctx.pulsed) GROWTH_HEAL_PULSE else 1f) * ctx.share)
        }
        // Overgrow.
        for (pos in delivery.blocks(level)) {
            val state = level.getBlockState(pos)
            val grown = OVERGROWN[state.block] ?: continue
            ctx.setBlock(pos, grown.withPropertiesOf(state))
        }
    }

    // -----------------------------------------------------------------------
    // Purity: Cleanse (before anything else), Restore, Reveal
    // -----------------------------------------------------------------------

    /** The most effects one Cleanse is seen draining off one target. */
    private const val MAX_DRAINS = 4

    /** Effects Cleanse leaves in place. Madness by default. */
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

    /** Cleanse: strips every status effect not in [CLEANSE_EXCLUSIONS], and none that the
     *  spell itself applies ([SpellContext.elements]): a held Purity beam would otherwise strip
     *  last pulse's Crystallized every pulse, shattering it each time. */
    fun cleanse(ctx: SpellContext, targets: List<LivingEntity>) {
        val own = ctx.elements.flatMap { appliedBy(it) }.toSet()
        for (target in targets) {
            val removable = target.activeEffects.map { it.effect }.filter {
                it !in own && !BuiltInRegistries.MOB_EFFECT.wrapAsHolder(it).`is`(CLEANSE_EXCLUSIONS)
            }
            for (effect in removable) target.removeEffect(effect)
            // Each effect taken drains away down the body, a few at most.
            for (effect in removable.take(MAX_DRAINS)) SpellVfx.cleanseDrain(ctx.level, target, effect.color)
        }
    }

    /** The status effects [element] puts on its targets. */
    private fun appliedBy(element: Element): List<MobEffect> = when (element) {
        Element.COLD -> listOf(EKEffects.FROZEN.get())
        Element.GROWTH -> listOf(EKEffects.ENTANGLED.get())
        Element.PURITY -> listOf(EKEffects.CRYSTALLIZED.get())
        Element.GRAVITY -> listOf(EKEffects.WEIGHTED.get())
        Element.CHAOS -> listOf(EKEffects.DISCORD.get())
        Element.BLOOD -> listOf(
            EKEffects.BLEEDING.get(), EKEffects.ENTHRALLED.get(), EKEffects.BLOODBOUND.get(),
            EKEffects.BLOOD_SURGE.get(), MobEffects.REGENERATION,
        )
        Element.POISON -> listOf(EKEffects.ROT.get(), MobEffects.HUNGER, MobEffects.WEAKNESS)
        Element.VOID -> listOf(EKEffects.HOLLOWED.get(), EKEffects.ABSENT.get())
        Element.HEAT, Element.SUNDERING, Element.SUNFIRE -> emptyList()
    }

    fun purity(ctx: SpellContext, delivery: Delivery, magnitude: Float) {
        val level = ctx.level
        // Reveal: Invisibility breaks, and concealed ships the spell reaches stutter.
        for (target in delivery.living(ctx)) target.removeEffect(MobEffects.INVISIBILITY)
        reveal(ctx, delivery, magnitude)
        // Restore: copper loses oxidation and wax; mossy and cracked blocks come clean.
        for (pos in delivery.blocks(level)) {
            val state = level.getBlockState(pos)
            val restored = restored(state) ?: continue
            ctx.setBlock(pos, restored)
        }
    }

    /** Reveal on a Staff of Concealment cloak: the ship flickers in and out of sight for
     *  3 + magnitude s instead of staying hidden. */
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

    // -----------------------------------------------------------------------
    // Gravity: Tether, Ground, Weighted
    // -----------------------------------------------------------------------

    private const val GROUND_PULL = 1.0
    /** VS2's TNT: rays toward every block within 4 (9 by 9 by 9, less the middle). */
    private const val TNT_RAYS = 728.0
    /** The most one cast's tether changes a ship's speed, in m/s. */
    private const val TETHER_MAX_SPEED = 3.0
    /** VS2's physics ticks a second: a queued force lasts one. */
    private const val PHYSICS_TPS = 60.0

    fun gravity(ctx: SpellContext, delivery: Delivery, magnitude: Float) {
        val weighted = EKEffects.WEIGHTED.get()
        for (target in delivery.living(ctx)) {
            // Ground: Elytra flight and Levitation end; phantoms and ghasts are dragged down.
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

    /**
     * Tether: ships in the sphere are pulled toward its centre, never the caster's own, about as
     * hard as a TNT blast pushes them in VS2. VS2 sends a ray from a blast toward every block
     * within its radius, each that strikes a ship pushing it by the server's explosion blast
     * force, weakening to nothing at twice the radius. Here there are no rays: each ship takes,
     * at its middle, the push of as many of TNT's rays as would strike it (the share of all
     * directions its bounds fill, seen from the centre), turned inward, weakening across the
     * sphere's own radius. A bigger sphere reaches further but pulls no harder; and no cast
     * changes a ship's speed by more than [TETHER_MAX_SPEED], so a light one is never flung
     * through the ground.
     */
    private fun tether(ctx: SpellContext, sphere: Delivery.Sphere, @Suppress("UNUSED_PARAMETER") magnitude: Float) {
        val level = ctx.level
        val gtpa = ValkyrienSkiesMod.getOrCreateGTPA(level.dimensionId)
        // The ship the caster is on now: VS2 keeps the last one stood on for a while after.
        val dragging = (ctx.caster as? IEntityDraggingInformationProvider)?.draggingInformation
        val own = dragging?.takeIf { it.isEntityBeingDraggedByAShip() }?.lastShipStoodOn
        val excluded = own?.let { setOf(it) + gtpa.getAllConnectedShips(it) } ?: emptySet()
        // In the world, even where it landed on a ship (a Bolt striking one).
        val c = level.toWorldCoordinates(sphere.center)
        val radius = sphere.radius
        // TNT's rays in VS2: 728 of them, each the server's blast force.
        val blast = VSGameConfig.SERVER.explosionBlastForce * TNT_RAYS
        for (ship in level.shipObjectWorld.loadedShips) {
            val srv = ship as? LoadedServerShip ?: continue
            if (srv.id in excluded || srv.chunkClaimDimension != level.dimensionId) continue
            val box = srv.worldAABB
            // Its nearest point: the rays reach only as far as the radius.
            val nx = c.x.coerceIn(box.minX(), box.maxX())
            val ny = c.y.coerceIn(box.minY(), box.maxY())
            val nz = c.z.coerceIn(box.minZ(), box.maxZ())
            val near = Vec3(nx, ny, nz).distanceTo(c)
            if (near > radius) continue
            val middle = Vec3((box.minX() + box.maxX()) * 0.5, (box.minY() + box.maxY()) * 0.5, (box.minZ() + box.maxZ()) * 0.5)
            val toward = c.subtract(middle)
            if (toward.lengthSqr() < 1.0e-6) continue
            val dir = toward.normalize()
            // The share of the blast's rays it would catch: its bounds as seen from the centre,
            // never more than the half of all directions on its side.
            val sx = box.maxX() - box.minX(); val sy = box.maxY() - box.minY(); val sz = box.maxZ() - box.minZ()
            val facing = abs(dir.x) * sy * sz + abs(dir.y) * sx * sz + abs(dir.z) * sx * sy
            val share = (facing / near.coerceAtLeast(1.0).pow(2) / (4 * PI)).coerceAtMost(0.5)
            val falloff = 1 - near / (radius * 2)
            // Never more than [TETHER_MAX_SPEED] of change for it in the one physics tick a
            // queued force lasts.
            val mostForMass = srv.inertiaData.mass * TETHER_MAX_SPEED * PHYSICS_TPS
            val mag = minOf(blast * share * falloff, mostForMass)
            if (!mag.isFinite() || mag <= 0) continue
            gtpa.applyInvariantForce(srv.id, Vector3d(dir.x * mag, dir.y * mag, dir.z * mag))
        }
    }

    // -----------------------------------------------------------------------
    // Chaos: Discord, Shuffle, Scatter
    // -----------------------------------------------------------------------

    /** Effects Shuffle may move; anything else stays put, which keeps other mods' effects safe. */
    val SHUFFLE_WHITELIST: TagKey<MobEffect> = TagKey.create(Registries.MOB_EFFECT, EnderkinesisMod.id("shuffle_whitelist"))

    /**
     * One effect a use: Discord along a Forward cast (the mind); the Area cast's is the position
     * cycle ([ElementEffects]); a rune Scatters everyone who trips it; an imbued blow Shuffles
     * between its wielder and what it strikes.
     */
    fun chaos(ctx: SpellContext, delivery: Delivery, magnitude: Float) {
        val targets = delivery.living(ctx)
        when (ctx.use) {
            SpellUse.FORWARD -> {
                val discord = EKEffects.DISCORD.get()
                for (target in targets) target.addEffect(MobEffectInstance(discord, SpellCurves.ticks(3f, 1f, magnitude)), ctx.caster)
            }
            SpellUse.AREA -> {}
            SpellUse.RUNE -> if (ctx.once()) for (target in targets) scatter(ctx, target, magnitude)
            SpellUse.IMBUE -> if (ctx.once()) shuffle(ctx, listOfNotNull(ctx.caster) + targets)
        }
    }

    /** Shuffle: whitelisted effects are pooled from every target and dealt back out at random. */
    private fun shuffle(ctx: SpellContext, targets: List<LivingEntity>) {
        if (targets.size < 2) return
        val pool = ArrayList<Pair<LivingEntity, MobEffectInstance>>()
        for (target in targets) {
            val movable = target.activeEffects.filter { BuiltInRegistries.MOB_EFFECT.wrapAsHolder(it.effect).`is`(SHUFFLE_WHITELIST) }
            for (instance in movable) {
                pool += target to MobEffectInstance(instance)
                target.removeEffect(instance.effect)
            }
        }
        for ((from, instance) in pool) {
            val to = targets[ctx.level.random.nextInt(targets.size)]
            to.addEffect(instance, ctx.caster)
            // What changed hands is seen slung across.
            if (to !== from) SpellVfx.shuffleSling(ctx.level, from, to, instance.effect.color)
        }
    }

    private const val SCATTER_TRIES = 16

    /** Scatter: teleports the target to a random safe spot up to 5 + magnitude away, never
     *  into a ship's yard. */
    private fun scatter(ctx: SpellContext, target: LivingEntity, magnitude: Float) {
        if (!mayTeleport(ctx.level, target)) return
        val range = 5.0 + magnitude
        val random = ctx.level.random
        repeat(SCATTER_TRIES) {
            val x = target.x + (random.nextDouble() * 2 - 1) * range
            val y = target.y + (random.nextDouble() * 2 - 1) * range * 0.5
            val z = target.z + (random.nextDouble() * 2 - 1) * range
            if (ctx.level.isBlockInShipyard(BlockPos.containing(x, y, z))) return@repeat
            if (target.randomTeleport(x, y, z, true)) return
        }
    }

    /** Whether spells may teleport [entity]: not where the Staff of Scales is refused (another
     *  mod owns ships there), not a boss, and not from inside a ship's yard. */
    fun mayTeleport(level: ServerLevel, entity: LivingEntity): Boolean =
        !ScalesManager.isDimensionBlacklisted(level) && !SpellContext.isBoss(entity) && !level.isBlockInShipyard(entity.blockPosition()) && !anchored(entity)

    /** Whether [entity] is held fast by Gravity's self-cast: spells neither pull nor throw it. */
    fun anchored(entity: Entity): Boolean = entity is LivingEntity && entity.hasEffect(EKEffects.ANCHORED.get())

    // -----------------------------------------------------------------------
    // Blood: Enthralled, Bleeding, Bloodbound (on the drained)
    // -----------------------------------------------------------------------

    private const val ENTHRALL_HEALTH = 0.4f

    /**
     * Each drained target meets one fate. An imbued blow binds what it strikes (Bloodbound,
     * 4 s at magnitude 1 to 10 s at 7: one chosen target, so longer than a spray's touch ever
     * was). Otherwise a mob drained to [ENTHRALL_HEALTH] or below becomes a thrall and nothing
     * else lands on it. Whatever didn't turn bleeds.
     */
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

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Every [block] in [box], skipping chunk sections that hold none. */
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
