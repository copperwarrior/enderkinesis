package org.shipwrights.enderkinesis.registry

import dev.architectury.registry.registries.DeferredRegister
import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.world.item.Instrument
import org.shipwrights.enderkinesis.EnderkinesisMod

/**
 * 19 cave-ambience [Instrument] records, code-registered (not datapack) because vanilla
 * `InstrumentItem.getInstrument` resolves the NBT against `BuiltInRegistries.INSTRUMENT`,
 * not the world's RegistryAccess — datapack instruments would silently fail to play.
 * The `data/enderkinesis/instrument/cave_*.json` files are intentionally absent to avoid
 * two sources of truth.
 */
object EKInstruments {
    val INSTRUMENTS: DeferredRegister<Instrument> =
        DeferredRegister.create(EnderkinesisMod.MOD_ID, Registries.INSTRUMENT)

    private const val USE_DURATION_TICKS: Int = 140
    private const val BROADCAST_RANGE_BLOCKS: Float = 256.0f

    /** Sound holders are looked up lazily inside the supplier — [EKSounds.register] runs
     *  before [register], so by instantiation time the cave sound events exist. */
    val CAVE_HORNS: List<RegistrySupplier<Instrument>> = (1..19).map { idx ->
        val instrumentName = "cave_$idx"
        val soundName = "horn.cave_$idx"
        INSTRUMENTS.register(instrumentName) {
            val soundKey = ResourceKey.create(
                Registries.SOUND_EVENT,
                EnderkinesisMod.id(soundName),
            )
            val soundHolder = BuiltInRegistries.SOUND_EVENT.getHolderOrThrow(soundKey)
            Instrument(soundHolder, USE_DURATION_TICKS, BROADCAST_RANGE_BLOCKS)
        }
    }

    fun register() = INSTRUMENTS.register()
}
