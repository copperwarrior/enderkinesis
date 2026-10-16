package org.shipwrights.enderkinesis.block

import net.minecraft.sounds.SoundEvent
import net.minecraft.world.entity.Entity
import org.shipwrights.enderkinesis.registry.EKSounds

object IchaericInkSounds {

    private const val SWIM_VOLUME = 0.35f
    private const val SWIM_INTERVAL_TICKS = 12

    /** Runs on both sides, as vanilla's water splash does: Player.playSound plays only for the local player on the
     *  client and skips that player on the server. Entity.playSound plays nothing on the client. */
    @JvmStatic
    fun tick(entity: Entity, previous: Double, current: Double) {
        if (entity.isSpectator) return
        when {
            previous <= 0.0 && current > 0.0 -> splash(entity, EKSounds.ICHAERIC_INK_SPLASH_IN.get())
            previous > 0.0 && current <= 0.0 -> splash(entity, EKSounds.ICHAERIC_INK_SPLASH_OUT.get())
            current > 0.0 && entity.isSwimming && entity.tickCount % SWIM_INTERVAL_TICKS == 0 -> {
                val pitch = 0.9f + entity.level().random.nextFloat() * 0.2f
                entity.playSound(EKSounds.ICHAERIC_INK_SWIM.get(), SWIM_VOLUME, pitch)
            }
        }
    }

    /** Entity.doWaterSplashEffect's volume and pitch. A slow entity at the surface splashes almost silently. */
    private fun splash(entity: Entity, event: SoundEvent) {
        val rider = entity.controllingPassenger
        val mover = if (entity.isVehicle && rider != null) rider else entity
        val scale = if (mover === entity) 0.2f else 0.9f
        val v = mover.deltaMovement
        val speed = Math.sqrt(v.x * v.x * 0.2 + v.y * v.y + v.z * v.z * 0.2).toFloat()
        val random = entity.level().random
        entity.playSound(event, (speed * scale).coerceAtMost(1f), 1f + (random.nextFloat() - random.nextFloat()) * 0.4f)
    }
}
