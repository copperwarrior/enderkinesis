package org.shipwrights.enderkinesis.sselith

import dev.architectury.event.EventResult
import dev.architectury.event.events.common.EntityEvent
import dev.architectury.event.events.common.TickEvent
import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.TagKey
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.DyeableLeatherItem
import net.minecraft.world.item.Item
import net.minecraft.server.level.ServerChunkCache
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.armortrim.ArmorTrim
import net.minecraft.world.item.armortrim.TrimMaterials
import net.minecraft.world.level.Level
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.chunk.LightChunkGetter
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.dimension.EKDamageSources
import org.shipwrights.enderkinesis.dimension.SselithRepertory
import org.shipwrights.enderkinesis.registry.EKParticles
import java.util.UUID

/**
 * Sselith Eclipse — periodic light-damage hazard in SselithRepertory.
 *
 * The cycle is derived from the sselith level's [Level.gameTime], so the schedule is
 * deterministic, persisted across restarts, and matches between server and client
 * without any sync packet — both sides sample the same monotonic counter.
 *
 * Within each [CYCLE_TICKS]-tick window:
 *  - 0 … [RAMP_TICKS) → ramp-in (intensity 0→1)
 *  - [RAMP_TICKS] … [ACTIVE_TICKS]-[RAMP_TICKS] → full power (1.0)
 *  - [ACTIVE_TICKS]-[RAMP_TICKS] … [ACTIVE_TICKS] → ramp-out (1→0)
 *  - [ACTIVE_TICKS] … [CYCLE_TICKS] → dormant (0)
 */
object SselithEclipse {

    private const val CYCLE_TICKS = 34_500L
    private const val ACTIVE_TICKS = 1_000L

    /** ≈5 in-game minutes (1000 * 5/60). */
    private const val RAMP_TICKS = 83L

    /** Damage trigger: block-light reading at or above this threshold during eclipse
     *  means the entity is "in the light". Aligned with the visual cutoff in
     *  [SselithRepertoryLighting] so what looks bright is what hurts. */
    private const val BLOCK_LIGHT_DAMAGE_MIN = 7

    private const val PLAYER_TICK_PERIOD = 40
    private const val MOB_TICK_PERIOD = 40
    private const val ITEM_TICK_PERIOD = 100
    private const val CHAT_LOG_PERIOD = 40
    private const val DAMAGE_BASE = 2.0f
    private const val DAMAGE_RAMPED = 4.0f

    /** Continuous exposure (in ticks of [PLAYER_TICK_PERIOD] cadence) past which a
     *  player's damage ramps from [DAMAGE_BASE] to [DAMAGE_RAMPED]. */
    private const val RAMP_AFTER_TICKS = 200

    private const val DUST_BURST_COUNT = 24

    /** Vanilla yellow dye color as packed RGB — `DyeColor.YELLOW.textColor`. Stored
     *  leather-armor color is the same int. */
    private const val YELLOW_LEATHER_COLOR = 0xFED83D

    private val SSELITH_HONORING: TagKey<Item> = TagKey.create(
        Registries.ITEM, EnderkinesisMod.id("sselith_honoring"),
    )
    private val LIGHT_DAMAGE_IMMUNE: TagKey<EntityType<*>> = TagKey.create(
        Registries.ENTITY_TYPE, EnderkinesisMod.id("light_damage_immune"),
    )

    private val ARMOR_SLOTS = listOf(
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
    )

    /** Per-player consecutive-exposure tracker, reset whenever the player exits light,
     *  the eclipse ends, or protection is restored. Server-thread-only access. */
    private val exposure: MutableMap<UUID, Int> = HashMap()

    /** Sentinel for "no manual trigger active." Sselith level gameTime is monotonic and
     *  positive, so [Long.MIN_VALUE] is unreachable as a real start tick. */
    private const val NO_MANUAL = Long.MIN_VALUE

    /** Sselith level gameTime at which a debug-triggered eclipse began, or [NO_MANUAL].
     *  Written on the server by [triggerNow] and on clients by the S2C receiver — both
     *  sides then read it from [intensity]. Volatile because the field crosses the
     *  integrated-server↔client thread boundary in single-player. */
    @Volatile private var manualTriggerStart: Long = NO_MANUAL

    /** S2C packet id carrying a single long: the start gameTime of a manually-triggered
     *  eclipse. Registered server-side in [init]; the client receiver lives in
     *  `SselithEclipseClient` and calls [setClientManualTrigger]. */
    val TRIGGER_PACKET: ResourceLocation = EnderkinesisMod.id("sselith_eclipse/trigger")

    fun init() {
        TickEvent.PLAYER_POST.register(::tickPlayer)
        TickEvent.SERVER_LEVEL_POST.register(::tickLevel)
        EntityEvent.LIVING_DEATH.register(::onLivingDeath)
    }

    /** Eclipse strength in [0, 1] at the given level-gameTime. A live manual trigger
     *  overrides the natural cycle for [ACTIVE_TICKS] ticks from its start; once
     *  elapsed it auto-clears and the natural cycle resumes. Pure read; safe on both
     *  sides since both mirror [manualTriggerStart]. */
    @JvmStatic
    fun intensity(gameTime: Long): Float = intensity(gameTime.toDouble())

    /** Fractional-tick overload: takes `gameTime + partialTick` so the rendering side
     *  can sample sub-tick intensity each frame, giving a smooth fade across all
     *  intensity-driven elements (lightmap, fog, sky clear-color) during the ramps. */
    @JvmStatic
    fun intensity(gameTimeFractional: Double): Float {
        val manual = manualTriggerStart
        if (manual != NO_MANUAL) {
            val elapsed = gameTimeFractional - manual.toDouble()
            if (elapsed >= 0.0 && elapsed < ACTIVE_TICKS) return rampedIntensity(elapsed)
            if (elapsed >= ACTIVE_TICKS) manualTriggerStart = NO_MANUAL
        }
        val cycle = CYCLE_TICKS.toDouble()
        val t = ((gameTimeFractional % cycle) + cycle) % cycle
        return if (t < ACTIVE_TICKS) rampedIntensity(t) else 0f
    }

    /** Trapezoid intensity for a position inside the active window: ramp-in, plateau,
     *  ramp-out. Out-of-range input returns 0. */
    private fun rampedIntensity(tickInWindow: Double): Float = when {
        tickInWindow < 0.0 || tickInWindow >= ACTIVE_TICKS -> 0f
        tickInWindow < RAMP_TICKS -> (tickInWindow / RAMP_TICKS).toFloat()
        tickInWindow < ACTIVE_TICKS - RAMP_TICKS -> 1f
        else -> ((ACTIVE_TICKS - tickInWindow) / RAMP_TICKS).toFloat()
    }

    /** Start a manual eclipse anchored to the sselith level's current gameTime, then
     *  broadcast the start tick to every player in that level so client visuals match.
     *  Server-side entry point used by `/eclipse next`. */
    fun triggerNow(level: ServerLevel) {
        val start = level.gameTime
        manualTriggerStart = start
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeLong(start)
        for (player in level.players()) {
            NetworkManager.sendToPlayer(player, TRIGGER_PACKET, FriendlyByteBuf(buf.copy()))
        }
    }

    /** Client-side mirror for the manual-trigger broadcast — receivers call this from
     *  the client packet handler. */
    fun setClientManualTrigger(start: Long) {
        manualTriggerStart = start
    }

    /** Side-aware resolver from [LightChunkGetter] (the field shadowed in the eclipse
     *  light mixins) back to a [Level]. Server side handles itself via the typed
     *  [ServerChunkCache] cast below. Client side registers its own resolver from
     *  `SselithEclipseClient.init()` so the common-side mixins can resolve the
     *  client-side level without importing `ClientChunkCache` (which would crash on a
     *  dedicated server). The shared resolution path keeps server-side reads and
     *  client-side reads using exactly the same level reference + intensity input,
     *  i.e. byte-for-byte identical scale at any given (gameTime, manualTrigger). */
    @Volatile private var clientLevelResolver: ((LightChunkGetter) -> Level?)? = null

    fun setClientLevelResolver(resolver: (LightChunkGetter) -> Level?) {
        clientLevelResolver = resolver
    }

    @JvmStatic
    fun resolveLevelForLightEngine(chunkSource: LightChunkGetter?): Level? {
        if (chunkSource == null) return null
        if (chunkSource is ServerChunkCache) return chunkSource.level
        return clientLevelResolver?.invoke(chunkSource)
    }

    private fun tickPlayer(player: Player) {
        if (player !is ServerPlayer) return
        if (player.isSpectator || player.isCreative) return
        if (player.level().dimension() != SselithRepertory.LEVEL_KEY) return
        if (player.tickCount % PLAYER_TICK_PERIOD != 0) return

        val level = player.serverLevel()
        if (intensity(level.gameTime) <= 0f ||
            !isExposed(player, level) ||
            isProtected(player, level)
        ) {
            exposure.remove(player.uuid)
            return
        }
        val next = (exposure[player.uuid] ?: 0) + PLAYER_TICK_PERIOD
        exposure[player.uuid] = next
        val dmg = if (next >= RAMP_AFTER_TICKS) DAMAGE_RAMPED else DAMAGE_BASE
        player.hurt(EKDamageSources.lightDamage(level, player), dmg)
    }

    private fun tickLevel(level: ServerLevel) {
        if (level.dimension() != SselithRepertory.LEVEL_KEY) return
        val tick = level.gameTime
        if (intensity(tick) <= 0f) return
        if (tick % MOB_TICK_PERIOD == 0L) tickMobs(level)
        if (tick % ITEM_TICK_PERIOD == 0L) tickItems(level)
        if (tick % CHAT_LOG_PERIOD == 0L) broadcastProgress(level, tick)
    }

    private fun broadcastProgress(level: ServerLevel, tick: Long) {
        val players = level.players()
        if (players.isEmpty()) return
        val tickInWindow = currentActiveTick(tick)
        val phase = when {
            tickInWindow < RAMP_TICKS -> "ramp-in"
            tickInWindow < ACTIVE_TICKS - RAMP_TICKS -> "full"
            else -> "ramp-out"
        }
        val pct = (intensity(tick) * 100f).toInt()
        val remaining = ACTIVE_TICKS - tickInWindow
        val msg = Component.literal(
            "Sselith Eclipse — $phase | intensity $pct% | tick $tickInWindow/$ACTIVE_TICKS ($remaining left)",
        )
        for (player in players) player.sendSystemMessage(msg)
    }

    /** Tick offset into the currently-active eclipse window (manual override wins, then
     *  natural). Caller must have already established that an eclipse is active. */
    private fun currentActiveTick(gameTime: Long): Long {
        val manual = manualTriggerStart
        if (manual != NO_MANUAL) {
            val elapsed = gameTime - manual
            if (elapsed in 0 until ACTIVE_TICKS) return elapsed
        }
        return ((gameTime % CYCLE_TICKS) + CYCLE_TICKS) % CYCLE_TICKS
    }

    private fun tickMobs(level: ServerLevel) {
        for (entity in level.allEntities) {
            if (entity !is LivingEntity || entity is Player) continue
            if (entity.type.`is`(LIGHT_DAMAGE_IMMUNE)) continue
            if (!isExposed(entity, level) || isProtected(entity, level)) continue
            entity.hurt(EKDamageSources.lightDamage(level, entity), DAMAGE_BASE)
        }
    }

    private fun tickItems(level: ServerLevel) {
        val doomed = mutableListOf<ItemEntity>()
        for (entity in level.allEntities) {
            if (entity !is ItemEntity) continue
            if (level.getBrightness(LightLayer.BLOCK, entity.blockPosition()) < BLOCK_LIGHT_DAMAGE_MIN) continue
            doomed += entity
        }
        for (item in doomed) {
            spawnDustBurst(level, item.x, item.y + 0.2, item.z)
            item.discard()
        }
    }

    /** Exposure check: block-light reading ≥ [BLOCK_LIGHT_DAMAGE_MIN]. The read goes
     *  through [BlockLightEngineSselithEclipseMixin], so during eclipse a torch's
     *  natural 14 reads as ~8 and only cells inside the surviving bright band trigger
     *  damage — matching what the player visually sees as "in the light". */
    private fun isExposed(entity: LivingEntity, level: ServerLevel): Boolean =
        level.getBrightness(LightLayer.BLOCK, BlockPos.containing(entity.eyePosition)) >= BLOCK_LIGHT_DAMAGE_MIN

    private fun isProtected(entity: LivingEntity, level: ServerLevel): Boolean {
        for (slot in ARMOR_SLOTS) {
            val piece = entity.getItemBySlot(slot)
            if (piece.isEmpty) return false
            if (!isPieceProtective(piece, level)) return false
        }
        return true
    }

    private fun isPieceProtective(stack: ItemStack, level: ServerLevel): Boolean =
        stack.`is`(SSELITH_HONORING) ||
            isYellowDyedLeather(stack) ||
            hasGoldTrim(stack, level)

    private fun isYellowDyedLeather(stack: ItemStack): Boolean {
        val item = stack.item as? DyeableLeatherItem ?: return false
        return item.hasCustomColor(stack) && item.getColor(stack) == YELLOW_LEATHER_COLOR
    }

    private fun hasGoldTrim(stack: ItemStack, level: ServerLevel): Boolean {
        val trim = ArmorTrim.getTrim(level.registryAccess(), stack).orElse(null) ?: return false
        return trim.material().unwrapKey().orElse(null) == TrimMaterials.GOLD
    }

    private fun onLivingDeath(entity: LivingEntity, source: DamageSource): EventResult {
        if (!isLightDamageSource(source)) return EventResult.pass()
        val level = entity.level() as? ServerLevel ?: return EventResult.pass()
        spawnDustBurst(level, entity.x, entity.y + entity.bbHeight * 0.5, entity.z)
        return EventResult.pass()
    }

    @JvmStatic
    fun isLightDamageSource(source: DamageSource): Boolean = when (source.msgId) {
        "light_damage", "light_damage_sunburnt", "light_damage_dark" -> true
        else -> false
    }

    /** Spawn the eclipse death-dust burst at the entity centre. Called from the
     *  `LivingEntity.die` mixin path so non-player entities killed by Light Damage
     *  go straight to dust without playing the red-flash / fall-over animation. */
    @JvmStatic
    fun spawnLightDeathDust(entity: LivingEntity) {
        val level = entity.level() as? ServerLevel ?: return
        spawnDustBurst(level, entity.x, entity.y + entity.bbHeight * 0.5, entity.z)
    }

    private fun spawnDustBurst(level: ServerLevel, x: Double, y: Double, z: Double) {
        level.sendParticles(
            EKParticles.sselithDust(), x, y, z,
            DUST_BURST_COUNT, 0.3, 0.4, 0.3, 0.02,
        )
    }
}
