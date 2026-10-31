package org.shipwrights.enderkinesis.spell

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.TickEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.TagKey
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.item.MysticScrollItem
import org.valkyrienskies.mod.common.BlockStateInfo
import org.valkyrienskies.mod.common.getShipsIntersecting
import kotlin.math.sqrt

/**
 * Elemental environment: how much of each element surrounds a spot, from 0 to 1. Blocks within
 * [RADIUS] count, the nearer the more, ships' blocks as much as the world's. A block counts
 * toward an element when it's in that element's tag (`enderkinesis:elemental_environment/<id>`,
 * so packs can add to them); Gravity also counts blocks by how much heavier than stone VS2
 * says they are, and open sky in clear daylight counts toward Sunfire ([SUN_SKY]). [FULL]
 * weighted blocks make a full 1.
 *
 * A spell cast in its element's environment is stronger, by up to one magnitude
 * ([SpellContext.environment]), and a scroll carried through one recharges ([recharge]).
 */
object ElementalEnvironment {
    const val RADIUS = 4
    /** Weighted blocks for a full environment: about a quarter of the blocks round you. */
    const val FULL = 24.0
    /** How long a creature's environment is kept before it's looked at again, in ticks. */
    private const val RESAMPLE_TICKS = 10

    private val TAGS: Map<Element, TagKey<net.minecraft.world.level.block.Block>> =
        Element.entries.filter { it != Element.SUNDERING }
            .associateWith { TagKey.create(Registries.BLOCK, EnderkinesisMod.id("elemental_environment/${it.id}")) }

    private class Sample(val tick: Long, val values: Map<Element, Float>)

    // Server thread only. Kept briefly: a held beam asks every two ticks.
    private val byEntity = HashMap<Int, Sample>()

    /** [entity]'s environment, round its middle. */
    fun of(level: ServerLevel, entity: Entity): Map<Element, Float> {
        val now = level.gameTime
        byEntity[entity.id]?.takeIf { now - it.tick < RESAMPLE_TICKS }?.let { return it.values }
        if (byEntity.size > 512) byEntity.entries.removeIf { now - it.value.tick >= RESAMPLE_TICKS }
        val values = at(level, entity.position().add(0.0, entity.bbHeight * 0.5, 0.0))
        byEntity[entity.id] = Sample(now, values)
        return values
    }

    /** The environment round [centre]. */
    fun at(level: ServerLevel, centre: Vec3): Map<Element, Float> {
        val sums = DoubleArray(Element.entries.size)
        val ships = level.getShipsIntersecting(AABB(centre, centre).inflate(RADIUS + 1.0)).toList()
        val origin = BlockPos.containing(centre)
        val pos = BlockPos.MutableBlockPos()
        val shipPoint = Vector3d()
        fun count(state: BlockState, weight: Double) {
            if (state.isAir) return
            for (element in TAGS.keys) sums[element.ordinal] += weight * share(state, element)
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
        // Clear daylight on open sky is half a Sunfire environment by itself.
        if (level.isDay && !level.isRaining && level.canSeeSky(origin.above())) sums[Element.SUNFIRE.ordinal] += SUN_SKY * FULL
        val out = HashMap<Element, Float>()
        for (element in TAGS.keys) {
            val value = (sums[element.ordinal] / FULL).coerceAtMost(1.0).toFloat()
            if (value > 0f) out[element] = value
        }
        return out
    }

    /** How much a block [dx], [dy], [dz] from the centre's block counts: fully at the centre,
     *  less the further out, nothing past [RADIUS]. */
    fun weight(dx: Int, dy: Int, dz: Int): Double {
        val d = sqrt((dx * dx + dy * dy + dz * dz).toDouble())
        return if (d > RADIUS + 0.5) 0.0 else 1.0 - d / (RADIUS + 1)
    }

    /** How much [state] counts toward [element], 0 to 1: fully when it's in the element's tag,
     *  and toward Gravity, also by how much heavier than stone it is. */
    fun share(state: BlockState, element: Element): Double {
        if (state.isAir) return 0.0
        val tag = TAGS[element] ?: return 0.0
        if (state.`is`(tag)) return 1.0
        if (element != Element.GRAVITY) return 0.0
        val stone = BlockStateInfo.get(Blocks.STONE.defaultBlockState())?.first ?: return 0.0
        if (stone <= 0.0) return 0.0
        val mass = BlockStateInfo.get(state)?.first ?: return 0.0
        // Twice stone's mass and more counts fully; stone and lighter, not at all.
        return ((mass / stone - 1.0) / HEAVY_SPAN).coerceIn(0.0, 1.0)
    }

    /** How much of a full Sunfire environment standing under clear daylight gives. */
    private const val SUN_SKY = 0.5

    /** How much heavier than stone (as a multiple of stone's mass) a block must be to count
     *  fully toward Gravity. */
    private const val HEAVY_SPAN = 1.0

    // ---- Recharging ---------------------------------------------------------------------------

    /** The environment an element needs before its scrolls recharge in it. */
    const val CHARGE_THRESHOLD = 0.3f

    /** Uses a second a scroll wholly of one element regains in that element's environment, on
     *  average: one use in ten minutes. */
    const val RECHARGE_PER_SECOND = 1f / 600f

    /** The chance each second that a carried scroll takes in any charge at all. It's unreliable:
     *  most seconds nothing, now and then a few seconds' worth at once, the same on average. */
    private const val RECHARGE_CHANCE = 0.1f

    /**
     * How fast a scroll of [spell] recharges in [environment], in uses a second on average: for each
     * element past [CHARGE_THRESHOLD] round it, [RECHARGE_PER_SECOND] times that element's
     * share of the scroll's glyphs. A seven-Heat scroll by lava recharges at the full rate; one
     * Heat and six Purity, a seventh of it.
     */
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
        LifecycleEvent.SERVER_STOPPING.register { reset() }
    }

    /** Ticks between recharges of carried scrolls. */
    private const val RECHARGE_TICKS = 20

    /** Every scroll [player] carries regains what its share of their environment gives it. */
    private fun rechargeCarried(player: ServerPlayer) {
        val inventory = player.inventory
        var environment: Map<Element, Float>? = null
        for (i in 0 until inventory.containerSize) {
            val stack = inventory.getItem(i)
            if (stack.item !is MysticScrollItem || MysticScrollItem.spent(stack) <= 0f) continue
            val spell = MysticScrollItem.spellOf(stack) ?: continue
            // Looked at only once something carried needs it.
            val around = environment ?: of(player.serverLevel(), player).also { environment = it }
            if (around.values.none { it > CHARGE_THRESHOLD }) return
            val rate = rechargeRate(spell, around)
            if (rate > 0f && player.random.nextFloat() < RECHARGE_CHANCE) {
                MysticScrollItem.recharge(stack, rate * RECHARGE_TICKS / 20f / RECHARGE_CHANCE)
            }
        }
    }

    fun reset() = byEntity.clear()
}
