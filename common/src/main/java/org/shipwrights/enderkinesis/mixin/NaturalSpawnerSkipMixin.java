package org.shipwrights.enderkinesis.mixin;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;
import org.shipwrights.enderkinesis.dimension.SselithRepertory;
import org.shipwrights.enderkinesis.dimension.YgannAbyss;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips vanilla natural mob-spawning entirely in dimensions whose biomes carry
 * <em>empty</em> spawner lists — {@link SselithRepertory} and {@link YgannAbyss}.
 *
 * <p>Both dimensions use a {@code minecraft:fixed} biome source pinned to a biome with
 * {@code "spawners": { "monster": [], "creature": [], … }} and {@code "spawn_costs": {}}.
 * Sselith's Catalogers come from the chunk generator's {@code spawnOriginalMobs} pass, not
 * the natural spawner, so {@link NaturalSpawner#spawnForChunk} can never produce anything
 * in either dimension.
 *
 * <p>It still runs its full per-chunk ritual every tick for every ticking chunk, though —
 * mob-cap counting, random position picks, heightmap + blockstate reads, biome→spawner-list
 * lookup — only to hit the empty list and spawn nothing. Spark profiling of Sselith with a
 * player present measured ~15% of the server tick burned here for a guaranteed-zero result
 * (the loaded ~59 regions × every category × position samples, per tick). Cancelling at HEAD
 * is a behavioural no-op that hands that time back.
 *
 * <p>Scoped to these two dimensions by key so every other dimension keeps spawning normally.
 * The sibling {@code NaturalSpawner.createState} entity-count pass (~0.5% of tick) is left
 * alone — it produces the {@code SpawnState} object the caller still passes around, so it
 * can't be cancelled as cleanly, and the win there is marginal.
 */
@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerSkipMixin {

    @Inject(method = "spawnForChunk", at = @At("HEAD"), cancellable = true)
    private static void enderkinesis$skipSpawningInEmptySpawnerDims(
        ServerLevel level,
        LevelChunk chunk,
        NaturalSpawner.SpawnState spawnState,
        boolean spawnFriendlies,
        boolean spawnMonsters,
        boolean spawnPassives,
        CallbackInfo ci
    ) {
        ResourceKey<Level> dim = level.dimension();
        if (dim == SselithRepertory.INSTANCE.getLEVEL_KEY()
            || dim == YgannAbyss.INSTANCE.getLEVEL_KEY()) {
            ci.cancel();
        }
    }
}
