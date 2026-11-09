package org.shipwrights.enderkinesis.item

import com.mojang.logging.LogUtils
import dev.architectury.event.EventResult
import dev.architectury.event.events.common.InteractionEvent
import dev.architectury.event.events.common.PlayerEvent
import dev.architectury.event.events.common.TickEvent
import dev.architectury.networking.NetworkManager
import net.minecraft.core.SectionPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.joml.Matrix3d
import org.joml.Quaterniond
import org.joml.Vector3d
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.body.GamylanCageBodyForce
import org.shipwrights.enderkinesis.body.GamylanCageData
import org.shipwrights.enderkinesis.body.GamylanCageNetwork
import org.shipwrights.enderkinesis.body.GamylanCagePedestals
import org.shipwrights.enderkinesis.body.GamylanCagePersistence
import org.shipwrights.enderkinesis.body.GamylanCageRegistry
import org.shipwrights.enderkinesis.body.GamylanCageShape
import org.shipwrights.enderkinesis.body.SoderShape
import org.valkyrienskies.core.api.bodies.VsBodyCreateData
import org.valkyrienskies.core.api.world.ServerShipWorld
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.vsCore
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.core.api.VsBeta
import org.shipwrights.enderkinesis.util.deleteStandaloneBody

/**
 * Server-side orchestrator for the Wand of Ga'Mylan capture + break
 * pipeline. Pure VS body — no Entity in the loop. The client raycasts
 * against active cages and sends [GamylanCageNetwork.CAGE_ATTACK] when
 * it lands a hit; [onAttackPacket] is the resulting damage handler.
 *
 * Persistence: per-dimension [GamylanCagePersistence] SavedData. VS
 * Core keeps body ids stable across restart, so we re-hook by id.
 * Records whose body didn't survive the VS side get swept by
 * [sweepStaleAfterLoad] on a delayed post-load tick.
 */
@OptIn(GameTickOnly::class, VsBeta::class)
object GamylanCageManager {

    private val LOG = LogUtils.getLogger()

    /** Vertical offset from the captured entity's feet to the cage's
     *  hover destination — the body's actual world position. The fade
     *  visual lerps the rendered entity from its capture origin up to
     *  here over the renderer's fade-in window. */
    private const val HOVER_OFFSET_Y: Double = 3.0

    /** Max HP per crystal (matches the spec). */
    private const val MAX_HP: Int = 20

    /** Damage per validated attack packet. */
    private const val DAMAGE_PER_HIT: Int = 1

    /** Max range (blocks) a player may be from a cage body when the
     *  client claims to have hit it. Defensive check against forged
     *  / spoofed packets; vanilla melee reach is ~3, plus some slack
     *  for ping. */
    private const val MAX_ATTACK_RANGE: Double = 6.0

    // Server thread only: the game time of each player's last cage hit, so a packet burst over many cages counts once.
    private val lastHitTick = HashMap<java.util.UUID, Long>()

    /** Mass for the dynamic VS body. During the fade the body is
     *  spring-locked at its hover position by [GamylanCageBodyForce]
     *  (which also cancels gravity); once the fade completes the body
     *  falls under VS2's gravity. */
    private const val CAGE_BODY_MASS: Double = 1000.0

    /** Ticks between successive stage-degrade regens. The crystal
     *  regains [HP_REGEN_PER_STAGE] HP per interval — one damage stage
     *  (0..9) of the client crumble overlay every 3 s of quiet, rather
     *  than snapping back to full. */
    private const val HP_REGEN_STAGE_INTERVAL_TICKS: Long = 60L

    /** HP restored per interval. MAX_HP / 10 stages = 2 HP per stage. */
    private const val HP_REGEN_PER_STAGE: Int = 2

    /** Player HP below this triggers the wand's kill-with-light path —
     *  "less than 2 hearts left" = `health < 4` (2 hearts × 2 HP). */
    private const val LOW_HP_THRESHOLD: Float = 4.0f

    /** Ticks of fade-in before the body is released to gravity. Match
     *  to [org.shipwrights.enderkinesis.client.GamylanCageRenderer]'s
     *  `FADE_TICKS` so the body starts falling at the same moment the
     *  shell finishes appearing. */
    private const val FADE_TICKS: Long = 30L

    /** Grace window between SERVER_LEVEL_LOAD and the stale-record
     *  sweep. VS Core loads its bodies asynchronously — polling before
     *  they're fully hydrated would nuke every persisted cage on
     *  restart. 200 ticks (~10s) is well past the point VS reports
     *  ready in practice. */
    private const val STALE_SWEEP_GRACE_TICKS: Long = 200L

    /** Ticks a chunk stays loaded before its terrain counts as in physics. */
    private const val TERRAIN_SETTLE_TICKS: Long = 20L

    /** Per-dimension deadline: at [gameTime] we sweep once, then clear
     *  the entry. Populated by SERVER_LEVEL_LOAD, consumed by the
     *  per-tick handler. */
    private val staleSweepDeadlines: java.util.concurrent.ConcurrentHashMap<
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, Long> =
        java.util.concurrent.ConcurrentHashMap()

    fun init() {
        // Intercept right-clicks on entities BEFORE entity.interact
        // runs. Without this, the entity's default mobInteract (villager
        // trade UI, horse mount, cat sit, etc.) takes precedence even
        // when the wand returns CONSUME from interactLivingEntity —
        // because some entities' mobInteract runs unconditionally on
        // certain paths or via other mod events. interruptTrue here
        // halts the vanilla flow entirely.
        InteractionEvent.INTERACT_ENTITY.register(::onInteractEntity)
        // Receive client-side attack-confirmed packets.
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, GamylanCageNetwork.CAGE_ATTACK) { buf, ctx ->
            val bodyId = buf.readLong()
            val player = ctx.player as? ServerPlayer ?: return@registerReceiver
            ctx.queue { onAttackPacket(player, bodyId) }
        }
        // Replay every loaded cage's payload to fresh viewers.
        PlayerEvent.PLAYER_JOIN.register { player ->
            GamylanCageNetwork.refreshViewer(player, player.serverLevel())
        }
        PlayerEvent.PLAYER_QUIT.register { player ->
            lastHitTick.remove(player.uuid)
            GamylanCageNetwork.forgetViewer(player)
        }
        PlayerEvent.CHANGE_DIMENSION.register { player, _, _ ->
            GamylanCageNetwork.refreshViewer(player, player.serverLevel())
        }
        // A respawn into a different dimension is not a CHANGE_DIMENSION event.
        PlayerEvent.PLAYER_RESPAWN.register { player, _ ->
            GamylanCageNetwork.refreshViewer(player, player.serverLevel())
        }
        // Cages come into and go out of each player's view as they and the cages move.
        TickEvent.SERVER_LEVEL_POST.register { level -> GamylanCageNetwork.tickViewers(level) }
        dev.architectury.event.events.common.LifecycleEvent.SERVER_STOPPED.register { GamylanCageNetwork.clearViewers() }
        // Per-tick: flip cages past their fade-end into the fall state.
        // The phys-tick force loop reads `fadeComplete` to decide
        // whether to keep the body pinned or let gravity take over.
        TickEvent.SERVER_LEVEL_POST.register(::tickCages)
        // Hydrate the in-memory registry from disk. VS Core bodies are
        // restored by VS itself with stable ids; we just need our
        // gameplay-state records back so the force loop, damage flow,
        // and PLAYER_JOIN broadcast pick up where they were.
        dev.architectury.event.events.common.LifecycleEvent.SERVER_LEVEL_LOAD.register { level ->
            val store = GamylanCagePersistence.forLevel(level)
            for (data in store.records.values) GamylanCageRegistry.restore(level, data)
            staleSweepDeadlines[level.dimension()] = level.gameTime + STALE_SWEEP_GRACE_TICKS
        }
        // VS2 phys-tick listener: counter-gravity + spring lock during
        // fade, then nothing once the fade completes.
        GamylanCageBodyForce.register()
    }

    private fun onInteractEntity(player: Player, entity: Entity, hand: InteractionHand): EventResult {
        val stack = player.getItemInHand(hand)
        if (stack.item !is WandOfGamylanItem) return EventResult.pass()
        if (entity !is LivingEntity) return EventResult.pass()
        if (entity === player) return EventResult.pass()
        if (entity.isPassengerOfSameVehicle(player)) return EventResult.pass()
        if (entity.isRemoved) return EventResult.pass()
        val level = player.level()
        if (level.isClientSide) return EventResult.interruptTrue()
        val serverLevel = level as? ServerLevel ?: return EventResult.pass()

        // Player-target special case. The wand always breaks on
        // use against a player (one shot, then dust); whether a
        // capture+kill happens depends on the victim's HP.
        if (entity is net.minecraft.world.entity.player.Player) {
            val attacker = player as? ServerPlayer
            val victim = entity as? ServerPlayer
            if (attacker != null && victim != null && victim.health < LOW_HP_THRESHOLD) {
                tryLowHpPlayerKill(serverLevel, victim, attacker)
            }
            // Wand is consumed regardless of HP — using it on a
            // player at all costs the player the wand. Skip the
            // shrink for creative attackers so they don't lose it
            // from their inventory unintentionally.
            if (!(attacker?.abilities?.instabuild ?: false)) stack.shrink(1)
            return EventResult.interruptTrue()
        }

        if (capture(serverLevel, entity)) {
            level.playSound(
                null, entity.x, entity.y, entity.z,
                org.shipwrights.enderkinesis.registry.EKSounds.GAMYLAN_CAPTURE.get(),
                net.minecraft.sounds.SoundSource.PLAYERS,
                1.0f, 1.0f,
            )
        }
        return EventResult.interruptTrue()
    }

    private fun tickCages(level: ServerLevel) {
        val now = level.gameTime
        var dirty = false
        for (data in GamylanCageRegistry.snapshot(level)) {
            // Impact damage accumulated on the phys thread since
            // the last drain. Applied here so HP mutation +
            // broadcastHp stay on the server thread.
            val impactDmg = GamylanCageBodyForce.drainImpactDamage(data.bodyId)
            if (impactDmg > 0 && data.hp > 0) {
                data.hp = (data.hp - impactDmg).coerceAtLeast(0)
                data.lastDamageTick = now
                dirty = true
                if (data.hp <= 0) {
                    breakCage(level, data)
                    continue
                }
                GamylanCageNetwork.broadcastHp(level, data.bodyId, data.hp)
                level.shipObjectWorld.allBodies.getById(data.bodyId)?.kinematics?.position?.let {
                    GamylanCageNetwork.broadcastImpact(level, data.bodyId, Vec3(it.x(), it.y(), it.z()))
                }
            }
            if (!data.fadeComplete && now >= data.fadeEndGameTime) {
                data.fadeComplete = true
                dirty = true
            }
            updateTerrainReady(level, data, now)
            // HP regen: one damage-stage per HP_REGEN_STAGE_INTERVAL_TICKS
            // of quiet. `lastDamageTick` doubles as the regen timer —
            // bumping it forward by the interval on each stage restore
            // means new damage always resets the clock. hp==0 is
            // filtered by breakCage in onAttackPacket, so we only
            // observe damaged-but-alive cages here.
            if (data.lastDamageTick >= 0L &&
                data.hp < data.maxHp &&
                now - data.lastDamageTick >= HP_REGEN_STAGE_INTERVAL_TICKS
            ) {
                data.hp = (data.hp + HP_REGEN_PER_STAGE).coerceAtMost(data.maxHp)
                data.lastDamageTick = if (data.hp >= data.maxHp) -1L else now
                GamylanCageNetwork.broadcastHp(level, data.bodyId, data.hp)
                dirty = true
            }
        }
        sweepStaleAfterLoad(level, now)
        if (GamylanCagePedestals.tick(level)) dirty = true
        if (dirty) GamylanCagePersistence.markDirty(level)
    }

    /** Low-HP player kill flow. The cage is made BEFORE the damage, so it holds the pose and
     *  the items the player had. With keepInventory OFF the cage takes the items and the
     *  inventory is cleared so the death drops nothing; with it ON the items stay for respawn.
     *
     *  No item is lost when a step fails: a refused capture leaves the inventory for a normal
     *  death, and a victim that lives (PvP off, same team, Wik'lak or totem) gets the inventory
     *  back and the cage keeps no copy. */
    private fun tryLowHpPlayerKill(level: ServerLevel, victim: ServerPlayer, attacker: ServerPlayer) {
        val posePreDeath = snapshotAnimation(victim)
        // Equipment before the clear below — otherwise the ghost renders naked.
        val equipmentSnapshot = net.minecraft.world.entity.EquipmentSlot.entries
            .map { victim.getItemBySlot(it).copy() }
        val keepInv = level.gameRules.getBoolean(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY)
        val savedItems: List<net.minecraft.world.item.ItemStack> =
            if (!keepInv) collectInventory(victim) else emptyList()

        // Capture MUST happen before hurt(): with the bypass-tag damage
        // source, some setups (hardcore-mode mods, session-management
        // hooks) close the victim's connection inside die(), which
        // wipes the entity's position + entityData before hurt() returns.
        // Snapshot the cage while the entity is still fully present,
        // then kill. Wik'lak / totem intercepting the kill leaves a
        // ghost cage of a living player — rare edge case, accepted.
        val cage = captureCage(
            level, victim,
            preSnapAnimation = posePreDeath,
            droppedItems = savedItems,
            equipmentOverride = equipmentSnapshot,
        )
        // Slot layout for the give-back; `savedItems` has no slots.
        var inventoryBackup: net.minecraft.nbt.ListTag? = null
        if (cage != null) {
            level.playSound(
                null, victim.x, victim.y, victim.z,
                org.shipwrights.enderkinesis.registry.EKSounds.GAMYLAN_CAPTURE.get(),
                net.minecraft.sounds.SoundSource.PLAYERS,
                1.0f, 1.0f,
            )
            if (!keepInv) {
                inventoryBackup = victim.inventory.save(net.minecraft.nbt.ListTag())
                victim.inventory.clearContent()
            }
        }

        victim.hurt(
            org.shipwrights.enderkinesis.dimension.EKDamageSources.gamylanLight(level, attacker),
            Float.MAX_VALUE,
        )

        if (victim.isAlive && cage != null && inventoryBackup != null) {
            victim.inventory.load(inventoryBackup)
            cage.droppedItems.clear()
        }
    }

    /** Copy every non-empty stack in the player's inventory (main +
     *  armor + off-hand) so we can hold them until the cage breaks. */
    private fun collectInventory(victim: ServerPlayer): List<net.minecraft.world.item.ItemStack> {
        val out = ArrayList<net.minecraft.world.item.ItemStack>()
        val inv = victim.inventory
        for (i in 0 until inv.containerSize) {
            val stack = inv.getItem(i)
            if (!stack.isEmpty) out.add(stack.copy())
        }
        return out
    }

    /** Pedestal entry point: conjure a small crystal cage holding [stack]
     *  directly above [pedestal]. Ordinary cage in every way — it drifts
     *  onto the pedestal via the standard home flow, welds, spins, takes
     *  damage; breaking it drops the item. On a ship-mounted pedestal the
     *  spawn point is transformed to world coords so the body starts in
     *  the right frame. */
    fun captureItem(level: ServerLevel, stack: net.minecraft.world.item.ItemStack, pedestal: net.minecraft.core.BlockPos): Boolean {
        // Item display box ~half a block — MIN_DIM in GamylanCageShape
        // floors the crystal at its smallest legal size.
        val itemCull = Vector3d(0.5, 0.5, 0.5)
        val shapeDims = GamylanCageShape.fromCullingBox(itemCull.x, itemCull.y, itemCull.z)

        // Spawn hover: pedestal-top + crystal bottom clearance, in the
        // pedestal's own frame, transformed to world for ship pedestals.
        val local = Vector3d(
            pedestal.x + 0.5,
            pedestal.y + 1.0 + shapeDims.y + shapeDims.z + 0.25,
            pedestal.z + 0.5,
        )
        val ship = level.getLoadedShipManagingPos(pedestal)
        val hover = Vector3d(local)
        ship?.transform?.shipToWorld?.transformPosition(hover)
        val origin = Vector3d(hover.x, hover.y - 1.0, hover.z)

        val bodyId = tryCreateDynamicBody(level, hover, shapeDims, itemCull, isTribulla = false) ?: run {
            return false
        }
        val addedAt = level.gameTime
        val data = GamylanCageData(
            bodyId = bodyId,
            capturedTag = CompoundTag(),
            animationTag = CompoundTag(),
            isTribulla = false,
            captureOrigin = origin,
            hoverPos = hover,
            addedAtGameTime = addedAt,
            maxHp = MAX_HP,
            hp = MAX_HP,
            shapeDims = shapeDims,
            entityCullSize = itemCull,
            dimId = level.dimensionId,
            fadeEndGameTime = addedAt + FADE_TICKS,
            displayedItem = stack,
        )
        GamylanCageRegistry.add(level, data)
        GamylanCageNetwork.broadcastAdded(level, data)
        return true
    }

    /** Wand-item entry point. Returns true on success. Optional
     *  overrides let the player-target flow pass in a pre-hurt pose
     *  snapshot (so the ghost holds the pose the player was actually
     *  in at the moment before death, not whatever ragdoll state hurt
     *  leaves them in) and the pre-hurt inventory (to drop from the
     *  cage on break instead of at the death position). */
    fun capture(
        level: ServerLevel, target: LivingEntity,
        preSnapAnimation: CompoundTag? = null,
        droppedItems: List<net.minecraft.world.item.ItemStack> = emptyList(),
        equipmentOverride: List<net.minecraft.world.item.ItemStack>? = null,
    ): Boolean = captureCage(level, target, preSnapAnimation, droppedItems, equipmentOverride) != null

    private fun captureCage(
        level: ServerLevel, target: LivingEntity,
        preSnapAnimation: CompoundTag?,
        droppedItems: List<net.minecraft.world.item.ItemStack>,
        equipmentOverride: List<net.minecraft.world.item.ItemStack>?,
    ): GamylanCageData? {
        val captured = CompoundTag()
        val isPlayer = target is net.minecraft.world.entity.player.Player
        if (!isPlayer) {
            // The save includes passengers, but only this entity is removed. A rider would
            // stay in the world and get a copy with the same UUID on release.
            target.ejectPassengers()
            target.stopRiding()
            // Players' `saveAsPassenger` refuses outright — handled
            // below via GameProfile + RemotePlayer on the client. Every
            // other living entity round-trips through NBT.
            if (!target.saveAsPassenger(captured)) {
                return null
            }
        }
        val animation = preSnapAnimation ?: snapshotAnimation(target)
        // Easter egg: Tribulla-named capture flips to the soder bottle.
        // Match either the display-name Component (name-tagged mob) or,
        // for a captured player, the player's game-profile name — so a
        // player who's actually called "Tribulla" gets the bottle too.
        val isTribulla = target.customName?.string == "Tribulla" ||
            (target is net.minecraft.world.entity.player.Player &&
                target.gameProfile.name == "Tribulla")

        val origin = Vector3d(target.x, target.y, target.z)
        val hover = Vector3d(target.x, target.y + HOVER_OFFSET_Y, target.z)
        // Crystal shape dims (rx, midH, tipLen) — sized to the captured
        // entity's culling AABB (`getBoundingBoxForCulling`) rather than
        // its collision bbox, so oversized-model entities (Ender Dragon,
        // Ghast, Ender Crystal, ...) get a crystal that actually
        // encloses the visible model. `rx` uses the XZ corner-to-corner
        // diagonal so the hex envelope contains the box regardless of
        // orientation inside the cage.
        val cullBox = target.boundingBoxForCulling
        val entityCullSize = Vector3d(cullBox.xsize, cullBox.ysize, cullBox.zsize)
        val shapeDims = GamylanCageShape.fromCullingBox(
            xSize = entityCullSize.x,
            ySize = entityCullSize.y,
            zSize = entityCullSize.z,
        )

        val bodyId = tryCreateDynamicBody(level, hover, shapeDims, entityCullSize, isTribulla) ?: run {
            return null
        }

        val addedAt = level.gameTime
        val playerProfileUuid = if (isPlayer) target.uuid else null
        val playerName = if (isPlayer) (target as net.minecraft.world.entity.player.Player).gameProfile.name else null
        // Player skin-layer visibility + full equipment set, so the
        // ghost renders wearing exactly what the source player had on.
        val customizationByte: Byte
        val equipment: List<net.minecraft.world.item.ItemStack>
        if (isPlayer) {
            val p = target as net.minecraft.world.entity.player.Player
            customizationByte = p.entityData.get(
                org.shipwrights.enderkinesis.mixin.PlayerCustomisationAccessor
                    .`enderkinesis$getDataCustomisation`(),
            )
            // Caller-supplied equipment wins because tryLowHpPlayerKill
            // clears the inventory before invoking us — reading slots
            // here would yield empty stacks. Fall back to live slots
            // for callers that didn't pre-snapshot.
            equipment = equipmentOverride ?: net.minecraft.world.entity.EquipmentSlot.entries
                .map { p.getItemBySlot(it).copy() }
        } else {
            customizationByte = 0
            equipment = emptyList()
        }
        val data = GamylanCageData(
            bodyId = bodyId,
            capturedTag = captured,
            animationTag = animation,
            isTribulla = isTribulla,
            captureOrigin = origin,
            hoverPos = hover,
            addedAtGameTime = addedAt,
            maxHp = MAX_HP,
            hp = MAX_HP,
            shapeDims = shapeDims,
            entityCullSize = entityCullSize,
            dimId = level.dimensionId,
            fadeEndGameTime = addedAt + FADE_TICKS,
            playerProfileUuid = playerProfileUuid,
            playerName = playerName,
            playerCustomizationByte = customizationByte,
            playerEquipment = equipment,
            droppedItems = java.util.ArrayList(droppedItems),
        )
        GamylanCageRegistry.add(level, data)
        GamylanCageNetwork.broadcastAdded(level, data)
        // Don't discard a player — the caller is going to kill them
        // through the gamylan_light damage source instead, which lets
        // the vanilla death message ("X brought light into Y's layer")
        // fire and respawn flow take over.
        if (!isPlayer) target.discard()
        return data
    }

    /** Client confirmed a hit on cage [bodyId]. Validate range, then
     *  drain HP and break if it crosses zero. Range is checked against
     *  the body's LIVE position — the crystal can fall after the fade,
     *  so `data.hoverPos` (captured-time position) is stale. */
    private fun onAttackPacket(player: ServerPlayer, bodyId: Long) {
        val level = player.serverLevel()
        if (player.isSpectator || !player.isAlive) return
        val data = GamylanCageRegistry.get(level, bodyId) ?: return
        // One hit for each tick, for each cage and for each player: a packet burst must not break cages at once.
        if (data.lastDamageTick == level.gameTime || lastHitTick[player.uuid] == level.gameTime) return
        val body = level.shipObjectWorld.allBodies.getById(data.bodyId)
        val livePos = body?.let { Vector3d(it.kinematics.position) } ?: data.hoverPos
        // From the eye to the surface, not from the feet to the centre: a large or scaled cage
        // has its centre more than the range away from each point a player can stand on.
        val scale = body?.kinematics?.transform?.scaling?.x() ?: 1.0
        val reach = MAX_ATTACK_RANGE + GamylanCageShape.boundingRadius(data.shapeDims) * scale
        val eye = player.eyePosition
        val dx = eye.x - livePos.x
        val dy = eye.y - livePos.y
        val dz = eye.z - livePos.z
        val distSq = dx * dx + dy * dy + dz * dz
        if (distSq > reach * reach) {
            return
        }

        data.hp = (data.hp - DAMAGE_PER_HIT).coerceAtLeast(0)
        data.lastDamageTick = level.gameTime
        lastHitTick[player.uuid] = level.gameTime
        GamylanCagePersistence.markDirty(level)
        GamylanCageNetwork.broadcastHp(level, bodyId, data.hp)
        if (data.hp <= 0) breakCage(level, data)
    }

    private fun updateTerrainReady(level: ServerLevel, data: GamylanCageData, now: Long) {
        val pos = level.shipObjectWorld.allBodies.getById(data.bodyId)?.kinematics?.position ?: return
        val loaded = level.hasChunk(
            SectionPos.blockToSectionCoord(pos.x()),
            SectionPos.blockToSectionCoord(pos.z()),
        )
        when {
            !loaded -> {
                data.terrainReady = false
                data.terrainLoadedAt = -1L
            }
            data.terrainReady -> {}
            data.terrainLoadedAt < 0L -> data.terrainLoadedAt = now
            now - data.terrainLoadedAt >= TERRAIN_SETTLE_TICKS -> data.terrainReady = true
        }
    }

    /** Look up the cage's current VS body position. Returns null if
     *  the body has been lost from VS2's store (shouldn't normally
     *  happen, but the manager doesn't insist). */
    private fun liveBodyPosition(level: ServerLevel, data: GamylanCageData): Vector3d? {
        val body = level.shipObjectWorld.allBodies.getById(data.bodyId) ?: return null
        return Vector3d(body.kinematics.position)
    }

    /** HP = 0 — release the captured entity (boss) or discard it
     *  (non-boss), pull the cage from the registry, send the removal
     *  packet. The VS body is left in VS2's body store (no public
     *  deleteBody API yet). */
    /** Put the captured entity, the pedestal item and the held player items into the world at
     *  [at]. Shared by [breakCage] and the stale sweep, so no path removes a cage with its
     *  contents inside. */
    private fun releaseContents(level: ServerLevel, data: GamylanCageData, at: Vector3d) {
        val breakPos = at
        // Always release the captured entity on break — boss, villager,
        // chicken, doesn't matter. Player captures return null from
        // loadEntityRecursive (capturedTag is empty for the RemotePlayer
        // path) which the fall-through handles as a no-op.
        val restored = EntityType.loadEntityRecursive(data.capturedTag, level) { e: Entity ->
            e.moveTo(breakPos.x, breakPos.y, breakPos.z, e.yRot, e.xRot)
            e
        }
        if (restored != null) level.addFreshEntity(restored)
        // Drop the captured player's inventory items at the cage's
        // break position — only populated by [tryLowHpPlayerKill] when
        // vanilla drop conditions apply.
        // Pedestal item cages return their displayed item.
        if (!data.displayedItem.isEmpty) {
            val item = net.minecraft.world.entity.item.ItemEntity(
                level, breakPos.x, breakPos.y, breakPos.z, data.displayedItem.copy(),
            )
            item.setDefaultPickUpDelay()
            level.addFreshEntity(item)
        }
        for (stack in data.droppedItems) {
            if (stack.isEmpty) continue
            val item = net.minecraft.world.entity.item.ItemEntity(
                level, breakPos.x, breakPos.y, breakPos.z, stack.copy(),
            )
            item.setDefaultPickUpDelay()
            level.addFreshEntity(item)
        }
        data.droppedItems.clear()
    }

    private fun breakCage(level: ServerLevel, data: GamylanCageData) {
        // Break position — also the spawn point of the client shatter
        // cubes + the audible glass-break, so the visual / aural cues
        // line up with where the entity respawns.
        val breakPos = liveBodyPosition(level, data) ?: data.hoverPos
        releaseContents(level, data, breakPos)
        level.playSound(
            null, breakPos.x, breakPos.y, breakPos.z,
            org.shipwrights.enderkinesis.registry.EKSounds.GAMYLAN_CAGE_SHATTER.get(),
            net.minecraft.sounds.SoundSource.BLOCKS,
            1.0f, 1.0f,
        )
        GamylanCagePedestals.forgetBody(level, data)
        GamylanCageRegistry.remove(level, data.bodyId)
        GamylanCageBodyForce.forgetBody(data.bodyId)
        GamylanCageNetwork.broadcastRemoved(level, data.bodyId)
        // Without this the invisible collision hull stays in physics and bumps entities off nothing.
        level.deleteStandaloneBody(data.bodyId)
    }

    private fun snapshotAnimation(entity: LivingEntity): CompoundTag {
        // Capture facing + tickCount only; motion fields
        // (AttackAnim/SwingTime/WalkAnim) are deliberately omitted so
        // the client renderer reads them as 0 (= idle pose) when it
        // reconstructs the ghost. The ghost is never ticked, so a
        // zero-speed walk animation plus a no-swing state freezes the
        // model in its idle frame for the duration of the cage.
        val tag = CompoundTag()
        tag.putInt("TickCount", entity.tickCount)
        tag.putFloat("YRot", entity.yRot)
        tag.putFloat("XRot", entity.xRot)
        tag.putFloat("YBodyRot", entity.yBodyRot)
        tag.putFloat("YHeadRot", entity.yHeadRot)
        tag.putFloat("YBodyRotO", entity.yBodyRotO)
        tag.putFloat("YHeadRotO", entity.yHeadRotO)
        return tag
    }

    /** Solid-box inertia of [CAGE_BODY_MASS] over the full extents of the crystal: width 2·rx, height 2·(midH + tipLen). */
    private fun boxInertia(dims: Vector3d): Matrix3d {
        val w = 2.0 * dims.x
        val h = 2.0 * (dims.y + dims.z)
        val k = CAGE_BODY_MASS / 12.0
        return Matrix3d().identity().scale(k * (h * h + w * w), k * (w * w + w * w), k * (w * w + h * h))
    }

    /** Create the cage's VS body as a DYNAMIC body. During the fade
     *  the [GamylanCageBodyForce] phys-tick listener counter-gravity +
     *  spring-locks it at [hoverPos] so it sits as if static; after
     *  fade the listener disengages and the body falls + tumbles
     *  under VS2 gravity. Collision shape is a compound hex-bipyramid
     *  hull (see [GamylanCageShape]) so the cage's hit footprint
     *  matches the rendered crystal mesh face-for-face — except when
     *  [isTribulla] is true, in which case the compound is a 6-box
     *  soder bottle hull from [SoderShape]. */
    private fun tryCreateDynamicBody(
        level: ServerLevel, hoverPos: Vector3d, shapeDims: Vector3d,
        entityCullSize: Vector3d, isTribulla: Boolean,
    ): Long? {
        return try {
            val shipWorld = level.shipObjectWorld as? ServerShipWorld ?: return null
            val body = shipWorld.createBody(
                VsBodyCreateData(
                    dimensionId = level.dimensionId,
                    inertiaData = vsCore.newShipInertiaData(
                        Vector3d(), CAGE_BODY_MASS,
                        boxInertia(shapeDims),
                    ),
                    kinematics = vsCore.newBodyKinematics(
                        Vector3d(), Vector3d(),
                        hoverPos, Quaterniond(), Vector3d(1.0), Vector3d(),
                    ),
                    collisionShape = if (isTribulla)
                        SoderShape.newCompoundShape(entityCullSize)
                    else
                        GamylanCageShape.newCompoundShape(shapeDims),
                    isStatic = false,
                    collisionMask = -1,
                    staticFrictionCoefficient = 0.3,
                    dynamicFrictionCoefficient = 0.3,
                    restitutionCoefficient = 0.1,
                )
            )
            body.id
        } catch (t: Throwable) {
            LOG.warn("[ga'mylan] VS body creation failed at {}", hoverPos, t)
            null
        }
    }

    /** Once per level, [STALE_SWEEP_GRACE_TICKS] after SERVER_LEVEL_LOAD:
     *  drop any hydrated record whose VS body didn't survive the VS
     *  side. Missing bodies would leave the record haunted — force loop
     *  no-ops, damage packets no-op (no shape to raycast), the record
     *  sits forever. */
    private fun sweepStaleAfterLoad(level: ServerLevel, now: Long) {
        val dim = level.dimension()
        val deadline = staleSweepDeadlines[dim] ?: return
        if (now < deadline) return
        staleSweepDeadlines.remove(dim)
        val shipWorld = level.shipObjectWorld
        for (data in GamylanCageRegistry.snapshot(level)) {
            if (shipWorld.allBodies.getById(data.bodyId) == null) {
                // The record goes, so its contents must come out first: a mob, a dead player's
                // inventory or a pedestal item.
                releaseContents(level, data, data.hoverPos)
                GamylanCagePedestals.forgetBody(level, data)
                GamylanCageRegistry.remove(level, data.bodyId)
                GamylanCageBodyForce.forgetBody(data.bodyId)
                GamylanCageNetwork.broadcastRemoved(level, data.bodyId)
            }
        }
    }

    /** In singleplayer the JVM lives across worlds, and VS2 ids start again in each save. */
    fun clearSession() {
        staleSweepDeadlines.clear()
        GamylanCageRegistry.clearSession()
        org.shipwrights.enderkinesis.body.GamylanCagePedestals.clearSession()
        org.shipwrights.enderkinesis.body.GamylanCageBodyForce.clearSession()
    }
}
