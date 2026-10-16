package org.shipwrights.enderkinesis.entity

import net.minecraft.core.BlockPos
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.util.Mth
import org.shipwrights.enderkinesis.entity.ConstriliarEntity.Companion.ANIM_JUMP_END
import org.shipwrights.enderkinesis.entity.ConstriliarEntity.Companion.ANIM_JUMP_START
import org.shipwrights.enderkinesis.entity.ConstriliarEntity.Companion.ANIM_LEG_POP
import org.shipwrights.enderkinesis.entity.ConstriliarEntity.Companion.ANIM_LEG_PLANT
import org.shipwrights.enderkinesis.entity.ConstriliarEntity.Companion.ANIM_RISE_END
import org.shipwrights.enderkinesis.entity.ConstriliarEntity.Companion.ANIM_RISE_START
import org.shipwrights.enderkinesis.entity.ConstriliarEntity.Companion.ANIM_SQUASH_END
import org.shipwrights.enderkinesis.entity.ConstriliarEntity.Companion.ANIM_SQUASH_START

/** Client-local spawn-intro particles, one beat per cue in constriliar_awaken.ogg.
 *  [t] is the client intro clock, which advances in whole ticks. */
internal object ConstriliarIntroFx {
    private const val CRACK_CRUMBS = 14
    private const val LEG_CRUMBS = 5
    private const val LAUNCH_RING = 12
    private const val LAND_RING = 22
    private const val LAND_POOFS = 6
    private const val MAX_GLYPHS = 4

    fun tick(entity: ConstriliarEntity, t: Float) {
        val body = BlockParticleOption(ParticleTypes.BLOCK, entity.bodyBlockState())
        when {
            t < ANIM_SQUASH_START -> sift(entity)
            t < ANIM_SQUASH_END -> {
                if (crossed(t, ANIM_SQUASH_START)) crack(entity, body)
                groan(entity, body, t)
            }
            t >= ANIM_RISE_START && t <= ANIM_RISE_END -> gather(entity, t)
        }
        for (leg in 0..3) {
            if (crossed(t, ANIM_LEG_POP[leg].toDouble())) legPop(entity, body, leg)
            if (crossed(t, (ANIM_LEG_POP[leg] + ANIM_LEG_PLANT).toDouble())) {
                entity.gait.playFootstep(entity, entity.gait.footCur[leg])
            }
        }
        if (crossed(t, ANIM_JUMP_START)) groundRing(entity, LAUNCH_RING, 0.12)
        if (crossed(t, ANIM_JUMP_END)) {
            groundRing(entity, LAND_RING, 0.22)
            poofs(entity)
        }
    }

    private fun crossed(t: Float, at: Double): Boolean = at > t - 1f && at <= t

    /** Pre-crack rumble: dust sifts off the still-whole block. */
    private fun sift(entity: ConstriliarEntity) {
        if (entity.tickCount % 2 != 0) return
        val rng = entity.random
        entity.level().addParticle(
            BlockParticleOption(ParticleTypes.FALLING_DUST, entity.bodyBlockState()),
            entity.x + (rng.nextDouble() - 0.5) * 1.1,
            entity.y + 0.9,
            entity.z + (rng.nextDouble() - 0.5) * 1.1,
            0.0, 0.0, 0.0,
        )
    }

    private fun crack(entity: ConstriliarEntity, body: ParticleOptions) {
        val rng = entity.random
        repeat(CRACK_CRUMBS) {
            val dx = rng.nextDouble() - 0.5
            val dz = rng.nextDouble() - 0.5
            entity.level().addParticle(
                body,
                entity.x + dx, entity.y + rng.nextDouble(), entity.z + dz,
                dx * 0.3, 0.1, dz * 0.3,
            )
        }
    }

    /** Crumbs shed from the faces as the block compresses under the groan. */
    private fun groan(entity: ConstriliarEntity, body: ParticleOptions, t: Float) {
        val rng = entity.random
        val top = 1.0 - 0.75 * ConstriliarEntity.spawnSquash(t)
        repeat(2) {
            val angle = rng.nextFloat() * Mth.TWO_PI
            entity.level().addParticle(
                body,
                entity.x + Mth.cos(angle) * 0.55,
                entity.y + rng.nextDouble() * top,
                entity.z + Mth.sin(angle) * 0.55,
                0.0, 0.0, 0.0,
            )
        }
    }

    /** The click as each leg snaps out: crumbs at the plate corner it leaves. */
    private fun legPop(entity: ConstriliarEntity, body: ParticleOptions, leg: Int) {
        val home = entity.gait.footCur[leg]
        val x = Mth.lerp(0.5, entity.x, home.x)
        val z = Mth.lerp(0.5, entity.z, home.z)
        val rng = entity.random
        repeat(LEG_CRUMBS) {
            entity.level().addParticle(
                body,
                x + (rng.nextDouble() - 0.5) * 0.2, entity.y + 0.3, z + (rng.nextDouble() - 0.5) * 0.2,
                0.0, 0.05, 0.0,
            )
        }
    }

    /** The rising swell: glyphs pull in toward the body, denser as it climbs. */
    private fun gather(entity: ConstriliarEntity, t: Float) {
        val p = ((t - ANIM_RISE_START) / (ANIM_RISE_END - ANIM_RISE_START)).toFloat()
        val rng = entity.random
        val cy = entity.y + 0.4
        // ENCHANT spawns at pos + velocity and drifts back to pos.
        repeat(1 + (p * (MAX_GLYPHS - 1)).toInt()) {
            entity.level().addParticle(
                ParticleTypes.ENCHANT,
                entity.x, cy, entity.z,
                (rng.nextDouble() - 0.5) * 2.5, rng.nextDouble() * 1.2, (rng.nextDouble() - 0.5) * 2.5,
            )
        }
    }

    private fun groundRing(entity: ConstriliarEntity, count: Int, speed: Double) {
        val ground = entity.level().getBlockState(BlockPos.containing(entity.x, entity.y - 0.1, entity.z))
        if (ground.isAir) return
        val dust = BlockParticleOption(ParticleTypes.BLOCK, ground)
        for (i in 0 until count) {
            val angle = i * Mth.TWO_PI / count
            val cx = Mth.cos(angle).toDouble()
            val cz = Mth.sin(angle).toDouble()
            entity.level().addParticle(
                dust,
                entity.x + cx * 0.6, entity.y + 0.05, entity.z + cz * 0.6,
                cx * speed, 0.05, cz * speed,
            )
        }
    }

    private fun poofs(entity: ConstriliarEntity) {
        val rng = entity.random
        repeat(LAND_POOFS) {
            entity.level().addParticle(
                ParticleTypes.POOF,
                entity.x + (rng.nextDouble() - 0.5) * 1.2,
                entity.y + 0.1,
                entity.z + (rng.nextDouble() - 0.5) * 1.2,
                0.0, 0.02, 0.0,
            )
        }
    }
}
