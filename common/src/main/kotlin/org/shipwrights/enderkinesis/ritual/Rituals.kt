package org.shipwrights.enderkinesis.ritual

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mojang.logging.LogUtils
import dev.architectury.event.EventResult
import dev.architectury.event.events.common.BlockEvent
import dev.architectury.event.events.common.EntityEvent
import dev.architectury.event.events.common.LifecycleEvent
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.NbtIo
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.TagKey
import net.minecraft.util.profiling.ProfilerFiller
import net.minecraft.world.Containers
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.util.Mth
import net.minecraft.world.item.crafting.Ingredient
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.Property
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.entity.WohlonRitePedestal
import org.shipwrights.enderkinesis.registry.EKSounds

/**
 * Data-driven rituals, `data/<ns>/rituals/<path>.json`. "layout" names a structure-block template
 * (`data/<ns>/structures/<layout>.nbt`) that holds the "center" block once; see [RitualLayout] for matching.
 */
object Rituals : SimpleJsonResourceReloadListener(Gson(), "rituals") {

    private val LOG = LogUtils.getLogger()

    // Vanilla LivingEntity.hurt drops a hit inside these i-frames that does not beat the last one.
    private const val HURT_IFRAMES = 10
    private const val SACRIFICE_HEALTH = Float.MIN_VALUE
    private const val KILL_REACH_DY = 1
    private const val SHOT_REACH = 3.0

    @Volatile
    private var byId: Map<ResourceLocation, Ritual> = emptyMap()

    fun byId(id: ResourceLocation): Ritual? = byId[id]

    override fun apply(objects: MutableMap<ResourceLocation, JsonElement>, manager: ResourceManager, profiler: ProfilerFiller) {
        val loaded = LinkedHashMap<ResourceLocation, Ritual>()
        for ((id, element) in objects.toSortedMap(compareBy { it.toString() })) {
            val error = runCatching { parse(id, element as? JsonObject ?: error("not an object"), manager) }
                .fold({ loaded[id] = it; null }, { it.message })
            if (error != null) LOG.warn("Ritual {} is not loaded: {}", id, error)
        }
        byId = loaded
    }

    private fun parse(id: ResourceLocation, json: JsonObject, manager: ResourceManager): Ritual {
        val center = parseCenter(json.getAsJsonObject("center") ?: error("needs \"center\""))
        val layoutId = location(json.get("layout")) ?: error("needs \"layout\"")
        val file = ResourceLocation(layoutId.namespace, "structures/${layoutId.path}.nbt")
        val tag = manager.getResource(file).orElse(null)?.open()?.use(NbtIo::readCompressed) ?: error("no template $file")
        val alternatives = json.getAsJsonObject("alternatives")?.entrySet()?.associate { (blockId, tagId) ->
            val block = BuiltInRegistries.BLOCK.getOptional(ResourceLocation(blockId)).orElse(null) ?: error("no block $blockId")
            block to blockTag(tagId.asString)
        }.orEmpty()
        val build = { RitualLayout.fromTemplate(tag, center::isTemplateCenter, alternatives) }
        // Block tags bind after the datapack listeners run, so only a plain-block center is checked now.
        val layoutFactory = if (center.usesTag) build else build()?.let { layout -> { layout } } ?: error("$file must hold the center block once")
        val effectJson = json.getAsJsonObject("effect") ?: error("needs \"effect\"")
        val effect = RitualEffect.parse(effectJson) ?: error("unknown or bad effect $effectJson")
        val offerings = effect.offerings ?: FixedOfferings(json.getAsJsonArray("offerings")?.map(::ingredient).orEmpty())
        val conditions = json.getAsJsonArray("conditions")?.map { parseCondition(it.asJsonObject) }.orEmpty()
        val trigger = parseTrigger(json.getAsJsonObject("trigger") ?: error("needs \"trigger\""))
        return Ritual(id, center, layoutFactory, trigger, conditions, offerings, effect)
    }

    private fun parseCenter(json: JsonObject): CenterRule {
        val id = json.get("block")?.asString ?: error("center needs a \"block\"")
        if (id.startsWith("#")) {
            if (json.has("properties")) error("a center tag takes no \"properties\"")
            return CenterRule(null, blockTag(id), emptyMap())
        }
        val block = ResourceLocation.tryParse(id)?.let(BuiltInRegistries.BLOCK::get)?.takeIf { it != Blocks.AIR }
            ?: error("no block $id")
        val properties = HashMap<Property<*>, Comparable<*>>()
        json.getAsJsonObject("properties")?.entrySet()?.forEach { (name, value) ->
            val property = block.stateDefinition.getProperty(name) ?: error("center block has no property $name")
            properties[property] = property.getValue(value.asString).orElse(null) ?: error("bad value for $name")
        }
        return CenterRule(block, null, properties)
    }

    private fun parseTrigger(json: JsonObject): RitualTrigger = when (type(json)) {
        "enderkinesis:light" -> RitualTrigger.Light
        "enderkinesis:kill" -> {
            val entity = json.get("entity")?.asString ?: error("kill trigger needs \"entity\"")
            val matcher = if (entity.startsWith("#")) {
                EntityMatcher(null, TagKey.create(Registries.ENTITY_TYPE, ResourceLocation(entity.substring(1))))
            } else {
                EntityMatcher(BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation(entity)).orElse(null) ?: error("no entity $entity"), null)
            }
            RitualTrigger.Kill(matcher, ingredient(json.get("weapon") ?: error("kill trigger needs \"weapon\"")), json.get("radius")?.asDouble ?: error("kill trigger needs \"radius\""))
        }
        "enderkinesis:projectile_at_sun" -> RitualTrigger.ProjectileAtSun(
            json.get("radius")?.asDouble ?: error("projectile_at_sun needs \"radius\""),
            json.get("angle")?.asDouble ?: error("projectile_at_sun needs \"angle\""),
        )
        "enderkinesis:place" -> RitualTrigger.Place(
            json.getAsJsonArray("advancements")?.map { ResourceLocation(it.asString) }.orEmpty(),
        )
        else -> error("unknown trigger ${type(json)}")
    }

    private fun parseCondition(json: JsonObject): RitualCondition = when (type(json)) {
        "enderkinesis:raining" -> RitualCondition.Raining
        "enderkinesis:open_sky" -> RitualCondition.OpenSky
        "enderkinesis:dimension" ->
            RitualCondition.Dimension(ResourceKey.create(Registries.DIMENSION, location(json.get("dimension")) ?: error("needs \"dimension\"")))
        "enderkinesis:biome" -> {
            val biome = json.get("biome")?.asString ?: error("biome condition needs \"biome\"")
            if (biome.startsWith("#")) RitualCondition.InBiome(null, RitualCondition.biomeTag(ResourceLocation(biome.substring(1))))
            else RitualCondition.InBiome(ResourceKey.create(Registries.BIOME, ResourceLocation(biome)), null)
        }
        else -> error("unknown condition ${type(json)}")
    }

    private fun blockTag(id: String): TagKey<Block> {
        if (!id.startsWith("#")) error("$id is not a #tag")
        return TagKey.create(Registries.BLOCK, ResourceLocation(id.substring(1)))
    }

    private fun type(json: JsonObject): String = location(json.get("type"))?.toString() ?: error("needs \"type\" in $json")

    private fun location(element: JsonElement?): ResourceLocation? =
        element?.takeIf { it.isJsonPrimitive }?.asString?.let(ResourceLocation::tryParse)

    /** An item id string, or an ingredient object such as `{"tag": "..."}`. */
    private fun ingredient(element: JsonElement): Ingredient =
        if (element.isJsonPrimitive) {
            val item = BuiltInRegistries.ITEM.getOptional(ResourceLocation(element.asString)).orElse(null) ?: error("no item $element")
            Ingredient.of(item)
        } else {
            Ingredient.fromJson(element)
        }

    fun init() {
        BlockEvent.PLACE.register(::onPlace)
        RitualTasks.init()
        SunErasure.init()
        Selves.init()
        EntityEvent.ADD.register { entity, level ->
            if (entity is Projectile && level is ServerLevel) onProjectile(entity, level)
            EventResult.pass()
        }
        // Tags are bound now, so a bad tag-center template shows in the log at start, not at first use.
        LifecycleEvent.SERVER_STARTED.register { _ -> byId.values.forEach { it.layout } }
        EntityEvent.LIVING_HURT.register { entity, source, _ ->
            val level = entity.level() as? ServerLevel
            if (level != null && entity.invulnerableTime <= HURT_IFRAMES && killRitual(level, entity, source) != null) {
                // The weapon hit itself must kill, so LIVING_DEATH sees the weapon as the source.
                entity.health = SACRIFICE_HEALTH
            }
            EventResult.pass()
        }
        EntityEvent.LIVING_DEATH.register { entity, source ->
            val level = entity.level() as? ServerLevel
            if (level != null) {
                killRitual(level, entity, source)?.let { (ritual, center, rotation) ->
                    start(level, ritual, center, rotation, entity.position().add(0.0, entity.bbHeight * 0.5, 0.0))
                }
            }
            EventResult.pass()
        }
    }

    /** Called when a block with a LIT property is placed or changes state. */
    @JvmStatic
    fun onLitBlockPlaced(level: Level, pos: BlockPos, state: BlockState, oldState: BlockState) {
        if (level !is ServerLevel || !oldState.`is`(state.block)) return
        if (oldState.getValue(BlockStateProperties.LIT) || !state.getValue(BlockStateProperties.LIT)) return
        for (ritual in byId.values) {
            if (ritual.trigger != RitualTrigger.Light || !ritual.center.isLit(state)) continue
            val rotation = ritual.match(level, pos)
            if (rotation < 0) continue
            start(level, ritual, pos, rotation, null)
            return
        }
    }

    // Architectury fires PLACE before the block is set on Fabric and after on Forge, so the check waits one task.
    private fun onPlace(level: Level, pos: BlockPos, state: BlockState, placer: Entity?): EventResult {
        val server = level as? ServerLevel ?: return EventResult.pass()
        val player = placer as? ServerPlayer ?: return EventResult.pass()
        if (byId.values.none { it.trigger is RitualTrigger.Place && it.center.isTemplateCenter(state) }) return EventResult.pass()
        val placed = pos.immutable()
        server.server.execute { tryPlaceTrigger(server, placed, player) }
        return EventResult.pass()
    }

    private fun tryPlaceTrigger(level: ServerLevel, pos: BlockPos, player: ServerPlayer) {
        val state = level.getBlockState(pos)
        for (ritual in byId.values) {
            val trigger = ritual.trigger as? RitualTrigger.Place ?: continue
            if (!ritual.center.isTemplateCenter(state) || !trigger.isKnownBy(player)) continue
            val rotation = ritual.match(level, pos)
            if (rotation < 0) continue
            start(level, ritual, pos, rotation, null, player)
            return
        }
    }

    private fun start(level: ServerLevel, ritual: Ritual, center: BlockPos, rotation: Int, source: Vec3?, player: ServerPlayer? = null) {
        val context = RitualContext(level, ritual, center, rotation, source, player)
        if (ritual.offerings.isEmpty) {
            val made = ritual.effect.resolve(context, emptyList())
            if (made != null && !made.isEmpty) Containers.dropItemStack(level, center.x + 0.5, center.y + 1.0, center.z + 0.5, made)
            return
        }
        if (level.getEntitiesOfClass(WohlonRitePedestal::class.java, AABB(center)).isNotEmpty()) return
        level.addFreshEntity(WohlonRitePedestal(level, context))
        level.playSound(
            null, center.x + 0.5, center.y + 0.5, center.z + 0.5,
            EKSounds.WOHLON_RITUAL_IGNITE.get(), SoundSource.BLOCKS, 2f, 1.2f,
        )
    }

    // The shot must leave from a player in the ring, so arrows loaded with a chunk are not shots.
    private fun onProjectile(projectile: Projectile, level: ServerLevel) {
        val shooter = projectile.owner as? ServerPlayer ?: return
        if (projectile.tickCount > 0 || projectile.distanceToSqr(shooter) > SHOT_REACH * SHOT_REACH) return
        val motion = projectile.deltaMovement
        if (motion.lengthSqr() < 1.0e-6) return
        // LevelRenderer.renderSky turns the sun by the time of day about the x axis, after a -90 degree turn about y.
        val angle = level.getTimeOfDay(1f) * Mth.TWO_PI
        val sunX = -Mth.sin(angle).toDouble()
        val sunY = Mth.cos(angle).toDouble()
        if (sunY <= 0.0) return
        val cos = (motion.x * sunX + motion.y * sunY) / motion.length()
        for (ritual in byId.values) {
            val trigger = ritual.trigger as? RitualTrigger.ProjectileAtSun ?: continue
            if (cos < Math.cos(Math.toRadians(trigger.maxAngle))) continue
            val center = findLitCenter(level, shooter, ritual, trigger.radius) ?: continue
            start(level, ritual, center.first, center.second, projectile.position(), shooter)
            RootTrail.send(level, projectile)
            return
        }
    }

    private fun findLitCenter(level: ServerLevel, player: ServerPlayer, ritual: Ritual, radius: Double): Pair<BlockPos, Int>? {
        val feet = player.blockPosition()
        val reach = kotlin.math.ceil(radius).toInt()
        val cursor = BlockPos.MutableBlockPos()
        for (dy in -KILL_REACH_DY..KILL_REACH_DY) {
            for (dz in -reach..reach) {
                for (dx in -reach..reach) {
                    cursor.setWithOffset(feet, dx, dy, dz)
                    if (!ritual.center.isLit(level.getBlockState(cursor))) continue
                    val ox = player.x - (cursor.x + 0.5)
                    val oz = player.z - (cursor.z + 0.5)
                    if (ox * ox + oz * oz > radius * radius) continue
                    val rotation = ritual.match(level, cursor)
                    if (rotation >= 0) return cursor.immutable() to rotation
                }
            }
        }
        return null
    }

    private data class KillMatch(val ritual: Ritual, val center: BlockPos, val rotation: Int)

    private fun killRitual(level: ServerLevel, victim: LivingEntity, source: DamageSource): KillMatch? {
        val killer = source.entity as? LivingEntity ?: return null
        if (source.isIndirect) return null
        for (ritual in byId.values) {
            val trigger = ritual.trigger as? RitualTrigger.Kill ?: continue
            if (!trigger.entity.test(victim.type) || !trigger.weapon.test(killer.mainHandItem)) continue
            findCenter(level, victim, ritual, trigger.radius)?.let { return it }
        }
        return null
    }

    private fun findCenter(level: ServerLevel, victim: LivingEntity, ritual: Ritual, radius: Double): KillMatch? {
        val feet = victim.blockPosition()
        val reach = kotlin.math.ceil(radius).toInt()
        val cursor = BlockPos.MutableBlockPos()
        for (dy in -KILL_REACH_DY..KILL_REACH_DY) {
            for (dz in -reach..reach) {
                for (dx in -reach..reach) {
                    cursor.setWithOffset(feet, dx, dy, dz)
                    if (!ritual.center.isLit(level.getBlockState(cursor))) continue
                    val ox = victim.x - (cursor.x + 0.5)
                    val oz = victim.z - (cursor.z + 0.5)
                    if (ox * ox + oz * oz > radius * radius) continue
                    val rotation = ritual.match(level, cursor)
                    if (rotation >= 0) return KillMatch(ritual, cursor.immutable(), rotation)
                }
            }
        }
        return null
    }
}
