package org.shipwrights.enderkinesis.spell

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.tags.TagKey
import net.minecraft.world.SimpleContainer
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
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
                // Thaw: ice melts to water; snow layers and powder snow vanish.
                state.`is`(Blocks.ICE) || state.`is`(Blocks.FROSTED_ICE) -> ctx.setBlock(pos, Blocks.WATER.defaultBlockState())
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

    fun growth(ctx: SpellContext, delivery: Delivery, magnitude: Float) {
        val level = ctx.level
        val entangled = EKEffects.ENTANGLED.get()
        for (target in delivery.living(ctx)) {
            // Quicken: babies grow up; grown animals fall in love.
            if (target is Animal) {
                if (target.isBaby) target.setAge(0)
                else if (target.canFallInLove()) target.setInLove(ctx.caster as? Player)
            }
            target.addEffect(MobEffectInstance(entangled, SpellCurves.ticks(1.5f, 0.5f, magnitude)), ctx.caster)
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

    /** Cleanse: strips every status effect not in [CLEANSE_EXCLUSIONS]. Runs before the rest
     *  of the spell, so a scroll never strips what it applies. */
    fun cleanse(ctx: SpellContext, delivery: Delivery) {
        for (target in delivery.living(ctx)) {
            val removable = target.activeEffects.map { it.effect }.filter {
                !BuiltInRegistries.MOB_EFFECT.wrapAsHolder(it).`is`(CLEANSE_EXCLUSIONS)
            }
            for (effect in removable) target.removeEffect(effect)
        }
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

    private const val TETHER_FORCE_PER_MASS = 8.0
    private const val TETHER_MIN_FORCE = 1000.0
    private const val GROUND_PULL = 1.0

    fun gravity(ctx: SpellContext, delivery: Delivery, magnitude: Float) {
        val weighted = EKEffects.WEIGHTED.get()
        for (target in delivery.living(ctx)) {
            // Ground: Elytra flight and Levitation end; phantoms and ghasts are dragged down.
            if (target is Player && target.isFallFlying) target.stopFallFlying()
            target.removeEffect(MobEffects.LEVITATION)
            if (target is Phantom || target is Ghast) {
                target.deltaMovement = target.deltaMovement.add(0.0, -GROUND_PULL * magnitude, 0.0)
                target.hurtMarked = true
            }
            target.addEffect(MobEffectInstance(weighted, SpellCurves.ticks(4f, 2f, magnitude)), ctx.caster)
        }
        if (delivery is Delivery.Sphere) tether(ctx, delivery, magnitude)
    }

    /** Tether: ships in the sphere are pulled toward its centre, never the caster's own. */
    private fun tether(ctx: SpellContext, sphere: Delivery.Sphere, magnitude: Float) {
        val level = ctx.level
        val gtpa = ValkyrienSkiesMod.getOrCreateGTPA(level.dimensionId)
        val own = (ctx.caster as? IEntityDraggingInformationProvider)?.draggingInformation?.lastShipStoodOn
        val excluded = own?.let { setOf(it) + gtpa.getAllConnectedShips(it) } ?: emptySet()
        val c = sphere.center
        val r = sphere.radius
        for (ship in level.shipObjectWorld.loadedShips) {
            val srv = ship as? LoadedServerShip ?: continue
            if (srv.id in excluded || srv.chunkClaimDimension != level.dimensionId) continue
            val box = srv.worldAABB
            val nx = c.x.coerceIn(box.minX(), box.maxX())
            val ny = c.y.coerceIn(box.minY(), box.maxY())
            val nz = c.z.coerceIn(box.minZ(), box.maxZ())
            if (Vec3(nx, ny, nz).distanceToSqr(c) > r * r) continue
            val centre = Vec3((box.minX() + box.maxX()) * 0.5, (box.minY() + box.maxY()) * 0.5, (box.minZ() + box.maxZ()) * 0.5)
            val toward = c.subtract(centre)
            if (toward.lengthSqr() < 1.0e-6) continue
            val dir = toward.normalize()
            val mag = (TETHER_FORCE_PER_MASS * srv.inertiaData.mass * magnitude).coerceAtLeast(TETHER_MIN_FORCE)
            gtpa.applyInvariantForce(srv.id, Vector3d(dir.x * mag, dir.y * mag, dir.z * mag))
        }
    }

    // -----------------------------------------------------------------------
    // Chaos: Discord, Shuffle, Scatter
    // -----------------------------------------------------------------------

    /** Effects Shuffle may move; anything else stays put, which keeps other mods' effects safe. */
    val SHUFFLE_WHITELIST: TagKey<MobEffect> = TagKey.create(Registries.MOB_EFFECT, EnderkinesisMod.id("shuffle_whitelist"))

    fun chaos(ctx: SpellContext, delivery: Delivery, magnitude: Float) {
        // None of these touch the caster, even where Chaos's own cycle does.
        val targets = delivery.living(ctx)
        val discord = EKEffects.DISCORD.get()
        for (target in targets) target.addEffect(MobEffectInstance(discord, SpellCurves.ticks(3f, 1f, magnitude)), ctx.caster)
        shuffle(ctx, targets)
        scatterTarget(delivery, targets, ctx)?.let { scatter(ctx, it, magnitude) }
    }

    /** Shuffle: whitelisted effects are pooled from every target and dealt back out at random. */
    private fun shuffle(ctx: SpellContext, targets: List<LivingEntity>) {
        if (targets.size < 2) return
        val pool = ArrayList<MobEffectInstance>()
        for (target in targets) {
            val movable = target.activeEffects.filter { BuiltInRegistries.MOB_EFFECT.wrapAsHolder(it.effect).`is`(SHUFFLE_WHITELIST) }
            for (instance in movable) {
                pool += MobEffectInstance(instance)
                target.removeEffect(instance.effect)
            }
        }
        for (instance in pool) targets[ctx.level.random.nextInt(targets.size)].addEffect(instance, ctx.caster)
    }

    /** Scatter only ever moves a single target: the one an entity hit strikes, the first on a
     *  beam, a lone chain target, or one entity of an Area burst. Never on a Bolt. */
    private fun scatterTarget(delivery: Delivery, targets: List<LivingEntity>, ctx: SpellContext): LivingEntity? = when (delivery) {
        is Delivery.SingleEntity -> targets.firstOrNull()
        is Delivery.Line -> targets.minByOrNull { it.distanceToSqr(delivery.from) }
        is Delivery.Group -> targets.singleOrNull()
        is Delivery.Sphere -> if (delivery.area && targets.isNotEmpty()) targets[ctx.level.random.nextInt(targets.size)] else null
        else -> null
    }

    private const val SCATTER_TRIES = 16

    /** Scatter: teleports the target to a random safe spot up to 5 + magnitude away. */
    private fun scatter(ctx: SpellContext, target: LivingEntity, magnitude: Float) {
        val range = 5.0 + magnitude
        val random = ctx.level.random
        repeat(SCATTER_TRIES) {
            val x = target.x + (random.nextDouble() * 2 - 1) * range
            val y = target.y + (random.nextDouble() * 2 - 1) * range * 0.5
            val z = target.z + (random.nextDouble() * 2 - 1) * range
            if (target.randomTeleport(x, y, z, true)) return
        }
    }

    // -----------------------------------------------------------------------
    // Blood: Enthralled, Bleeding, Bloodbound (on the drained)
    // -----------------------------------------------------------------------

    private const val ENTHRALL_HEALTH = 0.4f
    private const val BLOODBOUND_MAX_TICKS = 100

    fun blood(ctx: SpellContext, drained: List<LivingEntity>, master: LivingEntity?, magnitude: Float) {
        val bleeding = EKEffects.BLEEDING.get()
        for (target in drained) {
            if (!target.isAlive) continue
            target.addEffect(MobEffectInstance(bleeding, SpellCurves.ticks(4f, 2f, magnitude)), ctx.caster)
            master ?: continue
            if (target is Mob && target.health <= target.maxHealth * ENTHRALL_HEALTH) {
                BloodPacts.enthrall(ctx.level, target, master, magnitude, SpellCurves.ticks(15f, 5f, magnitude))
            }
            BloodPacts.bind(target, master, SpellCurves.ticks(1.5f, 0.5f, magnitude).coerceAtMost(BLOODBOUND_MAX_TICKS))
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Whether a block within [spacing] of [pos] is one of [block]. */
    fun near(ctx: SpellContext, pos: BlockPos, block: Block, spacing: Int): Boolean {
        val r2 = spacing * spacing
        for (other in BlockPos.betweenClosed(pos.offset(-spacing, -spacing, -spacing), pos.offset(spacing, spacing, spacing))) {
            if (other.distSqr(pos) < r2 && ctx.level.getBlockState(other).`is`(block)) return true
        }
        return false
    }
}
