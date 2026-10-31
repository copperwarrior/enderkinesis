package org.shipwrights.enderkinesis.spell.signature

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.BlockTags
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.item.FallingBlockEntity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.item.PrimedTnt
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseFireBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.WeatheringCopper
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.level.dimension.DimensionType
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.joml.Vector3f
import org.shipwrights.enderkinesis.dimension.EKDamageSources
import org.shipwrights.enderkinesis.entity.SignatureEntity
import org.shipwrights.enderkinesis.entity.SpellBoltEntity
import org.shipwrights.enderkinesis.registry.EKSounds
import org.shipwrights.enderkinesis.spell.Element
import org.shipwrights.enderkinesis.spell.Signature
import org.shipwrights.enderkinesis.spell.Spell
import org.shipwrights.enderkinesis.spell.SpellCasting
import org.shipwrights.enderkinesis.spell.SpellCurves
import org.shipwrights.enderkinesis.spell.SpellVfx
import it.unimi.dsi.fastutil.doubles.DoubleArrayList
import org.joml.Matrix4d
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object FieldSignatures {
    val ALL: Map<Signature, SignatureBehaviour> = mapOf(
        Signature.STILLPOINT to Stillpoint,
        Signature.AURORA_VEIL to AuroraVeil,
        Signature.ACID_RAIN to AcidRain,
        Signature.STORM_CLOUD to StormCloud,
        Signature.GUST_OF_GLASS to GustOfGlass,
        Signature.NETHER_RIFT to NetherRift,
    )

    // Tuned against a Sharpness V netherite sword at full speed (about 17 a second): this gives 15 to 18.
    private const val PULSE_EVERY = 5
    private const val PULSE = 3.5f
    private const val PULSE_POWER = 1f

    private fun pulse(power: Float): Float = PULSE + PULSE_POWER * power

    private fun placed(cast: SignatureCast, range: Double, back: Double): Vec3 {
        val aimed = cast.aimed(range)
        aimed.entity?.let { return SignatureKit.middle(it) }
        if (aimed.block == null) return aimed.point
        val toEye = cast.caster.eyePosition.subtract(aimed.point)
        return aimed.point.add(toEye.normalize().scale(minOf(back, toEye.length() * 0.5)))
    }

    private fun landed(cast: SignatureCast, range: Double): Vec3 {
        val aimed = cast.aimed(range)
        return aimed.entity?.position() ?: aimed.point
    }

    private fun ignite(e: SignatureEntity, pos: BlockPos): Boolean {
        val level = e.level() as ServerLevel
        if (!level.gameRules.getBoolean(GameRules.RULE_DOFIRETICK)) return false
        if (!level.getBlockState(pos).isAir || !BaseFireBlock.canBePlacedAt(level, pos, Direction.UP)) return false
        return SignatureKit.setBlock(e, pos, BaseFireBlock.getState(level, pos))
    }

    private fun horizontal(a: Vec3, b: Vec3): Double = sqrt((a.x - b.x) * (a.x - b.x) + (a.z - b.z) * (a.z - b.z))

    private fun clear(e: SignatureEntity, a: Vec3, b: Vec3): Boolean =
        e.level().clip(ClipContext(a, b, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, e)).type == HitResult.Type.MISS

    private fun beneath(e: SignatureEntity, cloud: Vec3, radius: Double, drop: Double): List<LivingEntity> {
        val box = AABB(cloud, cloud).inflate(radius, 0.0, radius).expandTowards(0.0, -drop, 0.0)
        return (e.level() as ServerLevel).getEntitiesOfClass(LivingEntity::class.java, box) {
            it !== e.caster && !it.isSpectator && it.isAlive && horizontal(it.position(), cloud) <= radius
        }.sortedBy { horizontal(it.position(), cloud) }
    }

    // Read loadedShips: SignatureShips.ships uses getShipsIntersecting, which casts saved ship data and can find nothing.
    private fun shipsNear(level: ServerLevel, centre: Vec3, r: Double): List<LoadedServerShip> {
        val dim = level.dimensionId
        return level.shipObjectWorld.loadedShips.filter { ship ->
            val b = ship.worldAABB
            ship.chunkClaimDimension == dim &&
                b.maxX() >= centre.x - r && b.minX() <= centre.x + r &&
                b.maxY() >= centre.y - r && b.minY() <= centre.y + r &&
                b.maxZ() >= centre.z - r && b.minZ() <= centre.z + r
        }
    }

    private fun shipBlockAt(level: ServerLevel, at: Vec3, ships: List<LoadedServerShip>): SignatureShips.ShipBlock? {
        val local = Vector3d()
        val world = Vector3d()
        for (ship in ships) {
            ship.transform.worldToShip.transformPosition(at.x, at.y, at.z, local)
            val pos = BlockPos.containing(local.x, local.y, local.z)
            val state = level.getBlockState(pos)
            if (state.isAir) continue
            ship.transform.shipToWorld.transformPosition(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, world)
            return SignatureShips.ShipBlock(ship, Vec3(world.x, world.y, world.z), pos, state)
        }
        return null
    }

    private fun shipBlocksNear(level: ServerLevel, centre: Vec3, r: Double): List<SignatureShips.ShipBlock> =
        shipsNear(level, centre, r).flatMap { SignatureShips.blocksNear(level, centre, r, it) }

    private class Struck(val at: Vec3, val ship: SignatureShips.ShipBlock?, val pos: BlockPos?, val creature: LivingEntity?)

    private fun downColumn(level: ServerLevel, x: Double, z: Double, fromY: Double, drop: Double, creature: LivingEntity? = null): Struck? {
        val ships = shipsNear(level, Vec3(x, fromY - drop / 2, z), drop / 2 + 1.0)
        var y = fromY
        while (y > fromY - drop) {
            val p = Vec3(x, y, z)
            if (creature != null && creature.boundingBox.contains(p)) return Struck(p, null, null, creature)
            val ship = shipBlockAt(level, p, ships)
            if (ship != null) return Struck(p, ship, ship.pos, null)
            val pos = BlockPos.containing(p)
            val state = level.getBlockState(pos)
            if (!state.fluidState.isEmpty || !state.getCollisionShape(level, pos).isEmpty) return Struck(p, null, pos, null)
            y -= 0.25
        }
        return null
    }

    private fun shipBlocksUnder(level: ServerLevel, ships: List<LoadedServerShip>, test: (BlockState) -> Boolean): List<SignatureShips.ShipBlock> {
        val out = ArrayList<SignatureShips.ShipBlock>()
        val world = Vector3d()
        val pos = BlockPos.MutableBlockPos()
        for (ship in ships) {
            val box = ship.shipAABB ?: continue
            val volume = (box.maxX() - box.minX() + 1).toLong() * (box.maxY() - box.minY() + 1) * (box.maxZ() - box.minZ() + 1)
            if (volume > SHIP_SCAN_LIMIT) continue
            for (x in box.minX()..box.maxX()) for (y in box.minY()..box.maxY()) for (z in box.minZ()..box.maxZ()) {
                pos.set(x, y, z)
                val state = level.getBlockState(pos)
                if (state.isAir || !test(state)) continue
                ship.transform.shipToWorld.transformPosition(x + 0.5, y + 0.5, z + 0.5, world)
                out += SignatureShips.ShipBlock(ship, Vec3(world.x, world.y, world.z), pos.immutable(), state)
            }
        }
        return out
    }

    private const val SHIP_SCAN_LIMIT = 65536L

    object Stillpoint : SignatureBehaviour() {
        const val RANGE = 64.0
        private const val BASE_LIFE = 320
        private const val POWER_LIFE = 240

        private const val SLOWING = 0.5
        private const val HANG = 0.01
        private const val HOLD = 0.2
        private const val CRAWL_AMP = 6
        private const val CUSHION = 0.75
        // Fraction of a block's momentum removed each tick: a small craft wholly inside loses about a third of its speed.
        private const val SHIP_BRAKE = 0.35
        private const val SHIP_BLOCK_MASS = 6.0

        // Client render and server logic both use this radius.
        fun radius(power: Float): Double = 8.0 + 6.0 * power

        private class Hung(val entity: Entity, val motion: Vec3, val noGravity: Boolean) { var ticks = 0 }

        private class State {
            val hung = HashMap<Int, Hung>()
            val lastPose = HashMap<Long, Matrix4d>()
        }

        override fun start(cast: SignatureCast): SignatureEntity {
            val r = radius(cast.power)
            val centre = placed(cast, RANGE, r * 0.6)
            SignatureKit.sound(cast.level, centre, SoundEvents.AMETHYST_BLOCK_RESONATE, 2.5f, 0.5f)
            SignatureKit.sound(cast.level, centre, SoundEvents.POWDER_SNOW_PLACE, 2f, 0.6f)
            SignatureKit.sound(cast.level, centre, SoundEvents.BEACON_ACTIVATE, 1.5f, 0.6f)
            return spawn(cast, centre, BASE_LIFE + (POWER_LIFE * cast.power).toInt()) { point = centre }
        }

        override fun tick(e: SignatureEntity) {
            e.deltaMovement = Vec3.ZERO
            if (e.position().distanceToSqr(e.point) > 1.0e-6) e.setPos(e.point)
            val level = e.level() as ServerLevel
            val state = e.state as? State ?: State().also { e.state = it }
            val centre = e.point
            val r = radius(e.power)
            val inside = HashSet<Int>()
            val bite = e.tickCount % PULSE_EVERY == 0
            for (entity in level.getEntities(e, AABB(centre, centre).inflate(r))) {
                if (entity.isSpectator || !entity.isAlive) continue
                if (SignatureKit.middle(entity).distanceTo(centre) > r) continue
                when (entity) {
                    is LivingEntity -> if (entity === e.caster) cushion(entity) else {
                        hold(entity)
                        if (bite) {
                            SignatureKit.hurtThrough(e, entity, EKDamageSources.COLD_KEY, pulse(e.power))
                            entity.ticksFrozen = minOf(entity.ticksFrozen + 20, entity.ticksRequiredToFreeze + 40)
                        }
                    }
                    is Projectile, is ItemEntity, is FallingBlockEntity, is PrimedTnt -> {
                        inside += entity.id
                        hang(state.hung.getOrPut(entity.id) { Hung(entity, entity.deltaMovement, entity.isNoGravity).also { entity.isNoGravity = true } })
                    }
                }
            }
            val left = state.hung.keys.filter { it !in inside }
            for (id in left) letGo(state.hung.remove(id)!!)
            brakeShips(level, centre, r, state)
            if (e.tickCount % 20 == 0) SignatureKit.sound(level, centre, SoundEvents.AMETHYST_BLOCK_RESONATE, 1.5f, 0.4f)
        }

        override fun end(e: SignatureEntity) {
            (e.state as? State)?.hung?.values?.forEach(::letGo)
            e.state = null
            val level = e.level() as ServerLevel
            SignatureKit.sound(level, e.position(), SoundEvents.AMETHYST_BLOCK_BREAK, 2f, 0.6f)
            SignatureKit.sound(level, e.position(), SoundEvents.GLASS_BREAK, 1.5f, 0.5f)
            SignatureKit.particles(level, ParticleTypes.SNOWFLAKE, e.position(), 120, radius(e.power) * 0.4, 0.05)
        }

        private fun hang(h: Hung) {
            h.ticks++
            h.entity.deltaMovement = h.motion.scale(maxOf(HANG, SLOWING.pow(h.ticks)))
            h.entity.hurtMarked = true
        }

        private fun letGo(h: Hung) {
            if (!h.entity.isAlive) return
            h.entity.isNoGravity = h.noGravity
            h.entity.deltaMovement = h.motion
            h.entity.hurtMarked = true
        }

        private fun hold(target: LivingEntity) {
            if (!SignatureKit.movable(target)) return
            target.addEffect(MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 10, CRAWL_AMP, false, false))
            target.addEffect(MobEffectInstance(MobEffects.DIG_SLOWDOWN, 10, 3, false, false))
            target.deltaMovement = target.deltaMovement.scale(HOLD)
            target.fallDistance = 0f
            target.hurtMarked = true
        }

        private fun cushion(caster: LivingEntity) {
            caster.deltaMovement = caster.deltaMovement.scale(CUSHION)
            caster.fallDistance = 0f
            caster.hurtMarked = true
        }

        // Measure block motion from last tick's transform so ship rotation counts; the first tick uses reported velocity.
        private fun brakeShips(level: ServerLevel, centre: Vec3, r: Double, state: State) {
            val seen = HashSet<Long>()
            val world = Vector3d()
            // Six doubles per block: the world middle, then the shipyard middle.
            val blocks = DoubleArrayList()
            for (ship in shipsNear(level, centre, r)) {
                seen += ship.id
                val last = state.lastPose[ship.id]
                blocks.clear()
                SignatureShips.forEachBlockNear(level, centre, r, ship) { _, at, pos, _ ->
                    blocks.add(at.x); blocks.add(at.y); blocks.add(at.z)
                    blocks.add(pos.x + 0.5); blocks.add(pos.y + 0.5); blocks.add(pos.z + 0.5)
                }
                val count = blocks.size / 6
                if (count > 0) brakeBlocks(ship, blocks, count, last, world)
                if (last != null) last.set(ship.transform.shipToWorld) else state.lastPose[ship.id] = Matrix4d(ship.transform.shipToWorld)
            }
            state.lastPose.keys.retainAll(seen)
        }

        private fun brakeBlocks(ship: LoadedServerShip, blocks: DoubleArrayList, count: Int, last: Matrix4d?, world: Vector3d) {
            val share = minOf(ship.inertiaData.mass / SignatureShips.BLOCK_KG / count, SHIP_BLOCK_MASS)
            val com = ship.transform.positionInWorld
            val v = ship.velocity
            val w = ship.angularVelocity
            val k = -SHIP_BRAKE * share
            for (n in 0 until count) {
                val o = n * 6
                val bx = blocks.getDouble(o); val by = blocks.getDouble(o + 1); val bz = blocks.getDouble(o + 2)
                val mx: Double; val my: Double; val mz: Double
                if (last != null) {
                    last.transformPosition(blocks.getDouble(o + 3), blocks.getDouble(o + 4), blocks.getDouble(o + 5), world)
                    mx = bx - world.x; my = by - world.y; mz = bz - world.z
                } else {
                    val rx = bx - com.x(); val ry = by - com.y(); val rz = bz - com.z()
                    // Convert v + ω × r from metres per second to blocks per tick.
                    val perTick = 1.0 / 20.0
                    mx = (v.x() + w.y() * rz - w.z() * ry) * perTick
                    my = (v.y() + w.z() * rx - w.x() * rz) * perTick
                    mz = (v.z() + w.x() * ry - w.y() * rx) * perTick
                }
                if (mx * mx + my * my + mz * mz < 1.0e-8) continue
                SignatureShips.push(ship, bx, by, bz, mx * k, my * k, mz * k)
            }
        }
    }

    object AuroraVeil : SignatureBehaviour() {
        const val RANGE = 64.0
        const val LIFE = 440
        const val HEIGHT = 5.0
        const val RISEN = 10
        const val FADING = 415
        // Equal to the sway depth of the client waves, so anything that touches the light is in the veil.
        const val DEPTH = 1.6
        // The client shimmer uses this value too.
        const val RECLEANSE = 20
        private const val CHILL = 100
        private const val CONFUSE = 160
        private const val BITE = 12f
        private const val BITE_POWER = 18f
        private const val FLING = 0.6

        fun width(power: Float): Double = 7.0 + 5.0 * power

        fun facing(e: SignatureEntity): Vec3 {
            val to = Vec3(e.point.x - e.x, 0.0, e.point.z - e.z)
            return if (to.lengthSqr() < 1.0e-6) Vec3(0.0, 0.0, 1.0) else to.normalize()
        }

        // Client and server use this test with cleansed(), so the client shimmer matches the server cleanse.
        fun inVeil(e: SignatureEntity, target: Entity, foot: Vec3 = e.position()): Boolean {
            val n = facing(e)
            val across = Vec3(-n.z, 0.0, n.x)
            val rel = target.position().add(0.0, target.bbHeight * 0.5, 0.0).subtract(foot)
            if (Math.abs(rel.dot(n)) > DEPTH + target.bbWidth / 2) return false
            if (Math.abs(rel.dot(across)) > width(e.power) / 2 + target.bbWidth / 2) return false
            return target.y <= foot.y + HEIGHT && target.y + target.bbHeight >= foot.y
        }

        fun cleansed(seen: HashMap<Int, Int>, target: Entity, tick: Int): Boolean {
            val last = seen.put(target.id, tick)
            return last == null || tick - last > RECLEANSE
        }

        override fun start(cast: SignatureCast): SignatureEntity {
            val at = landed(cast, RANGE)
            val ground = SignatureKit.groundNear(cast.level, at, 2, 4)
            val foot = Vec3(at.x, ground ?: (at.y - HEIGHT / 2), at.z)
            SignatureKit.sound(cast.level, foot, SoundEvents.AMETHYST_BLOCK_CHIME, 2f, 0.6f)
            SignatureKit.sound(cast.level, foot, SoundEvents.BEACON_POWER_SELECT, 1.4f, 1.6f)
            SignatureKit.sound(cast.level, foot, SoundEvents.BEACON_ACTIVATE, 1f, 1.4f)
            return spawn(cast, foot, LIFE) { point = cast.caster.position() }
        }

        @Suppress("UNCHECKED_CAST") // This behaviour stores only HashMap<Int, Int> in the untyped e.state.
        override fun tick(e: SignatureEntity) {
            if (e.tickCount < RISEN || e.tickCount > FADING) return
            val level = e.level() as ServerLevel
            val touched = e.state as? HashMap<Int, Int> ?: HashMap<Int, Int>().also { e.state = it }
            val foot = e.position()
            val w = width(e.power)
            val box = AABB(foot, foot.add(0.0, HEIGHT, 0.0)).inflate(w / 2 + 2.0, 0.5, w / 2 + 2.0)
            for (target in level.getEntitiesOfClass(LivingEntity::class.java, box)) {
                if (target.isSpectator || !target.isAlive || !inVeil(e, target, foot)) continue
                val clean = cleansed(touched, target, e.tickCount)
                if (clean) cleanse(e, level, target)
                else if (target !== e.caster && e.tickCount % PULSE_EVERY == 0) SignatureKit.hurtThrough(e, target, EKDamageSources.COLD_KEY, pulse(e.power))
                if (target !== e.caster) target.ticksFrozen = minOf(target.ticksFrozen + 10, target.ticksRequiredToFreeze + CHILL)
            }
            if (e.tickCount % 30 == 0) SignatureKit.sound(level, foot.add(0.0, HEIGHT / 2, 0.0), SoundEvents.AMETHYST_BLOCK_RESONATE, 1.2f, 1.5f)
        }

        private fun cleanse(e: SignatureEntity, level: ServerLevel, target: LivingEntity) {
            // Remove effects one at a time so each removal syncs to the client.
            for (effect in target.activeEffectsMap.keys.toList()) target.removeEffect(effect)
            target.clearFire()
            if (target !== e.caster) {
                SignatureKit.hurtThrough(e, target, EKDamageSources.COLD_KEY, BITE + BITE_POWER * e.power)
                // Use ticksFrozen, not an effect, so confusion is the only effect that stays.
                target.ticksFrozen = maxOf(target.ticksFrozen, target.ticksRequiredToFreeze + CHILL)
                target.addEffect(MobEffectInstance(MobEffects.CONFUSION, CONFUSE, 0), e.caster)
            }
            if (target is Mob) {
                target.target = null
                target.navigation.stop()
                target.yRot = target.yRot + 90f + level.random.nextFloat() * 180f
                val a = level.random.nextDouble() * Math.PI * 2
                SignatureKit.push(target, Vec3(cos(a) * FLING, 0.3, sin(a) * FLING))
            }
            val mid = SignatureKit.middle(target)
            val spread = target.bbWidth * 0.6
            SignatureKit.particles(level, ParticleTypes.SNOWFLAKE, mid, 24, spread, 0.04)
            SignatureKit.particles(level, ParticleTypes.END_ROD, mid, 18, spread, 0.08)
            SignatureKit.particles(level, ParticleTypes.FIREWORK, mid, 14, spread, 0.12)
            level.sendParticles(DustParticleOptions(Vector3f(0.4f, 1f, 0.7f), 1.2f), mid.x, mid.y, mid.z, 16, spread, target.bbHeight * 0.5, spread, 0.0)
            level.sendParticles(DustParticleOptions(Vector3f(0.75f, 0.5f, 1f), 1.2f), mid.x, mid.y, mid.z, 16, spread, target.bbHeight * 0.5, spread, 0.0)
            val pitch = 1.2f + level.random.nextFloat() * 0.4f
            SignatureKit.sound(level, mid, SoundEvents.AMETHYST_BLOCK_CHIME, 2f, pitch)
            SignatureKit.sound(level, mid, SoundEvents.NOTE_BLOCK_CHIME.value(), 1.2f, pitch)
            SignatureKit.sound(level, mid, SoundEvents.ILLUSIONER_CAST_SPELL, 0.8f, 1.6f)
        }
    }

    abstract class Cloud : SignatureBehaviour() {
        abstract val range: Double
        abstract val height: Double
        /** How far below the cloud its rain reaches. */
        abstract val fall: Double

        abstract fun radius(power: Float): Double

        fun rainReach(power: Float): Double = radius(power) * RAIN_REACH

        fun raining(e: SignatureEntity, age: Int): Boolean = age >= FORM && age <= e.life - FADE

        protected fun form(cast: SignatureCast, life: Int): SignatureEntity {
            val ground = landed(cast, range)
            return spawn(cast, ground.add(0.0, height, 0.0), life) { point = ground; deltaMovement = WIND }
        }

        // Client and server both set this, so the cloud moves as one.
        fun drift(e: SignatureEntity) {
            e.deltaMovement = WIND
        }

        companion object {
            const val GROW = 30
            const val FORM = 20
            const val FADE = 30
            const val RAIN_REACH = 1.3
            // Blocks per tick, to the east like vanilla clouds.
            const val WIND_SPEED = 0.03
            val WIND = Vec3(WIND_SPEED, 0.0, 0.0)
        }
    }

    private val FLORA: Set<Block> = setOf(
        Blocks.GRASS, Blocks.TALL_GRASS, Blocks.FERN, Blocks.LARGE_FERN, Blocks.VINE, Blocks.DEAD_BUSH,
        Blocks.SWEET_BERRY_BUSH, Blocks.SUGAR_CANE, Blocks.LILY_PAD, Blocks.MOSS_CARPET, Blocks.GLOW_LICHEN,
        Blocks.BROWN_MUSHROOM, Blocks.RED_MUSHROOM, Blocks.CACTUS, Blocks.BAMBOO, Blocks.BAMBOO_SAPLING,
        Blocks.HANGING_ROOTS, Blocks.AZALEA, Blocks.FLOWERING_AZALEA, Blocks.BIG_DRIPLEAF, Blocks.BIG_DRIPLEAF_STEM,
        Blocks.SMALL_DRIPLEAF, Blocks.SPORE_BLOSSOM, Blocks.PINK_PETALS, Blocks.PUMPKIN, Blocks.MELON,
    )

    object AcidRain : Cloud() {
        override val range = 64.0
        override val height = 12.0
        override val fall = 30.0
        private const val BASE_LIFE = 200
        private const val POWER_LIFE = 140
        private const val POISON_EVERY = 20
        private const val ARMOUR_EVERY = 20
        // Each column under the cloud takes a hit about every 1.5 seconds.
        private const val CORRODE_EVERY = 4
        private const val CORRODE_SHARE = 8
        val ACID = Vector3f(0.45f, 1f, 0.2f)

        override fun radius(power: Float): Double = 7.0 + 6.0 * power

        override fun start(cast: SignatureCast): SignatureEntity {
            val e = form(cast, BASE_LIFE + (POWER_LIFE * cast.power).toInt())
            SignatureKit.sound(cast.level, e.position(), SoundEvents.BREWING_STAND_BREW, 2f, 0.5f)
            SignatureKit.sound(cast.level, e.position(), SoundEvents.WITCH_THROW, 1.4f, 0.5f)
            return e
        }

        override fun tick(e: SignatureEntity) {
            drift(e)
            if (!raining(e, e.tickCount)) return
            val level = e.level() as ServerLevel
            val cloud = e.position()
            val reach = rainReach(e.power)
            if (e.tickCount % PULSE_EVERY == 0) for (target in beneath(e, cloud, reach, fall)) {
                SignatureKit.hurtThrough(e, target, EKDamageSources.ROT_KEY, pulse(e.power))
                if (e.tickCount % POISON_EVERY == 0) target.addEffect(MobEffectInstance(MobEffects.POISON, 100, 2), e.caster)
                if (e.tickCount % ARMOUR_EVERY == 0) eatArmour(target)
            }
            if (e.tickCount % CORRODE_EVERY != 0) return
            val ships = shipsNear(level, cloud.subtract(0.0, fall / 2, 0.0), maxOf(reach, fall / 2))
            val columns = (Math.PI * reach * reach / CORRODE_SHARE).toInt()
            repeat(columns) {
                val a = level.random.nextDouble() * Math.PI * 2
                val d = reach * sqrt(level.random.nextDouble())
                corrodeColumn(e, level, cloud, cloud.x + cos(a) * d, cloud.z + sin(a) * d, ships)
            }
        }

        private fun eatArmour(target: LivingEntity) {
            for (slot in EquipmentSlot.values()) {
                if (slot.type != EquipmentSlot.Type.ARMOR) continue
                val stack = target.getItemBySlot(slot)
                if (stack.isDamageableItem) stack.hurtAndBreak(1, target) { it.broadcastBreakEvent(slot) }
            }
        }

        // Use WORLD_SURFACE, not MOTION_BLOCKING, so drops land on plants too.
        private fun corrodeColumn(e: SignatureEntity, level: ServerLevel, cloud: Vec3, x: Double, z: Double, ships: List<LoadedServerShip>) {
            val bx = Math.floor(x).toInt(); val bz = Math.floor(z).toInt()
            val pos = BlockPos.MutableBlockPos(bx, minOf(level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz) - 1, Math.floor(cloud.y).toInt()), bz)
            // If the cloud is under a roof, start below the cloud.
            while (pos.y > cloud.y - fall && level.getBlockState(pos).isAir) pos.move(0, -1, 0)
            if (pos.y <= cloud.y - fall) return
            if (ships.isNotEmpty()) {
                var y = cloud.y - 0.2
                while (y > pos.y + 1) {
                    val ship = shipBlockAt(level, Vec3(x, y, z), ships)
                    if (ship != null) { corrode(e, level, ship.pos, ship.state, Vec3(x, y, z)); return }
                    y -= 0.5
                }
            }
            val state = level.getBlockState(pos)
            if (!state.fluidState.isEmpty) return
            corrode(e, level, pos.immutable(), state, Vec3(x, pos.y + 1.0, z))
        }

        private fun corrode(e: SignatureEntity, level: ServerLevel, pos: BlockPos, state: BlockState, at: Vec3) {
            val next: BlockState? = when {
                state.`is`(BlockTags.CROPS) -> {
                    val age = state.properties.firstOrNull { it is IntegerProperty && it.name == "age" } as? IntegerProperty
                    val now = age?.let { state.getValue(it) } ?: 0
                    if (age == null || now <= 0) Blocks.AIR.defaultBlockState() else state.setValue(age, maxOf(0, now - 2))
                }
                state.`is`(BlockTags.LEAVES) || state.`is`(BlockTags.FLOWERS) || state.`is`(BlockTags.SAPLINGS) ||
                    state.`is`(BlockTags.CAVE_VINES) || state.block in FLORA -> Blocks.AIR.defaultBlockState()
                state.`is`(Blocks.GRASS_BLOCK) || state.`is`(Blocks.MYCELIUM) || state.`is`(Blocks.PODZOL) ||
                    state.`is`(Blocks.MOSS_BLOCK) || state.`is`(Blocks.FARMLAND) -> Blocks.DIRT.defaultBlockState()
                state.block is WeatheringCopper -> WeatheringCopper.getNext(state.block).map { it.withPropertiesOf(state) }.orElse(null)
                else -> null
            }
            level.sendParticles(DustParticleOptions(ACID, 1.1f), at.x, at.y + 0.1, at.z, 3, 0.2, 0.05, 0.2, 0.0)
            if (next == null) return
            if (SignatureKit.setBlock(e, pos, next)) {
                level.sendParticles(ParticleTypes.SMOKE, at.x, at.y + 0.2, at.z, 5, 0.25, 0.1, 0.25, 0.01)
                SignatureKit.sound(level, at, SoundEvents.FIRE_EXTINGUISH, 0.5f, 1.6f + level.random.nextFloat() * 0.3f)
            }
        }
    }

    object StormCloud : Cloud() {
        override val range = 72.0
        override val height = 15.0
        override val fall = 34.0
        private const val BASE_LIFE = 240
        private const val POWER_LIFE = 200
        private const val STRIKE_EVERY = 14
        private const val TWICE = 0.7f
        private const val SOAK_EVERY = 10
        private const val BOLT_DAMAGE = 15f
        private const val BOLT_POWER = 20f
        private const val CHAIN_DAMAGE = 10f
        private const val CHAIN_POWER = 10f
        private const val CHAIN_RANGE = 9.0
        private const val CHAIN_LINKS = 6
        private const val THROW = 0.9
        private const val SHIP_KNOCK = 1.4
        const val BOLT = 0x9FC8FF

        override fun radius(power: Float): Double = 9.0 + 7.0 * power

        override fun start(cast: SignatureCast): SignatureEntity {
            val e = form(cast, BASE_LIFE + (POWER_LIFE * cast.power).toInt())
            SignatureKit.sound(cast.level, e.position(), SoundEvents.LIGHTNING_BOLT_THUNDER, 3f, 0.8f)
            SignatureKit.sound(cast.level, e.point, SoundEvents.WEATHER_RAIN_ABOVE, 1.5f, 0.6f)
            return e
        }

        override fun tick(e: SignatureEntity) {
            drift(e)
            if (!raining(e, e.tickCount)) return
            val level = e.level() as ServerLevel
            val cloud = e.position()
            val beneath = beneath(e, cloud, rainReach(e.power), fall)
            if (e.tickCount % SOAK_EVERY == 0) soak(e, level, cloud)
            if (e.tickCount % PULSE_EVERY == 0) for (target in beneath) {
                SignatureKit.hurtThrough(e, target, EKDamageSources.COLD_KEY, pulse(e.power))
                level.playSound(null, target.x, target.y + target.bbHeight, target.z, SoundEvents.GLASS_HIT, SoundSource.WEATHER, 0.8f, 1.6f + level.random.nextFloat() * 0.3f)
            }
            if (e.tickCount % STRIKE_EVERY == 0) {
                strike(e, level, beneath, beneath.getOrNull(0))
                if (e.power >= TWICE) strike(e, level, beneath, beneath.getOrNull(1))
            }
            if (e.tickCount % 60 == 0) SignatureKit.sound(level, cloud, SoundEvents.LIGHTNING_BOLT_THUNDER, 1.2f, 0.5f + level.random.nextFloat() * 0.3f)
        }

        private fun soak(e: SignatureEntity, level: ServerLevel, cloud: Vec3) {
            val reach = rainReach(e.power)
            val ri = Math.ceil(reach).toInt()
            val pos = BlockPos.MutableBlockPos()
            var doused = 0
            for (dx in -ri..ri) for (dz in -ri..ri) {
                val x = Math.floor(cloud.x).toInt() + dx; val z = Math.floor(cloud.z).toInt() + dz
                if (horizontal(Vec3(x + 0.5, 0.0, z + 0.5), cloud) > reach) continue
                val top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z)
                if (top > cloud.y) continue
                pos.set(x, top, z)
                if (level.getBlockState(pos).`is`(BlockTags.FIRE) && douse(e, level, pos)) doused++
            }
            val ships = shipsNear(level, cloud.subtract(0.0, fall / 2, 0.0), maxOf(reach, fall / 2))
            for (b in shipBlocksUnder(level, ships) { it.`is`(BlockTags.FIRE) }) {
                if (horizontal(b.world, cloud) > reach || b.world.y > cloud.y || b.world.y < cloud.y - fall) continue
                if (douse(e, level, b.pos)) doused++
            }
            if (doused > 0) SignatureKit.sound(level, cloud.subtract(0.0, height, 0.0), SoundEvents.FIRE_EXTINGUISH, 1.5f, 1f)
            val box = AABB(cloud, cloud).inflate(reach, 0.0, reach).expandTowards(0.0, -fall, 0.0)
            for (target in level.getEntitiesOfClass(LivingEntity::class.java, box) { it.isAlive && !it.isSpectator && horizontal(it.position(), cloud) <= reach }) {
                if (target.isOnFire) {
                    target.clearFire()
                    level.sendParticles(ParticleTypes.CLOUD, target.x, target.y + target.bbHeight * 0.5, target.z, 6, 0.3, 0.3, 0.3, 0.02)
                }
                if (target !== e.caster) target.ticksFrozen = minOf(target.ticksFrozen + 20, target.ticksRequiredToFreeze + 40)
            }
        }

        private fun douse(e: SignatureEntity, level: ServerLevel, pos: BlockPos): Boolean {
            if (!SignatureKit.setBlock(e, pos.immutable(), Blocks.AIR.defaultBlockState())) return false
            level.sendParticles(ParticleTypes.SMOKE, pos.x + 0.5, pos.y + 0.3, pos.z + 0.5, 4, 0.25, 0.1, 0.25, 0.01)
            return true
        }

        private fun strike(e: SignatureEntity, level: ServerLevel, beneath: List<LivingEntity>, first: LivingEntity?) {
            val cloud = e.position()
            val column = first?.position() ?: run {
                val a = level.random.nextDouble() * Math.PI * 2
                val r = radius(e.power) * 0.8 * sqrt(level.random.nextDouble())
                cloud.add(cos(a) * r, 0.0, sin(a) * r)
            }
            val top = Vec3(column.x, cloud.y - 0.2, column.z)
            val struck = downColumn(level, column.x, column.z, top.y, fall, first)
            val at = struck?.at ?: Vec3(column.x, top.y - fall, column.z)
            // No fire: the cloud's own rain puts it out at once.
            struck?.ship?.let { ship -> SignatureShips.push(ship.ship, at, Vec3(0.0, -SHIP_KNOCK, 0.0)) }
            SpellVfx.arc(level, top, at, BOLT, 8)
            SpellVfx.flash(level, at, 2.4, 0xE4F0FF, 8)
            SignatureKit.particles(level, ParticleTypes.ELECTRIC_SPARK, at, 30, 0.6, 0.4)
            SignatureKit.sound(level, at, SoundEvents.LIGHTNING_BOLT_IMPACT, 2.5f, 1f)
            SignatureKit.sound(level, top, SoundEvents.LIGHTNING_BOLT_THUNDER, 2.5f, 1.1f + level.random.nextFloat() * 0.3f)
            val hit = struck?.creature ?: return
            SignatureKit.hurtThrough(e, hit, DamageTypes.LIGHTNING_BOLT, BOLT_DAMAGE + BOLT_POWER * e.power)
            val out = Vec3(hit.x - cloud.x, 0.0, hit.z - cloud.z).let { if (it.lengthSqr() < 1.0e-4) Vec3(1.0, 0.0, 0.0) else it.normalize() }
            SignatureKit.push(hit, out.scale(THROW).add(0.0, 0.45, 0.0))
            val left = beneath.filter { it !== hit }.toMutableList()
            var from = SignatureKit.middle(hit)
            repeat(CHAIN_LINKS) {
                val next = left.minByOrNull { SignatureKit.middle(it).distanceToSqr(from) } ?: return
                val to = SignatureKit.middle(next)
                if (to.distanceTo(from) > CHAIN_RANGE) return
                left.remove(next)
                SpellVfx.arc(level, from, to, BOLT, 7)
                SignatureKit.hurtThrough(e, next, DamageTypes.LIGHTNING_BOLT, CHAIN_DAMAGE + CHAIN_POWER * e.power)
                from = to
            }
        }
    }

    object GustOfGlass : SignatureBehaviour() {
        const val RANGE = 60.0
        const val LIFE = 160
        const val BREAK = 16
        private const val JOSTLE = 0.6
        private const val BURST = 1.6
        private const val SHATTER = 15f
        private const val SHATTER_POWER = 20f
        private const val THROW = 1.2
        private const val DEFLECT_AGAIN = 6

        fun radius(power: Float): Double = 3.5 + 2.5 * power

        override fun start(cast: SignatureCast): SignatureEntity {
            val centre = placed(cast, RANGE, 2.5)
            SignatureKit.sound(cast.level, centre, EKSounds.AEGIS_RAISE.get(), 2f, 0.8f)
            SignatureKit.sound(cast.level, centre, SoundEvents.AMETHYST_CLUSTER_BREAK, 2f, 1.4f)
            return spawn(cast, centre, LIFE)
        }

        @Suppress("UNCHECKED_CAST") // This behaviour stores only HashMap<Int, Int> in the untyped e.state.
        override fun tick(e: SignatureEntity) {
            val level = e.level() as ServerLevel
            val centre = e.position()
            val r = radius(e.power)
            if (e.tickCount == LIFE - BREAK) shatter(e, level, centre, r)
            if (e.tickCount < 6 || e.tickCount >= LIFE - BREAK) return
            val random = level.random
            if (e.tickCount % PULSE_EVERY == 0) for (target in SignatureKit.creatures(e, centre, r + 0.3)) {
                if (!clear(e, centre, SignatureKit.middle(target))) continue
                SignatureKit.hurtThrough(e, target, EKDamageSources.CRYSTALLIZATION_KEY, pulse(e.power))
                SignatureKit.push(target, Vec3((random.nextDouble() - 0.5) * 2 * JOSTLE, 0.2, (random.nextDouble() - 0.5) * 2 * JOSTLE))
            }
            val deflected = e.state as? HashMap<Int, Int> ?: HashMap<Int, Int>().also { e.state = it }
            for (shot in level.getEntitiesOfClass(Projectile::class.java, AABB(centre, centre).inflate(r))) {
                if (shot.position().distanceTo(centre) > r) continue
                val last = deflected[shot.id]
                if (last != null && e.tickCount - last < DEFLECT_AGAIN) continue
                val speed = shot.deltaMovement.length()
                if (speed < 0.05) continue
                val dir = Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian()).normalize()
                shot.deltaMovement = dir.scale(speed)
                shot.hurtMarked = true
                deflected[shot.id] = e.tickCount
                SignatureKit.sound(level, shot.position(), EKSounds.AEGIS_DEFLECT.get(), 1.4f, 1.2f + random.nextFloat() * 0.4f)
            }
            if (e.tickCount % 8 == 0) SignatureKit.sound(level, centre, SoundEvents.AMETHYST_BLOCK_CHIME, 1.4f, 1.5f + random.nextFloat() * 0.4f)
        }

        private fun shatter(e: SignatureEntity, level: ServerLevel, centre: Vec3, r: Double) {
            SignatureKit.sound(level, centre, EKSounds.AEGIS_SHATTER.get(), 2.5f, 0.9f)
            SignatureKit.sound(level, centre, SoundEvents.GLASS_BREAK, 2.5f, 0.7f)
            level.sendParticles(BlockParticleOption(ParticleTypes.BLOCK, Blocks.GLASS.defaultBlockState()), centre.x, centre.y, centre.z, 120, r * 0.6, r * 0.6, r * 0.6, 0.3)
            SignatureKit.particles(level, ParticleTypes.END_ROD, centre, 60, r * 0.5, 0.25)
            for (target in SignatureKit.creatures(e, centre, r * BURST)) {
                if (!clear(e, centre, SignatureKit.middle(target))) continue
                SignatureKit.hurtThrough(e, target, EKDamageSources.CRYSTALLIZATION_KEY, SHATTER + SHATTER_POWER * e.power)
                val out = SignatureKit.middle(target).subtract(centre).let { if (it.lengthSqr() < 1.0e-4) Vec3(0.0, 1.0, 0.0) else it.normalize() }
                SignatureKit.push(target, out.scale(THROW).add(0.0, 0.3, 0.0))
            }
        }
    }

    // SignatureEntity.point is the pinned foot; SignatureEntity.data is the face direction in milliradians about the vertical.
    object NetherRift : SignatureBehaviour() {
        const val RANGE = 60.0
        const val LIFE = 280
        const val HEIGHT = 8.0
        const val HALF_WIDTH = 2.0
        const val OPEN = 12
        const val CLOSE = 260
        const val SPILL_FROM = 8
        const val SPILL_TO = 260
        const val REACH = 10.0
        private const val FACE_DEPTH = 0.6
        private const val BURN_EVERY = 10
        private const val BURN = 10f
        private const val BURN_SECONDS = 20
        private const val IGNITE_EVERY = 40
        private const val FIRE_EVERY = 8
        private const val VOLLEY_POWER = 2
        private const val FIREBALL_GLYPHS = 4
        private const val FIREBALL_SPEED = 0.8f
        private val LOB_SPREAD = Math.toRadians(55.0)
        private val LOB_LOW = Math.toRadians(8.0)
        private val LOB_HIGH = Math.toRadians(40.0)
        // Launch past the face and past the pull that a bolt has on near entities.
        private const val LAUNCH = HALF_WIDTH + 3.0
        private const val LAUNCH_SPACING = 1.5
        private const val SAFE_SEARCH = 16
        private const val RETRY = 40
        private val FIREBALL = Spell(List(FIREBALL_GLYPHS) { Element.HEAT })

        private class State {
            val failed = HashMap<Int, Int>()
        }

        fun facing(e: SignatureEntity): Vec3 {
            val a = e.data / 1000.0
            return Vec3(cos(a), 0.0, sin(a))
        }

        override fun start(cast: SignatureCast): SignatureEntity {
            val aimed = cast.aimed(RANGE)
            val at = aimed.entity?.position() ?: aimed.point.let { p ->
                if (aimed.block == null) p else p.add(cast.caster.eyePosition.subtract(p).normalize().scale(HALF_WIDTH + 0.5))
            }
            val ground = SignatureKit.groundNear(cast.level, at, 1, 6)
            val foot = if (ground != null) Vec3(at.x, ground + 0.1, at.z) else at.subtract(0.0, HEIGHT / 2, 0.0)
            val look = cast.caster.lookAngle.let { Vec3(-it.x, 0.0, -it.z) }.let { if (it.lengthSqr() < 1.0e-6) Vec3(1.0, 0.0, 0.0) else it.normalize() }
            SignatureKit.sound(cast.level, foot, SoundEvents.RESPAWN_ANCHOR_CHARGE, 3f, 0.5f)
            SignatureKit.sound(cast.level, foot, SoundEvents.PORTAL_TRIGGER, 1.5f, 1.2f)
            SignatureKit.sound(cast.level, foot, SoundEvents.GENERIC_EXPLODE, 2f, 0.6f)
            SignatureKit.sound(cast.level, foot, SoundEvents.BLAZE_SHOOT, 2.5f, 0.5f)
            return spawn(cast, foot, LIFE) {
                point = foot
                data = Math.round(Math.atan2(look.z, look.x) * 1000).toInt()
            }
        }

        override fun tick(e: SignatureEntity) {
            // Pin it: a Heat bolt pulls near entities along its path, the rift too.
            e.deltaMovement = Vec3.ZERO
            if (e.position().distanceToSqr(e.point) > 1.0e-6) e.setPos(e.point)
            val level = e.level() as ServerLevel
            val state = e.state as? State ?: State().also { e.state = it }
            val foot = e.point
            val mid = foot.add(0.0, HEIGHT / 2, 0.0)
            if (e.tickCount % 8 == 0) SignatureKit.sound(level, mid, SoundEvents.PORTAL_AMBIENT, 2f, 0.8f)
            if (e.tickCount in OPEN..CLOSE) transport(e, level, foot, state)
            if (e.tickCount < SPILL_FROM || e.tickCount > SPILL_TO) return
            if (e.tickCount % 5 == 0) SignatureKit.sound(level, mid, SoundEvents.FIRE_AMBIENT, 3f, 0.6f + level.random.nextFloat() * 0.4f)
            if (e.tickCount % 7 == 0) SignatureKit.sound(level, mid, SoundEvents.LAVA_POP, 2.5f, 0.6f + level.random.nextFloat() * 0.4f)
            if (e.tickCount % BURN_EVERY == 0) {
                for (target in SignatureKit.creatures(e, foot.add(0.0, 1.0, 0.0), REACH)) {
                    target.setSecondsOnFire(BURN_SECONDS)
                    SignatureKit.hurtThrough(e, target, EKDamageSources.HEAT_KEY, BURN)
                }
                SignatureKit.sound(level, mid, SoundEvents.FIRECHARGE_USE, 2.5f, 0.7f + level.random.nextFloat() * 0.3f)
                level.sendParticles(ParticleTypes.FLAME, mid.x, mid.y, mid.z, 80, REACH * 0.4, 1.5, REACH * 0.4, 0.08)
            }
            if (e.tickCount % FIRE_EVERY == 0) fireballs(e, level, mid, state)
            if (e.tickCount % IGNITE_EVERY == 0) {
                val deck = shipBlocksNear(level, foot.add(0.0, 0.5, 0.0), REACH)
                if (deck.isNotEmpty() && level.random.nextBoolean()) ignite(e, deck[level.random.nextInt(deck.size)].pos.above())
                else {
                    val a = level.random.nextDouble() * Math.PI * 2
                    val r = REACH * sqrt(level.random.nextDouble())
                    val x = foot.x + cos(a) * r; val z = foot.z + sin(a) * r
                    SignatureKit.ground(level, x, z, foot.y + 3.5, 8)?.let { ignite(e, BlockPos.containing(x, it + 0.01, z)) } ?: false
                }
            }
        }

        private fun fireballs(e: SignatureEntity, level: ServerLevel, mid: Vec3, state: State) {
            val caster = e.caster ?: return
            val random = level.random
            val face = facing(e)
            val volley = 1 + (VOLLEY_POWER * e.power).toInt()
            repeat(volley) { k ->
                val out = if (random.nextBoolean()) face else face.scale(-1.0)
                val yaw = (random.nextDouble() * 2 - 1) * LOB_SPREAD
                val flat = Vec3(out.x * cos(yaw) - out.z * sin(yaw), 0.0, out.x * sin(yaw) + out.z * cos(yaw))
                val pitch = LOB_LOW + random.nextDouble() * (LOB_HIGH - LOB_LOW)
                val dir = flat.scale(cos(pitch)).add(0.0, sin(pitch), 0.0).normalize()
                val from = mid.add(flat.scale(LAUNCH)).add(0.0, (k - (volley - 1) / 2.0) * LAUNCH_SPACING, 0.0)
                if (!clear(e, mid, from)) return@repeat
                val bolt = SpellBoltEntity(level, caster, FIREBALL, from, SpellCasting.boltPower(maxOf(e.power, 0.5f)))
                // No surface fire: the rift lights its own flames.
                bolt.surfaceFire = false
                bolt.shoot(dir.x, dir.y, dir.z, FIREBALL_SPEED * (0.75f + 0.35f * random.nextFloat()), 0f)
                level.addFreshEntity(bolt)
            }
            SignatureKit.sound(level, mid, SoundEvents.GHAST_SHOOT, 2f, 0.8f + random.nextFloat() * 0.3f)
            SignatureKit.sound(level, mid, SoundEvents.BLAZE_SHOOT, 2f, 0.6f)
        }

        private fun transport(e: SignatureEntity, level: ServerLevel, foot: Vec3, state: State) {
            val to = when (level.dimension()) {
                Level.OVERWORLD -> level.server.getLevel(Level.NETHER)
                Level.NETHER -> level.server.getLevel(Level.OVERWORLD)
                else -> null
            } ?: return
            val n = facing(e)
            val across = Vec3(-n.z, 0.0, n.x)
            val box = AABB(foot, foot.add(0.0, HEIGHT, 0.0)).inflate(HALF_WIDTH + 1.0, 0.0, HALF_WIDTH + 1.0)
            val touching = level.getEntities(e, box) {
                if (!(it is LivingEntity || it is ItemEntity) || it is Projectile || !it.isAlive || it.isSpectator || it.isPassenger) return@getEntities false
                if (it.isOnPortalCooldown || !it.canChangeDimensions()) return@getEntities false
                val rel = it.position().subtract(foot)
                Math.abs(rel.dot(n)) <= FACE_DEPTH + it.bbWidth / 2 && Math.abs(rel.dot(across)) <= HALF_WIDTH * 0.8
            }
            for (entity in touching) {
                val last = state.failed[entity.id]
                if (last != null && e.tickCount - last < RETRY) continue
                val scale = DimensionType.getTeleportationScale(level.dimensionType(), to.dimensionType())
                val spot = safeSpot(to, Math.floor(entity.x * scale).toInt(), Math.floor(entity.z * scale).toInt())
                if (spot == null) { state.failed[entity.id] = e.tickCount; continue }
                SignatureKit.sound(level, entity.position(), SoundEvents.ENDERMAN_TELEPORT, 1.5f, 0.6f)
                level.sendParticles(ParticleTypes.PORTAL, entity.x, entity.y + entity.bbHeight / 2, entity.z, 40, 0.4, 0.6, 0.4, 0.5)
                // Set before teleport so a non-player copy keeps it; a player is not copied, so set it again after.
                entity.setPortalCooldown()
                entity.teleportTo(to, spot.x, spot.y, spot.z, emptySet(), entity.yRot, entity.xRot)
                if (entity is ServerPlayer) entity.setPortalCooldown()
                SignatureKit.sound(to, spot, SoundEvents.PORTAL_TRIGGER, 0.8f, 1.6f)
                to.sendParticles(ParticleTypes.PORTAL, spot.x, spot.y + 1.0, spot.z, 40, 0.4, 0.6, 0.4, 0.5)
            }
        }

        private fun safeSpot(level: ServerLevel, x: Int, z: Int): Vec3? {
            val border = level.worldBorder
            val pos = BlockPos.MutableBlockPos()
            val nether = level.dimension() == Level.NETHER
            for (ring in 0..SAFE_SEARCH step 2) for (dx in -ring..ring step 2) for (dz in -ring..ring step 2) {
                if (maxOf(Math.abs(dx), Math.abs(dz)) != ring) continue
                val bx = x + dx; val bz = z + dz
                if (!border.isWithinBounds(BlockPos(bx, 0, bz))) continue
                level.getChunk(bx shr 4, bz shr 4)
                val top = if (nether) minOf(level.minBuildHeight + level.dimensionType().logicalHeight() - 8, level.maxBuildHeight - 2) else level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz)
                var y = top
                while (y > level.minBuildHeight + 1) {
                    pos.set(bx, y, bz)
                    if (standable(level, pos)) return Vec3(bx + 0.5, y.toDouble(), bz + 0.5)
                    if (!nether) break
                    y--
                }
            }
            return null
        }

        private fun standable(level: ServerLevel, pos: BlockPos): Boolean {
            val feet = level.getBlockState(pos); val head = level.getBlockState(pos.above()); val under = level.getBlockState(pos.below())
            if (!feet.getCollisionShape(level, pos).isEmpty || !feet.fluidState.isEmpty || feet.`is`(BlockTags.FIRE)) return false
            if (!head.getCollisionShape(level, pos.above()).isEmpty || !head.fluidState.isEmpty) return false
            if (!under.isFaceSturdy(level, pos.below(), Direction.UP)) return false
            return !under.`is`(Blocks.MAGMA_BLOCK) && !under.`is`(Blocks.CAMPFIRE) && !under.`is`(Blocks.SOUL_CAMPFIRE) && under.fluidState.isEmpty
        }

        override fun end(e: SignatureEntity) {
            val level = e.level() as ServerLevel
            SignatureKit.sound(level, e.position(), SoundEvents.FIRE_EXTINGUISH, 2.5f, 0.6f)
        }
    }
}
