package org.shipwrights.enderkinesis.item

import dev.architectury.event.EventResult
import dev.architectury.event.events.common.InteractionEvent
import dev.architectury.event.events.common.TickEvent
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.Level
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.state.BlockState
import org.joml.Vector3d
import org.shipwrights.enderkinesis.physics.RodLevitationAttachment
import org.shipwrights.enderkinesis.physics.ShipGravityHold
import org.shipwrights.enderkinesis.registry.EKItems
import org.valkyrienskies.core.api.attachment.getOrPutAttachment
import org.valkyrienskies.mod.common.assembly.ShipAssembler
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld

/**
 * Coordinator for the Rod of Levitation's two non-entity-effect modes:
 *
 * - **Lift on right-clicked block**: assembles the clicked block into a single-block ship,
 *   waits for VS2 to promote it from `allShips` → `loadedShips` (next preTick), then
 *   attaches a [RodLevitationAttachment] aimed at originalY + [LIFT_BLOCKS]. The manager
 *   touches the attachment each game tick until [DURATION_TICKS] elapses, then stops —
 *   the attachment's staleness window releases the lift and the assembled ship stays
 *   intact at its hovered position as a regular VS2 ship.
 *
 * - **Nudge on left-clicked ship**: queues a one-shot impulse on the ship's
 *   [RodLevitationAttachment] (shared with the lift path) at the clicked face's
 *   shipyard-frame position, in the player's look direction. Off-COM application means
 *   the click point determines both push and induced spin.
 */
object RodOfLevitationManager {

    /** 4-second hover window after lift, matching the levitation-on-entity duration. */
    const val DURATION_TICKS = 80

    /** Vertical rise (blocks) from the original block position. */
    private const val LIFT_BLOCKS = 2.0

    /** Per-tick acceleration the nudge produces on the target ship. */
    private const val NUDGE_ACCEL = 15.0

    /** Portal particles spawned beneath the ship each game tick during the lift. */
    private const val PARTICLES_PER_TICK = 4

    /** How far below the ship's bottom Y the particles spawn, in blocks. The portal
     *  particle's built-in upward drift carries them up through the ship from below. */
    private const val UNDER_DEPTH = 1.0

    /** Game ticks for the spiral to climb from the bottom of the ship to the top. */
    private const val SPIRAL_TICKS = 20
    private const val SPIRAL_TURNS = 2.0
    private const val SPIRAL_ARMS = 2
    private const val SPIRAL_STEPS_PER_TICK = 2

    /** Gap (blocks) between the footprint corners and the spiral. */
    private const val SPIRAL_MARGIN = 0.2

    private const val NUDGE_PARTICLES = 4
    private const val NUDGE_PARTICLE_SPREAD = 0.25
    private const val NUDGE_PARTICLE_SPEED = 0.1

    private data class Pending(
        val state: BlockState,
        val targetY: Double,
        var ticksRemaining: Int,
        var attached: Boolean,
        /** Source dimension. `TickEvent.SERVER_LEVEL_POST` fires once per ServerLevel per
         *  real tick, so without this gate the countdown decrements 6× per real tick on a
         *  6-dimension world — the 4 s lift collapses to under a second. */
        val dim: ResourceKey<Level>,
    )

    private val pending = mutableMapOf<Long, Pending>()

    fun init() {
        TickEvent.SERVER_LEVEL_POST.register(::onLevelTick)
        InteractionEvent.LEFT_CLICK_BLOCK.register { player, hand, pos, face ->
            handleLeftClick(player, hand, pos, face)
        }
    }

    /** Right-click-block entry. Assembles [pos] into a fresh ship and records it for the
     *  tick loop to pick up once VS2 has promoted it to a `LoadedServerShip` (attachments
     *  can't be set on the bare `ServerShip` returned mid-assembly). Returns false only
     *  on assembly refusal — the caller treats that as PASS. */
    @JvmStatic
    fun assembleAndLift(level: ServerLevel, pos: BlockPos, state: BlockState): Boolean {
        // Pull in anything attached to the lifted block — the torch on top, the tall
        // grass above the grass block, etc. Shared helper with the lattice / tome paths.
        val blocks = org.shipwrights.enderkinesis.block.CrepusculiteLatticeBlock
            .expandWithAttachments(level, setOf(pos.immutable()))
        val ship = ShipAssembler.assembleToShip(level, blocks, 1.0) ?: return false
        // Ship centre after assembly sits at the original block's centre; +LIFT_BLOCKS
        // hovers it 2 blocks above where the player clicked.
        val targetY = pos.y + 0.5 + LIFT_BLOCKS
        pending[ship.id] = Pending(state, targetY, DURATION_TICKS, attached = false, dim = level.dimension())
        playRodSound(level, SoundEvents.AMETHYST_BLOCK_RESONATE, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        return true
    }

    /** Right-click-block entry for re-lifting an *existing* 1×1×1 ship — the cube the
     *  rod made on a previous click. Skips assembly entirely and just resets the lift
     *  timer + retargets to current world Y + [LIFT_BLOCKS], so each tap nudges the
     *  cube up another 2 blocks (rather than jumping it back to its original spawn Y).
     *  Replaces any in-flight entry for the same ship — each rod-tap restarts the 4 s
     *  window from scratch. */
    @JvmStatic
    fun reLevitate(level: ServerLevel, ship: org.valkyrienskies.core.api.ships.LoadedServerShip): Boolean {
        val currentY = ship.transform.positionInWorld.y()
        val targetY = currentY + LIFT_BLOCKS
        // Ship is already loaded, so `attached = true` skips the wait-for-promotion
        // gate in onLevelTick and the touch lands the first phys tick after this call.
        pending[ship.id] = Pending(
            state = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
            targetY = targetY,
            ticksRemaining = DURATION_TICKS,
            attached = true,
            dim = level.dimension(),
        )
        ship.getOrPutAttachment(RodLevitationAttachment::class.java) { RodLevitationAttachment() }
            .touch(targetY)
        val shipPos = ship.transform.positionInWorld
        playRodSound(level, SoundEvents.AMETHYST_BLOCK_RESONATE, shipPos.x(), shipPos.y(), shipPos.z())
        return true
    }

    private fun playRodSound(level: ServerLevel, sound: SoundEvent, x: Double, y: Double, z: Double) {
        level.playSound(null, x, y, z, sound, SoundSource.PLAYERS, 1f, 1f)
    }

    private fun onLevelTick(level: ServerLevel) {
        if (pending.isEmpty()) return
        val iter = pending.entries.iterator()
        while (iter.hasNext()) {
            val (shipId, entry) = iter.next()
            // SERVER_LEVEL_POST fires once per ServerLevel — gate on the source dimension
            // so the countdown ticks at 20 Hz instead of `20 × dimension_count` Hz.
            if (entry.dim != level.dimension()) continue

            // `allShips` (not `loadedShips`): freshly assembled ships sit in allShips
            // immediately but only promote to loadedShips on VS2's next preTick. Same
            // gotcha called out in VoidHookBlockEntity.checkAssembliesLeftCone — if we
            // lose the ship in allShips, it's gone for good.
            val ship = level.shipObjectWorld.allShips.getById(shipId)
            if (ship == null) { iter.remove(); continue }

            // Wait for promotion to LoadedServerShip — attachments live there, not on
            // the raw ServerShip handed back by assembleToShip. The countdown pauses
            // during the (typically one-tick) wait so the player still gets a full 4 s.
            val loaded = level.shipObjectWorld.loadedShips.getById(shipId) ?: continue
            if (!entry.attached) {
                loaded.getOrPutAttachment(RodLevitationAttachment::class.java) { RodLevitationAttachment() }
                entry.attached = true
            }
            loaded.getAttachment(RodLevitationAttachment::class.java)?.touch(entry.targetY)
            ShipGravityHold.hold(level, loaded)
            spawnLiftParticles(level, loaded)
            spawnSpiralParticles(level, loaded, DURATION_TICKS - entry.ticksRemaining)
            entry.ticksRemaining--
            if (entry.ticksRemaining > 0) continue

            // Timer expired: stop touching the attachment. Its staleness window expires
            // ~100 ms later and the lift force releases, leaving the ship intact at its
            // hovered position. From that point on it's a regular VS2 ship the player can
            // grab, push, sail, or break — we just walk away.
            iter.remove()
        }
    }

    /** Sprays a handful of [ParticleTypes.PORTAL] particles each game tick beneath the
     *  ship's actual block footprint — the ender-purple wisps that signal "this block
     *  is under the rod's effect." `count=1, xd=yd=zd=0, speed=0` parks each particle at
     *  the exact spawn point with no random drift; the portal particle's built-in tick
     *  animation carries it gently upward through the ship without any extra velocity.
     *
     *  XZ is picked in *shipyard* coords (uniform over the block footprint) and then
     *  transformed to world, which avoids the OBB→AABB inflation of a world-frame box.
     *  Y is world-frame: "beneath" follows world down, not ship down. */
    private fun spawnLiftParticles(level: ServerLevel, ship: org.valkyrienskies.core.api.ships.LoadedServerShip) {
        val ab = ship.shipAABB ?: return
        val toWorld = ship.transform.shipToWorld
        val sMinX = ab.minX().toDouble(); val sMaxX = ab.maxX().toDouble()
        val sMinZ = ab.minZ().toDouble(); val sMaxZ = ab.maxZ().toDouble()
        val sMidY = (ab.minY() + ab.maxY()) * 0.5
        val worldMinY = ship.worldAABB.minY()
        val random = level.random
        val p = Vector3d()
        repeat(PARTICLES_PER_TICK) {
            p.set(
                sMinX + random.nextDouble() * (sMaxX - sMinX),
                sMidY,
                sMinZ + random.nextDouble() * (sMaxZ - sMinZ),
            )
            toWorld.transformPosition(p)
            p.y = worldMinY - random.nextDouble() * UNDER_DEPTH
            level.sendParticles(ParticleTypes.PORTAL, p.x, p.y, p.z, 1, 0.0, 0.0, 0.0, 0.0)
        }
    }

    /** REVERSE_PORTAL stays at its spawn point at speed 0, so the trail keeps the helix
     *  shape. PORTAL starts 1 block high and falls. The helix axis is world Y, not ship Y.
     *  `shipAABB` max is exclusive: a 1×1×1 ship has max - min == 1. */
    private fun spawnSpiralParticles(
        level: ServerLevel,
        ship: org.valkyrienskies.core.api.ships.LoadedServerShip,
        elapsedTicks: Int,
    ) {
        if (elapsedTicks >= SPIRAL_TICKS) return
        val ab = ship.shipAABB ?: return
        val sizeX = (ab.maxX() - ab.minX()).toDouble()
        val sizeZ = (ab.maxZ() - ab.minZ()).toDouble()
        val radius = Math.hypot(sizeX, sizeZ) * 0.5 + SPIRAL_MARGIN
        val center = Vector3d(
            ab.minX() + sizeX * 0.5,
            (ab.minY() + ab.maxY()) * 0.5,
            ab.minZ() + sizeZ * 0.5,
        )
        ship.transform.shipToWorld.transformPosition(center)
        val worldAABB = ship.worldAABB
        val bottomY = worldAABB.minY()
        val height = worldAABB.maxY() - bottomY
        for (step in 0 until SPIRAL_STEPS_PER_TICK) {
            val progress = (elapsedTicks + step.toDouble() / SPIRAL_STEPS_PER_TICK) / SPIRAL_TICKS
            val baseAngle = progress * SPIRAL_TURNS * 2.0 * Math.PI
            for (arm in 0 until SPIRAL_ARMS) {
                val angle = baseAngle + arm * 2.0 * Math.PI / SPIRAL_ARMS
                level.sendParticles(
                    ParticleTypes.REVERSE_PORTAL,
                    center.x + radius * Math.cos(angle),
                    bottomY + progress * height,
                    center.z + radius * Math.sin(angle),
                    1, 0.0, 0.0, 0.0, 0.0,
                )
            }
        }
    }

    /** Left-click-block entry. Hijacks the attack swing only when the player is holding
     *  the rod AND the click landed on a ship — anything else passes through so the rod
     *  doesn't muck up vanilla mining.
     *
     *  We need to evaluate "click on ship?" on BOTH sides: the client's
     *  [InteractionEvent.LEFT_CLICK_BLOCK] callback returning [EventResult.pass] there
     *  lets creative-mode insta-break race the server's nudge handler before our
     *  [EventResult.interruptTrue] from the server side can land. VS2's shipObjectWorld
     *  is mirrored client-side, so the ship presence check works there too. */
    private fun handleLeftClick(player: Player, hand: InteractionHand, pos: BlockPos, face: Direction): EventResult {
        if (!player.getItemInHand(hand).`is`(EKItems.ROD_OF_LEVITATION.get())) return EventResult.pass()
        val level = player.level()
        // Ship-ness check on whichever side fired the event. If the click isn't on a ship,
        // pass through so vanilla mining still works while the rod is equipped.
        if (level.getLoadedShipManagingPos(pos) == null) return EventResult.pass()

        // Apply the nudge on the server side only — clients can't manipulate ship physics.
        if (level is ServerLevel) {
            val ship = level.getLoadedShipManagingPos(pos)
            if (ship != null) {
                val mass = ship.inertiaData.mass
                if (!ship.isStatic && mass > 0.0) {
                    // Force direction = look vector; magnitude scales with mass so the
                    // perceived acceleration is the same regardless of ship size.
                    val look = player.lookAngle
                    val force = Vector3d(look.x, look.y, look.z).mul(mass * NUDGE_ACCEL)
                    // COM-relative clicked-cell centre in shipyard frame —
                    // applyInvariantForceToPos generates linear+torque from off-COM input.
                    val com = ship.inertiaData.centerOfMass
                    val rel = Vector3d(
                        pos.x + 0.5 - com.x(),
                        pos.y + 0.5 - com.y(),
                        pos.z + 0.5 - com.z(),
                    )
                    ship.getOrPutAttachment(RodLevitationAttachment::class.java) { RodLevitationAttachment() }
                        .queueNudge(force, rel)
                    nudgeFeedback(level, ship, pos, face)
                }
            }
        }
        // Both sides return interruptTrue: client cancels the swing animation /
        // creative-mode insta-break; server cancels the attack-block packet round-trip.
        return EventResult.interruptTrue()
    }

    /** [pos] is a shipyard position. The burst spawns on the clicked face, because
     *  particles at the block centre are hidden inside the block. */
    private fun nudgeFeedback(
        level: ServerLevel,
        ship: org.valkyrienskies.core.api.ships.LoadedServerShip,
        pos: BlockPos,
        face: Direction,
    ) {
        val p = Vector3d(
            pos.x + 0.5 + face.stepX * 0.5,
            pos.y + 0.5 + face.stepY * 0.5,
            pos.z + 0.5 + face.stepZ * 0.5,
        )
        ship.transform.shipToWorld.transformPosition(p)
        level.sendParticles(
            ParticleTypes.REVERSE_PORTAL, p.x, p.y, p.z, NUDGE_PARTICLES,
            NUDGE_PARTICLE_SPREAD, NUDGE_PARTICLE_SPREAD, NUDGE_PARTICLE_SPREAD, NUDGE_PARTICLE_SPEED,
        )
        playRodSound(level, SoundEvents.AMETHYST_BLOCK_CHIME, p.x, p.y, p.z)
    }

    /** In singleplayer the JVM lives across worlds, and VS2 ids start again in each save. */
    fun clearSession() {
        pending.clear()
    }
}
