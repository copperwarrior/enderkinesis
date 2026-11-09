package org.shipwrights.enderkinesis.spell

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.item.AegisBox
import org.shipwrights.enderkinesis.util.CrossFrameBlocks
import org.shipwrights.enderkinesis.util.colliderClip
import org.shipwrights.enderkinesis.util.rayAabbEntry
import org.shipwrights.enderkinesis.util.standaloneBodyBoxes
import org.joml.Vector3d
import org.joml.primitives.AABBdc
import org.valkyrienskies.mod.common.util.toJOML
import org.valkyrienskies.mod.common.toWorldCoordinates

sealed class Delivery {
    abstract val anchor: Vec3

    fun entities(ctx: SpellContext, includeCaster: Boolean = false): List<Entity> =
        (foundEntities ?: candidates(ctx).also { foundEntities = it })
            .filter { it.isAlive && !it.isSpectator && (includeCaster || !ctx.isCaster(it)) }

    // Each element of a cast and each named effect ask for the same entities and blocks, so find them once.
    private var foundEntities: List<Entity>? = null
    private var foundBlocks: List<BlockPos>? = null

    fun living(ctx: SpellContext, includeCaster: Boolean = false): List<LivingEntity> =
        entities(ctx, includeCaster).filterIsInstance<LivingEntity>()

    protected abstract fun candidates(ctx: SpellContext): List<Entity>

    fun bounds(): AABB = when (this) {
        is Sphere -> AABB(center, center).inflate(radius)
        is Line -> AABB(from, to).inflate(halfWidth)
        is Cone -> AABB(origin, origin).inflate(length)
        is Group -> targets.map { it.boundingBox }.reduceOrNull(AABB::minmax) ?: AABB(anchor, anchor)
        is SingleEntity -> entity.boundingBox
        is SingleBlock -> AABB(pos)
    }

    fun blocks(level: Level): List<BlockPos> = foundBlocks ?: findBlocks(level).also { foundBlocks = it }

    protected abstract fun findBlocks(level: Level): List<BlockPos>

    open fun scatterRoll(ctx: SpellContext, denominator: Int): Boolean = ctx.chance(1f / denominator)

    /** [area] marks an Area cast at the caster's feet: visuals sit one block higher, and Chaos includes the caster.
     *  [waves] draws waves that go out from the centre, not one burst. */
    class Sphere(val center: Vec3, val radius: Double, val area: Boolean = false, val waves: Boolean = area) : Delivery() {
        override val anchor: Vec3 = if (area) center.add(0.0, 1.0, 0.0) else center

        override fun candidates(ctx: SpellContext): List<Entity> {
            val r2 = radius * radius
            return ctx.level.getEntities(null as Entity?, AABB.ofSize(center, radius * 2, radius * 2, radius * 2)) {
                it.position().distanceToSqr(center) <= r2
            }
        }

        override fun findBlocks(level: Level): List<BlockPos> {
            // The test is on offsets from the middle of the centre cell, whole blocks for world cells, so the world sphere is as
            // before; each frame walks its own cells, so a scaled ship loses none.
            val reach = radius + HALF_DIAGONAL
            val blocks = CrossFrameBlocks(level, center, reach)
            val r2 = radius * radius
            val ox = blocks.origin.x + 0.5; val oy = blocks.origin.y + 0.5; val oz = blocks.origin.z + 0.5
            val m = blocks.visitedMiddle
            val out = ArrayList<BlockPos>()
            blocks.forEachWithin(reach) { pos, _ ->
                val dx = m.x - ox; val dy = m.y - oy; val dz = m.z - oz
                if (dx * dx + dy * dy + dz * dz <= r2) out += pos.immutable()
            }
            return out
        }
    }

    class Line(val from: Vec3, val to: Vec3, val halfWidth: Double = 0.5) : Delivery() {
        override val anchor: Vec3 get() = to

        override fun candidates(ctx: SpellContext): List<Entity> =
            ctx.level.getEntities(null as Entity?, AABB(from, to).inflate(halfWidth)) {
                val box = it.boundingBox.inflate(halfWidth)
                box.contains(from) || box.clip(from, to).isPresent
            }

        override fun findBlocks(level: Level): List<BlockPos> {
            val worldFrom = level.toWorldCoordinates(from)
            val worldTo = level.toWorldCoordinates(to)
            val length = worldFrom.distanceTo(worldTo)
            val blocks = CrossFrameBlocks(level, worldFrom.lerp(worldTo, 0.5), length * 0.5 + halfWidth)
            val out = LinkedHashSet<BlockPos>()
            val cursor = BlockPos.MutableBlockPos()
            march(worldFrom.x, worldFrom.y, worldFrom.z, worldTo.x, worldTo.y, worldTo.z, cursor, out)
            // Each ship marches in its own frame, so a scaled ship gets every cell that the line crosses.
            val a = Vector3d()
            val b = Vector3d()
            for (ship in blocks.ships) {
                ship.transform.worldToShip.transformPosition(worldFrom.x, worldFrom.y, worldFrom.z, a)
                ship.transform.worldToShip.transformPosition(worldTo.x, worldTo.y, worldTo.z, b)
                march(a.x, a.y, a.z, b.x, b.y, b.z, cursor, out)
            }
            return out.toList()
        }

        @Suppress("LongParameterList") // Raw doubles: no vector for each call.
        private fun march(
            ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double,
            cursor: BlockPos.MutableBlockPos, out: MutableSet<BlockPos>,
        ) {
            val dx = bx - ax; val dy = by - ay; val dz = bz - az
            val steps = Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz) / STEP).toInt().coerceAtLeast(1)
            for (i in 0..steps) {
                val t = i.toDouble() / steps
                cursor.set(ax + dx * t, ay + dy * t, az + dz * t)
                if (cursor !in out) out += cursor.immutable()
            }
        }

        private companion object {
            const val STEP = 0.25
        }
    }

    /** [direction] must be a unit vector. [halfAngle] is in radians. */
    class Cone(
        val origin: Vec3, val direction: Vec3, val length: Double, val halfAngle: Double,
        val blockers: List<AegisBox.Frame> = emptyList(),
    ) : Delivery() {
        override val anchor: Vec3 get() = origin.add(direction.scale(length * 0.5))
        private val cosHalf = Math.cos(halfAngle)

        fun contains(point: Vec3): Boolean = contains(point.x, point.y, point.z)

        fun contains(x: Double, y: Double, z: Double): Boolean {
            val tx = x - origin.x; val ty = y - origin.y; val tz = z - origin.z
            val dist = Math.sqrt(tx * tx + ty * ty + tz * tz)
            if (dist > length) return false
            if (dist < 1.0e-3) return true
            return (tx * direction.x + ty * direction.y + tz * direction.z) / dist >= cosHalf
        }

        private fun bodies(level: Level): List<AABBdc> =
            level.standaloneBodyBoxes(AABB(origin, origin).inflate(length).toJOML())

        /** A standalone body is not a block, so it only casts a shadow. */
        private fun visible(level: Level, point: Vec3, target: BlockPos?, bodies: List<AABBdc>): Boolean {
            if (shadowed(origin, point, blockers)) return false
            if (bodies.isNotEmpty()) {
                val span = point.subtract(origin)
                val dist = span.length()
                if (dist > 1.0e-6 && bodies.any { rayAabbEntry(origin, span.scale(1.0 / dist), it) < dist }) return false
            }
            val hit = level.clip(colliderClip(origin, point))
            return hit.type == HitResult.Type.MISS || (target != null && hit.blockPos == target)
        }

        override fun candidates(ctx: SpellContext): List<Entity> {
            val bodies = bodies(ctx.level)
            return ctx.level.getEntities(null as Entity?, AABB(origin, origin).inflate(length)) {
                val centre = it.boundingBox.center
                contains(centre) && visible(ctx.level, centre, null, bodies)
            }
        }

        override fun findBlocks(level: Level): List<BlockPos> {
            // The cone is in the sphere of its length, and each frame walks its own cells, so scaled ships lose none.
            val blocks = CrossFrameBlocks(level, origin, length)
            val out = ArrayList<BlockPos>()
            val bodies = bodies(level)
            val m = blocks.visitedMiddle
            blocks.forEachWithin(length) { pos, _ ->
                if (!contains(m.x, m.y, m.z)) return@forEachWithin
                // VS2 clip() gives the SHIPYARD blockPos of a ship hit, so the frame's own pos is the target.
                if (visible(level, Vec3(m.x, m.y, m.z), pos, bodies)) out += pos.immutable()
            }
            return out
        }
    }

    class Group(val targets: List<Entity>, override val anchor: Vec3) : Delivery() {
        override fun candidates(ctx: SpellContext): List<Entity> = targets
        override fun findBlocks(level: Level): List<BlockPos> = emptyList()
    }

    companion object {
        // Half the diagonal of a block: a cell middle is at most this far from any point of the cell.
        private const val HALF_DIAGONAL = 0.8661

        fun shadowed(from: Vec3, to: Vec3, frames: List<AegisBox.Frame>): Boolean {
            if (frames.isEmpty()) return false
            val span = to.subtract(from)
            val dist = span.length()
            if (dist < 1.0e-6) return false
            val dir = span.scale(1.0 / dist)
            return frames.any { frame -> AegisBox.rayIntersect(from, dir, frame)?.let { it.distance < dist } == true }
        }
    }

    class SingleEntity(val entity: Entity) : Delivery() {
        override val anchor: Vec3 get() = entity.position().add(0.0, entity.bbHeight * 0.5, 0.0)
        override fun candidates(ctx: SpellContext): List<Entity> = listOf(entity)
        override fun findBlocks(level: Level): List<BlockPos> = emptyList()
    }

    /** Includes the air in front of [face], so effects that place a block (fire) have room. */
    class SingleBlock(val pos: BlockPos, val face: Direction) : Delivery() {
        override val anchor: Vec3 get() = Vec3.atCenterOf(pos).relative(face, 0.5)
        override fun candidates(ctx: SpellContext): List<Entity> = emptyList()
        override fun findBlocks(level: Level): List<BlockPos> = listOf(pos, pos.relative(face))
        // Always pass, so a spell aimed at one block is never wasted.
        override fun scatterRoll(ctx: SpellContext, denominator: Int): Boolean = true
    }
}
