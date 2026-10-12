package org.shipwrights.enderkinesis.dimension

import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.Level
import net.minecraft.world.level.dimension.DimensionType
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.EnderkinesisMod

/**
 * Identity for **Sureibjin** — the dream-coast ulder dimension reached by
 * sleeping inside another ulder dim. Infinite N/S beach with a steep west
 * dune cap and a long east shelf into deep ocean; obsidian / crying-obsidian
 * tendril fractals lift through the world; sky is a desaturated blue-pink-
 * grey noise flow.
 *
 *  - Chunk generation: [SureibjinChunkGenerator]
 *  - Sky rendering: `SureibjinSky` (client-side, via Mixin into
 *    `LevelRenderer.renderSky`)
 */
object Sureibjin {
    val ID: ResourceLocation = EnderkinesisMod.id("sureibjin")
    val LEVEL_KEY: ResourceKey<Level> = ResourceKey.create(Registries.DIMENSION, ID)
    val DIMENSION_TYPE_KEY: ResourceKey<DimensionType> =
        ResourceKey.create(Registries.DIMENSION_TYPE, ID)

    // Vanilla swim-up is +0.04 Y a tick. This value is below the in-water gravity of 0.005, so the net is a slow sink.
    private const val SWIM_UP = 0.003

    /**
     * The swim-up boost for an entity with its eyes under water in Sureibjin. Returns false when the
     * vanilla boost applies: a different dimension, or an entity that wades with its head above water.
     */
    /**
     * The look vector that `Player.travel` uses to lift a player in the swim pose. A submerged player
     * in Sureibjin gets no upward part, so a sprint-swim cannot go around [applyWeakSwim].
     */
    @JvmStatic
    fun swimLook(entity: LivingEntity, look: Vec3): Vec3 {
        if (look.y <= 0.0 || entity.level().dimension() != LEVEL_KEY || !entity.isUnderWater) return look
        return Vec3(look.x, 0.0, look.z)
    }

    @JvmStatic
    fun applyWeakSwim(entity: LivingEntity): Boolean {
        if (entity.level().dimension() != LEVEL_KEY || !entity.isUnderWater) return false
        entity.deltaMovement = entity.deltaMovement.add(0.0, SWIM_UP, 0.0)
        return true
    }
}
