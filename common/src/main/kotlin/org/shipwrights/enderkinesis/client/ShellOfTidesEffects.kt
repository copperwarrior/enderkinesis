package org.shipwrights.enderkinesis.client

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import org.shipwrights.enderkinesis.blockentity.CrepusculiteLatticeBlockEntity
import org.shipwrights.enderkinesis.item.ShellOfTidesItem
import org.shipwrights.enderkinesis.physics.LatticeCatchZone
import org.shipwrights.enderkinesis.physics.LatticeRegistry
import org.shipwrights.enderkinesis.registry.EKSounds
import org.shipwrights.enderkinesis.registry.EKParticles
import org.valkyrienskies.mod.common.getShipManagingPos

/** Client side of the Shell of Tides. onUseTick also runs on clients for remote players, so no packet is needed. */
object ShellOfTidesEffects {

    private const val RING_INTERVAL_TICKS = 20
    private const val HOLE_REACH = 0.9
    private const val HOLE_DROP = 0.1
    private const val MAX_EXTRA_MOTES = 3
    private const val BASE_SPEED = 0.12
    private const val STEEP_SPEED = 0.16
    private const val SPREAD = 0.05
    private const val MAX_PITCH = 90.0

    fun tick(level: Level, user: LivingEntity, lattice: CrepusculiteLatticeBlockEntity) {
        if (user is Player) {
            IncantationSounds.hold(
                user, user.useItem.item, EKSounds.SHELL_OF_TIDES_BLOW_LOOP.get(), SoundSource.RECORDS, ShellOfTidesItem.HORN_VOLUME,
            )
        }
        val shipId = level.getShipManagingPos(lattice.blockPos)?.id ?: return
        var zone: LatticeCatchZone? = null
        for ((_, z) in LatticeRegistry.entries(level)) {
            if (z.shipId == shipId) { zone = z; break }
        }
        if (zone == null) return
        if (user.ticksUsingItem % RING_INTERVAL_TICKS == 0) {
            // MC pitch is negative when the user looks up, which raises the sea.
            val sign = if (user.xRot > 0f) -1.0 else 1.0
            TideRings.call(zone.cx, zone.cz, level.gameTime.toDouble(), sign)
            val sound = if (sign > 0.0) EKSounds.SHELL_OF_TIDES_SURGE else EKSounds.SHELL_OF_TIDES_EBB
            level.playLocalSound(zone.cx, zone.cy, zone.cz, sound.get(), SoundSource.PLAYERS, 1f, 1f, false)
        }
        spray(level, user)
    }

    private fun spray(level: Level, user: LivingEntity) {
        val pitch = Math.toRadians(user.xRot.toDouble())
        val yaw = Math.toRadians(user.yRot.toDouble())
        val lx = -sin(yaw) * cos(pitch)
        val ly = -sin(pitch)
        val lz = cos(yaw) * cos(pitch)
        val steep = abs(user.xRot.toDouble()) / MAX_PITCH
        val hx = user.x + lx * HOLE_REACH
        val hy = user.eyeY + ly * HOLE_REACH - HOLE_DROP
        val hz = user.z + lz * HOLE_REACH
        val speed = BASE_SPEED + STEEP_SPEED * steep
        val random = level.random
        repeat(1 + (MAX_EXTRA_MOTES * steep).roundToInt()) {
            level.addParticle(
                EKParticles.tideMote(), hx, hy, hz,
                lx * speed + (random.nextDouble() - 0.5) * SPREAD,
                ly * speed + (random.nextDouble() - 0.5) * SPREAD,
                lz * speed + (random.nextDouble() - 0.5) * SPREAD,
            )
        }
    }
}
