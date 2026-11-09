package org.shipwrights.enderkinesis.dimension

import dev.architectury.event.EventResult
import dev.architectury.event.events.common.EntityEvent
import dev.architectury.event.events.common.TickEvent
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.TagKey
import net.minecraft.util.Mth
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.dimension.SselithChatTeleport.ReturnPoint
import org.shipwrights.enderkinesis.entity.WeheloStoneCreature
import org.shipwrights.enderkinesis.entity.WeheloTearEntity
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.shipwrights.enderkinesis.registry.EKEntities
import org.shipwrights.enderkinesis.registry.EKItems
import org.shipwrights.enderkinesis.registry.EKSounds
import org.shipwrights.enderkinesis.spell.SpellSounds
import org.joml.Vector3d
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.mod.common.shipObjectWorld
import java.util.UUID

/** One 64-cube pocket for each player, on a grid in one dimension, sealed by an unchangeable Wogor Wood shell. */
object WeheloPocket {

    val ID: ResourceLocation = EnderkinesisMod.id("wehelo_pocket")
    @JvmField val LEVEL_KEY: ResourceKey<Level> = ResourceKey.create(Registries.DIMENSION, ID)

    const val SIZE = 64
    const val FLOOR_Y = 16
    /** A pocket and its neighbour never share a loaded chunk. */
    private const val SPACING = 256
    private const val COLUMNS = 64
    /** Bounds the guarded area, so the VS2 shipyard far out in this dimension stays usable. */
    private const val ROWS = 4096
    private const val DAMAGE_INTERVAL = 20
    private const val DAMAGE = 2.0f
    private const val CONTAIN_INTERVAL = 20
    private const val TEAR_REACH = 1.6
    private const val EDGE_INSET = 0.5
    const val FOG_END = 32.0f
    /** Deep in the fog, where the fog eyes rest. */
    const val HIDDEN_DISTANCE = FOG_END * 0.85
    /** Ticks a dropped blade lies before a stone creature comes for it. */
    private const val BLADE_CLAIM_DELAY = 60
    private const val FOG_SPOT_CANDIDATES = 32
    private const val FOG_SPOT_INSET = 1.5
    private const val UNREACHABLE_TAG = "enderkinesis.wehelo_unreachable"
    private val CREATURE_TARGETS: TagKey<Item> =
        TagKey.create(Registries.ITEM, EnderkinesisMod.id("wehelo_stone_creature_targets"))

    private val WORLD_ORIGIN = ReturnPoint(Level.OVERWORLD, 0.0, 0.0, 0.0, 0f, 0f)

    private val LOG = com.mojang.logging.LogUtils.getLogger()
    private val HOUSE: ResourceLocation = EnderkinesisMod.id("house_in_the_middle")

    private val shellBypass: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

    fun init() {
        EntityEvent.LIVING_DEATH.register(::onLivingDeath)
        TickEvent.PLAYER_POST.register(::tickPlayer)
        TickEvent.SERVER_LEVEL_POST.register(::tickLevel)
    }

    private fun origin(slot: Int): BlockPos = BlockPos((slot % COLUMNS) * SPACING, FLOOR_Y, (slot / COLUMNS) * SPACING)

    fun isInterior(x: Int, y: Int, z: Int): Boolean =
        y >= FLOOR_Y && y < FLOOR_Y + SIZE && Math.floorMod(x, SPACING) < SIZE && Math.floorMod(z, SPACING) < SIZE

    /** True for every block of the pocket grid outside the pockets. Those blocks never change after the shell is built. */
    fun isGuarded(pos: BlockPos): Boolean {
        if (shellBypass.get()) return false
        if (pos.x < -SPACING || pos.x >= COLUMNS * SPACING || pos.z < -SPACING || pos.z >= ROWS * SPACING) return false
        return !isInterior(pos.x, pos.y, pos.z)
    }

    /** A shell block, or any other grid block outside the pockets, in [level]. */
    @JvmStatic
    fun isShellAt(level: Level, pos: BlockPos): Boolean = level.dimension() == LEVEL_KEY && isGuarded(pos)

    /** The corner of the pocket nearest to x, z. */
    private fun nearestOrigin(x: Double, z: Double): BlockPos {
        val col = Mth.clamp(Math.round((x - SIZE / 2.0) / SPACING).toInt(), 0, COLUMNS - 1)
        val row = Mth.clamp(Math.round((z - SIZE / 2.0) / SPACING).toInt(), 0, ROWS - 1)
        return BlockPos(col * SPACING, FLOOR_Y, row * SPACING)
    }

    /** The nearest point inside a pocket, for a body [height] tall. */
    fun clampInside(x: Double, y: Double, z: Double, height: Double): Vec3 {
        val o = nearestOrigin(x, z)
        val ox = o.x.toDouble(); val oz = o.z.toDouble()
        return Vec3(
            Mth.clamp(x, ox + EDGE_INSET, ox + SIZE - EDGE_INSET),
            Mth.clamp(y, FLOOR_Y.toDouble(), FLOOR_Y + SIZE - height),
            Mth.clamp(z, oz + EDGE_INSET, oz + SIZE - EDGE_INSET),
        )
    }

    /** Opens a tear in front of [player]: into their pocket, or out of the pocket they are in. */
    fun openTear(level: ServerLevel, player: ServerPlayer): WeheloTearEntity? {
        val tear = EKEntities.WEHELO_TEAR.get().create(level) ?: return null
        val flat = Vec3(player.lookAngle.x, 0.0, player.lookAngle.z)
        val ahead = if (flat.lengthSqr() < 1.0e-6) Vec3(0.0, 0.0, 1.0) else flat.normalize()
        val world = player.position().add(ahead.scale(TEAR_REACH))
        val at = if (level.dimension() == LEVEL_KEY) {
            tear.setup(player.uuid, exit = true, returnFor(player))
            world
        } else {
            val back = ReturnPoint.of(player)
            tear.setup(player.uuid, exit = false, back)
            back.ship?.let { shipyardPoint(level, it.id, world) } ?: world
        }
        tear.moveTo(at.x, at.y, at.z, 0f, 0f)
        return tear.takeIf { level.addFreshEntity(it) }
    }

    /**
     * [at] on the ship in SHIPYARD coordinates, so the tear rides the ship (vs_entities/wehelo_tear.json pairs it
     * with the VS2 shipyard handler). The world point when the ship is gone. Called from openTear on the server thread.
     */
    @OptIn(GameTickOnly::class)
    private fun shipyardPoint(level: ServerLevel, shipId: Long, at: Vec3): Vec3 {
        val ship = level.shipObjectWorld.allShips.getById(shipId) ?: return at
        val local = ship.transform.worldToShip.transformPosition(Vector3d(at.x, at.y, at.z))
        return Vec3(local.x, local.y, local.z)
    }

    /** Where an entity arrives in the pocket at [o]; it matches the return point it came from. */
    private fun arrival(o: BlockPos): Vec3 = Vec3(o.x + SIZE / 2 + 0.5, FLOOR_Y.toDouble(), o.z + SIZE / 2 + 0.5)

    private fun slotAt(o: BlockPos): Int = (o.z / SPACING) * COLUMNS + o.x / SPACING

    /** Moves [entity] into [owner]'s pocket; it returns to [back]. */
    internal fun enter(entity: Entity, owner: UUID, back: ReturnPoint): Boolean {
        val server = entity.server ?: return false
        val level = server.getLevel(LEVEL_KEY) ?: return false
        val slot = pocketFor(level, owner) ?: return false
        WeheloPocketData.get(server).setReturn(entity.uuid, back)
        val at = arrival(origin(slot))
        return move(entity, level, at.x, at.y, at.z, entity.yRot, entity.xRot)
    }

    /**
     * Back to the entity's return point, moved by how far it is from where it arrived, facing as it faces now.
     * A return point on a ship follows that ship and turns with it.
     * An entity made in the pocket follows the last entity that left it, or the world origin when none has.
     */
    internal fun leave(entity: Entity): Boolean {
        val server = entity.server ?: return false
        val data = WeheloPocketData.get(server)
        val o = nearestOrigin(entity.x, entity.z)
        val slot = slotAt(o)
        val anchor = data.takeReturn(entity.uuid) ?: data.lastLeft(slot) ?: WORLD_ORIGIN
        data.setLastLeft(slot, anchor)
        val from = arrival(o)
        val level = server.getLevel(anchor.dimension) ?: server.overworld()
        val landing = anchor.landing(level)
        val to = landing.offset(entity.x - from.x, entity.y - from.y, entity.z - from.z)
        return move(entity, level, to.x, to.y, to.z, entity.yRot + landing.yawChange, entity.xRot)
    }

    /** Drops the return point of an entity that is gone for good, so the saved data does not grow. */
    @JvmStatic
    fun forget(entity: Entity) {
        if (entity is Player || entity.level().dimension() != LEVEL_KEY) return
        val server = entity.server ?: return
        WeheloPocketData.get(server).takeReturn(entity.uuid)
    }

    /** To [to] as it is, with no offset; not through a tear. */
    private fun returnExactly(player: ServerPlayer, to: ReturnPoint): Boolean {
        WeheloPocketData.get(player.server).takeReturn(player.uuid)
        val level = player.server.getLevel(to.dimension)
        if (level != null) {
            val landing = to.landing(level)
            return move(player, level, landing.x, landing.y, landing.z, to.yRot + landing.yawChange, to.xRot)
        }
        val spawn = player.server.overworld().sharedSpawnPos
        return move(player, player.server.overworld(), spawn.x + 0.5, spawn.y.toDouble(), spawn.z + 0.5, player.yRot, player.xRot)
    }

    private fun returnFor(player: ServerPlayer): ReturnPoint =
        WeheloPocketData.get(player.server).peekReturn(player.uuid) ?: player.server.overworld().let {
            val spawn = it.sharedSpawnPos
            ReturnPoint(it.dimension(), spawn.x + 0.5, spawn.y.toDouble(), spawn.z + 0.5, player.yRot, player.xRot)
        }

    private fun move(entity: Entity, level: ServerLevel, x: Double, y: Double, z: Double, yRot: Float, xRot: Float): Boolean {
        entity.stopRiding()
        // Set before the move: a non-player move copies the entity from NBT, cooldown included.
        entity.setPortalCooldown()
        if (!entity.teleportTo(level, x, y, z, emptySet(), yRot, xRot)) return false
        SpellSounds.play(level, Vec3(x, y, z), EKSounds.WEHELO_PASS)
        return true
    }

    private fun pocketFor(level: ServerLevel, owner: UUID): Int? {
        val data = WeheloPocketData.get(level.server)
        val slot = data.slotOf(owner) ?: data.allocate(owner, COLUMNS * ROWS)?.also { buildShell(level, it) } ?: return null
        // Pockets made before the house existed get it on their next visit.
        if (!data.isFurnished(slot) && placeHouse(level, slot)) data.markFurnished(slot)
        return slot
    }

    private fun placeHouse(level: ServerLevel, slot: Int): Boolean {
        val template = level.structureManager.get(HOUSE).orElse(null)
        if (template == null) {
            LOG.warn("Missing structure {}; the Wehelo pocket stays empty", HOUSE)
            return false
        }
        val size = template.size
        val corner = origin(slot).offset((SIZE - size.x) / 2, 0, (SIZE - size.z) / 2)
        return template.placeInWorld(level, corner, corner, StructurePlaceSettings(), level.random, Block.UPDATE_CLIENTS)
    }

    private fun buildShell(level: ServerLevel, slot: Int) {
        val o = origin(slot)
        val wall = EKBlocks.WEHELO_POCKET_WALL.get().defaultBlockState()
        val pos = BlockPos.MutableBlockPos()
        shellBypass.set(true)
        try {
            for (x in -1..SIZE) for (y in -1..SIZE) {
                val face = x == -1 || x == SIZE || y == -1 || y == SIZE
                if (face) {
                    for (z in -1..SIZE) level.setBlock(pos.set(o.x + x, o.y + y, o.z + z), wall, Block.UPDATE_CLIENTS)
                } else {
                    level.setBlock(pos.set(o.x + x, o.y + y, o.z - 1), wall, Block.UPDATE_CLIENTS)
                    level.setBlock(pos.set(o.x + x, o.y + y, o.z + SIZE), wall, Block.UPDATE_CLIENTS)
                }
            }
        } finally {
            shellBypass.set(false)
        }
    }

    // Any death in a pocket returns the player alive with their items to where the rift opened.
    private fun onLivingDeath(entity: LivingEntity, source: DamageSource): EventResult {
        if (entity !is ServerPlayer || entity.level().dimension() != LEVEL_KEY) return EventResult.pass()
        entity.health = entity.maxHealth
        entity.clearFire()
        entity.fallDistance = 0.0f
        entity.airSupply = entity.maxAirSupply
        returnExactly(entity, returnFor(entity))
        return EventResult.interruptFalse()
    }

    private fun tickPlayer(player: Player) {
        if (player !is ServerPlayer || player.level().dimension() != LEVEL_KEY) return
        contain(player)
        if (player.isCreative || player.isSpectator || player.tickCount % DAMAGE_INTERVAL != 0) return
        if (player.inventory.hasAnyMatching(::isBlade)) return
        if (player.hurt(EKDamageSources.weheloRift(player.serverLevel()), DAMAGE)) {
            SpellSounds.play(player.serverLevel(), player.position(), EKSounds.WEHELO_UNMAKE)
        }
    }

    private fun tickLevel(level: ServerLevel) {
        if (level.dimension() != LEVEL_KEY || level.gameTime % CONTAIN_INTERVAL != 0L) return
        val strays = ArrayList<Entity>()
        val lost = ArrayList<ItemEntity>()
        val claimed = HashSet<UUID>()
        for (entity in level.allEntities) {
            // A stone creature phases out through the walls and fades; containing it would turn it back.
            if (entity !is Player && entity !is WeheloStoneCreature && !inside(entity)) strays += entity
            if (entity is ItemEntity && isLostBlade(entity)) lost += entity
            if (entity is WeheloStoneCreature) entity.target?.let(claimed::add)
        }
        for (entity in strays) contain(entity)
        for (item in lost) if (item.uuid !in claimed) summonStoneCreature(level, item)
    }

    fun isBlade(stack: ItemStack): Boolean = stack.`is`(EKItems.WEHELO_BLADE.get())

    fun isCreatureTarget(stack: ItemStack): Boolean = stack.`is`(CREATURE_TARGETS)

    internal fun markUnreachable(item: ItemEntity) {
        item.addTag(UNREACHABLE_TAG)
    }

    private fun isLostBlade(item: ItemEntity): Boolean =
        item.isAlive && isCreatureTarget(item.item) && item.age >= BLADE_CLAIM_DELAY && UNREACHABLE_TAG !in item.tags

    private fun summonStoneCreature(level: ServerLevel, item: ItemEntity) {
        val type = EKEntities.WEHELO_STONE_CREATURE.get()
        val at = fogSpot(level, item.position(), type.dimensions) ?: return
        val creature = type.create(level) ?: return
        creature.moveTo(at.x, at.y, at.z, level.random.nextFloat() * 360f, 0f)
        creature.fetch(item)
        level.addFreshEntity(creature)
    }

    /**
     * The free floor spot on the edge of the pocket that holds [near], farthest from the players in that pocket.
     * Without players there, the spot farthest from [near]. Null when no edge spot is free.
     */
    fun fogSpot(level: ServerLevel, near: Vec3, body: EntityDimensions): Vec3? {
        val o = nearestOrigin(near.x, near.z)
        val span = SIZE - 2 * FOG_SPOT_INSET
        var best: Vec3? = null
        var bestDistance = -1.0
        for (i in 0 until FOG_SPOT_CANDIDATES) {
            // Walk the edge square: four sides, each a quarter of the candidates.
            val t = (i % (FOG_SPOT_CANDIDATES / 4)) * span * 4 / FOG_SPOT_CANDIDATES
            val (dx, dz) = when (i * 4 / FOG_SPOT_CANDIDATES) {
                0 -> t to 0.0
                1 -> span to t
                2 -> span - t to span
                else -> 0.0 to span - t
            }
            val spot = Vec3(o.x + FOG_SPOT_INSET + dx, FLOOR_Y.toDouble(), o.z + FOG_SPOT_INSET + dz)
            if (!level.noCollision(body.makeBoundingBox(spot))) continue
            val distance = nearestPlayerDistance(level, spot, o) ?: spot.distanceTo(near)
            if (distance > bestDistance) {
                best = spot
                bestDistance = distance
            }
        }
        return best
    }

    private fun nearestPlayerDistance(level: ServerLevel, spot: Vec3, pocket: BlockPos): Double? {
        var nearest: Double? = null
        for (player in level.players()) {
            if (player.isSpectator || nearestOrigin(player.x, player.z) != pocket) continue
            val d = player.position().distanceTo(spot)
            if (nearest == null || d < nearest) nearest = d
        }
        return nearest
    }

    internal fun inside(entity: Entity): Boolean {
        val x = Mth.floor(entity.x); val z = Mth.floor(entity.z)
        return isInterior(x, Mth.floor(entity.y), z) && isInterior(x, Mth.floor(entity.y + entity.bbHeight) - 1, z)
    }

    private fun contain(entity: Entity) {
        if (inside(entity)) return
        val to = clampInside(entity.x, entity.y, entity.z, entity.bbHeight.toDouble())
        entity.teleportTo(to.x, to.y, to.z)
        entity.deltaMovement = Vec3.ZERO
    }
}
