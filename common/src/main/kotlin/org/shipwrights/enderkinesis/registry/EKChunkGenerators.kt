package org.shipwrights.enderkinesis.registry

import com.mojang.serialization.Codec
import dev.architectury.registry.registries.DeferredRegister
import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.chunk.ChunkGenerator
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.dimension.SselithRepertoryChunkGenerator
import org.shipwrights.enderkinesis.dimension.SureibjinChunkGenerator
import org.shipwrights.enderkinesis.dimension.WohlonnogondoniaChunkGenerator

/**
 * Codec registry for custom [ChunkGenerator] subclasses. The codec is what the
 * `type` field in `data/enderkinesis/dimension/<dim>.json#generator` resolves to —
 * registering it under `enderkinesis:sselith_repertory` lets the dimension JSON
 * reference `"type": "enderkinesis:sselith_repertory"`.
 */
object EKChunkGenerators {

    val CHUNK_GENERATORS: DeferredRegister<Codec<out ChunkGenerator>> =
        DeferredRegister.create(EnderkinesisMod.MOD_ID, Registries.CHUNK_GENERATOR)

    val SSELITH_REPERTORY: RegistrySupplier<Codec<out ChunkGenerator>> =
        CHUNK_GENERATORS.register("sselith_repertory") { SselithRepertoryChunkGenerator.CODEC }

    val WOHLONNOGONDONIA: RegistrySupplier<Codec<out ChunkGenerator>> =
        CHUNK_GENERATORS.register("wohlonnogondonia") { WohlonnogondoniaChunkGenerator.CODEC }

    val SUREIBJIN: RegistrySupplier<Codec<out ChunkGenerator>> =
        CHUNK_GENERATORS.register("sureibjin") { SureibjinChunkGenerator.CODEC }

    fun register() = CHUNK_GENERATORS.register()
}
