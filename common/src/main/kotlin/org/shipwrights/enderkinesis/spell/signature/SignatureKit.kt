package org.shipwrights.enderkinesis.spell.signature

import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.damagesource.DamageType
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.shipwrights.enderkinesis.entity.SignatureEntity
import org.shipwrights.enderkinesis.item.BeamTrace
import org.shipwrights.enderkinesis.item.SunderingBeamTrace
import org.shipwrights.enderkinesis.spell.BeamManager
import org.shipwrights.enderkinesis.spell.Delivery
import org.shipwrights.enderkinesis.spell.SpellCasting
import org.shipwrights.enderkinesis.physics.LauncherRecoilAttachment
import org.shipwrights.enderkinesis.spell.NamedEffects
import org.shipwrights.enderkinesis.spell.SpellContext
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld
import java.util.UUID

object SignatureKit {
    fun creatures(e: SignatureEntity, centre: Vec3, r: Double): List<LivingEntity> =
        creatures(e.level() as ServerLevel, centre, r, e.caster)

    fun creatures(level: ServerLevel, centre: Vec3, r: Double, except: Entity?): List<LivingEntity> =
        level.getEntitiesOfClass(LivingEntity::class.java, AABB(centre, centre).inflate(r + 2.0)) {
            it.isAlive && !it.isSpectator && it !== except && it !is ArmorStand && middle(it).distanceTo(centre) <= r
        }

    fun creaturesAlong(e: SignatureEntity, a: Vec3, b: Vec3, halfWidth: Double): List<LivingEntity> =
        (e.level() as ServerLevel).getEntitiesOfClass(LivingEntity::class.java, AABB(a, b).inflate(halfWidth + 1.0)) {
            if (!it.isAlive || it.isSpectator || it === e.caster || it is ArmorStand) return@getEntitiesOfClass false
            val box = it.boundingBox.inflate(halfWidth)
            box.contains(a) || box.clip(a, b).isPresent
        }

    fun middle(entity: Entity): Vec3 = entity.position().add(0.0, entity.bbHeight * 0.5, 0.0)

    fun hurt(e: SignatureEntity, target: Entity, type: ResourceKey<DamageType>, amount: Float): Boolean {
        val ctx = e.ctx ?: return false
        return target.hurt(ctx.damage(type), amount)
    }

    /** For damage that ticks faster than i-frames allow. */
    fun hurtThrough(e: SignatureEntity, target: Entity, type: ResourceKey<DamageType>, amount: Float): Boolean {
        val living = target as? LivingEntity ?: return hurt(e, target, type, amount)
        val frames = living.invulnerableTime
        living.invulnerableTime = 0
        val hit = hurt(e, living, type, amount)
        living.invulnerableTime = frames
        return hit
    }

    fun movable(entity: Entity): Boolean = !SpellContext.isBoss(entity) && !NamedEffects.anchored(entity)

    fun push(entity: Entity, v: Vec3) {
        if (!movable(entity)) return
        entity.deltaMovement = entity.deltaMovement.add(v)
        entity.hurtMarked = true
    }

    fun pull(entity: Entity, point: Vec3, strength: Double) {
        val to = point.subtract(middle(entity))
        val d = to.length()
        if (d < 1.0e-3) return
        push(entity, to.scale(strength / d))
    }

    /** Searches [depth] + 1 blocks down from [fromY]; null when no block has collision. */
    fun ground(level: ServerLevel, x: Double, z: Double, fromY: Double, depth: Int = 8): Double? {
        val pos = BlockPos.MutableBlockPos(Math.floor(x).toInt(), Math.floor(fromY).toInt(), Math.floor(z).toInt())
        repeat(depth + 1) {
            val state = level.getBlockState(pos)
            if (!state.getCollisionShape(level, pos).isEmpty) return pos.y + state.getCollisionShape(level, pos).max(net.minecraft.core.Direction.Axis.Y)
            pos.move(0, -1, 0)
        }
        return null
    }

    fun groundNear(level: ServerLevel, p: Vec3, up: Int = 3, depth: Int = 8): Double? = ground(level, p.x, p.z, p.y + up, depth + up)

    fun sound(level: ServerLevel, at: Vec3, sound: SoundEvent, volume: Float = 1f, pitch: Float = 1f) {
        level.playSound(null, at.x, at.y, at.z, sound, SoundSource.PLAYERS, volume, pitch)
    }

    fun particles(level: ServerLevel, type: ParticleOptions, at: Vec3, count: Int, spread: Double, speed: Double = 0.0) {
        level.sendParticles(type, at.x, at.y, at.z, count, spread, spread, spread, speed)
    }

    /** Obeys the caster's build permissions. The cast origin is not where a signature acts, so the change has no direction. */
    fun setBlock(e: SignatureEntity, pos: BlockPos, state: BlockState): Boolean = e.ctx?.setBlock(pos, state, directed = false) ?: false
}

/** Push the touched blocks at their points, never the ship as a whole, so the effect scales with mass. */
object SignatureShips {
    /** VS2 mass of an ordinary block, in kg. */
    const val BLOCK_KG = 1000.0

    /** [world] is the block centre in world coordinates; [pos] is in the shipyard. */
    class ShipBlock(val ship: LoadedServerShip, val world: Vec3, val pos: BlockPos, val state: BlockState)

    /** [at] is a world point. [impulse] is in block-masses times blocks per tick: 1 moves a lone block at 1 block per tick. */
    fun push(ship: LoadedServerShip, at: Vec3, impulse: Vec3) = push(ship, at.x, at.y, at.z, impulse.x, impulse.y, impulse.z)

    fun push(ship: LoadedServerShip, ax: Double, ay: Double, az: Double, ix: Double, iy: Double, iz: Double) {
        val ns = BLOCK_KG * 20.0
        ship.getOrPutAttachment(LauncherRecoilAttachment::class.java) { LauncherRecoilAttachment() }
            .enqueue(Vector3d(ax, ay, az), Vector3d(ix * ns, iy * ns, iz * ns))
    }

    /** Do not use the VS intersecting-ships query: it returns saved ship data, which you cannot push. */
    fun ships(level: ServerLevel, centre: Vec3, r: Double): List<LoadedServerShip> {
        val dimension = level.dimensionId
        return level.shipObjectWorld.loadedShips.filter { ship ->
            val b = ship.worldAABB
            ship.chunkClaimDimension == dimension &&
                b.maxX() >= centre.x - r && b.minX() <= centre.x + r &&
                b.maxY() >= centre.y - r && b.minY() <= centre.y + r &&
                b.maxZ() >= centre.z - r && b.minZ() <= centre.z + r
        }
    }

    fun blocksNear(level: ServerLevel, centre: Vec3, r: Double, only: LoadedServerShip? = null): List<ShipBlock> {
        val out = ArrayList<ShipBlock>()
        forEachBlockNear(level, centre, r, only) { ship, world, pos, state ->
            out += ShipBlock(ship, Vec3(world.x, world.y, world.z), pos.immutable(), state)
        }
        return out
    }

    /** [world] and [pos] are scratch objects: copy them to keep them after [visit] returns. */
    inline fun forEachBlockNear(
        level: ServerLevel, centre: Vec3, r: Double, only: LoadedServerShip? = null,
        visit: (ship: LoadedServerShip, world: Vector3d, pos: BlockPos.MutableBlockPos, state: BlockState) -> Unit,
    ) {
        val local = Vector3d()
        val world = Vector3d()
        val pos = BlockPos.MutableBlockPos()
        val ri = Math.ceil(r).toInt()
        for (ship in if (only != null) listOf(only) else ships(level, centre, r)) {
            ship.transform.worldToShip.transformPosition(centre.x, centre.y, centre.z, local)
            val cx = Math.floor(local.x).toInt(); val cy = Math.floor(local.y).toInt(); val cz = Math.floor(local.z).toInt()
            for (dx in -ri..ri) for (dy in -ri..ri) for (dz in -ri..ri) {
                pos.set(cx + dx, cy + dy, cz + dz)
                val state = level.getBlockState(pos)
                if (state.isAir) continue
                ship.transform.shipToWorld.transformPosition(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, world)
                val ox = world.x - centre.x; val oy = world.y - centre.y; val oz = world.z - centre.z
                if (Math.sqrt(ox * ox + oy * oy + oz * oz) <= r) visit(ship, world, pos, state)
            }
        }
    }

    fun blockAt(level: ServerLevel, at: Vec3): ShipBlock? {
        for (ship in ships(level, at, 0.5)) {
            val local = Vector3d()
            ship.transform.worldToShip.transformPosition(at.x, at.y, at.z, local)
            val pos = BlockPos.containing(local.x, local.y, local.z)
            val state = level.getBlockState(pos)
            if (state.isAir) continue
            val world = Vector3d()
            ship.transform.shipToWorld.transformPosition(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, world)
            return ShipBlock(ship, Vec3(world.x, world.y, world.z), pos, state)
        }
        return null
    }

    fun nearestBlocks(level: ServerLevel, centre: Vec3, r: Double): List<ShipBlock> {
        val out = ArrayList<ShipBlock>()
        for (ship in ships(level, centre, r)) {
            var best: ShipBlock? = null
            var bestD = Double.POSITIVE_INFINITY
            forEachBlockNear(level, centre, r, ship) { _, world, pos, state ->
                val ox = centre.x - world.x; val oy = centre.y - world.y; val oz = centre.z - world.z
                val d = ox * ox + oy * oy + oz * oz
                if (d < bestD) {
                    bestD = d
                    best = ShipBlock(ship, Vec3(world.x, world.y, world.z), pos.immutable(), state)
                }
            }
            best?.let { out += it }
        }
        return out
    }

    fun toWorld(ship: LoadedServerShip, p: Vec3): Vec3 {
        val w = Vector3d()
        ship.transform.shipToWorld.transformPosition(p.x, p.y, p.z, w)
        return Vec3(w.x, w.y, w.z)
    }
}

/** Draw these beams with [org.shipwrights.enderkinesis.client.signature.SignatureVisuals.beamPath], which traces and cuts the same way. */
object SignatureBeams {
    const val HALF_WIDTH = SunderingBeamTrace.DEFAULT_HALF_WIDTH

    fun trace(e: SignatureEntity, origin: Vec3, dir: Vec3, reach: Double, halfWidth: Double = HALF_WIDTH): BeamTrace {
        val level = e.level() as ServerLevel
        return SunderingBeamTrace.trace(level, e.caster, origin, dir, reach, BeamManager.mirrors(level, e.caster), halfWidth)
    }

    /** Returns the part that still acts. The main beam must use the caster id, because the client cuts by it. */
    fun register(
        e: SignatureEntity, trace: BeamTrace, reach: Double, id: UUID? = null, halfWidth: Double = HALF_WIDTH,
        cancel: () -> Unit = { e.caster?.releaseUsingItem() },
    ): BeamTrace {
        val caster = e.caster
        val beamId = id ?: caster?.uuid ?: e.uuid
        BeamManager.register(BeamManager.Beam(beamId, e.level() as ServerLevel, caster, e.signature.glyphs, 0f, trace, reach, halfWidth, fromStaff = false, cancel))
        return BeamTrace(BeamManager.clip(beamId, trace), if (BeamManager.isCut(beamId)) null else trace.finalBlockPos)
    }

    fun subId(e: SignatureEntity, k: Int): UUID {
        val base = e.caster?.uuid ?: e.uuid
        return UUID(base.mostSignificantBits, base.leastSignificantBits xor (0x5197L * (k + 1)))
    }

    fun lines(trace: BeamTrace): List<Delivery.Line> = SpellCasting.beamLines(trace)

    fun end(trace: BeamTrace, origin: Vec3): Vec3 = trace.segments.lastOrNull()?.end ?: origin
}
