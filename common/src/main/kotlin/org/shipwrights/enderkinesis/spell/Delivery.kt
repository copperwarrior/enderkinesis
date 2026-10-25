package org.shipwrights.enderkinesis.spell

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.Mth
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * The region an element's effect lands on. Shapes and cast modes reduce to these four: Area
 * casts, Bolt detonations and runes are a [Sphere]; Beams are a [Line]; imbued hits and single
 * targets are a [SingleEntity]; a projectile striking a block is a [SingleBlock].
 */
sealed class Delivery {
    /** Where the delivery's particles and sound centre. */
    abstract val anchor: Vec3

    /** Every live, non-spectator entity the delivery touches. The caster is left out unless
     *  [includeCaster]. */
    fun entities(ctx: SpellContext, includeCaster: Boolean = false): List<Entity> =
        candidates(ctx).filter { it.isAlive && !it.isSpectator && (includeCaster || !ctx.isCaster(it)) }

    fun living(ctx: SpellContext, includeCaster: Boolean = false): List<LivingEntity> =
        entities(ctx, includeCaster).filterIsInstance<LivingEntity>()

    protected abstract fun candidates(ctx: SpellContext): List<Entity>

    /** Every block position the delivery touches. */
    abstract fun blocks(level: Level): List<BlockPos>

    /** A one-in-[denominator] roll for scattered block effects (surface fire, bone meal).
     *  A single-block hit always passes, so a spell aimed at one block is never wasted. */
    open fun scatterRoll(random: RandomSource, denominator: Int): Boolean = random.nextInt(denominator) == 0

    /** A sphere of [radius] around [center]. [area] marks an Area cast centred on the
     *  caster's feet: its visuals sit a block higher, and Chaos puts the caster in its cycle. */
    class Sphere(val center: Vec3, val radius: Double, val area: Boolean = false) : Delivery() {
        override val anchor: Vec3 = if (area) center.add(0.0, 1.0, 0.0) else center

        override fun candidates(ctx: SpellContext): List<Entity> {
            val r2 = radius * radius
            return ctx.level.getEntities(null as Entity?, AABB.ofSize(center, radius * 2, radius * 2, radius * 2)) {
                it.position().distanceToSqr(center) <= r2
            }
        }

        /** Sphere-clipped block positions; the squared-radius test culls the AABB corners the
         *  visible sphere wouldn't reach. */
        override fun blocks(level: Level): List<BlockPos> {
            val r = Math.ceil(radius).toInt()
            val cx = Mth.floor(center.x); val cy = Mth.floor(center.y); val cz = Mth.floor(center.z)
            val r2 = radius * radius
            val out = ArrayList<BlockPos>()
            for (dx in -r..r) for (dy in -r..r) for (dz in -r..r) {
                if (dx * dx + dy * dy + dz * dz > r2) continue
                out += BlockPos(cx + dx, cy + dy, cz + dz)
            }
            return out
        }
    }

    /** A ray from [from] to [to] that hits entities within [halfWidth] of it. */
    class Line(val from: Vec3, val to: Vec3, val halfWidth: Double = 0.5) : Delivery() {
        override val anchor: Vec3 get() = to

        override fun candidates(ctx: SpellContext): List<Entity> =
            ctx.level.getEntities(null as Entity?, AABB(from, to).inflate(halfWidth)) {
                val box = it.boundingBox.inflate(halfWidth)
                box.contains(from) || box.clip(from, to).isPresent
            }

        /** The blocks the ray's centre passes through, sampled every quarter block. */
        override fun blocks(level: Level): List<BlockPos> {
            val length = from.distanceTo(to)
            val steps = Math.ceil(length / STEP).toInt().coerceAtLeast(1)
            val out = LinkedHashSet<BlockPos>()
            for (i in 0..steps) out += BlockPos.containing(from.lerp(to, i.toDouble() / steps))
            return out.toList()
        }

        private companion object {
            const val STEP = 0.25
        }
    }

    /** A cone from [origin] along the unit vector [direction], [length] long and [halfAngle]
     *  radians wide. Only what the origin can see is hit, so a Spray stops at walls. */
    class Cone(val origin: Vec3, val direction: Vec3, val length: Double, val halfAngle: Double) : Delivery() {
        override val anchor: Vec3 get() = origin.add(direction.scale(length * 0.5))
        private val cosHalf = Math.cos(halfAngle)

        fun contains(point: Vec3): Boolean {
            val to = point.subtract(origin)
            val dist = to.length()
            if (dist > length) return false
            if (dist < 1.0e-3) return true
            return to.dot(direction) / dist >= cosHalf
        }

        private fun visible(level: Level, point: Vec3, target: BlockPos?): Boolean {
            val hit = level.clip(ClipContext(origin, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null))
            return hit.type == HitResult.Type.MISS || (target != null && hit.blockPos == target)
        }

        override fun candidates(ctx: SpellContext): List<Entity> =
            ctx.level.getEntities(null as Entity?, AABB(origin, origin).inflate(length)) {
                val centre = it.boundingBox.center
                contains(centre) && visible(ctx.level, centre, null)
            }

        /** Block positions whose centres lie in the cone, in sight of the origin. */
        override fun blocks(level: Level): List<BlockPos> {
            val r = Math.ceil(length).toInt()
            val o = BlockPos.containing(origin)
            val out = ArrayList<BlockPos>()
            for (dx in -r..r) for (dy in -r..r) for (dz in -r..r) {
                val pos = o.offset(dx, dy, dz)
                val centre = Vec3.atCenterOf(pos)
                if (contains(centre) && visible(level, centre, pos)) out += pos
            }
            return out
        }
    }

    /** A chosen set of [targets], such as a Chain's. No block effects. */
    class Group(val targets: List<Entity>, override val anchor: Vec3) : Delivery() {
        override fun candidates(ctx: SpellContext): List<Entity> = targets
        override fun blocks(level: Level): List<BlockPos> = emptyList()
    }

    /** One entity, struck directly. No block effects. */
    class SingleEntity(val entity: Entity) : Delivery() {
        override val anchor: Vec3 get() = entity.position().add(0.0, entity.bbHeight * 0.5, 0.0)
        override fun candidates(ctx: SpellContext): List<Entity> = listOf(entity)
        override fun blocks(level: Level): List<BlockPos> = emptyList()
    }

    /** One block, struck on [face]. The air in front of the face is included, so effects that
     *  place something (fire) have room to. No entities. */
    class SingleBlock(val pos: BlockPos, val face: Direction) : Delivery() {
        override val anchor: Vec3 get() = Vec3.atCenterOf(pos).relative(face, 0.5)
        override fun candidates(ctx: SpellContext): List<Entity> = emptyList()
        override fun blocks(level: Level): List<BlockPos> = listOf(pos, pos.relative(face))
        override fun scatterRoll(random: RandomSource, denominator: Int): Boolean = true
    }
}
