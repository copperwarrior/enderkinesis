package org.shipwrights.enderkinesis.ritual

import com.mojang.logging.LogUtils
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.TagKey
import net.minecraft.world.entity.EntityType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.Ingredient
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.Property
import net.minecraft.world.phys.Vec3
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.shipAwareCanSeeSky
import org.valkyrienskies.mod.common.toWorldCoordinates

class Ritual(
    val id: ResourceLocation,
    val center: CenterRule,
    layoutFactory: () -> RitualLayout?,
    val trigger: RitualTrigger,
    val conditions: List<RitualCondition>,
    val offerings: OfferingRule,
    val effect: RitualEffect,
) {
    /** Null when the template does not hold the center once. Lazy, because a tag center can be found only after tags bind. */
    val layout: RitualLayout? by lazy {
        layoutFactory().also { if (it == null) LogUtils.getLogger().warn("Ritual {} never fires: its template must hold the center block once", id) }
    }

    /** The matched rotation index, or -1 when the layout or a condition fails. The center is not checked. */
    fun match(level: ServerLevel, center: BlockPos): Int {
        val layout = layout ?: return -1
        val rotation = layout.match(level, center)
        if (rotation < 0) return -1
        val blocks = layout.rotations[rotation]
        return if (conditions.all { it.test(level, center, blocks) }) rotation else -1
    }
}

/**
 * Where a ritual fired: [rotation] indexes [RitualLayout.rotations]; [source] is the trigger point, such as a sacrifice.
 * [targetShip] is the ship that a ship trigger hit.
 */
class RitualContext(
    val level: ServerLevel, val ritual: Ritual, val center: BlockPos, val rotation: Int, val source: Vec3?,
    val player: ServerPlayer? = null, val targetShip: Long? = null,
) {
    val blocks: List<LayoutBlock> get() = checkNotNull(ritual.layout).rotations[rotation]
}

/** One [block], or any block in [tag]. [properties] apply only to [block]. */
class CenterRule(
    private val block: Block?, private val tag: TagKey<Block>?, private val properties: Map<Property<*>, Comparable<*>>,
) {
    val usesTag: Boolean get() = tag != null

    /** Template test: the block and listed properties only. */
    fun isTemplateCenter(state: BlockState): Boolean =
        if (tag != null) state.`is`(tag)
        else state.`is`(block!!) && properties.all { (property, value) -> state.getValue(property) == value }

    /** World test: as [isTemplateCenter], and lit if the block can be lit. */
    fun isLit(state: BlockState): Boolean =
        isTemplateCenter(state) && (!state.hasProperty(BlockStateProperties.LIT) || state.getValue(BlockStateProperties.LIT))
}

sealed interface RitualTrigger {
    /** A player lights the center block. */
    data object Light : RitualTrigger

    /** A [weapon] in the killer's main hand kills an [entity] within [radius] of a lit center. */
    class Kill(val entity: EntityMatcher, val weapon: Ingredient, val radius: Double) : RitualTrigger

    /** A player standing within [radius] of a lit center shoots a projectile within [maxAngle] degrees of the sun. */
    class ProjectileAtSun(val radius: Double, val maxAngle: Double) : RitualTrigger

    /**
     * A player standing within [radius] of a lit center shoots a projectile that hits a ship with mass. The hit must be
     * within [range] of the center on the level and at most [height] above it.
     */
    class ProjectileAtShip(val radius: Double, val range: Double, val height: Double) : RitualTrigger {
        init {
            require(radius > 0 && range > 0 && height > 0) { "projectile_at_ship needs a positive radius, range and height" }
        }

        fun inRange(center: Vec3, hit: Vec3): Boolean {
            val dx = hit.x - center.x
            val dz = hit.z - center.z
            return dx * dx + dz * dz <= range * range && hit.y - center.y <= height
        }
    }

    /** A player who has every one of [advancements] places the center block. */
    class Place(private val advancements: List<ResourceLocation>) : RitualTrigger {
        fun isKnownBy(player: ServerPlayer): Boolean = advancements.all { id ->
            val advancement = player.server.advancements.getAdvancement(id)
            advancement != null && player.advancements.getOrStartProgress(advancement).isDone
        }
    }
}

class EntityMatcher(private val type: EntityType<*>?, private val tag: TagKey<EntityType<*>>?) {
    fun test(type: EntityType<*>): Boolean = type == this.type || (tag != null && type.`is`(tag))
}

/** [blocks] are the matched rotation's template blocks, as offsets from [center]. */
sealed interface RitualCondition {
    fun test(level: ServerLevel, center: BlockPos, blocks: List<LayoutBlock>): Boolean

    data object Raining : RitualCondition {
        // As Level.isRainingAt, with the sky test and the biome taken at the world point, so a ship deck works.
        override fun test(level: ServerLevel, center: BlockPos, blocks: List<LayoutBlock>): Boolean {
            if (!level.isRaining || !shipAwareCanSeeSky(level, center.above())) return false
            val world = worldPos(level, center.above())
            return level.getBiome(world).value().getPrecipitationAt(world) == Biome.Precipitation.RAIN
        }
    }

    /** Refuses a center in a ship's shipyard. */
    data object NotOnShip : RitualCondition {
        override fun test(level: ServerLevel, center: BlockPos, blocks: List<LayoutBlock>) = level.getShipManagingPos(center) == null
    }

    class Dimension(private val key: ResourceKey<Level>) : RitualCondition {
        override fun test(level: ServerLevel, center: BlockPos, blocks: List<LayoutBlock>) = level.dimension() == key
    }

    /** The center and every template block see the sky. */
    data object OpenSky : RitualCondition {
        override fun test(level: ServerLevel, center: BlockPos, blocks: List<LayoutBlock>): Boolean {
            if (!shipAwareCanSeeSky(level, center)) return false
            val cursor = BlockPos.MutableBlockPos()
            return blocks.all { shipAwareCanSeeSky(level, cursor.setWithOffset(center, it.offset)) }
        }
    }

    class InBiome(private val biome: ResourceKey<Biome>?, private val tag: TagKey<Biome>?) : RitualCondition {
        override fun test(level: ServerLevel, center: BlockPos, blocks: List<LayoutBlock>): Boolean {
            val holder = level.getBiome(worldPos(level, center))
            return (biome != null && holder.`is`(biome)) || (tag != null && holder.`is`(tag))
        }
    }

    companion object {
        fun biomeTag(id: ResourceLocation): TagKey<Biome> = TagKey.create(Registries.BIOME, id)

        /** A shipyard pos has a garbage biome and sky: read them at the world point. */
        internal fun worldPos(level: ServerLevel, pos: BlockPos): BlockPos = BlockPos.containing(level.toWorldCoordinates(pos))
    }
}

/** What the pedestal takes, in order. A ritual with no offerings fires at once, with no pedestal. */
interface OfferingRule {
    val isEmpty: Boolean get() = false

    /** True when [stack] is the next offering after [offered]. */
    fun wants(offered: List<ItemStack>, stack: ItemStack): Boolean

    fun isComplete(offered: List<ItemStack>): Boolean
}

class FixedOfferings(private val items: List<Ingredient>) : OfferingRule {
    override val isEmpty: Boolean get() = items.isEmpty()

    override fun wants(offered: List<ItemStack>, stack: ItemStack): Boolean =
        items.getOrNull(offered.size)?.test(stack) == true

    override fun isComplete(offered: List<ItemStack>): Boolean = offered.size == items.size
}
