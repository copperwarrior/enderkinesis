package org.shipwrights.enderkinesis.item

import com.mojang.logging.LogUtils
import dev.architectury.event.EventResult
import dev.architectury.event.events.common.InteractionEvent
import dev.architectury.event.events.common.PlayerEvent
import dev.architectury.event.events.common.TickEvent
import dev.architectury.networking.NetworkManager
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
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
        InteractionEvent.INTERACT_ENTITY.register { player, entity, hand ->
            val stack = player.getItemInHand(hand)
            if (stack.item !is WandOfGamylanItem) return@register EventResult.pass()
            if (entity !is LivingEntity) return@register EventResult.pass()
            if (entity === player) return@register EventResult.pass()
            if (entity.isPassengerOfSameVehicle(player)) return@register EventResult.pass()
            if (entity.isRemoved) return@register EventResult.pass()
            val level = player.level()
            if (level.isClientSide) return@register EventResult.interruptTrue()
            val serverLevel = level as? ServerLevel ?: return@register EventResult.pass()

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
                return@register EventResult.interruptTrue()
            }

            if (capture(serverLevel, entity)) {
                level.playSound(
                    null, entity.x, entity.y, entity.z,
                    net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_RESONATE,
                    net.minecraft.sounds.SoundSource.PLAYERS,
                    1.0f, 0.7f,
                )
            }
            EventResult.interruptTrue()
        }
        // Receive client-side attack-confirmed packets.
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, GamylanCageNetwork.CAGE_ATTACK) { buf, ctx ->
            val bodyId = buf.readLong()
            val player = ctx.player as? ServerPlayer ?: return@registerReceiver
            ctx.queue { onAttackPacket(player, bodyId) }
        }
        // Replay every loaded cage's payload to fresh viewers.
        PlayerEvent.PLAYER_JOIN.register { player ->
            GamylanCageNetwork.sendFullList(player, player.serverLevel())
        }
        PlayerEvent.CHANGE_DIMENSION.register { player, _, _ ->
            GamylanCageNetwork.sendFullList(player, player.serverLevel())
        }
        // A respawn into a different dimension is not a CHANGE_DIMENSION event.
        PlayerEvent.PLAYER_RESPAWN.register { player, _ ->
            GamylanCageNetwork.sendFullList(player, player.serverLevel())
        }
        // Per-tick: flip cages past their fade-end into the fall state.
        // The phys-tick force loop reads `fadeComplete` to decide
        // whether to keep the body pinned or let gravity take over.
        TickEvent.SERVER_LEVEL_POST.register { level ->
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
                    GamylanCageNetwork.broadcastImpact(level, data.bodyId)
                }
                if (!data.fadeComplete && now >= data.fadeEndGameTime) {
                    data.fadeComplete = true
                    dirty = true
                }
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
        val equipmentSnapshot = net.minecraft.world.entity.EquipmentSlot.values()
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
                net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_RESONATE,
                net.minecraft.sounds.SoundSource.PLAYERS,
                1.0f, 0.7f,
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
            equipment = equipmentOverride ?: net.minecraft.world.entity.EquipmentSlot.values()
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
        // One hit for each tick: a packet burst must not break the cage at once.
        if (data.lastDamageTick == level.gameTime) return
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
        GamylanCagePersistence.markDirty(level)
        GamylanCageNetwork.broadcastHp(level, bodyId, data.hp)
        if (data.hp <= 0) breakCage(level, data)
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
            net.minecraft.sounds.SoundEvents.GLASS_BREAK,
            net.minecraft.sounds.SoundSource.BLOCKS,
            1.0f, 1.0f,
        )
        GamylanCagePedestals.forgetBody(level, data)
        GamylanCageRegistry.remove(level, data.bodyId)
        GamylanCageBodyForce.forgetBody(data.bodyId)
        GamylanCageNetwork.broadcastRemoved(level, data.bodyId)
        // Delete the VS2 body itself. Uses the same
        // `vsApi.getServerShipWorld(server)` accessor Skyriders'
        // VehicleManager uses (rather than `level.shipObjectWorld`)
        // because that path resolves the SERVER-scoped world; without
        // this deleteBody() call the invisible collision hull sits in
        // physics forever, bumping entities off nothing.
        // UPSTREAM VS CORE BUG (2.4.13): standalone bodies created via
        // ShipObjectServerWorld.createBody(VsBodyCreateData) cannot be
        // deleted cleanly. In ShipObjectServerWorld.kt:
        //
        //   1056:  _allBodies.add(newBody)                       // createBody
        //   1058:  loadedStandaloneBodies.add(newBody.id)        // createBody
        //   ...
        //    791:  if (…&& loadedStandaloneBodies.add(body.id))  // per-tick wrapper loop
        //    793:      _loadedBodies.add(newBodyObject)          // (never runs — set-add returned false)
        //   ...
        //   1181:  _allBodies.remove(body.id)                    // deleteBody
        //   1182:  _loadedBodies.remove(body.id)                 // THROWS IAE — wrapper never created
        //   1184:  deletedBodies.add(body.id)                    // never runs, phys queue never notified
        //
        // Because createBody preemptively puts the id in
        // loadedStandaloneBodies, the per-tick loop's `set.add()`
        // guard returns false and _loadedBodies is never populated
        // for our body. deleteBody's second line then throws, which
        // aborts before deletedBodies gets the id — so the physics
        // engine is never told to drop the body, and the collision
        // hull persists forever.
        //
        // Nothing to work around at our level cleanly. Best fix is a
        // vs-core patch: either swap line 1182 with 1184 (queue the
        // physics delete first), guard the _loadedBodies.remove with
        // a `contains` check, or drop the preemptive
        // `loadedStandaloneBodies.add` at line 1058. See
        // vs-core/impl/src/main/kotlin/org/valkyrienskies/core/impl/
        // game/ships/ShipObjectServerWorld.kt.
        val shipWorld = org.valkyrienskies.mod.api.vsApi.getServerShipWorld(level.server)
        val body = shipWorld?.allBodies?.getById(data.bodyId)
        if (shipWorld != null && body != null) {
            try {
                shipWorld.deleteBody(body)
            } catch (e: IllegalArgumentException) {
                // See comment above — upstream bug, body stays in
                // physics until vs-core is patched.
            } catch (e: Throwable) {
                LOG.warn("[ga'mylan] deleteBody({}) failed", data.bodyId, e)
            }
        }
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
                        Matrix3d().identity().scale(CAGE_BODY_MASS),
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
            // UPSTREAM VS CORE BUG (2.4.13): `createBody` preemptively
            // adds `newBody.id` to a private `loadedStandaloneBodies`
            // set inside ShipObjectServerWorld. The per-tick loop that
            // wraps standalone bodies into `_loadedBodies` (which is
            // what the update dispatcher iterates for setTransform-
            // driven scaling and what deleteBody removes from) is
            // gated on `loadedStandaloneBodies.add(body.id)` returning
            // true — and it returns false because createBody already
            // added it. Result: our body never enters `_loadedBodies`,
            // so `setTransform` marks it dirty but nothing picks up
            // the update; Staff of Scales silently does nothing, and
            // `deleteBody` throws IAE on the missing wrapper.
            //
            // Fix in-place until vs-core is patched: reflect out the
            // preemptive add. Next tick, the orphan-check loop's
            // `loadedStandaloneBodies.add(body.id)` now returns true,
            // the wrapper is created cleanly, and both scaling +
            // deletion work end-to-end.
            unregisterPreemptiveStandaloneBody(shipWorld, body.id)
            body.id
        } catch (t: Throwable) {
            LOG.warn("[ga'mylan] VS body creation failed at {}", hoverPos, t)
            null
        }
    }

    /** Reflect into [org.valkyrienskies.core.impl.game.ships.ShipObjectServerWorld]
     *  and remove [bodyId] from its private `loadedStandaloneBodies`
     *  set. See the callsite comment in [tryCreateDynamicBody] for the
     *  full VS Core bug description. Safe no-op if the field can't be
     *  resolved (vs-core version drift) — worst case the scaling stays
     *  broken until we notice. */
    private fun unregisterPreemptiveStandaloneBody(shipWorld: Any, bodyId: Long) {
        try {
            val field = findFieldInHierarchy(shipWorld.javaClass, "loadedStandaloneBodies")
                ?: run {
                    return
                }
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val set = field.get(shipWorld) as? MutableSet<Long> ?: return
            set.remove(bodyId)
        } catch (t: Throwable) {
            LOG.warn("[ga'mylan] failed to unregister preemptive standalone body id={}", bodyId, t)
        }
    }

    private fun findFieldInHierarchy(cls: Class<*>, name: String): java.lang.reflect.Field? {
        var c: Class<*>? = cls
        while (c != null) {
            try {
                return c.getDeclaredField(name)
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            }
        }
        return null
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
