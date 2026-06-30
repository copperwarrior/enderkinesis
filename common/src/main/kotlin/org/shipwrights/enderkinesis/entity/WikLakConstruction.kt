package org.shipwrights.enderkinesis.entity

import dev.architectury.event.EventResult
import dev.architectury.event.events.common.BlockEvent
import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.block.HeartOfTheWildManager
import org.shipwrights.enderkinesis.block.WohlonnogondoniaPortalRitual
import org.shipwrights.enderkinesis.dimension.Wohlonnogondonia
import org.shipwrights.enderkinesis.registry.EKEntities
import org.shipwrights.enderkinesis.registry.EKParticles

/**
 * Detects the "mud + mud + skull" golem pattern and converts it into a
 * [WikLakHostEntity] owned by the placer. Pattern (bottom → top):
 *
 *   y    : mud      (becomes the host's feet position)
 *   y+1  : mud
 *   y+2  : skeleton skull OR wither-skeleton skull   ← placement trigger
 *
 * Both skull variants work; the placer becomes the host's [WikLakHostEntity.creatorUuid].
 * Gated to Wohlonnogondonia biome cells — anywhere else a player just builds
 * a regular skull-on-mud pillar. Wall-mounted skulls (`SKELETON_WALL_SKULL`,
 * `WITHER_SKELETON_WALL_SKULL`) are excluded by design; the construction
 * must read as a vertical totem, not an arbitrary head-on-a-wall.
 *
 * Architectury's [BlockEvent.PLACE] fires at different points across
 * Fabric (pre-set) and Forge (post-set), so the actual pattern check is
 * deferred one server tick via `server.execute { … }` — by then the world
 * state is consistent on both loaders.
 */
object WikLakConstruction {

    fun init() {
        BlockEvent.PLACE.register(::onPlace)
    }

    private fun onPlace(
        level: Level,
        pos: BlockPos,
        state: BlockState,
        placer: Entity?,
    ): EventResult {
        if (level.isClientSide) return EventResult.pass()
        val server = level as? ServerLevel ?: return EventResult.pass()
        val player = placer as? ServerPlayer ?: return EventResult.pass()
        if (!isSkull(state)) return EventResult.pass()

        // Capture coords + creator now; defer the pattern check so Fabric
        // and Forge agree on whether the skull is in the world yet.
        val skullPos = pos.immutable()
        val creator = player.uuid
        server.server.execute { tryConstruct(server, skullPos, creator) }
        return EventResult.pass()
    }

    private fun tryConstruct(level: ServerLevel, skullPos: BlockPos, creator: UUID) {
        if (!isSkull(level.getBlockState(skullPos))) return
        val mudUpper = skullPos.below()
        val mudLower = skullPos.below(2)
        if (!level.getBlockState(mudUpper).`is`(Blocks.MUD)) return
        if (!level.getBlockState(mudLower).`is`(Blocks.MUD)) return
        if (!level.getBiome(skullPos).`is`(Wohlonnogondonia.BIOME_KEY)) return
        // Same 8-slot light-blue candle ring the Heart Candle portal ritual
        // uses (see [WohlonnogondoniaPortalRitual.PATTERN]) — checked at the
        // bottom-of-pillar Y plane so the pattern surrounds the host's feet,
        // matching where the heart candle sits in the portal ritual. All four
        // rotations are accepted, same as the portal.
        if (!WohlonnogondoniaPortalRitual.checkPattern(level, mudLower)) return

        // Consume the three pattern blocks first so a half-build can't strand
        // a floating skull if entity creation fails on the next line. The
        // suppress guard skips [HeartOfTheWildManager.onBlockDestroyed] for
        // these specific destructions — the construction sacrifices the
        // blocks intentionally and a wogor bud must NOT regrow where the
        // host now stands.
        HeartOfTheWildManager.withEnrollSuppressed {
            level.removeBlock(skullPos, false)
            level.removeBlock(mudUpper, false)
            level.removeBlock(mudLower, false)
        }

        val host = EKEntities.WIK_LAK_HOST.get().create(level) ?: return
        host.moveTo(
            mudLower.x + 0.5,
            mudLower.y.toDouble(),
            mudLower.z + 0.5,
            level.random.nextFloat() * 360f,
            0f,
        )
        host.creatorUuid = creator
        level.addFreshEntity(host)

        level.playSound(
            null, mudLower,
            SoundEvents.SOUL_ESCAPE, SoundSource.NEUTRAL,
            1f, 0.6f,
        )
        level.sendParticles(
            ParticleTypes.SCULK_SOUL,
            mudLower.x + 0.5, mudLower.y + 1.0, mudLower.z + 0.5,
            32, 0.3, 0.6, 0.3, 0.05,
        )

        // Bind-thread visual: a dense beaded line of short-life fireflies
        // from the host's chest to the creator's. The bind particle is the
        // 1-second variant of the ambient Wohlon firefly, so the line
        // appears bright at summon and disappears in step with the soul-
        // escape sound rather than persisting as ambient glitter.
        val creatorPlayer = level.getPlayerByUUID(creator) as? ServerPlayer
        if (creatorPlayer != null) {
            val hostChest = host.position().add(0.0, host.bbHeight * 0.75, 0.0)
            val creatorChest = creatorPlayer.position().add(0.0, creatorPlayer.bbHeight * 0.75, 0.0)
            spawnFireflyThread(level, hostChest, creatorChest)
        }

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

    private fun isSkull(state: BlockState): Boolean =
        state.`is`(Blocks.SKELETON_SKULL) || state.`is`(Blocks.WITHER_SKELETON_SKULL)

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
