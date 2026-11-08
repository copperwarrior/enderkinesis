package org.shipwrights.enderkinesis.spell

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import dev.architectury.event.EventResult
import dev.architectury.event.events.common.BlockEvent
import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.TickEvent
import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.core.Holder
import net.minecraft.tags.BiomeTags
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.biome.Biome
import net.minecraft.tags.DamageTypeTags
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.block.CropBlock
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.TagKey
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.dimension.EKDamageSources
import org.shipwrights.enderkinesis.dimension.SselithRepertory
import org.shipwrights.enderkinesis.dimension.YgannAbyss
import org.shipwrights.enderkinesis.item.UlderScrollItem
import org.shipwrights.enderkinesis.ritual.SunErasure
import org.valkyrienskies.mod.common.BlockStateInfo
import org.valkyrienskies.mod.common.getShipsIntersecting
import java.util.Locale
import kotlin.math.sqrt

typealias ElementReasons = MutableMap<Element, MutableList<String>>

/** Packs add blocks to an element with the tag `enderkinesis:elemental_environment/<id>`. */
object ElementalEnvironment {
    const val RADIUS = 4
    // About a quarter of the blocks in [RADIUS].
    const val FULL = 24.0
    private const val RESAMPLE_TICKS = 10

    // Declared before TAGS: object properties initialize in order.
    private val NOT_FROM_BLOCKS = setOf(Element.SUNDERING, Element.QUINTESSENCE, Element.SUNFIRE)

    private val TAGS: Map<Element, TagKey<net.minecraft.world.level.block.Block>> =
        Element.entries.filter { it !in NOT_FROM_BLOCKS }
            .associateWith { TagKey.create(Registries.BLOCK, EnderkinesisMod.id("elemental_environment/${it.id}")) }

    private class Sample(val tick: Long, val values: Map<Element, Float>)

    // Server thread only. A held beam asks every two ticks, so keep each sample for a short time.
    private val byEntity = HashMap<Int, Sample>()

    fun of(level: ServerLevel, entity: Entity): Map<Element, Float> {
        val now = level.gameTime
        byEntity[entity.id]?.takeIf { now - it.tick < RESAMPLE_TICKS }?.let { return it.values }
        if (byEntity.size > 512) byEntity.entries.removeIf { now - it.value.tick >= RESAMPLE_TICKS }
        val values = sample(level, entity.position().add(0.0, entity.bbHeight * 0.5, 0.0))
        addEntitySources(level, entity, values)
        addQuintessence(values)
        byEntity[entity.id] = Sample(now, values)
        return values
    }

    fun at(level: ServerLevel, centre: Vec3): Map<Element, Float> = sample(level, centre).also { addQuintessence(it) }

    /** A fresh sample of [entity] with the sources of each element, for `/enderkinesis elements`. */
    fun explain(level: ServerLevel, entity: Entity): Pair<Map<Element, Float>, Map<Element, List<String>>> {
        val why: ElementReasons = HashMap()
        val values = sample(level, entity.position().add(0.0, entity.bbHeight * 0.5, 0.0), why)
        addEntityState(level, entity, values, why)
        deeds[entity.id]?.let { addDeeds(it, level.gameTime, values, why) }
        addQuintessence(values, why)
        return values to why
    }

    /** Quintessence is the absence of all other elements, so call this after every other source. */
    fun addQuintessence(values: MutableMap<Element, Float>, why: ElementReasons? = null) {
        if (values.isEmpty()) add(values, Element.QUINTESSENCE, SOURCE, why, "no other element")
    }

    private fun add(values: MutableMap<Element, Float>, element: Element, value: Float) {
        values[element] = ((values[element] ?: 0f) + value).coerceAtMost(1f)
    }

    private fun add(
        values: MutableMap<Element, Float>, element: Element, value: Float, why: ElementReasons?, reason: String,
    ) {
        add(values, element, value)
        why?.getOrPut(element, ::ArrayList)?.add(reason)
    }

    private fun sample(level: ServerLevel, centre: Vec3, why: ElementReasons? = null): HashMap<Element, Float> {
        val sums = DoubleArray(Element.entries.size)
        val ships = level.getShipsIntersecting(AABB(centre, centre).inflate(RADIUS + 1.0)).toList()
        val origin = BlockPos.containing(centre)
        val pos = BlockPos.MutableBlockPos()
        val shipPoint = Vector3d()
        val byBlock = if (why == null) null else HashMap<Element, HashMap<Block, Double>>()
        fun count(state: BlockState, weight: Double) {
            if (state.isAir) return
            for (element in TAGS.keys) {
                val part = weight * share(state, element)
                sums[element.ordinal] += part
                if (byBlock != null && part > 0.0) {
                    byBlock.getOrPut(element, ::HashMap).merge(state.block, part) { a, b -> a + b }
                }
            }
        }
        for (dx in -RADIUS..RADIUS) for (dy in -RADIUS..RADIUS) for (dz in -RADIUS..RADIUS) {
            val weight = weight(dx, dy, dz)
            if (weight <= 0.0) continue
            pos.set(origin.x + dx, origin.y + dy, origin.z + dz)
            count(level.getBlockState(pos), weight)
            for (ship in ships) {
                ship.transform.worldToShip.transformPosition(origin.x + dx + 0.5, origin.y + dy + 0.5, origin.z + dz + 0.5, shipPoint)
                pos.set(shipPoint.x, shipPoint.y, shipPoint.z)
                count(level.getBlockState(pos), weight)
            }
        }
        val out = HashMap<Element, Float>()
        for (element in TAGS.keys) {
            val value = (sums[element.ordinal] / FULL).coerceAtMost(1.0).toFloat()
            if (value > 0f) out[element] = value
        }
        byBlock?.forEach { (element, blocks) -> why?.put(element, mutableListOf(blockReason(blocks))) }
        addPlaceSources(level, origin, out, why)
        return out
    }

    private fun blockReason(blocks: Map<Block, Double>): String =
        "blocks: " + blocks.entries.sortedByDescending { it.value }.take(REASON_BLOCKS).joinToString { (block, part) ->
            "%s %.2f".format(Locale.ROOT, BuiltInRegistries.BLOCK.getKey(block).path, part / FULL)
        }

    /** The sources at [origin] that are not blocks. Both sides can read them. */
    fun addPlaceSources(level: Level, origin: BlockPos, out: MutableMap<Element, Float>, why: ElementReasons? = null) {
        val sun = sunlight(level, origin.above())
        if (sun > 0f) add(out, Element.SUNFIRE, SUN_SKY * sun, why, "daylight or Sselith light")
        if (level.getBiome(origin).value().coldEnoughToSnow(origin)) add(out, Element.WINTER, SOURCE, why, "cold biome")
        if (inVoid(level, origin)) add(out, Element.VOID, SOURCE, why, "the End, Ygann or the void biome")
        if (wet(level.getBiome(origin))) add(out, Element.CORRUPTION, SOURCE, why, "wet biome")
    }

    // 0 to 1: the sine of the sun's height, so 1 at noon and 0 at the horizon.
    private fun sunlight(level: Level, pos: BlockPos): Float {
        if (level.isRaining || level.isThundering || !level.canSeeSky(pos)) return 0f
        // Sselith has a fixed time and a constant light, so isDay is always false there.
        if (level.dimension() == SselithRepertory.LEVEL_KEY) return 1f
        if (!isDay(level) || SunErasure.erased(level)) return 0f
        return Mth.cos(level.getTimeOfDay(1f) * Mth.TWO_PI).coerceAtLeast(0f)
    }

    // Level.isDay reads skyDarken, which ClientLevel sets only in its constructor; this is updateSkyBrightness's sum.
    private fun isDay(level: Level): Boolean {
        if (level.dimensionType().hasFixedTime()) return false
        val rain = 1.0 - (level.getRainLevel(1f) * WEATHER_DIM) / WEATHER_DIM_DIVISOR
        val thunder = 1.0 - (level.getThunderLevel(1f) * WEATHER_DIM) / WEATHER_DIM_DIVISOR
        val sun = 0.5 + 2.0 * Mth.clamp(Mth.cos(level.getTimeOfDay(1f) * Mth.TWO_PI).toDouble(), -0.25, 0.25)
        return ((1.0 - sun * rain * thunder) * SKY_DARKEN_RANGE).toInt() < DAY_SKY_DARKEN
    }

    // Vanilla oceans and rivers have an ordinary downfall of 0.5.
    private fun wet(biome: Holder<Biome>): Boolean =
        BiomeClimate.downfall(biome.value()) >= WET_DOWNFALL ||
            biome.`is`(BiomeTags.IS_OCEAN) || biome.`is`(BiomeTags.IS_RIVER)

    private fun inVoid(level: Level, pos: BlockPos): Boolean =
        level.dimension() == Level.END || level.dimension() == YgannAbyss.LEVEL_KEY ||
            level.getBiome(pos).`is`(Biomes.THE_VOID)

    /** The sources in the state of [entity]. Both sides can read them. Deeds are in [addDeeds]. */
    fun addEntityState(level: Level, entity: Entity, values: MutableMap<Element, Float>, why: ElementReasons? = null) {
        if (entity.isOnFire || entity.isInLava) add(values, Element.PYRE, SOURCE, why, "on fire or in lava")
        if (!entity.onGround() && entity.fallDistance > FREE_FALL) {
            add(values, Element.GRAVITY, SOURCE, why, "free fall")
        }
        if (entity.isInPowderSnow || entity.wasInPowderSnow ||
            (entity.onGround() && level.getBlockState(entity.onPos).`is`(BlockTags.ICE))
        ) add(values, Element.WINTER, SOURCE, why, "powder snow or ice")
    }

    private fun addEntitySources(level: ServerLevel, entity: Entity, values: HashMap<Element, Float>) {
        addEntityState(level, entity, values)
        deeds[entity.id]?.let { addDeeds(it, level.gameTime, values) }
    }

    /** [times] holds the game time of the last deed for each element, by ordinal. */
    fun addDeeds(times: LongArray, now: Long, values: MutableMap<Element, Float>, why: ElementReasons? = null) {
        for (element in Element.entries) {
            val age = now - times[element.ordinal]
            if (age >= DEED_TICKS) continue
            add(values, element, SOURCE)
            why?.getOrPut(element, ::ArrayList)?.add("deed $age ticks ago")
        }
    }

    fun noDeeds(): LongArray = LongArray(Element.entries.size) { NEVER }

    // What one element source gives. CHARGE_THRESHOLD is lower, so one source recharges scrolls.
    private const val SOURCE = 0.5f
    private const val REASON_BLOCKS = 4
    private const val WET_DOWNFALL = 0.9f
    private const val WEATHER_DIM = 5f
    private const val WEATHER_DIM_DIVISOR = 16.0
    private const val SKY_DARKEN_RANGE = 11.0
    private const val DAY_SKY_DARKEN = 4
    private const val FREE_FALL = 3f
    private const val DEED_TICKS = 40L
    private const val NEVER = Long.MIN_VALUE / 2

    // Server thread only: entity id to the game time of its last deed, and of its last send, for each element.
    private val deeds = HashMap<Int, LongArray>()
    private val sent = HashMap<Int, LongArray>()

    /** A deed of [entity] that gives [element] for a short time: a bone meal use, a teleport, a hit. */
    @JvmStatic
    fun deed(entity: Entity, element: Element) {
        val level = entity.level() as? ServerLevel ?: return
        val now = level.gameTime
        if (deeds.size > MAX_TRACKED) {
            deeds.values.removeIf { times -> times.all { now - it >= DEED_TICKS } }
            sent.keys.retainAll(deeds.keys)
        }
        deeds.getOrPut(entity.id, ::noDeeds)[element.ordinal] = now
        if (entity !is ServerPlayer) return
        val sentTimes = sent.getOrPut(entity.id, ::noDeeds)
        if (now - sentTimes[element.ordinal] < RESEND_TICKS) return
        sentTimes[element.ordinal] = now
        sendDeed(entity, element)
    }

    val S2C_DEED: ResourceLocation = EnderkinesisMod.id("elemental_deed")

    // The client then sees a deed at most this late, so its Quintessence check stays close to the server.
    private const val RESEND_TICKS = 10L

    private fun sendDeed(player: ServerPlayer, element: Element) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeByte(element.ordinal)
        NetworkManager.sendToPlayer(player, S2C_DEED, buf)
    }

    private const val MAX_TRACKED = 512
    private const val TELEPORT_MIN_SQR = 4.0

    @JvmStatic
    fun teleported(entity: Entity, to: Level, x: Double, y: Double, z: Double) {
        if (entity.level() != to || entity.distanceToSqr(x, y, z) >= TELEPORT_MIN_SQR) deed(entity, Element.CHAOS)
    }

    @JvmStatic
    fun damaged(entity: LivingEntity, source: DamageSource) {
        deed(entity, Element.BLOOD)
        if (source.`is`(DamageTypeTags.IS_FALL)) deed(entity, Element.GRAVITY)
        if (source.`is`(EKDamageSources.ROT_KEY) || (source.`is`(DamageTypes.MAGIC) && entity.hasEffect(MobEffects.POISON))) {
            deed(entity, Element.CORRUPTION)
        }
    }

    fun weight(dx: Int, dy: Int, dz: Int): Double {
        val d = sqrt((dx * dx + dy * dy + dz * dz).toDouble())
        return if (d > RADIUS + 0.5) 0.0 else 1.0 - d / (RADIUS + 1)
    }

    fun givesElement(state: BlockState): Boolean {
        if (state.isAir) return false
        for (element in TAGS.keys) if (share(state, element) > 0.0) return true
        return false
    }

    fun share(state: BlockState, element: Element): Double {
        if (state.isAir) return 0.0
        val tag = TAGS[element] ?: return 0.0
        if (state.`is`(tag)) return 1.0
        if (element != Element.GRAVITY) return 0.0
        val stone = BlockStateInfo.get(Blocks.STONE.defaultBlockState())?.first ?: return 0.0
        if (stone <= 0.0) return 0.0
        val mass = BlockStateInfo.get(state)?.first ?: return 0.0
        return ((mass / stone - 1.0) / HEAVY_SPAN).coerceIn(0.0, 1.0)
    }

    private const val SUN_SKY = 0.5f

    // In multiples of the mass of stone.
    private const val HEAVY_SPAN = 1.0

    const val CHARGE_THRESHOLD = 0.3f

    const val RECHARGE_PER_SECOND = 1f / 600f

    // Charge comes in random bursts, with the same average rate.
    private const val RECHARGE_CHANCE = 0.1f

    fun rechargeRate(spell: Spell, environment: Map<Element, Float>): Float {
        val glyphs = spell.glyphs.size
        if (glyphs == 0) return 0f
        var rate = 0f
        for ((element, value) in environment) {
            if (value <= CHARGE_THRESHOLD) continue
            rate += RECHARGE_PER_SECOND * spell.magnitude(element) / glyphs
        }
        return rate
    }

    fun init() {
        TickEvent.SERVER_POST.register(TickEvent.Server { server ->
            if (server.tickCount % RECHARGE_TICKS != 0) return@Server
            for (player in server.playerList.players) rechargeCarried(player)
        })
        BlockEvent.BREAK.register { _, _, state, player, _ ->
            val block = state.block
            if (block is CropBlock && block.isMaxAge(state)) deed(player, Element.GENESIS)
            EventResult.pass()
        }
        LifecycleEvent.SERVER_STOPPING.register { reset() }
    }

    private const val RECHARGE_TICKS = 20

    private fun rechargeCarried(player: ServerPlayer) {
        val inventory = player.inventory
        var environment: Map<Element, Float>? = null
        for (i in 0 until inventory.containerSize) {
            val stack = inventory.getItem(i)
            if (stack.item !is UlderScrollItem || UlderScrollItem.spent(stack) <= 0f) continue
            val spell = UlderScrollItem.spellOf(stack) ?: continue
            val around = environment ?: of(player.serverLevel(), player).also { environment = it }
            if (around.values.none { it > CHARGE_THRESHOLD }) return
            val rate = rechargeRate(spell, around)
            if (rate > 0f && player.random.nextFloat() < RECHARGE_CHANCE) {
                UlderScrollItem.recharge(stack, rate * RECHARGE_TICKS / 20f / RECHARGE_CHANCE)
            }
        }
    }

    fun reset() {
        byEntity.clear()
        deeds.clear()
        sent.clear()
    }
}
