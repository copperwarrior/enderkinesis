package org.shipwrights.enderkinesis.item

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.UseAnim
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import org.joml.Vector3d
import org.shipwrights.enderkinesis.registry.EKParticles
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.mod.common.ValkyrienSkiesMod
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld

/** Incantation of Winds — hold right-click to blast a gale forward.
 *
 *  While held down, every ship whose AABB overlaps a wide box projected
 *  along the player's look direction is pushed forward (along the look
 *  vector, mass-relative force via the GTPA — same bridge the Staff of
 *  Aegis push uses), with yellow sselith dust blasting downrange. The
 *  ship the player is riding, and everything joint-connected to it, is
 *  excluded so you can't sail your own wind.
 *
 *  Costs 1 XP per second in survival (half a heart per second once XP
 *  runs dry — the cast never refuses, it just feeds on the caster);
 *  free in creative. The item renders
 *  as an empty hand (scale-0 hand display transforms) — the rising hand
 *  dust from [org.shipwrights.enderkinesis.client.IncantationOfWindsClient]
 *  is the only tell. */
class IncantationOfWindsItem(properties: Properties) : Item(properties) {

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        player.startUsingItem(hand)
        return InteractionResultHolder.consume(stack)
    }

    override fun getUseDuration(stack: ItemStack): Int = Int.MAX_VALUE
    override fun getUseAnimation(stack: ItemStack): UseAnim = UseAnim.NONE

    override fun onUseTick(level: Level, entity: LivingEntity, stack: ItemStack, remainingUseDuration: Int) {
        if (level !is ServerLevel) return
        if (entity !is Player) return
        if (!entity.abilities.instabuild) {
            val used = getUseDuration(stack) - remainingUseDuration
            if (used > 0 && used % 20 == 0) {
                if (hasXp(entity)) {
                    entity.giveExperiencePoints(-1)
                } else {
                    // Out of XP: the incantation feeds on the caster
                    // instead — half a heart per second, cast continues.
                    entity.hurt(
                        org.shipwrights.enderkinesis.dimension.EKDamageSources.lightDamage(level, entity),
                        NO_XP_HEALTH_COST,
                    )
                }
            }
        }
        // Wind direction is yaw-locked: derived from the head yaw only, so
        // looking up or down never aims the gale vertically — the wind
        // always blows level.
        val yawRad = Math.toRadians(entity.yRot.toDouble())
        val look = net.minecraft.world.phys.Vec3(-kotlin.math.sin(yawRad), 0.0, kotlin.math.cos(yawRad))
        val eye = entity.eyePosition
        // Wind volume: from BEHIND_RANGE behind the eye to RANGE ahead,
        // inflated wide laterally and tall vertically.
        val bounds = AABB(eye.subtract(look.scale(BEHIND_RANGE)), eye.add(look.scale(RANGE)))
            .inflate(HALF_WIDTH, HALF_HEIGHT, HALF_WIDTH)
        pushShips(level, entity, bounds, look)
        pushEntities(level, entity, bounds, look)
        blastParticles(level, entity, bounds, look)
    }

    private fun hasXp(player: Player): Boolean =
        player.experienceLevel > 0 || player.experienceProgress > 0f

    private fun pushShips(level: ServerLevel, player: Player, bounds: AABB, look: net.minecraft.world.phys.Vec3) {
        val gtpa = ValkyrienSkiesMod.getOrCreateGTPA(level.dimensionId)
        // Exclude the ridden ship plus everything joint-connected to it —
        // otherwise the player could push the deck they stand on.
        val excluded: Set<Long> = currentlyDraggingShip(player)?.let { ridden ->
            val ids = HashSet<Long>()
            ids.add(ridden.id)
            ids.addAll(gtpa.getAllConnectedShips(ridden.id))
            ids
        } ?: emptySet()
        val dim = level.dimensionId
        for (ship in level.shipObjectWorld.loadedShips) {
            val srv = ship as? LoadedServerShip ?: continue
            if (srv.id in excluded || srv.chunkClaimDimension != dim) continue
            val sa = srv.worldAABB
            if (sa.maxX() < bounds.minX || sa.minX() > bounds.maxX) continue
            if (sa.maxY() < bounds.minY || sa.minY() > bounds.maxY) continue
            if (sa.maxZ() < bounds.minZ || sa.minZ() > bounds.maxZ) continue
            val mass = srv.inertiaData.mass.coerceAtLeast(1.0)
            val mag = (PUSH_FORCE_PER_MASS * mass).coerceAtLeast(PUSH_MIN_FORCE)
            gtpa.applyInvariantForce(srv.id, Vector3d(look.x * mag, look.y * mag, look.z * mag))
        }
    }

    /** All live entities in the wind volume (except the caster and their
     *  mount) get accelerated along the look direction each tick. Same
     *  motion-sync handling as the Aegis push — ServerPlayers need the
     *  explicit motion packet or the client never feels it. */
    private fun pushEntities(level: ServerLevel, player: Player, bounds: AABB, look: net.minecraft.world.phys.Vec3) {
        val entities = level.getEntities(player, bounds) { e ->
            e.isAlive && !e.isSpectator && e !== player.vehicle
        }
        for (entity in entities) {
            entity.deltaMovement = entity.deltaMovement.add(look.scale(ENTITY_PUSH_SPEED))
            entity.hasImpulse = true
            if (entity is net.minecraft.server.level.ServerPlayer) {
                entity.hurtMarked = true
                entity.connection.send(
                    net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(entity),
                )
            }
        }
    }

    private fun blastParticles(level: ServerLevel, player: Player, bounds: AABB, look: net.minecraft.world.phys.Vec3) {
        val rnd = level.random
        // Stream pouring from the casting hand (raised up-and-forward —
        // mirrors the client's third-person cast pose anchor). Each gust
        // launches along the player's TRUE view direction (pitch included,
        // fanned on a randomized cone) — the initial blast goes where the
        // hand points; the particle itself then flattens into the
        // yaw-locked wind (see WindDustParticle's GUST vertical decay).
        val view = player.lookAngle
        val hand = castingHandPosition(player)
        repeat(HAND_STREAM_PARTICLES_PER_TICK) {
            val dir = view.add(
                (rnd.nextDouble() - 0.5) * FAN_SPREAD,
                (rnd.nextDouble() - 0.5) * FAN_SPREAD,
                (rnd.nextDouble() - 0.5) * FAN_SPREAD,
            )
            level.sendParticles(
                EKParticles.windGust(),
                hand.x + (rnd.nextDouble() - 0.5) * 0.3,
                hand.y + (rnd.nextDouble() - 0.5) * 0.3,
                hand.z + (rnd.nextDouble() - 0.5) * 0.3,
                0,
                dir.x, dir.y, dir.z,
                BLAST_PARTICLE_SPEED,
            )
        }
        // Sparse fill across the FULL wind volume (including behind the
        // player) so the gale reads as a moving air mass, not a muzzle jet.
        repeat(VOLUME_PARTICLES_PER_TICK) {
            level.sendParticles(
                EKParticles.windGust(),
                bounds.minX + rnd.nextDouble() * (bounds.maxX - bounds.minX),
                bounds.minY + rnd.nextDouble() * (bounds.maxY - bounds.minY),
                bounds.minZ + rnd.nextDouble() * (bounds.maxZ - bounds.minZ),
                0,
                look.x, look.y, look.z,
                VOLUME_PARTICLE_SPEED,
            )
        }
    }

    /** Where the caster SEES their hand: a camera-frame anchor built from
     *  the true view direction (pitch included) matching the first-person
     *  cast pose at full draw — forward push, raise, and side offset. The
     *  old body-yaw approximation drifted well off the on-screen hand,
     *  especially when looking up or down. */
    private fun castingHandPosition(player: Player): net.minecraft.world.phys.Vec3 {
        val arm = if (player.usedItemHand == InteractionHand.MAIN_HAND) {
            player.mainArm
        } else {
            player.mainArm.opposite
        }
        val side = if (arm == net.minecraft.world.entity.HumanoidArm.RIGHT) 1.0 else -1.0
        val fwd = player.lookAngle
        val yawRad = Math.toRadians(player.yRot.toDouble())
        val right = net.minecraft.world.phys.Vec3(
            -kotlin.math.cos(yawRad), 0.0, -kotlin.math.sin(yawRad),
        )
        val up = right.cross(fwd)
        return player.eyePosition
            .add(fwd.scale(CAST_HAND_FORWARD))
            .add(right.scale(CAST_HAND_SIDE * side))
            .add(up.scale(CAST_HAND_UP))
    }

    private fun currentlyDraggingShip(player: Player): LoadedServerShip? {
        val info = (player as? org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider)
            ?.draggingInformation ?: return null
        val shipId = info.lastShipStoodOn ?: return null
        return player.level().shipObjectWorld.loadedShips.getById(shipId) as? LoadedServerShip
    }

    companion object {
        /** Reach of the wind box along the look direction, in blocks. */
        private const val RANGE = 48.0

        /** The box also reaches this far BEHIND the eye, so ships and
         *  entities beside/behind the caster still catch the gale. */
        private const val BEHIND_RANGE = 8.0

        /** Lateral half-width of the wind box. */
        private const val HALF_WIDTH = 18.0

        /** Vertical half-height of the wind box. */
        private const val HALF_HEIGHT = 14.0

        /** Blocks/tick of acceleration for entities caught in the wind. */
        private const val ENTITY_PUSH_SPEED = 0.06

        /** N per kg of ship mass — mass-relative so every ship feels the
         *  same shove (~0.8 m/s² of acceleration while held). Same scale
         *  family as the Aegis ship push (6 N/kg). */
        private const val PUSH_FORCE_PER_MASS = 8.0

        /** Floor so near-massless ships still visibly move. */
        private const val PUSH_MIN_FORCE = 1_000.0

        private const val HAND_STREAM_PARTICLES_PER_TICK = 2
        private const val VOLUME_PARTICLES_PER_TICK = 4
        private const val BLAST_PARTICLE_SPEED = 1.4
        private const val VOLUME_PARTICLE_SPEED = 0.9

        /** Per-component velocity jitter on the hand stream (±0.45 against
         *  a unit look vector ≈ a ~25° cone) — sized so the stream's spread
         *  at RANGE roughly matches the box's lateral half-extents. */
        private const val FAN_SPREAD = 0.9

        /** Camera-frame offsets of the on-screen casting hand at full draw
         *  (first-person anchor 0.55 fwd/0.32 side/−0.28 up, plus the cast
         *  push, raise, and correction offsets). */
        private const val CAST_HAND_FORWARD = 1.1
        private const val CAST_HAND_SIDE = 0.5
        private const val CAST_HAND_UP = -0.05

        /** Half a heart per second while casting with no XP left. */
        private const val NO_XP_HEALTH_COST = 1.0f
    }
}
