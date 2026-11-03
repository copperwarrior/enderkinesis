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
import net.minecraft.util.Mth
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.dimension.SselithChatTeleport.ReturnPoint
import org.shipwrights.enderkinesis.entity.WeheloTearEntity
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.shipwrights.enderkinesis.registry.EKEntities
import org.shipwrights.enderkinesis.registry.EKItems
import org.shipwrights.enderkinesis.registry.EKSounds
import org.shipwrights.enderkinesis.spell.SpellSounds
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
    @JvmStatic
    fun isGuarded(pos: BlockPos): Boolean {
        if (shellBypass.get()) return false
        if (pos.x < -SPACING || pos.x >= COLUMNS * SPACING || pos.z < -SPACING || pos.z >= ROWS * SPACING) return false
        return !isInterior(pos.x, pos.y, pos.z)
    }

    /** A shell block, or any other grid block outside the pockets, in [level]. */
    @JvmStatic
    fun isShellAt(level: Level, pos: BlockPos): Boolean = level.dimension() == LEVEL_KEY && isGuarded(pos)

    /** The nearest point inside a pocket, for a body [height] tall. */
    fun clampInside(x: Double, y: Double, z: Double, height: Double): Vec3 {
        val col = Mth.clamp(Math.round((x - SIZE / 2.0) / SPACING).toInt(), 0, COLUMNS - 1)
        val row = Mth.clamp(Math.round((z - SIZE / 2.0) / SPACING).toInt(), 0, ROWS - 1)
        val ox = col * SPACING.toDouble(); val oz = row * SPACING.toDouble()
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
        val at = player.position().add(ahead.scale(TEAR_REACH))
        if (level.dimension() == LEVEL_KEY) {
            tear.setup(player.uuid, exit = true, returnFor(player))
        } else {
            tear.setup(player.uuid, exit = false, ReturnPoint(level.dimension(), player.x, player.y, player.z, player.yRot, player.xRot))
        }
        tear.moveTo(at.x, at.y, at.z, 0f, 0f)
        return tear.takeIf { level.addFreshEntity(it) }
    }

    /** Moves [entity] into [owner]'s pocket. A player who enters returns to [back]. */
    internal fun enter(entity: Entity, owner: UUID, back: ReturnPoint): Boolean {
        val server = entity.server ?: return false
        val level = server.getLevel(LEVEL_KEY) ?: return false
        val slot = pocketFor(level, owner) ?: return false
        if (entity is ServerPlayer) WeheloPocketData.get(server).setReturn(entity.uuid, back)
        val o = origin(slot)
        return move(entity, level, o.x + SIZE / 2 + 0.5, FLOOR_Y.toDouble(), o.z + SIZE / 2 + 0.5, entity.yRot, entity.xRot)
    }

    internal fun leave(entity: Entity, to: ReturnPoint): Boolean {
        val server = entity.server ?: return false
        if (entity is ServerPlayer) WeheloPocketData.get(server).takeReturn(entity.uuid)
        val level = server.getLevel(to.dimension)
        if (level != null) return move(entity, level, to.x, to.y, to.z, to.yRot, to.xRot)
        val spawn = server.overworld().sharedSpawnPos
        return move(entity, server.overworld(), spawn.x + 0.5, spawn.y.toDouble(), spawn.z + 0.5, entity.yRot, entity.xRot)
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
        leave(entity, returnFor(entity))
        return EventResult.interruptFalse()
    }

    private fun tickPlayer(player: Player) {
        if (player !is ServerPlayer || player.level().dimension() != LEVEL_KEY) return
        contain(player)
        if (player.isCreative || player.isSpectator || player.tickCount % DAMAGE_INTERVAL != 0) return
        if (player.inventory.hasAnyMatching { it.`is`(EKItems.WEHELO_BLADE.get()) }) return
        if (player.hurt(EKDamageSources.weheloRift(player.serverLevel()), DAMAGE)) {
            SpellSounds.play(player.serverLevel(), player.position(), EKSounds.WEHELO_UNMAKE)
        }
    }

    private fun tickLevel(level: ServerLevel) {
        if (level.dimension() != LEVEL_KEY || level.gameTime % CONTAIN_INTERVAL != 0L) return
        val strays = ArrayList<Entity>()
        for (entity in level.allEntities) if (entity !is Player && !inside(entity)) strays += entity
        for (entity in strays) contain(entity)
    }

    private fun inside(entity: Entity): Boolean {
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
