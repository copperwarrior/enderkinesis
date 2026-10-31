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

/** What signature behaviours share: finding creatures, hurting and moving them, the ground. */
object SignatureKit {
    /** Living creatures whose middles are within [r] of [centre], the caster left out. */
    fun creatures(e: SignatureEntity, centre: Vec3, r: Double): List<LivingEntity> =
        creatures(e.level() as ServerLevel, centre, r, e.caster)

    fun creatures(level: ServerLevel, centre: Vec3, r: Double, except: Entity?): List<LivingEntity> =
        level.getEntitiesOfClass(LivingEntity::class.java, AABB(centre, centre).inflate(r + 2.0)) {
            it.isAlive && !it.isSpectator && it !== except && it !is ArmorStand && middle(it).distanceTo(centre) <= r
        }

    /** Creatures whose boxes come within [halfWidth] of the segment [a]–[b]. */
    fun creaturesAlong(e: SignatureEntity, a: Vec3, b: Vec3, halfWidth: Double): List<LivingEntity> =
        (e.level() as ServerLevel).getEntitiesOfClass(LivingEntity::class.java, AABB(a, b).inflate(halfWidth + 1.0)) {
            if (!it.isAlive || it.isSpectator || it === e.caster || it is ArmorStand) return@getEntitiesOfClass false
            val box = it.boundingBox.inflate(halfWidth)
            box.contains(a) || box.clip(a, b).isPresent
        }

    fun middle(entity: Entity): Vec3 = entity.position().add(0.0, entity.bbHeight * 0.5, 0.0)

    /** Hurts [target] by [amount] of [type], dealt by [e]'s caster. */
    fun hurt(e: SignatureEntity, target: Entity, type: ResourceKey<DamageType>, amount: Float): Boolean {
        val ctx = e.ctx ?: return false
        return target.hurt(ctx.damage(type), amount)
    }

    /** Hurts [target] through its i-frames, for damage that ticks faster than they allow. */
    fun hurtThrough(e: SignatureEntity, target: Entity, type: ResourceKey<DamageType>, amount: Float): Boolean {
        val living = target as? LivingEntity ?: return hurt(e, target, type, amount)
        val frames = living.invulnerableTime
        living.invulnerableTime = 0
        val hit = hurt(e, living, type, amount)
        living.invulnerableTime = frames
        return hit
    }

    /** Whether spells may move [entity]: not a boss, not one held fast by Gravity's self-cast. */
    fun movable(entity: Entity): Boolean = !SpellContext.isBoss(entity) && !NamedEffects.anchored(entity)

    /** Adds [v] (blocks a tick) to [entity]'s motion, if it may be moved. */
    fun push(entity: Entity, v: Vec3) {
        if (!movable(entity)) return
        entity.deltaMovement = entity.deltaMovement.add(v)
        entity.hurtMarked = true
    }

    /** Draws [entity] toward [point] by [strength] blocks a tick. */
    fun pull(entity: Entity, point: Vec3, strength: Double) {
        val to = point.subtract(middle(entity))
        val d = to.length()
        if (d < 1.0e-3) return
        push(entity, to.scale(strength / d))
    }

    /** The top of the highest solid block in the column at [x], [z], from [fromY] down up to
     *  [depth] blocks, or null where there's none. */
    fun ground(level: ServerLevel, x: Double, z: Double, fromY: Double, depth: Int = 8): Double? {
        val pos = BlockPos.MutableBlockPos(Math.floor(x).toInt(), Math.floor(fromY).toInt(), Math.floor(z).toInt())
        repeat(depth + 1) {
            val state = level.getBlockState(pos)
            if (!state.getCollisionShape(level, pos).isEmpty) return pos.y + state.getCollisionShape(level, pos).max(net.minecraft.core.Direction.Axis.Y)
            pos.move(0, -1, 0)
        }
        return null
    }

    /** The ground under [p], searching a few blocks above it first (for uneven ground). */
    fun groundNear(level: ServerLevel, p: Vec3, up: Int = 3, depth: Int = 8): Double? = ground(level, p.x, p.z, p.y + up, depth + up)

    fun sound(level: ServerLevel, at: Vec3, sound: SoundEvent, volume: Float = 1f, pitch: Float = 1f) {
        level.playSound(null, at.x, at.y, at.z, sound, SoundSource.PLAYERS, volume, pitch)
    }

    fun particles(level: ServerLevel, type: ParticleOptions, at: Vec3, count: Int, spread: Double, speed: Double = 0.0) {
        level.sendParticles(type, at.x, at.y, at.z, count, spread, spread, spread, speed)
    }

    /** Sets [pos] to [state] as [e]'s caster would be allowed to by hand. */
    fun setBlock(e: SignatureEntity, pos: BlockPos, state: BlockState): Boolean = e.ctx?.setBlock(pos, state) ?: false
}

/**
 * Ships, to signature spells, are bodies of blocks moved by force over mass: a spell pushes
 * the blocks it touches, at those points, never a ship as a whole. The same push sends a block
 * of debris flying and barely moves a carrier.
 */
object SignatureShips {
    /** VS2's mass of an ordinary block, in kilograms. */
    const val BLOCK_KG = 1000.0

    /** One ship block: the ship, where the block's middle is in the world, and the block in the
     *  ship's yard. */
    class ShipBlock(val ship: LoadedServerShip, val world: Vec3, val pos: BlockPos, val state: BlockState)

    /**
     * A one-tick shove on [ship] at the world point [at]. [impulse] is in block-masses times
     * blocks a tick: 1 sends a lone block off at a block a tick, and a ship of n blocks at 1/n.
     * Off its centre of mass it turns it, too.
     */
    fun push(ship: LoadedServerShip, at: Vec3, impulse: Vec3) {
        val ns = BLOCK_KG * 20.0
        ship.getOrPutAttachment(LauncherRecoilAttachment::class.java) { LauncherRecoilAttachment() }
            .enqueue(Vector3d(at.x, at.y, at.z), Vector3d(impulse.x * ns, impulse.y * ns, impulse.z * ns))
    }

    /** Loaded ships in [level] whose world box comes within [r] of [centre]. From the loaded
     *  ships themselves: VS's intersecting-ships query searches its saved ship data, which isn't
     *  a loaded ship and can't be pushed. */
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

    /** Every ship block whose middle is within [r] of [centre]. */
    fun blocksNear(level: ServerLevel, centre: Vec3, r: Double, only: LoadedServerShip? = null): List<ShipBlock> {
        val out = ArrayList<ShipBlock>()
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
                val w = Vec3(world.x, world.y, world.z)
                if (w.distanceTo(centre) <= r) out += ShipBlock(ship, w, pos.immutable(), state)
            }
        }
        return out
    }

    /** The ship block at the world point [at], if one is there. */
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

    /** Each ship within [r] of [centre], with its block nearest [centre]. */
    fun nearestBlocks(level: ServerLevel, centre: Vec3, r: Double): List<ShipBlock> =
        blocksNear(level, centre, r).groupBy { it.ship.id }.values.map { blocks -> blocks.minBy { it.world.distanceToSqr(centre) } }

    /** Where a world point in a ship's yard ([pos], a block of [ship]) is in the world. */
    fun toWorld(ship: LoadedServerShip, p: Vec3): Vec3 {
        val w = Vector3d()
        ship.transform.shipToWorld.transformPosition(p.x, p.y, p.z, w)
        return Vec3(w.x, w.y, w.z)
    }
}

/**
 * Signature beams are beams like a scroll's: traced off Aegis boxes and cut where they loop on
 * themselves, and registered with [BeamManager] each tick, so they cross, merge, clash and
 * explode with every other beam, carrying the signature's glyphs. Draw them on the client with
 * [org.shipwrights.enderkinesis.client.signature.SignatureVisuals.beamPath], which traces and
 * cuts them the same way.
 */
object SignatureBeams {
    const val HALF_WIDTH = SunderingBeamTrace.DEFAULT_HALF_WIDTH

    /** [e]'s beam from [origin] along [dir], [reach] long, as it is drawn: reflected, cut at a loop. */
    fun trace(e: SignatureEntity, origin: Vec3, dir: Vec3, reach: Double, halfWidth: Double = HALF_WIDTH): BeamTrace {
        val level = e.level() as ServerLevel
        return SunderingBeamTrace.trace(level, e.caster, origin, dir, reach, BeamManager.mirrors(level, e.caster), halfWidth)
    }

    /**
     * Registers this tick's [trace] of [e]'s beam with [BeamManager] and returns the part of it
     * that still acts: all of it, or up to where it fed a merged beam last tick. [id] tells
     * apart one caster's beams; the main beam is the caster's own id, which the client cuts by.
     * A clash that blows the beam calls [cancel]; by default it lets go of the hold.
     */
    fun register(
        e: SignatureEntity, trace: BeamTrace, reach: Double, id: UUID? = null, halfWidth: Double = HALF_WIDTH,
        cancel: () -> Unit = { e.caster?.releaseUsingItem() },
    ): BeamTrace {
        val caster = e.caster
        val beamId = id ?: caster?.uuid ?: e.uuid
        BeamManager.register(BeamManager.Beam(beamId, e.level() as ServerLevel, caster, e.signature.glyphs, 0f, trace, reach, halfWidth, fromStaff = false, cancel))
        return BeamTrace(BeamManager.clip(beamId, trace), if (BeamManager.isCut(beamId)) null else trace.finalBlockPos)
    }

    /** One of a caster's other beams' ids: a split or a second ray, told apart by [k]. */
    fun subId(e: SignatureEntity, k: Int): UUID {
        val base = e.caster?.uuid ?: e.uuid
        return UUID(base.mostSignificantBits, base.leastSignificantBits xor (0x5197L * (k + 1)))
    }

    /** Lines along a traced beam, the last biting into the block it strikes, as a scroll beam's. */
    fun lines(trace: BeamTrace): List<Delivery.Line> = SpellCasting.beamLines(trace)

    /** Where a traced beam ends. */
    fun end(trace: BeamTrace, origin: Vec3): Vec3 = trace.segments.lastOrNull()?.end ?: origin
}
