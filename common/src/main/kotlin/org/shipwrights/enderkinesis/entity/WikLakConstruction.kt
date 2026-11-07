package org.shipwrights.enderkinesis.entity

import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.block.HeartOfTheWildManager
import org.shipwrights.enderkinesis.registry.EKEntities
import org.shipwrights.enderkinesis.registry.EKParticles
import org.shipwrights.enderkinesis.registry.EKSounds
import org.shipwrights.enderkinesis.ritual.RitualContext
import org.shipwrights.enderkinesis.ritual.RitualEffect

/**
 * Raises a [WikLakHostEntity] for the player who fired the ritual (`data/enderkinesis/rituals/wik_lak_host.json`).
 * The totem is the center block and the template blocks straight below it; the host stands on the lowest one.
 */
object WikLakConstruction : RitualEffect {

    override fun resolve(context: RitualContext, offered: List<ItemStack>): ItemStack? {
        val level = context.level
        val creator = context.player ?: return null
        val column = context.blocks.filter { it.offset.x == 0 && it.offset.z == 0 && it.offset.y < 0 }
            .map { context.center.offset(it.offset) }
        val feet = column.minByOrNull { it.y } ?: context.center
        // The totem is sacrificed on purpose, so a wogor bud must not regrow where the host stands.
        HeartOfTheWildManager.withEnrollSuppressed {
            level.removeBlock(context.center, false)
            column.forEach { level.removeBlock(it, false) }
        }

        val host = EKEntities.WIK_LAK_HOST.get().create(level) ?: return ItemStack.EMPTY
        host.moveTo(feet.x + 0.5, feet.y.toDouble(), feet.z + 0.5, level.random.nextFloat() * 360f, 0f)
        host.creatorUuid = creator.uuid
        level.addFreshEntity(host)

        level.playSound(null, feet, EKSounds.WIK_LAK_BIND.get(), SoundSource.NEUTRAL, 1f, 1f)
        level.sendParticles(ParticleTypes.SCULK_SOUL, feet.x + 0.5, feet.y + 1.0, feet.z + 0.5, 32, 0.3, 0.6, 0.3, 0.05)

        val hostChest = host.position().add(0.0, host.bbHeight * 0.75, 0.0)
        val creatorChest = creator.position().add(0.0, creator.bbHeight * 0.75, 0.0)
        spawnFireflyThread(level, hostChest, creatorChest)
        return ItemStack.EMPTY
    }
    /** Distribute [fireflies][EKParticles.WOHLON_FIREFLY] along the segment
     *  `from → to`, one per ~0.4 blocks of length up to [MAX_FIREFLIES].
     *  Each anchor gets a small ~0.1-block perpendicular jitter so the line
     *  reads as organic rather than ruler-straight. Server-side broadcast,
     *  so all nearby clients see the same beads. */
    private fun spawnFireflyThread(level: ServerLevel, from: Vec3, to: Vec3) {
        val delta = to.subtract(from)
        val length = delta.length()
        if (length < 0.01) return
        val count = ((length / FIREFLY_SPACING_BLOCKS).toInt() + 1).coerceAtMost(MAX_FIREFLIES)
        val type = EKParticles.WIK_LAK_BIND.get() as SimpleParticleType
        val random = level.random
        for (i in 0 until count) {
            val t = (i + 0.5) / count
            val px = from.x + delta.x * t
            val py = from.y + delta.y * t
            val pz = from.z + delta.z * t
            // Per-anchor jitter is the firefly particle's `xOffset/yOffset/zOffset`
            // gaussian spread input — uses the standard sendParticles overload
            // where `count=1` + offsets becomes the jitter on a single spawn.
            level.sendParticles(
                type,
                px, py, pz,
                1,
                FIREFLY_JITTER_BLOCKS, FIREFLY_JITTER_BLOCKS, FIREFLY_JITTER_BLOCKS,
                0.0,
            )
        }
        // Cap with a small burst at each endpoint so the host and the creator
        // each have a visible glow cluster anchoring the line, regardless of
        // the distance-derived bead density above.
        val endpointCount = ENDPOINT_BURST
        for (endpoint in arrayOf(from, to)) {
            for (i in 0 until endpointCount) {
                val rx = (random.nextDouble() - 0.5) * 0.6
                val ry = (random.nextDouble() - 0.5) * 0.6
                val rz = (random.nextDouble() - 0.5) * 0.6
                level.sendParticles(
                    type,
                    endpoint.x + rx, endpoint.y + ry, endpoint.z + rz,
                    1,
                    FIREFLY_JITTER_BLOCKS, FIREFLY_JITTER_BLOCKS, FIREFLY_JITTER_BLOCKS,
                    0.0,
                )
            }
        }
    }

    /** Spacing between consecutive firefly anchors along the bind line.
     *  ~0.2 blocks per bead gives a continuous-looking rope of glitter
     *  at construction-scale distances (mud → player ≤ ~5 blocks). */
    private const val FIREFLY_SPACING_BLOCKS: Double = 0.2

    /** Per-axis gaussian spread input to `sendParticles`. ~0.1 blocks of
     *  perpendicular wiggle keeps the line organic. */
    private const val FIREFLY_JITTER_BLOCKS: Double = 0.10

    /** Hard cap on the per-spawn firefly count, so a creator standing a
     *  hundred blocks away doesn't trigger a particle flood. Doubled from
     *  the pre-densification limit to keep the rope visually solid at the
     *  tighter spacing. */
    private const val MAX_FIREFLIES: Int = 160

    /** Extra fireflies clustered at each endpoint (host and creator) so
     *  the two ends always read as "lit" regardless of distance. */
    private const val ENDPOINT_BURST: Int = 12
}
