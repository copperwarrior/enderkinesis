package org.shipwrights.enderkinesis.item

import net.minecraft.core.registries.Registries
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.TagKey
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.AbstractArrow
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.mod.common.ValkyrienSkiesMod
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.util.GameToPhysicsAdapter

/** Server-side per-tick logic for the Staff of Aegis.
 *
 *  Three effects, all gated on the entity (or ship) intersecting the
 *  shield's detection volume ([AegisBox]):
 *
 *  1. **Projectiles** are reflected — owner reassigned to the shield user
 *     (so the projectile won't damage them on the way back, and any
 *     downstream kill credit lands with the player); velocity reversed
 *     along the away-from-player axis at speed equal to the incoming
 *     magnitude. Acceleration-based projectiles (Ghast / Wither fireballs)
 *     also get their `xPower`/`yPower`/`zPower` reset.
 *  2. **Tagged "deflectable" entities** (TNT, TNT minecart by default) get
 *     a horizontal-ish push away from the player + a small lift.
 *  3. **Ships** whose AABB overlaps the box get pushed away from the
 *     player too, EXCEPT for the ship the player is currently riding /
 *     dragging AND any ship transitively joint-connected to it
 *     (`GameToPhysicsAdapter.getAllConnectedShips`).
 *
 *  Effects run once per tick per player; called from
 *  [StaffOfAegisItem.onUseTick] on the server. */
object AegisManager {

    val DEFLECTABLE_TAG: TagKey<EntityType<*>> = TagKey.create(
        Registries.ENTITY_TYPE, EnderkinesisMod.id("deflectable"),
    )

    /** Reflection / push speeds. Tuned for "feels firm" — projectiles bounce
     *  with intent, TNT skitters, ships nudge. */
    private const val PROJECTILE_REFLECT_SPEED_FLOOR: Double = 0.6
    private const val PROJECTILE_REFLECT_BOOST: Double = 1.1
    private const val DEFLECTABLE_PUSH_SPEED: Double = 0.8
    private const val DEFLECTABLE_LIFT: Double = 0.18
    private const val SHIP_PUSH_FORCE_PER_MASS: Double = 6.0
    private const val SHIP_PUSH_MIN_FORCE: Double = 800.0
    private const val SHIP_PUSH_LIFT_FRACTION: Double = 0.05

    /** Tick. Idempotent: each affected entity is reflected/pushed once per
     *  call regardless of how many ticks they've been inside the box —
     *  vanilla projectiles can't be inside the box for >1 tick after
     *  reflection anyway because their velocity now points outward. */
    fun tick(level: ServerLevel, player: Player) {
        val frame = AegisBox.forPlayer(player)
        applyEntityEffects(level, player, frame)
        applyShipEffects(level, player, frame)
    }

    private fun applyEntityEffects(level: ServerLevel, player: Player, frame: AegisBox.Frame) {
        val entities = level.getEntities(player, frame.worldAabb) { e ->
            e !== player && e.isAlive
        }
        for (entity in entities) {
            // Two ways an entity can intersect:
            //   a) Its current position is inside the OBB.
            //   b) Its segment from (last-tick → this-tick) crosses the OBB,
            //      catching fast projectiles that would otherwise tunnel.
            val pos = entity.position()
            val prev = Vec3(entity.xOld, entity.yOld, entity.zOld)
            val hit = frame.containsDetection(pos) || frame.segmentIntersects(prev, pos)
            if (!hit) continue

            // Direction from player to entity, projected away from the
            // player's position (not the box centre — players want stuff
            // shoved away from THEM specifically).
            val awayRaw = pos.subtract(player.position())
            val away = if (awayRaw.lengthSqr() < 1e-6) frame.axisZ else awayRaw.normalize()

            if (entity is Projectile) {
                reflectProjectile(entity, player, away)
            } else {
                // Push every non-projectile entity inside the shield. The
                // [DEFLECTABLE_TAG] is no longer required — TNT, mobs,
                // players, items all get shoved away from the wielder.
                pushEntity(entity, away)
            }
        }
    }

    private fun reflectProjectile(proj: Projectile, player: Player, away: Vec3) {
        // Reassign owner so the projectile no longer treats the shield user
        // as the source — important for ghast fireballs, which fly back at
        // the ghast that fired them and detonate normally; also makes the
        // shielder the "credited killer" for any downstream damage, and
        // (for arrows) lets the arrow strike its original shooter (the
        // owner-hit guard only protects the *current* owner = the player).
        proj.owner = player

        // Arrows have a special vanilla wrinkle: `AbstractArrow.setOwner`
        // flips `pickup → ALLOWED` whenever the new owner is a Player. We
        // want the wielder to keep the kill credit and the return-fire
        // behaviour, but NOT to be able to harvest reflected skeleton
        // arrows from the ground. Reset pickup to DISALLOWED right after
        // the owner reassignment so the arrow is effectively a single-use
        // missile — flies back, deals damage, then sits inert until
        // despawn.
        if (proj is AbstractArrow) {
            proj.pickup = AbstractArrow.Pickup.DISALLOWED
        }

        // Projectiles deflect in the wielder's *view direction* — the
        // Aegis "aims" the return shot wherever the player is looking, so
        // they can punch a ghast's own fireball straight back at it. The
        // `away` arg (used for non-projectile pushes) is ignored here.
        val lookRaw = player.lookAngle
        val look = if (lookRaw.lengthSqr() < 1e-6) away else lookRaw.normalize()

        val incoming = proj.deltaMovement
        val speed = incoming.length().coerceAtLeast(PROJECTILE_REFLECT_SPEED_FLOOR) *
            PROJECTILE_REFLECT_BOOST
        val newVel = look.scale(speed)
        proj.deltaMovement = newVel

        // Acceleration-based projectiles (LargeFireball, SmallFireball,
        // WitherSkull, DragonFireball) ignore deltaMovement on the next tick
        // and re-derive it from their x/y/zPower. Without resetting those,
        // they'd resume their original course one tick later.
        if (proj is AbstractHurtingProjectile) {
            val origPower = Vec3(proj.xPower, proj.yPower, proj.zPower).length().coerceAtLeast(0.05)
            proj.xPower = look.x * origPower
            proj.yPower = look.y * origPower
            proj.zPower = look.z * origPower
        }
        // (AbstractArrow's `inGround` reset is intentionally skipped — the
        // field is package-private in vanilla, accessing it would need an
        // accessor mixin, and arrows are unlikely to be stationary inside a
        // shield projection at 4 blocks away in normal play.)
        proj.hasImpulse = true
    }

    /** Adds a single-tick away-from-player impulse (with a small upward
     *  lift) to any non-projectile entity inside the shield. ADD to
     *  existing motion rather than overwriting — looks more physical than
     *  a hard velocity replacement. For [ServerPlayer]s we also push a
     *  [ClientboundSetEntityMotionPacket] because vanilla overwrites the
     *  server-side delta with the next client move packet otherwise. */
    private fun pushEntity(entity: Entity, away: Vec3) {
        val push = Vec3(
            away.x * DEFLECTABLE_PUSH_SPEED,
            away.y * DEFLECTABLE_PUSH_SPEED + DEFLECTABLE_LIFT,
            away.z * DEFLECTABLE_PUSH_SPEED,
        )
        entity.deltaMovement = entity.deltaMovement.add(push)
        entity.hasImpulse = true
        if (entity is ServerPlayer) {
            entity.hurtMarked = true
            entity.connection.send(ClientboundSetEntityMotionPacket(entity))
        }
    }

    private fun applyShipEffects(level: ServerLevel, player: Player, frame: AegisBox.Frame) {
        val ridingShip = currentlyDraggingShip(player)
        val aabb = frame.worldAabb
        val gtpa = ValkyrienSkiesMod.getOrCreateGTPA(level.dimensionId)
        // Exclusion set: the ship the player rides plus everything
        // transitively wired to it through VS2 joints. GTPA maintains the
        // ship→joint map game-thread-safely (ConcurrentHashMap) and walks
        // the graph for us.
        val excluded: Set<Long> = ridingShip?.let { rs ->
            val ids = HashSet<Long>()
            ids.add(rs.id)
            ids.addAll(gtpa.getAllConnectedShips(rs.id))
            ids
        } ?: emptySet()
        for (ship in level.shipObjectWorld.loadedShips) {
            val srv = ship as? LoadedServerShip ?: continue
            if (srv.id in excluded) continue
            val sa = srv.worldAABB
            // Manual AABB overlap test — AABBdc.intersectsAABB only takes a
            // joml AABBd object, not 6 doubles, and we don't want to allocate.
            if (sa.maxX() < aabb.minX || sa.minX() > aabb.maxX) continue
            if (sa.maxY() < aabb.minY || sa.minY() > aabb.maxY) continue
            if (sa.maxZ() < aabb.minZ || sa.minZ() > aabb.maxZ) continue

            val cx = (sa.minX() + sa.maxX()) * 0.5
            val cy = (sa.minY() + sa.maxY()) * 0.5
            val cz = (sa.minZ() + sa.maxZ()) * 0.5
            val awayRaw = Vec3(cx - player.x, cy - player.y, cz - player.z)
            val away = if (awayRaw.lengthSqr() < 1e-6) frame.axisZ else awayRaw.normalize()
            pushShip(gtpa, srv, away)
        }
    }

    /** Queue an invariant-frame world force on `ship`, away from the player,
     *  via the dimension's GTPA. The force is consumed on the next physics
     *  tick; magnitude scales with mass so big ships still react and we
     *  clamp at a minimum so tiny ones don't fly to the moon. */
    private fun pushShip(
        gtpa: GameToPhysicsAdapter,
        ship: LoadedServerShip,
        away: Vec3,
    ) {
        val mass = ship.inertiaData.mass.coerceAtLeast(1.0)
        val mag = (SHIP_PUSH_FORCE_PER_MASS * mass).coerceAtLeast(SHIP_PUSH_MIN_FORCE)
        val force = Vector3d(
            away.x * mag,
            away.y * mag + mag * SHIP_PUSH_LIFT_FRACTION,
            away.z * mag,
        )
        gtpa.applyInvariantForce(ship.id, force)
    }

    /** The ship the player is currently riding / standing on, or null if
     *  they're on world ground. Used to exclude that ship from the push
     *  list. */
    private fun currentlyDraggingShip(player: Player): LoadedServerShip? {
        val info = (player as? org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider)
            ?.draggingInformation ?: return null
        val shipId = info.lastShipStoodOn ?: return null
        return player.level().shipObjectWorld.loadedShips.getById(shipId) as? LoadedServerShip
    }
}
