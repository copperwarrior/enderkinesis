package org.shipwrights.enderkinesis.client

import dev.architectury.event.EventResult
import dev.architectury.event.events.client.ClientRawInputEvent
import dev.architectury.event.events.client.ClientTickEvent
import dev.architectury.networking.NetworkManager
import net.minecraft.client.Minecraft
import org.shipwrights.enderkinesis.registry.EKParticles
import org.valkyrienskies.core.api.ships.ClientShip
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.item.WyllandTomeItem
import org.shipwrights.enderkinesis.item.WyllandTomeNetwork
import org.shipwrights.enderkinesis.registry.EKItems
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld

/**
 * Client-side glue for the Wylland Tome.
 *
 *  - Watches the attack key while the player holds a tome. On press,
 *    raycasts from the player and tells the server which ship/entity
 *    to grab. On release, tells the server to drop the grab.
 *  - Each tick while grabbing, spawns ENCHANT particles along the line
 *    from the player's chest to the **server-synced grab point** on the
 *    target — reads as a continuous beam terminating on the actual
 *    contact point, not a guessed look-projected location.
 *  - Receives [WyllandTomeNetwork.GRAB_POINT_SYNC] each server tick and
 *    caches the world position for beam rendering.
 *
 * The server is the source of truth for the grab itself (see
 * [org.shipwrights.enderkinesis.item.WyllandTomeManager]); the client
 * is purely an input + visual feedback layer.
 */
object WyllandTomeClient {

    private var attackHeldLastTick = false
    private var grabbing = false

    /** Public read-only view of the local-client grab state, for
     *  consumers that need to know whether the tome is actively
     *  being used. Used by [WyllandTomeBEWLR] to switch the
     *  in-hand page animation from the idle gentle flop to the
     *  rapid right-to-left riffle while a grab is held. Mutation
     *  happens only on the client tick thread (in [tickInput] /
     *  [tryBeginGrab]); a stale read across threads resolves on
     *  the next render frame at worst. */
    fun isGrabbing(): Boolean = grabbing

    /** Latest grab-anchor world position received from the server. Used
     *  as the beam's far endpoint. Null until the first sync arrives or
     *  after a grab ends. */
    @Volatile private var syncedAnchor: Vec3? = null

    /** Latest cursor (look-projected spring target) world position from
     *  the server. Used as the bezier control point that bows the beam
     *  toward the cursor when the ship lags behind it. */
    @Volatile private var syncedCursor: Vec3? = null

    /** Id of the ship currently grabbed, or null for an entity grab / no
     *  grab. Drives the enchanted-book rain spawned inside the ship's
     *  local AABB each tick. */
    @Volatile private var syncedShipId: Long? = null

    fun init() {
        ClientTickEvent.CLIENT_POST.register(ClientTickEvent.Client { mc ->
            tickInput(mc)
        })
        // Scroll behaviour while holding the tome:
        //  - mod key DOWN + grabbing → roll the ship around the player's
        //    look direction. Positive scroll-amount (wheel up) rolls
        //    counter-clockwise from the player's POV.
        //  - mod key DOWN + not grabbing → swallow (hotbar would be
        //    distracting while the player is staging a grab).
        //  - mod key UP  + grabbing → push/pull along the look ray.
        //    Positive scroll-amount pulls closer.
        //  - otherwise → fall through to vanilla hotbar cycling.
        ClientRawInputEvent.MOUSE_SCROLLED.register(
            ClientRawInputEvent.MouseScrolled { mc, amount ->
                val player = mc.player ?: return@MouseScrolled EventResult.pass()
                if (player.mainHandItem.item !== EKItems.WYLLAND_TOME.get())
                    return@MouseScrolled EventResult.pass()
                if (WyllandTomeKeys.isModKeyDown()) {
                    if (grabbing) {
                        WyllandTomeNetwork.sendApplyRoll(amount * WyllandTomeItem.SCROLL_ROLL_STEP)
                    }
                    return@MouseScrolled EventResult.interruptFalse()
                }
                if (!grabbing) return@MouseScrolled EventResult.pass()
                WyllandTomeNetwork.sendAdjustDistance(amount * WyllandTomeItem.SCROLL_DISTANCE_STEP)
                EventResult.interruptFalse()
            }
        )
        // S2C grab-point sync — server tells us where the anchor and
        // the cursor (look-projected spring target) currently sit in
        // world space. The beam uses anchor as its endpoint and cursor
        // as a bezier control point.
        NetworkManager.registerReceiver(
            NetworkManager.Side.S2C,
            WyllandTomeNetwork.GRAB_POINT_SYNC,
        ) { buf, _ ->
            val hasShip = buf.readBoolean()
            val shipId = buf.readLong()
            val ax = buf.readDouble()
            val ay = buf.readDouble()
            val az = buf.readDouble()
            val cx = buf.readDouble()
            val cy = buf.readDouble()
            val cz = buf.readDouble()
            syncedAnchor = Vec3(ax, ay, az)
            syncedCursor = Vec3(cx, cy, cz)
            syncedShipId = if (hasShip) shipId else null
        }
        // S2C grab-end — server cut the connection (max distance, dragged
        // by ship, target despawned…). Stop drawing the beam immediately.
        NetworkManager.registerReceiver(
            NetworkManager.Side.S2C,
            WyllandTomeNetwork.GRAB_END,
        ) { _, _ ->
            grabbing = false
            syncedAnchor = null
            syncedCursor = null
            syncedShipId = null
            BeamRegistry.remove(BeamRegistry.PLAYER_BEAM_ID)
        }
    }

    /** Hook from [LocalPlayerWyllandTomeTurnMixin]. While the player is
     *  holding the tome with an active grab AND the mod key is held,
     *  mouse motion routes into an explicit rotation packet for the
     *  grabbed object instead of turning the player's camera. Returns
     *  true to tell the mixin to cancel the {@code LocalPlayer.turn}
     *  call.
     *
     *  This is the GMod E-while-holding pose — without the mod key,
     *  camera + view turn normally and the server's per-tick
     *  camera-delta rotation handles ship orientation. With the mod
     *  key the camera locks, mouse-to-rotation is direct, and the
     *  camera-delta path contributes zero (since xRot/yRot are
     *  frozen). */
    fun captureTurnIfRotating(yawDelta: Double, pitchDelta: Double): Boolean {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return false
        if (player.mainHandItem.item !== EKItems.WYLLAND_TOME.get()) return false
        if (!grabbing) return false
        if (!WyllandTomeKeys.isModKeyDown()) return false
        // Scaled by MOUSE_ROTATE_SENSITIVITY (lower than vanilla's 0.15
        // turn() factor) so fine wrist motion translates to slow,
        // controlled ship rotation instead of camera-speed whip.
        val yawDeg = yawDelta * WyllandTomeItem.MOUSE_ROTATE_SENSITIVITY
        val pitchDeg = pitchDelta * WyllandTomeItem.MOUSE_ROTATE_SENSITIVITY
        if (yawDeg != 0.0 || pitchDeg != 0.0) {
            WyllandTomeNetwork.sendRotateInput(yawDeg, pitchDeg)
        }
        return true
    }

    private fun tickInput(mc: Minecraft) {
        val player: Player = mc.player ?: return
        mc.level ?: return
        val holdingTome = player.mainHandItem.item === EKItems.WYLLAND_TOME.get()
        if (!holdingTome) {
            if (grabbing) {
                WyllandTomeNetwork.sendRelease()
                grabbing = false
                syncedAnchor = null
                syncedCursor = null
                syncedShipId = null
            }
            attackHeldLastTick = false
            return
        }
        val attackHeld = mc.options.keyAttack.isDown

        if (attackHeld && !attackHeldLastTick) {
            tryBeginGrab(mc, player)
        } else if (!attackHeld && attackHeldLastTick && grabbing) {
            WyllandTomeNetwork.sendRelease()
            grabbing = false
            syncedAnchor = null
            syncedCursor = null
            syncedShipId = null
            BeamRegistry.remove(BeamRegistry.PLAYER_BEAM_ID)
        }

        if (grabbing) {
            spawnBeamParticles(mc, player)
            spawnShipParticles(mc)
        }

        attackHeldLastTick = attackHeld
    }

    /** Raycast forward from the player's eye and try to grab whatever
     *  the crosshair finds — a VS2 ship (via block hit on its shipyard
     *  block, then looking up the managing ship) or a non-player entity. */
    private fun tryBeginGrab(mc: Minecraft, player: Player) {
        val eye = player.eyePosition
        val look = player.lookAngle
        val reach = WyllandTomeItem.PICKUP_REACH
        val end = eye.add(look.scale(reach))
        val level = player.level()

        // Entity hit takes precedence (otherwise grabbing an entity in
        // front of a wall would always grab the wall instead).
        val entityHit = pickEntity(player, eye, end)
        if (entityHit != null) {
            val target = entityHit.entity
            if (target !is Player) {
                WyllandTomeNetwork.sendBeginGrabEntity(target.uuid)
                grabbing = true
                return
            }
        }

        // Block hit — if the block belongs to a VS2 ship, grab the ship.
        // VS2's `MixinLevel.clip` routes through `clipIncludeShips`,
        // which returns a BlockHitResult with `.blockPos` in shipyard
        // coords (used to find the managing ship) but `.location` in
        // a frame we shouldn't trust for sub-block precision — for
        // partial blocks (slabs, stairs) the returned world location
        // ends up off the visible voxelshape surface. So we use
        // clipIncludeShips only to identify *which* block was hit, then
        // re-run the voxelshape clip ourselves in pure ship-frame
        // coordinates and forward those directly to the server. Same
        // pattern VMod uses in its physgun raycast.
        val ctx = ClipContext(
            eye, end,
            ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player,
        )
        val blockHit = level.clip(ctx)
        if (blockHit.type != HitResult.Type.BLOCK) return
        val bp = (blockHit as BlockHitResult).blockPos
        val ship = level.getLoadedShipManagingPos(bp)
        if (ship != null) {
            // Re-derive the hit position in ship-frame, against the
            // block's actual outline voxelshape. Use the ship's render
            // transform on the client so the result matches what the
            // player visually sees.
            val worldToShip = (ship as? ClientShip)?.renderTransform?.worldToShip
                ?: ship.worldToShip
            val shipEye = org.joml.Vector3d(eye.x, eye.y, eye.z)
                .also { worldToShip.transformPosition(it) }
            val shipEnd = org.joml.Vector3d(end.x, end.y, end.z)
                .also { worldToShip.transformPosition(it) }
            val state = level.getBlockState(bp)
            val shape = state.getShape(level, bp)
            val shipHit = shape.clip(
                Vec3(shipEye.x, shipEye.y, shipEye.z),
                Vec3(shipEnd.x, shipEnd.y, shipEnd.z),
                bp,
            ) ?: return
            val shipLoc = shipHit.location
            WyllandTomeNetwork.sendBeginGrabShip(ship.id, shipLoc.x, shipLoc.y, shipLoc.z)
            grabbing = true
            return
        }
        // Non-ship world block. The tome rips up a roughly-spherical
        // chunk of the world centred on the click — bounded by an
        // explosion-style raycast that respects each block's
        // explosionResistance. Cheap pre-filter only (skip
        // truly-unbreakable hits like bedrock); the server runs the
        // actual sphere collection + assembly + grab.
        val state = level.getBlockState(bp)
        if (state.getDestroySpeed(level, bp) < 0f) return
        val hit = blockHit.location
        WyllandTomeNetwork.sendBeginGrabBlock(bp, hit.x, hit.y, hit.z)
        grabbing = true
    }

    /** Vanilla-style entity pickup raycast — narrow box around the ray. */
    private fun pickEntity(player: Player, eye: Vec3, end: Vec3): EntityHitResult? {
        val level = player.level()
        val box = player.boundingBox.expandTowards(end.subtract(eye)).inflate(1.0, 1.0, 1.0)
        return net.minecraft.world.entity.projectile.ProjectileUtil
            .getEntityHitResult(
                level, player, eye, end, box,
            ) { e -> !e.isSpectator && e.isPickable && e !== player }
    }

    /** Update the shared beam state and spawn new path-bound particles.
     *  Particles are client-side only (`addParticle`), so they're
     *  inexpensive — the server doesn't render anything.
     *
     *  The beam follows a quadratic bezier curve with three control
     *  points: the player's chest (start), the **cursor** (control —
     *  the look-projected spring target the ship is being dragged
     *  toward), and the **anchor** (end — the actual grab point on the
     *  ship). When the spring is at rest the cursor and anchor align
     *  and the curve is nearly straight; when the spring is stretched
     *  the curve bows visibly through the cursor.
     *
     *  Each particle is bound to the bezier by an opaque `t` parameter
     *  + angular offset (see [EnchantedBookBeamParticle]); it follows
     *  the *current* curve every tick, so there is no trailing-behind
     *  even if the player whips their view around. */
    private fun spawnBeamParticles(mc: Minecraft, player: Player) {
        val level = mc.level ?: return
        val eye = player.eyePosition
        val look = player.lookAngle
        // Start: player chest height.
        val start = Vec3(eye.x, eye.y - WyllandTomeItem.BEAM_ORIGIN_DROP, eye.z)
        // End: the actual grab anchor on the held object. Falls back to
        // a look-projected guess until the first server sync arrives.
        val end = syncedAnchor
            ?: eye.add(look.scale(WyllandTomeItem.DEFAULT_GRAB_DISTANCE))
        // Control point: the cursor (look-projected spring target). When
        // unsynced, fall back to the linear midpoint so the curve
        // degenerates to a straight line. Either way, drop the control
        // point by BEAM_SAG so the beam droops in the middle — the
        // midpoint follows at half the drop, reading as a little slack
        // in the tether rather than a taut laser.
        val controlBase = syncedCursor ?: Vec3(
            (start.x + end.x) * 0.5,
            (start.y + end.y) * 0.5,
            (start.z + end.z) * 0.5,
        )
        val control = Vec3(
            controlBase.x,
            controlBase.y - WyllandTomeItem.BEAM_SAG,
            controlBase.z,
        )
        // Publish the current beam parameters for every live particle's
        // next tick. The registry hands back the same `BeamPath` across
        // calls (keyed by PLAYER_BEAM_ID), so updates are in-place — no
        // churn, and the volatile field writes are visible to particles.
        val path = BeamRegistry.get(BeamRegistry.PLAYER_BEAM_ID)
            ?: BeamRegistry.put(BeamRegistry.PLAYER_BEAM_ID, BeamPath(flowRate = WyllandTomeItem.BEAM_FLOW_RATE))
        path.start = start
        path.control = control
        path.end = end
        path.radius = WyllandTomeItem.BEAM_RADIUS

        val rand = level.random
        val pathIdAsDouble = BeamRegistry.PLAYER_BEAM_ID.toDouble()
        repeat(WyllandTomeItem.BEAM_PARTICLES_PER_TICK) {
            // (t, theta, pathId) are encoded in the standard xd/yd/zd
            // slots — see EnchantedBookBeamParticle.Provider. The position
            // (x,y,z) is set to `start` as a placeholder; the particle's
            // init() will immediately snap to the correct bezier point.
            val t = rand.nextDouble()
            val theta = rand.nextDouble() * Math.PI * 2.0
            level.addParticle(
                EKParticles.enchantedBookBeam(),
                start.x, start.y, start.z,
                t, theta, pathIdAsDouble,
            )
        }
    }

    /** Rain a few emissive enchanted-book glyphs from random points
     *  inside the targeted ship's local AABB. Only runs for ship grabs
     *  (the server sends the ship id in [WyllandTomeNetwork.GRAB_POINT_SYNC]).
     *
     *  We sample in ship-frame (shipyard block coords) and push each
     *  point through the ship's *render* transform so the glyphs land
     *  exactly where the player sees the hull, then let the particle
     *  fall under gravity. Count is kept low ([WyllandTomeItem.SHIP_GLYPHS_PER_TICK]);
     *  the particles' full-bright emissivity carries the effect. */
    private fun spawnShipParticles(mc: Minecraft) {
        val shipId = syncedShipId ?: return
        val level = mc.level ?: return
        val ship = level.shipObjectWorld.loadedShips.getById(shipId) as? ClientShip ?: return
        val aabb = ship.shipAABB ?: return
        val s2w = ship.renderTransform.shipToWorld
        val rand = level.random
        val spanX = (aabb.maxX() + 1 - aabb.minX()).toDouble()
        val spanY = (aabb.maxY() + 1 - aabb.minY()).toDouble()
        val spanZ = (aabb.maxZ() + 1 - aabb.minZ()).toDouble()
        val local = org.joml.Vector3d()
        repeat(WyllandTomeItem.SHIP_GLYPHS_PER_TICK) {
            local.set(
                aabb.minX() + rand.nextDouble() * spanX,
                aabb.minY() + rand.nextDouble() * spanY,
                aabb.minZ() + rand.nextDouble() * spanZ,
            )
            s2w.transformPosition(local)
            level.addParticle(
                EKParticles.wyllandTomeShipGlyph(),
                local.x, local.y, local.z,
                0.0, 0.0, 0.0,
            )
        }
    }
}
