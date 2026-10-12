package org.shipwrights.enderkinesis.item

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import java.math.BigInteger
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Item
import net.minecraft.world.item.context.UseOnContext
import org.shipwrights.enderkinesis.dimension.SselithParallel
import org.shipwrights.enderkinesis.remnant.IrreverantRemnantManager

/**
 * Testing tool: seeps a cluster of Irreverant Remnant cubes out of the
 * clicked face, as drips do, on the user's own parallel so they can see it.
 *
 * Not a vanilla [net.minecraft.world.item.SpawnEggItem] — that requires an
 * [net.minecraft.world.entity.EntityType], and the remnant is a
 * pseudo-entity with no entity behind it. This is a plain item that calls
 * the simulation directly; the egg look comes from the vanilla spawn-egg
 * model plus a registered item-colour handler.
 *
 * The remnant is only simulated in Sselith, so a click anywhere else says
 * so rather than silently doing nothing.
 */
class IrreverantRemnantSpawnEggItem(properties: Properties) : Item(properties) {

    override fun useOn(context: UseOnContext): InteractionResult {
        val level = context.level
        if (level !is ServerLevel) return InteractionResult.SUCCESS
        val face = context.clickedFace
        val pos = context.clickedPos
        val player = context.player
        val parallel = (player as? ServerPlayer)?.let(SselithParallel::get) ?: BigInteger.ZERO

        // Sneak to clear instead of seed. Particles never merge or expire,
        // so a test site otherwise only ever accumulates.
        if (player != null && player.isShiftKeyDown) {
            val removed = IrreverantRemnantManager.clearNear(
                level, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, CLEAR_RADIUS, parallel,
            )
            player.displayClientMessage(
                Component.literal("Cleared $removed remnant particles")
                    .withStyle(ChatFormatting.GRAY),
                true,
            )
            return InteractionResult.CONSUME
        }
        val spawned = IrreverantRemnantManager.spawnCluster(level, pos, face, SPAWN_PARTICLES, parallel)
        if (!spawned && player != null) {
            player.displayClientMessage(
                Component.translatable("item.enderkinesis.irreverant_remnant_spawn_egg.failed")
                    .withStyle(ChatFormatting.GRAY),
                true,
            )
            return InteractionResult.FAIL
        }
        if (player != null && !player.abilities.instabuild) {
            context.itemInHand.shrink(1)
        }
        return InteractionResult.CONSUME
    }

    private companion object {
        /** Radius cleared by a sneak-click. */
        private const val CLEAR_RADIUS = 12.0

        /** Particles seeded per egg; a usable test lump needs a crowd. */
        private const val SPAWN_PARTICLES = 14
    }
}
