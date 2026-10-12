package org.shipwrights.enderkinesis.entity

import com.mojang.brigadier.StringReader
import net.minecraft.commands.arguments.selector.EntitySelector
import net.minecraft.commands.arguments.selector.EntitySelectorParser
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.chunk.ChunkStatus
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import org.shipwrights.enderkinesis.blockentity.Commandable
import org.shipwrights.enderkinesis.item.OldPaperItem
import org.shipwrights.enderkinesis.item.StaffOfCommandItem
import org.shipwrights.enderkinesis.util.LoadedRay
import org.shipwrights.enderkinesis.util.ShipBlockMove
import org.shipwrights.enderkinesis.util.ShipMoveListener
import org.shipwrights.enderkinesis.worldgen.ShipyardCrew
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.getShipObjectManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld

/**
 * Shulkonaut — a shulker-like gunnery officer. Sits where placed, holds a list of
 * [Commandable] block positions (transferred from a Staff of Command) and a single
 * entity target selector (given on a written book / book-and-quill's first line, e.g.
 * `@e[type=enderman]`). Every [FIRE_INTERVAL] it commands the next listed block
 * (in sequence) toward the CLOSEST entity matching the selector.
 *
 * Ship-safe by construction: the entity's own position is always world-frame (VS2
 * carries standing entities with decks), stored block positions keep the staff's
 * shipyard-coordinate convention, and per-shot dispatch re-resolves the host ship the
 * same way the staff does.
 */
class ShulkonautEntity(type: EntityType<out ShulkonautEntity>, level: Level) : Mob(type, level), ShipMoveListener, ShipyardCrew {

    private val commanded = ArrayList<StaffOfCommandItem.Companion.Entry>()
    private val commandedEntities = ArrayList<StaffOfCommandItem.Companion.EntityEntry>()
    /** One standing order: an optional FAMILY key (block/entity registry id — the
     *  staff's wheel-key space) scoping it to matching devices, a verb, and its own
     *  selector + resolved-target runtime. Key null = the default order. */
    private class StandingOrder(
        val key: String?,
        val verb: String,
        val arg: Double?,
        val selector: String,
    ) {
        /** Literal "x y z" in the selector slot — a fixed world block target. */
        val blockTarget: net.minecraft.core.BlockPos? =
            BLOCK_TARGET_REGEX.matchEntire(selector)?.let {
                net.minecraft.core.BlockPos(
                    it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt(),
                )
            }
        var parsedSelector: EntitySelector? = null
        var targetId: Int = -1
        var targetShipId: Long = -1L
        var scatterBeat: Long = -1L
        var scatterDX: Double = 0.0
        var scatterDZ: Double = 0.0
        fun hasTarget(): Boolean = targetId >= 0 || targetShipId >= 0 || blockTarget != null
    }

    /** The book's orders document — one entry per parsed line, last-wins per key. */
    private val orders = ArrayList<StandingOrder>()

    /** Order governing a device family: its own line, else the default line. */
    private fun orderForKey(key: String?): StandingOrder? =
        (if (key != null) orders.firstOrNull { it.key == key } else null)
            ?: orders.firstOrNull { it.key == null }

    /** The physical order book (kept, not consumed — returned on shift-click). The
     *  selector is its first page; structure templates ship signed books ("The
     *  Shulkommandant") directly in this slot. */
    private var orderBook: ItemStack = ItemStack.EMPTY

    private var fireSequence: Int = 0

    /** One-shot spawn processor: a structure-spawned Shulkonaut aboard a ship enrolls
     *  every Commandable on it (the roaming end ship's gunner arrives armed). Spawn-egg
     *  placements suppress it — player decks stay manually managed. */
    private var autoEnrolled = false

    /** Block position at the last save — the origin of [commanded] in a structure template. */
    private var savedAnchor: net.minecraft.core.BlockPos? = null
    private var templateRotation: Rotation = Rotation.NONE

    init {
        setPersistenceRequired()
    }

    override fun registerGoals() {
        // No goals at all: as a shipyard entity its coordinates may live in ship space,
        // where vanilla look goals compute garbage — head aiming is manual world-frame
        // math in tick() (idle players + targets alike).
    }

    /** Full turret swivel — the body never turns, so the head must. */
    override fun getMaxHeadYRot(): Int = 180
    override fun getMaxHeadXRot(): Int = 60

    /** Shulker-style axis lock: this hook is where vanilla drags the body yaw after
     *  the head (and it runs on BOTH sides — server-side zeroing alone left clients
     *  re-deriving a rotated body). Pinning it here locks the model to the grid. */
    override fun tickHeadTurn(yRot: Float, animStep: Float): Float {
        yBodyRot = 0f
        this.yRot = 0f
        return animStep
    }

    override fun isPushable(): Boolean = false
    override fun removeWhenFarAway(distanceToClosestPlayer: Double): Boolean = false

    override fun dropCustomDeathLoot(
        source: net.minecraft.world.damagesource.DamageSource,
        looting: Int,
        recentlyHit: Boolean,
    ) {
        super.dropCustomDeathLoot(source, looting, recentlyHit)
        if (!orderBook.isEmpty) {
            spawnAtLocation(orderBook)
            orderBook = ItemStack.EMPTY
        }
    }

    fun hasBook(): Boolean = entityData.get(DATA_HAS_BOOK)

    override fun defineSynchedData() {
        super.defineSynchedData()
        entityData.define(DATA_HAS_BOOK, false)
    }

    override fun mobInteract(player: Player, hand: InteractionHand): InteractionResult {
        val stack = player.getItemInHand(hand)
        // The staff handles its own interaction (block-list transfer) in
        // interactLivingEntity — which vanilla only reaches if we PASS here.
        if (stack.item is StaffOfCommandItem) return InteractionResult.PASS
        if (stack.`is`(Items.WRITTEN_BOOK) || stack.`is`(Items.WRITABLE_BOOK) || stack.item is OldPaperItem) {
            if (level().isClientSide) return InteractionResult.SUCCESS
            val lines = extractOrderLines(stack)
            if (lines.isEmpty()) {
                player.displayClientMessage(
                    Component.translatable("entity.enderkinesis.shulkonaut.bad_selector"), true,
                )
                return InteractionResult.FAIL
            }
            val parsed = ArrayList<StandingOrder>()
            for (line in lines) {
                val order = parseOrderSpec(line)
                if (order == null) {
                    player.displayClientMessage(
                        Component.translatable("entity.enderkinesis.shulkonaut.bad_selector")
                            .append(": ").append(Component.literal("unknown qualifier in \"$line\"")),
                        true,
                    )
                    return InteractionResult.FAIL
                }
                val parseError = if (order.blockTarget != null) null else selectorError(order.selector)
                if (parseError != null) {
                    player.displayClientMessage(
                        Component.translatable("entity.enderkinesis.shulkonaut.bad_selector")
                            .append(" (\"$line\"): ").append(parseError),
                        true,
                    )
                    return InteractionResult.FAIL
                }
                parsed.removeAll { it.key == order.key }   // last-wins per family
                parsed.add(order)
            }
            // Swap books: any previous order book comes back to the giver.
            if (!orderBook.isEmpty && !player.addItem(orderBook)) spawnAtLocation(orderBook)
            orderBook = stack.copyWithCount(1)
            orders.clear()
            orders.addAll(parsed)
            entityData.set(DATA_HAS_BOOK, true)
            if (!player.abilities.instabuild) stack.shrink(1)
            player.displayClientMessage(
                Component.translatable(
                    "entity.enderkinesis.shulkonaut.selector_set",
                    lines.joinToString(" | "),
                ),
                true,
            )
            return InteractionResult.CONSUME
        }
        if (stack.isEmpty && player.isShiftKeyDown && !level().isClientSide) {
            if (!orderBook.isEmpty) {
                if (!player.addItem(orderBook)) spawnAtLocation(orderBook)
                orderBook = ItemStack.EMPTY
            }
            orders.clear()
            entityData.set(DATA_HAS_BOOK, false)
            player.displayClientMessage(
                Component.translatable("entity.enderkinesis.shulkonaut.cleared"), true,
            )
            return InteractionResult.CONSUME
        }
        return super.mobInteract(player, hand)
    }

    /** Staff transfer entry point: absorbs the staff's block list. Returns how many
     *  NEW entries were added (duplicates fold). */
    fun receiveCommandedBlocks(entries: List<StaffOfCommandItem.Companion.Entry>): Int {
        var added = 0
        for (e in entries) {
            if (commanded.none { it.pos == e.pos && it.dim == e.dim }) {
                commanded.add(e)
                added++
            }
        }
        return added
    }

    /** Commanded ENTITIES (constriliars) transfer with the staff list too — the
     *  gunnery beat sends them charging at the target's position. */
    fun receiveCommandedEntities(entries: List<StaffOfCommandItem.Companion.EntityEntry>): Int {
        var added = 0
        for (e in entries) {
            if (e.uuid == uuid) continue
            if (commandedEntities.none { it.uuid == e.uuid }) {
                commandedEntities.add(e)
                added++
            }
        }
        return added
    }

    fun commandedCount(): Int = commanded.size + commandedEntities.size

    override fun finalizeSpawn(
        level: net.minecraft.world.level.ServerLevelAccessor,
        difficulty: net.minecraft.world.DifficultyInstance,
        spawnType: net.minecraft.world.entity.MobSpawnType,
        spawnData: net.minecraft.world.entity.SpawnGroupData?,
        spawnTag: CompoundTag?,
    ): net.minecraft.world.entity.SpawnGroupData? {
        if (spawnType == net.minecraft.world.entity.MobSpawnType.SPAWN_EGG ||
            spawnType == net.minecraft.world.entity.MobSpawnType.BUCKET
        ) {
            autoEnrolled = true
        }
        return super.finalizeSpawn(level, difficulty, spawnType, spawnData, spawnTag)
    }

    // Only StructureTemplate.placeEntities calls rotate then mirror, after the entity
    // loads at its placed position — so mirror() is the re-anchor point for templates.
    override fun rotate(transformRotation: Rotation): Float {
        templateRotation = transformRotation
        return super.rotate(transformRotation)
    }

    override fun mirror(transformMirror: Mirror): Float {
        reanchorFromTemplate(transformMirror, templateRotation)
        return super.mirror(transformMirror)
    }

    private fun reanchorFromTemplate(mirror: Mirror, rotation: Rotation) {
        commandedEntities.clear()   // the template strips every entity UUID
        val anchor = savedAnchor
        if (anchor == null) {
            commanded.clear()
            return
        }
        val dim = level().dimension().location()
        val origin = blockPosition()
        commanded.replaceAll { e ->
            val offset = StructureTemplate.transform(
                e.pos.subtract(anchor), mirror, rotation, net.minecraft.core.BlockPos.ZERO,
            )
            e.copy(pos = origin.offset(offset), dim = dim)
        }
    }

    override fun tick() {
        super.tick()
        val server = level() as? ServerLevel ?: return
        if (!autoEnrolled) {
            // Wait until actually aboard before enrolling: a structure-spawned gunner
            // can tick BEFORE the assembly task relocates it into the shipyard, and
            // burning the one-shot flag on a world-space tick armed it with nothing.
            // A grace window still closes the flag for genuinely ground-placed spawns.
            if (aboardShip(server) != null) {
                autoEnrolled = true
                enrollShipCommandables(server)
            } else if (tickCount > AUTO_ENROLL_GRACE_TICKS) {
                autoEnrolled = true
            }
        }
        // Staggered by entity id so a fleet of shulkonauts spreads its selector
        // evaluations and volleys across ticks instead of spiking together.
        if ((tickCount + id) % REFRESH_INTERVAL == 0) refreshOrders(server)
        // Shulker-style block alignment: pinned to the grid centre (the axis lock
        // itself lives in tickHeadTurn, which runs on both sides).
        if (onGround()) {
            val cx = Math.floor(x) + 0.5
            val cz = Math.floor(z) + 0.5
            if (x != cx || z != cz) {
                setPos(cx, y, cz)
                setDeltaMovement(0.0, deltaMovement.y, 0.0)
            }
        }
        // Head tracks the DEFAULT order's target, else any live family target.
        val headAim = orderForKey(null)?.let { currentAim(server, it) }
            ?: orders.firstNotNullOfOrNull { currentAim(server, it) }
        if (headAim != null) {
            aimHeadAt(server, headAim.pos)
        } else {
            // Idle curiosity: nearest player within 8 of the WORLD position.
            val wp = worldPos(server)
            val watcher = server.getNearestPlayer(wp.x, wp.y, wp.z, IDLE_LOOK_RANGE, false)
            if (watcher != null) aimHeadAt(server, watcher.getEyePosition(1f))
        }
        if ((tickCount + id) % FIRE_INTERVAL == 0 && orders.isNotEmpty()) {
            commandNextBlock(server)
        }
    }

    /** Sweep the host ship's chunks for [Commandable] block entities and enroll them
     *  all. Chunk BE maps, not block iteration — a large ship costs a few map walks. */
    private fun enrollShipCommandables(server: ServerLevel) {
        val ship = aboardShip(server) ?: return
        val aabb = ship.shipAABB ?: return
        val dim = server.dimension().location()
        var added = 0
        for (cx in (aabb.minX() shr 4)..(aabb.maxX() shr 4)) {
            for (cz in (aabb.minZ() shr 4)..(aabb.maxZ() shr 4)) {
                val chunk = server.getChunk(cx, cz, ChunkStatus.FULL, false) as? LevelChunk ?: continue
                for ((pos, be) in chunk.blockEntities) {
                    if (be !is Commandable) continue
                    if (pos.x < aabb.minX() || pos.x > aabb.maxX() ||
                        pos.y < aabb.minY() || pos.y > aabb.maxY() ||
                        pos.z < aabb.minZ() || pos.z > aabb.maxZ()
                    ) {
                        continue
                    }
                    if (commanded.none { it.pos == pos && it.dim == dim }) {
                        val blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                            .getKey(server.getBlockState(pos).block)
                        commanded.add(StaffOfCommandItem.Companion.Entry(pos.immutable(), dim, blockId))
                        added++
                    }
                }
            }
        }
        if (added > 0) {
        }
    }

    // ------------------------------------------------------------- shipyard framing

    /** The ship whose shipyard region this entity's raw coordinates live in — non-null
     *  exactly when it was placed aboard (spawn-egg clicks on ship decks hand out
     *  shipyard coordinates; VS2 renders and collides such entities with the ship,
     *  which is how vanilla shulkers work on ships). */
    private fun aboardShip(server: ServerLevel) =
        server.getShipObjectManagingPos(blockPosition())

    /** World-frame position: raw for world placements, ship-transformed aboard. */
    private fun worldPos(server: ServerLevel): net.minecraft.world.phys.Vec3 {
        val ship = aboardShip(server) ?: return position()
        val v = ship.transform.shipToWorld.transformPosition(org.joml.Vector3d(x, y, z))
        return net.minecraft.world.phys.Vec3(v.x, v.y, v.z)
    }

    private fun worldEye(server: ServerLevel): net.minecraft.world.phys.Vec3 =
        worldPos(server).add(0.0, eyeHeight.toDouble(), 0.0)

    /** Ship yaw (degrees) for aboard placements — head angles are stored in the
     *  entity's local frame, which VS2 renders unrotated, so world aim must be
     *  counter-rotated. */
    private fun aboardShipYawDeg(server: ServerLevel): Float {
        val ship = aboardShip(server) ?: return 0f
        val f = ship.transform.shipToWorldRotation.transform(org.joml.Vector3d(0.0, 0.0, 1.0))
        return Math.toDegrees(Math.atan2(-f.x, f.z)).toFloat()
    }

    /** Manual head aim in world frame (both idle and target tracking). */
    private fun aimHeadAt(server: ServerLevel, targetEye: net.minecraft.world.phys.Vec3) {
        val eye = worldEye(server)
        val dx = targetEye.x - eye.x
        val dy = targetEye.y - eye.y
        val dz = targetEye.z - eye.z
        val horizontal = Math.sqrt(dx * dx + dz * dz)
        val worldYaw = Math.toDegrees(Math.atan2(-dx, dz)).toFloat()
        val pitch = (-Math.toDegrees(Math.atan2(dy, horizontal))).toFloat().coerceIn(-60f, 60f)
        val localYaw = Mth.wrapDegrees(worldYaw - aboardShipYawDeg(server))
        yHeadRot = Mth.approachDegrees(yHeadRot, localYaw, HEAD_TURN_SPEED)
        xRot = Mth.approachDegrees(xRot, pitch, HEAD_TURN_SPEED)
    }

    /** Aim at the vessel's VISUAL centre (world AABB midpoint) — the transform anchor
     *  is the centre of MASS, which heavy cargo skews away from the hull's body; the
     *  staff's gaze lands on the ship itself, so the gunner should too. */
    private fun shipAimPoint(ship: org.valkyrienskies.core.api.ships.Ship): net.minecraft.world.phys.Vec3 {
        val box = ship.worldAABB
        return net.minecraft.world.phys.Vec3(
            (box.minX() + box.maxX()) * 0.5,
            (box.minY() + box.maxY()) * 0.5,
            (box.minZ() + box.maxZ()) * 0.5,
        )
    }

    private fun canSeePos(server: ServerLevel, to: net.minecraft.world.phys.Vec3): Boolean =
        worldOnlyRayMisses(server, worldEye(server), to)

    /** World-terrain-only line of sight from the WORLD eye — ships never occlude. */
    private fun canSee(server: ServerLevel, entity: Entity): Boolean =
        worldOnlyRayMisses(server, worldEye(server), entity.getEyePosition(1f))

    /** TRUE world-only raycast. Level.clip is NOT usable here: VS2 mixin-replaces it
     *  with the ship-inclusive raycast, which (a) made ship hulls occlude and (b)
     *  crashed the server reading unloaded shipyard chunks when a ray crossed a far
     *  ship's AABB. This walks the raw block traversal beneath vanilla clip — no VS2
     *  hook — and treats unloaded terrain as occluding rather than crashing. */
    private fun worldOnlyRayMisses(
        server: ServerLevel,
        from: net.minecraft.world.phys.Vec3,
        to: net.minecraft.world.phys.Vec3,
    ): Boolean {
        val ctx = net.minecraft.world.level.ClipContext(
            from, to,
            net.minecraft.world.level.ClipContext.Block.COLLIDER,
            net.minecraft.world.level.ClipContext.Fluid.NONE,
            this,
        )
        val hit = net.minecraft.world.level.BlockGetter.traverseBlocks(
            from, to, ctx,
            { c, pos ->
                if (!LoadedRay.blockLoaded(server, pos)) {
                    // Unverifiable terrain = occluded; never a sync chunk load.
                    net.minecraft.world.phys.BlockHitResult(
                        net.minecraft.world.phys.Vec3.atCenterOf(pos),
                        net.minecraft.core.Direction.UP, pos, false,
                    )
                } else {
                    val state = server.getBlockState(pos)
                    val shape = c.getBlockShape(state, server, pos)
                    server.clipWithInteractionOverride(from, to, pos, shape, state)
                }
            },
            { null },
        )
        return hit == null
    }

    /** Refresh every standing order's target. Evaluation is server-thread by
     *  necessity (entity lists aren't safe off-thread); performance comes from the
     *  per-order parse cache, the refresh stagger, and a small order count. */
    private fun refreshOrders(server: ServerLevel) {
        if (commanded.isEmpty() && commandedEntities.isEmpty()) {
            for (o in orders) { o.targetId = -1; o.targetShipId = -1L }
            return
        }
        for (o in orders) refreshOrderTarget(server, o)
    }

    private fun refreshOrderTarget(server: ServerLevel, order: StandingOrder) {
        if (order.blockTarget != null) {
            order.targetId = -1
            order.targetShipId = -1L
            return
        }
        if (order.selector == VESSEL_SELECTOR) {
            refreshVesselTarget(server, order)
            return
        }
        order.targetShipId = -1L
        try {
            if (order.parsedSelector == null) {
                order.parsedSelector = parseLikeCommands(order.selector)
            }
            val source = createCommandSourceStack().withPermission(2).withSuppressedOutput()
            val found = order.parsedSelector!!.findEntities(source)
            // Closest VISIBLE match, measured from the WORLD position (aboard a ship
            // the raw coordinates are shipyard-frame). LOS skips ships by riding the
            // vanilla world-only clip. Closest-first with a raycast cap.
            val wp = worldPos(server)
            val closest = found.asSequence()
                // A selector can return entities of all dimensions.
                .filter { it !== this && it.isAlive && it.level() === server }
                .sortedBy { it.position().distanceToSqr(wp) }
                .take(LOS_CHECK_LIMIT)
                .firstOrNull { canSee(server, it) }
            order.targetId = closest?.id ?: -1
        } catch (_: com.mojang.brigadier.exceptions.CommandSyntaxException) {
            order.targetId = -1
        }
    }

    /** @v: closest loaded ship with terrain line of sight, excluding its own host —
     *  a gunner must never volley the deck it stands on. */
    private fun refreshVesselTarget(server: ServerLevel, order: StandingOrder) {
        order.targetId = -1
        val ownShipId = aboardShip(server)?.id
        val wp = worldPos(server)
        var best: Long = -1L
        var bestDistSq = Double.MAX_VALUE
        val dimId = server.dimensionId
        for (ship in server.shipObjectWorld.loadedShips) {
            if (ship.id == ownShipId) continue
            // loadedShips is server-wide: without this, an End gunner could "target"
            // an overworld ship and volley its coordinates into the wrong dimension.
            if (ship.chunkClaimDimension != dimId) continue
            val p = shipAimPoint(ship)
            val d = wp.distanceToSqr(p)
            if (d > VESSEL_RANGE_SQ || d >= bestDistSq) continue
            if (canSeePos(server, p)) {
                bestDistSq = d
                best = ship.id
            }
        }
        order.targetShipId = best
    }

    /** Current order: an entity to lock, or a world point + SOLID shipyard anchor
     *  block for vessel targets (point-target missiles validate against a real block,
     *  like the staff's gaze stamp). */
    private class Aim(
        val entity: Entity?,
        val pos: net.minecraft.world.phys.Vec3,
        val anchor: net.minecraft.core.BlockPos?,
    )

    private fun currentAim(server: ServerLevel, order: StandingOrder): Aim? {
        val bt = order.blockTarget
        if (bt != null) {
            val base = net.minecraft.world.phys.Vec3.atCenterOf(bt)
            val pos = applyOrderVerb(server, order, base, net.minecraft.world.phys.Vec3.ZERO)
            // The block anchors TO-shots only while it EXISTS — a literal position in
            // the air (or a since-mined block) fires airburst point shots instead.
            val anchor = if (order.verb == VERB_TO && LoadedRay.blockLoaded(server, bt) &&
                !server.getBlockState(bt).isAir
            ) bt else null
            return Aim(null, pos, anchor)
        }
        val entity = currentTarget(server, order)
        if (entity != null) {
            val base = entity.getEyePosition(1f)
            val vel = entity.deltaMovement.scale(20.0)   // per-tick → per-second
            val pos = applyOrderVerb(server, order, base, vel)
            return Aim(entity, pos, null)
        }
        if (order.targetShipId >= 0) {
            val ownShipId = aboardShip(server)?.id
            val ship = server.shipObjectWorld.loadedShips.getById(order.targetShipId)
            if (ship != null && ship.id != ownShipId) {
                val anchor = solidShipAnchor(server, ship)
                // Aim at the anchor block's WORLD spot when we have one (a guaranteed
                // hull hit); the AABB centre is the fallback aim.
                val base = if (anchor != null) {
                    val v = ship.shipToWorld.transformPosition(
                        org.joml.Vector3d(anchor.x + 0.5, anchor.y + 0.5, anchor.z + 0.5),
                    )
                    net.minecraft.world.phys.Vec3(v.x, v.y, v.z)
                } else {
                    shipAimPoint(ship)
                }
                val v = ship.velocity
                val pos = applyOrderVerb(server, order, base, net.minecraft.world.phys.Vec3(v.x(), v.y(), v.z()))
                // The hull anchor only means "this exact block" for a plain TO — a
                // transformed aim is a point in space, and passing the anchor would
                // make missiles snap back to the hull instead of chasing the point.
                return Aim(null, pos, if (order.verb == VERB_TO) anchor else null)
            }
            order.targetShipId = -1L
        }
        return null
    }

    /** The qualifier seam: every consumer (eyeroscope pins, constriliar move orders,
     *  missile point shots, head-look) receives the TRANSFORMED point. [velPerSec] is
     *  the target's world velocity (entity or ship) for LEAD/BEHIND. */
    private fun applyOrderVerb(
        server: ServerLevel,
        order: StandingOrder,
        base: net.minecraft.world.phys.Vec3,
        velPerSec: net.minecraft.world.phys.Vec3,
    ): net.minecraft.world.phys.Vec3 {
        val self = worldPos(server)
        return when (order.verb) {
            VERB_AROUND -> {
                val r = order.arg ?: 8.0
                val theta = tickCount * (2.0 * Math.PI / ORBIT_PERIOD_TICKS)
                base.add(Math.cos(theta) * r, 0.0, Math.sin(theta) * r)
            }
            VERB_NEAR -> {
                val d = order.arg ?: 8.0
                val toTarget = base.subtract(self)
                val len = toTarget.length()
                if (len < 1e-3) base else base.subtract(toTarget.scale(Math.min(d, len * 0.5) / len))
            }
            VERB_ABOVE -> base.add(0.0, order.arg ?: 8.0, 0.0)
            VERB_BEHIND -> {
                val d = order.arg ?: 8.0
                val speed = velPerSec.horizontalDistance()
                val dir = if (speed > 0.05) {
                    net.minecraft.world.phys.Vec3(velPerSec.x, 0.0, velPerSec.z).scale(1.0 / speed)
                } else {
                    // Stationary target: "past it" means the far side from us.
                    val away = net.minecraft.world.phys.Vec3(base.x - self.x, 0.0, base.z - self.z)
                    val l = away.length()
                    if (l < 1e-3) return base
                    away.scale(1.0 / l)
                }
                base.add(dir.scale(d))
            }
            VERB_LEAD -> base.add(velPerSec.scale(order.arg ?: 1.0))
            VERB_SCATTER -> {
                val r = order.arg ?: 6.0
                val beat = (tickCount + id).toLong() / FIRE_INTERVAL
                if (beat != order.scatterBeat) {
                    order.scatterBeat = beat
                    val angle = random.nextDouble() * 2.0 * Math.PI
                    val dist = Math.sqrt(random.nextDouble()) * r
                    order.scatterDX = Math.cos(angle) * dist
                    order.scatterDZ = Math.sin(angle) * dist
                }
                base.add(order.scatterDX, 0.0, order.scatterDZ)
            }
            VERB_BETWEEN -> self.add(base.subtract(self).scale(0.5))
            else -> base   // TO, WATCH: plain aim; WATCH filters dispatch instead.
        }
    }

    private fun solidShipAnchor(
        server: ServerLevel,
        ship: org.valkyrienskies.core.api.ships.Ship,
    ): net.minecraft.core.BlockPos? =
        org.shipwrights.enderkinesis.util.ShipTargeting.solidShipAnchor(server, ship)

    private fun currentTarget(server: ServerLevel, order: StandingOrder): Entity? {
        if (order.targetId < 0) return null
        val e = server.getEntity(order.targetId)
        return if (e != null && e.isAlive) e else null
    }

    /** The 2-second gunnery beat: the next listed block or constriliar IN SEQUENCE,
     *  each dispatched under ITS OWN standing order (family line, else the default).
     *  Devices whose governing order has no live target don't occupy beats. */
    override fun onShipBlocksMoved(level: ServerLevel, move: ShipBlockMove) {
        val dim = level.dimension().location()
        commanded.replaceAll { entry ->
            if (entry.dim == dim && move.contains(entry.pos)) entry.copy(pos = move.mapBlock(entry.pos)) else entry
        }
    }

    override fun onAssembledToShipyard(offset: net.minecraft.core.BlockPos) {
        val dim = level().dimension().location()
        commanded.replaceAll { if (it.dim == dim) it.copy(pos = it.pos.offset(offset)) else it }
    }

    private fun isDeviceGone(server: ServerLevel, entry: StaffOfCommandItem.Companion.Entry): Boolean {
        val state = server.getBlockState(entry.pos)
        val id = entry.blockId ?: return state.isAir
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.block) != id
    }

    private fun commandNextBlock(server: ServerLevel) {
        val dim = server.dimension().location()
        commanded.removeAll { it.dim == dim && LoadedRay.blockLoaded(server, it.pos) && isDeviceGone(server, it) }
        val blocks = commanded.filter {
            it.dim == dim && LoadedRay.blockLoaded(server, it.pos) &&
                orderForKey(it.blockId?.toString())?.hasTarget() == true
        }
        // An entity that does not resolve can be in an unloaded chunk, so it stays in the list.
        val minions = commandedEntities.filter {
            it.dim == dim && orderForKey(it.typeId.toString())?.hasTarget() == true &&
                server.getEntity(it.uuid) != null
        }
        val total = blocks.size + minions.size
        if (total == 0) return
        val pick = fireSequence % total
        fireSequence = (fireSequence + 1) % total.coerceAtLeast(1)
        if (pick < blocks.size) {
            val entry = blocks[pick]
            val order = orderForKey(entry.blockId?.toString()) ?: return
            val aim = currentAim(server, order) ?: return
            val watch = order.verb == VERB_WATCH
            val ship = server.getLoadedShipManagingPos(entry.pos)
            val be = server.getBlockEntity(entry.pos)
            val responded: Boolean = when {
                be is Commandable -> {
                    if (watch) {
                        // WATCH: orient, never actuate. Devices with no passive
                        // orientation return false and stay quiet.
                        be.executeAutoAim(aim.pos, ship)
                    } else if (aim.entity != null && order.verb == VERB_TO) {
                        be.executeAutoCommand(aim.entity, ship)
                        true
                    } else {
                        be.executeAutoCommandAt(aim.pos, aim.anchor, ship)
                        true
                    }
                }
                !watch -> {
                    // Staff-parity: vanilla blocks (levers, doors, TNT, pistons…)
                    // actuate through the same handler the staff trigger uses.
                    val state = server.getBlockState(entry.pos)
                    org.shipwrights.enderkinesis.item.VanillaCommandables.supports(state) &&
                        org.shipwrights.enderkinesis.item.VanillaCommandables.trigger(null, server, entry.pos, state)
                }
                else -> false
            }
            if (responded) StaffOfCommandItem.spawnBlockSpark(server, entry.pos, ship)
        } else {
            val entry = minions[pick - blocks.size]
            val order = orderForKey(entry.typeId.toString()) ?: return
            val aim = currentAim(server, order) ?: return
            val watch = order.verb == VERB_WATCH
            when (val e = server.getEntity(entry.uuid)) {
                is ConstriliarEntity -> {
                    if (watch) {
                        e.commandFaceToward(aim.pos)
                    } else {
                        e.commandMoveTo(if (order.verb == VERB_TO) aim.entity?.position() ?: aim.pos else aim.pos)
                    }
                    StaffOfCommandItem.spawnBlockSpark(server, e.blockPosition(), null)
                }
                is net.minecraft.world.entity.boss.enderdragon.EndCrystal -> {
                    // Staff-parity: trigger detonates (vanilla shot-the-crystal
                    // branch); aim/WATCH skips — a crystal has nothing to orient.
                    if (!watch) {
                        StaffOfCommandItem.spawnBlockSpark(server, e.blockPosition(), null)
                        e.hurt(server.damageSources().generic(), 1.0f)
                    }
                }
                else -> return
            }
        }
    }

    /** Worldgen order issue: parse + adopt [line] and rewrite the held order book's
     *  page to match, so looting the book shows this ship's actual standing orders.
     *  False when the line doesn't parse (caller keeps the template default). */
    fun issueOrder(line: String): Boolean {
        val order = parseOrderSpec(line) ?: return false
        if (order.blockTarget == null && selectorError(order.selector) != null) return false
        orders.clear()
        orders.add(order)
        entityData.set(DATA_HAS_BOOK, true)
        if (!orderBook.isEmpty) {
            val pages = net.minecraft.nbt.ListTag()
            pages.add(
                net.minecraft.nbt.StringTag.valueOf(
                    Component.Serializer.toJson(Component.literal(line)),
                ),
            )
            orderBook.getOrCreateTag().put("pages", pages)
        }
        return true
    }

    /** `[family:id] [VERB[(arg)]: ] selector` → a standing order. The leading token
     *  is a family key iff it's a valid registry id (contains ':', no '@') — verbs
     *  have no colon-in-key shape and selectors start with '@', so the grammar can't
     *  collide. Bare selector = TO. Null only for an UNKNOWN qualifier. */
    private fun parseOrderSpec(line: String): StandingOrder? {
        var rest = line.trim()
        var key: String? = null
        val space = rest.indexOf(' ')
        if (space > 0) {
            val tok = rest.substring(0, space)
            if (!tok.startsWith("@") && tok.contains(':') &&
                ResourceLocation.tryParse(tok) != null
            ) {
                key = tok
                rest = rest.substring(space + 1).trim()
            }
        }
        val m = ORDER_REGEX.matchEntire(rest) ?: return StandingOrder(key, VERB_TO, null, rest)
        var verb = m.groupValues[1].uppercase()
        if (verb == VERB_AT) verb = VERB_TO   // AT reads naturally after a family key
        if (verb !in KNOWN_VERBS) return null
        val arg = m.groupValues[2].takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        return StandingOrder(key, verb, arg, m.groupValues[3].trim())
    }

    /** Every non-blank line across ALL pages — plain text for book-and-quill, JSON
     *  component text per page for a signed written book. */
    private fun extractOrderLines(stack: ItemStack): List<String> {
        val pages = stack.tag?.getList("pages", Tag.TAG_STRING.toInt()) ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until pages.size) {
            val raw = pages.getString(i)
            val text = if (stack.`is`(Items.WRITTEN_BOOK) || stack.item is OldPaperItem) {
                runCatching { Component.Serializer.fromJson(raw)?.string }.getOrNull() ?: raw
            } else {
                raw
            }
            text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { out.add(it) }
        }
        return out
    }

    /** Null when the selector parses; otherwise the parser's own error message —
     *  surfaced verbatim so a typo reads exactly like it would in a command. `@v` is
     *  the Shulkonaut's own vessel selector (VS2 ships), always valid. */
    private fun selectorError(selector: String): Component? {
        if (selector == VESSEL_SELECTOR) return null
        return try {
            parseLikeCommands(selector)
            null
        } catch (e: com.mojang.brigadier.exceptions.CommandSyntaxException) {
            Component.literal(e.rawMessage.string)
        }
    }

    override fun addAdditionalSaveData(tag: CompoundTag) {
        super.addAdditionalSaveData(tag)
        val orderList = ListTag()
        for (o in orders) {
            val c = CompoundTag()
            o.key?.let { c.putString(NBT_ORDER_KEY, it) }
            c.putString(NBT_ORDER_VERB, o.verb)
            o.arg?.let { c.putDouble(NBT_ORDER_ARG, it) }
            c.putString(NBT_SELECTOR, o.selector)
            orderList.add(c)
        }
        tag.put(NBT_ORDERS, orderList)
        tag.putBoolean(NBT_AUTO_ENROLLED, autoEnrolled)
        tag.putLong(NBT_ANCHOR, blockPosition().asLong())
        if (!orderBook.isEmpty) tag.put(NBT_ORDER_BOOK, orderBook.save(CompoundTag()))
        val list = ListTag()
        for (e in commanded) {
            val c = CompoundTag()
            c.putLong(StaffOfCommandItem.POS_TAG, e.pos.asLong())
            c.putString(StaffOfCommandItem.DIM_TAG, e.dim.toString())
            e.blockId?.let { c.putString(StaffOfCommandItem.BLOCK_TAG, it.toString()) }
            list.add(c)
        }
        tag.put(NBT_BLOCKS, list)
        val entityList = ListTag()
        for (e in commandedEntities) {
            val c = CompoundTag()
            c.putUUID(StaffOfCommandItem.UUID_TAG, e.uuid)
            c.putString(StaffOfCommandItem.DIM_TAG, e.dim.toString())
            c.putString(StaffOfCommandItem.TYPE_TAG, e.typeId.toString())
            entityList.add(c)
        }
        tag.put(NBT_ENTITIES, entityList)
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)
        orders.clear()
        if (tag.contains(NBT_ORDERS)) {
            val orderList = tag.getList(NBT_ORDERS, Tag.TAG_COMPOUND.toInt())
            for (i in 0 until orderList.size) {
                val c = orderList.getCompound(i)
                orders.add(
                    StandingOrder(
                        c.getString(NBT_ORDER_KEY).takeIf { it.isNotEmpty() },
                        c.getString(NBT_ORDER_VERB).takeIf { it.isNotEmpty() } ?: VERB_TO,
                        if (c.contains(NBT_ORDER_ARG)) c.getDouble(NBT_ORDER_ARG) else null,
                        c.getString(NBT_SELECTOR),
                    ),
                )
            }
        } else if (tag.getString(NBT_SELECTOR).isNotEmpty()) {
            // Legacy single-order fields → the default order.
            orders.add(
                StandingOrder(
                    null,
                    tag.getString(NBT_ORDER_VERB).takeIf { it.isNotEmpty() } ?: VERB_TO,
                    if (tag.contains(NBT_ORDER_ARG)) tag.getDouble(NBT_ORDER_ARG) else null,
                    tag.getString(NBT_SELECTOR),
                ),
            )
        }
        autoEnrolled = tag.getBoolean(NBT_AUTO_ENROLLED)
        savedAnchor = if (tag.contains(NBT_ANCHOR)) net.minecraft.core.BlockPos.of(tag.getLong(NBT_ANCHOR)) else null
        orderBook = if (tag.contains(NBT_ORDER_BOOK)) {
            ItemStack.of(tag.getCompound(NBT_ORDER_BOOK))
        } else {
            ItemStack.EMPTY
        }
        // A shipped order book IS the orders source when none are stored (structure
        // templates sign real books instead of writing raw NBT strings).
        if (orders.isEmpty() && !orderBook.isEmpty) {
            for (line in extractOrderLines(orderBook)) {
                val order = parseOrderSpec(line) ?: continue
                if (order.blockTarget == null && selectorError(order.selector) != null) continue
                orders.removeAll { it.key == order.key }
                orders.add(order)
            }
        }
        entityData.set(DATA_HAS_BOOK, orders.isNotEmpty())
        commanded.clear()
        val list = tag.getList(NBT_BLOCKS, Tag.TAG_COMPOUND.toInt())
        for (i in 0 until list.size) {
            val c = list.getCompound(i)
            val dim = ResourceLocation.tryParse(c.getString(StaffOfCommandItem.DIM_TAG)) ?: continue
            commanded.add(
                StaffOfCommandItem.Companion.Entry(
                    net.minecraft.core.BlockPos.of(c.getLong(StaffOfCommandItem.POS_TAG)),
                    dim,
                    ResourceLocation.tryParse(c.getString(StaffOfCommandItem.BLOCK_TAG)),
                ),
            )
        }
        commandedEntities.clear()
        val entityList = tag.getList(NBT_ENTITIES, Tag.TAG_COMPOUND.toInt())
        for (i in 0 until entityList.size) {
            val c = entityList.getCompound(i)
            if (!c.hasUUID(StaffOfCommandItem.UUID_TAG)) continue
            val dim = ResourceLocation.tryParse(c.getString(StaffOfCommandItem.DIM_TAG)) ?: continue
            commandedEntities.add(
                StaffOfCommandItem.Companion.EntityEntry(
                    c.getUUID(StaffOfCommandItem.UUID_TAG), dim,
                    ResourceLocation.tryParse(c.getString(StaffOfCommandItem.TYPE_TAG))
                        ?: StaffOfCommandItem.CONSTRILIAR_ID,
                ),
            )
        }
    }

    companion object {
        const val ID_PATH = "shulkonaut"

        private const val NBT_SELECTOR = "EkTargetSelector"
        private const val NBT_BLOCKS = "EkCommandedBlocks"
        private const val NBT_ENTITIES = "EkCommandedEntities"
        private const val NBT_AUTO_ENROLLED = "EkAutoEnrolled"
        private const val NBT_ANCHOR = "EkAnchor"
        private const val NBT_ORDER_BOOK = "EkOrderBook"
        private const val NBT_ORDER_VERB = "EkOrderVerb"
        private const val NBT_ORDER_ARG = "EkOrderArg"
        private const val NBT_ORDERS = "EkOrders"
        private const val NBT_ORDER_KEY = "EkOrderKey"

        private const val VERB_TO = "TO"
        private const val VERB_AROUND = "AROUND"
        private const val VERB_NEAR = "NEAR"
        private const val VERB_ABOVE = "ABOVE"
        private const val VERB_BEHIND = "BEHIND"
        private const val VERB_LEAD = "LEAD"
        private const val VERB_SCATTER = "SCATTER"
        private const val VERB_BETWEEN = "BETWEEN"
        private const val VERB_WATCH = "WATCH"
        /** Alias of TO — reads naturally after a family key. Normalized at parse. */
        private const val VERB_AT = "AT"
        private val KNOWN_VERBS = setOf(
            VERB_TO, VERB_AROUND, VERB_NEAR, VERB_ABOVE, VERB_BEHIND,
            VERB_LEAD, VERB_SCATTER, VERB_BETWEEN, VERB_WATCH,
        )
        private val ORDER_REGEX = Regex("""^([A-Za-z]+)\s*(?:\(\s*([-\d.]+)\s*\))?\s*:\s*(.+)$""")
        /** Literal block-position target: three integers in the selector slot. */
        private val BLOCK_TARGET_REGEX = Regex("""^(-?\d+)\s+(-?\d+)\s+(-?\d+)$""")
        /** Full AROUND orbit each 10 s. */
        private const val ORBIT_PERIOD_TICKS = 200.0

        private const val REFRESH_INTERVAL = 20
        /** Ticks a fresh spawn waits to end up aboard before auto-enroll gives up. */
        private const val AUTO_ENROLL_GRACE_TICKS = 200
        private const val FIRE_INTERVAL = 40
        /** Max line-of-sight raycasts per refresh (closest candidates first). */
        private const val LOS_CHECK_LIMIT = 10
        private const val IDLE_LOOK_RANGE = 8.0
        private const val HEAD_TURN_SPEED = 12f
        /** The Shulkonaut's own vessel selector: closest VS2 ship, never its host. */
        private const val VESSEL_SELECTOR = "@v"
        /** Vessel engagement range — also bounds LOS rays to loaded-ish terrain. */
        private const val VESSEL_RANGE_SQ = 192.0 * 192.0

        /** Exactly the argument type commands use — any selector /execute accepts,
         *  the Shulkonaut accepts (predicate=, scores=, nbt=, all of it). */
        private fun parseLikeCommands(selector: String): EntitySelector =
            net.minecraft.commands.arguments.EntityArgument.entities().parse(StringReader(selector))

        private val DATA_HAS_BOOK: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(ShulkonautEntity::class.java, EntityDataSerializers.BOOLEAN)

        fun createAttributes(): AttributeSupplier.Builder = createMobAttributes()
            .add(Attributes.MAX_HEALTH, 30.0)
            .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
            .add(Attributes.MOVEMENT_SPEED, 0.0)

        @Suppress("UNUSED_PARAMETER")
        fun category(): MobCategory = MobCategory.MISC
    }
}
