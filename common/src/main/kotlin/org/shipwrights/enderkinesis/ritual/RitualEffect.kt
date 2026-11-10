package org.shipwrights.enderkinesis.ritual

import com.google.gson.JsonObject
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.BlockTags
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.block.WohlonReachingRoot
import org.shipwrights.enderkinesis.block.WohlonRitualNetwork
import org.shipwrights.enderkinesis.block.WohlonRollingWaters
import org.shipwrights.enderkinesis.block.WohlonStaffRites
import org.shipwrights.enderkinesis.block.WohlonnogondoniaPortalManager
import org.shipwrights.enderkinesis.block.WohlonnogondoniaSpreader
import org.shipwrights.enderkinesis.block.WohlonnogondoniaTreeGrower
import org.shipwrights.enderkinesis.entity.WikLakConstruction
import org.shipwrights.enderkinesis.registry.EKSounds

/** Effects are code; a ritual JSON picks one by its "type" and gives its parameters. */
interface RitualEffect {
    /** Replaces the ritual's "offerings" list when the effect decides its own. */
    val offerings: OfferingRule? get() = null

    /** The item the ritual makes ([ItemStack.EMPTY] for none), or null when it has nothing to act on and gives the offerings back. */
    fun resolve(context: RitualContext, offered: List<ItemStack>): ItemStack?

    /** Template blocks that feed the pedestal in its visuals. */
    fun feedSlots(context: RitualContext): List<LayoutBlock> = emptyList()

    companion object {
        // Lambdas, not bound Foo::parse: a client init that touches an effect class first runs this during that class's
        // init (it implements this interface), and a bound reference would keep its still-null companion.
        private val TYPES: Map<ResourceLocation, (JsonObject) -> RitualEffect?> = mapOf(
            EnderkinesisMod.id("wohlon_portal") to { _ -> WohlonPortal },
            EnderkinesisMod.id("reaching_root") to { _ -> ReachingRoot },
            EnderkinesisMod.id("rolling_waters") to { _ -> RollingWaters },
            EnderkinesisMod.id("staff") to { json -> Staff.parse(json) },
            EnderkinesisMod.id("wik_lak_host") to { _ -> WikLakConstruction },
            EnderkinesisMod.id("set_weather") to { json -> SetWeather.parse(json) },
            EnderkinesisMod.id("weathering") to { json -> Weathering.parse(json) },
            EnderkinesisMod.id("erase_sun") to { _ -> EraseSun },
            EnderkinesisMod.id("conflagration") to { json -> Conflagration.parse(json) },
        )

        fun parse(json: JsonObject): RitualEffect? {
            val type = json.get("type")?.takeIf { it.isJsonPrimitive }?.asString?.let(ResourceLocation::tryParse) ?: return null
            return TYPES[type]?.invoke(json)
        }
    }
}

object WohlonPortal : RitualEffect {
    override fun resolve(context: RitualContext, offered: List<ItemStack>): ItemStack? {
        val level = context.level
        val heart = context.center
        val portalPos = heart.above(2)
        if (!WohlonnogondoniaPortalManager.addPortal(level, portalPos)) return null
        WohlonnogondoniaSpreader.startPortalSeed(level, portalPos)
        val candles = context.blocks.filter { it.state.`is`(BlockTags.CANDLES) }.map { it.offset }
        WohlonnogondoniaTreeGrower.startTree(level, portalPos, candles.size)
        WohlonRitualNetwork.sendIgnite(level, heart, context.source ?: Vec3.atCenterOf(heart), candles)
        WohlonRitualNetwork.sendCall(level, heart)
        level.playSound(
            null, heart.x + 0.5, heart.y + 0.5, heart.z + 0.5,
            EKSounds.WOHLON_RITUAL_IGNITE.get(), SoundSource.BLOCKS, 3f, 1f,
        )
        return ItemStack.EMPTY
    }
}

object ReachingRoot : RitualEffect {
    override fun resolve(context: RitualContext, offered: List<ItemStack>): ItemStack? =
        WohlonnogondoniaPortalManager.nearestPortal(context.level, context.center)
            ?.let { WohlonReachingRoot.start(context.level, it, context.center); ItemStack.EMPTY }
}

object RollingWaters : RitualEffect {
    override fun resolve(context: RitualContext, offered: List<ItemStack>): ItemStack? =
        if (WohlonRollingWaters.start(context.level, context.center)) ItemStack.EMPTY else null
}

/** The gem goes in first and picks a [WohlonStaffRites.Recipe]; its slots follow. [feedCandles] glow toward the pedestal. */
class Staff(private val feedCandles: Set<Block>) : RitualEffect {
    override val offerings: OfferingRule = object : OfferingRule {
        override fun wants(offered: List<ItemStack>, stack: ItemStack): Boolean =
            if (offered.isEmpty()) WohlonStaffRites.forGem(stack.item) != null
            else recipe(offered)?.slots?.getOrNull(offered.size - 1)?.let(stack::`is`) == true

        override fun isComplete(offered: List<ItemStack>): Boolean =
            recipe(offered)?.let { offered.size == 1 + it.slots.size } == true
    }

    override fun resolve(context: RitualContext, offered: List<ItemStack>): ItemStack? =
        recipe(offered)?.let { ItemStack(it.result) }

    override fun feedSlots(context: RitualContext): List<LayoutBlock> = context.blocks.filter { it.state.block in feedCandles }

    companion object {
        fun recipe(offered: List<ItemStack>): WohlonStaffRites.Recipe? =
            offered.firstOrNull()?.let { WohlonStaffRites.forGem(it.item) }

        fun parse(json: JsonObject): Staff? {
            val list = json.get("feed_candles")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
            val blocks = list.map { e ->
                val id = e.takeIf { it.isJsonPrimitive }?.asString?.let(ResourceLocation::tryParse) ?: return null
                BuiltInRegistries.BLOCK.get(id).takeIf { it != Blocks.AIR } ?: return null
            }
            return Staff(blocks.toSet())
        }
    }
}

/** Sets the weather for [duration] ticks, or for the random duration `/weather` uses. */
class SetWeather(private val weather: Weather, private val duration: Int?) : RitualEffect {
    enum class Weather { CLEAR, RAIN, THUNDER }

    override fun resolve(context: RitualContext, offered: List<ItemStack>): ItemStack? {
        val level = context.level
        // Vanilla runs the weather cycle only where there is sky light; elsewhere the rite has nothing to act on.
        if (!level.dimensionType().hasSkyLight()) return null
        // Other dimensions read the overworld's weather through DerivedLevelData, whose setters do nothing.
        val overworld = level.server.overworld()
        val random = level.random
        when (weather) {
            Weather.CLEAR -> overworld.setWeatherParameters(duration ?: ServerLevel.RAIN_DELAY.sample(random), 0, false, false)
            Weather.RAIN -> overworld.setWeatherParameters(0, duration ?: ServerLevel.RAIN_DURATION.sample(random), true, false)
            Weather.THUNDER -> overworld.setWeatherParameters(0, duration ?: ServerLevel.THUNDER_DURATION.sample(random), true, true)
        }
        return ItemStack.EMPTY
    }

    companion object {
        fun parse(json: JsonObject): SetWeather? {
            val name = json.get("weather")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
            val weather = Weather.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return null
            val durationJson = json.get("duration")
            val duration = when {
                durationJson == null -> null
                !durationJson.isJsonPrimitive -> return null
                else -> durationJson.asInt.takeIf { it > 0 } ?: return null
            }
            return SetWeather(weather, duration)
        }
    }
}
