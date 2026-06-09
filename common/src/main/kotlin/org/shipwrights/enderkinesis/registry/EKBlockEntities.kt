package org.shipwrights.enderkinesis.registry

import dev.architectury.registry.registries.DeferredRegister
import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.block.entity.BlockEntityType
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.blockentity.CrepusculiteLatticeBlockEntity
import org.shipwrights.enderkinesis.blockentity.EnderAstrolabeBlockEntity
import org.shipwrights.enderkinesis.blockentity.EyeroscopeBlockEntity
import org.shipwrights.enderkinesis.blockentity.AetherPadBlockEntity
import org.shipwrights.enderkinesis.blockentity.HeartOfTheWildBlockEntity
import org.shipwrights.enderkinesis.blockentity.OrbOfLinkingBlockEntity
import org.shipwrights.enderkinesis.blockentity.PlanarAnchorBlockEntity
import org.shipwrights.enderkinesis.blockentity.ShulkerPufferBlockEntity
import org.shipwrights.enderkinesis.blockentity.VoidHarnessBlockEntity

object EKBlockEntities {
    val BLOCK_ENTITIES: DeferredRegister<BlockEntityType<*>> =
        DeferredRegister.create(EnderkinesisMod.MOD_ID, Registries.BLOCK_ENTITY_TYPE)

    @Suppress("UNCHECKED_CAST")
    val CREPUSCULITE_LATTICE: RegistrySupplier<BlockEntityType<CrepusculiteLatticeBlockEntity>> =
        BLOCK_ENTITIES.register("crepusculite_lattice") {
            BlockEntityType.Builder.of(
                ::CrepusculiteLatticeBlockEntity, EKBlocks.CREPUSCULITE_LATTICE.get()
            ).build(null) as BlockEntityType<CrepusculiteLatticeBlockEntity>
        }

    @Suppress("UNCHECKED_CAST")
    val ENDER_ASTROLABE: RegistrySupplier<BlockEntityType<EnderAstrolabeBlockEntity>> =
        BLOCK_ENTITIES.register("ender_astrolabe") {
            BlockEntityType.Builder.of(
                ::EnderAstrolabeBlockEntity, EKBlocks.ENDER_ASTROLABE.get()
            ).build(null) as BlockEntityType<EnderAstrolabeBlockEntity>
        }

    @Suppress("UNCHECKED_CAST")
    val EYEROSCOPE: RegistrySupplier<BlockEntityType<EyeroscopeBlockEntity>> =
        BLOCK_ENTITIES.register("eyeroscope") {
            BlockEntityType.Builder.of(
                ::EyeroscopeBlockEntity, EKBlocks.EYEROSCOPE.get()
            ).build(null) as BlockEntityType<EyeroscopeBlockEntity>
        }

    @Suppress("UNCHECKED_CAST")
    val PLANAR_ANCHOR: RegistrySupplier<BlockEntityType<PlanarAnchorBlockEntity>> =
        BLOCK_ENTITIES.register("planar_anchor") {
            BlockEntityType.Builder.of(
                ::PlanarAnchorBlockEntity, EKBlocks.PLANAR_ANCHOR.get()
            ).build(null) as BlockEntityType<PlanarAnchorBlockEntity>
        }

    @Suppress("UNCHECKED_CAST")
    val VOID_HARNESS: RegistrySupplier<BlockEntityType<VoidHarnessBlockEntity>> =
        BLOCK_ENTITIES.register("void_harness") {
            BlockEntityType.Builder.of(
                ::VoidHarnessBlockEntity, EKBlocks.VOID_HARNESS.get()
            ).build(null) as BlockEntityType<VoidHarnessBlockEntity>
        }

    @Suppress("UNCHECKED_CAST")
    val AETHER_PAD: RegistrySupplier<BlockEntityType<AetherPadBlockEntity>> =
        BLOCK_ENTITIES.register("aether_pad") {
            BlockEntityType.Builder.of(
                ::AetherPadBlockEntity, EKBlocks.AETHER_PAD.get()
            ).build(null) as BlockEntityType<AetherPadBlockEntity>
        }

    @Suppress("UNCHECKED_CAST")
    val SHULKER_PUFFER: RegistrySupplier<BlockEntityType<ShulkerPufferBlockEntity>> =
        BLOCK_ENTITIES.register("shulker_puffer") {
            BlockEntityType.Builder.of(
                ::ShulkerPufferBlockEntity, EKBlocks.SHULKER_PUFFER.get()
            ).build(null) as BlockEntityType<ShulkerPufferBlockEntity>
        }

    @Suppress("UNCHECKED_CAST")
    val ORB_OF_LINKING: RegistrySupplier<BlockEntityType<OrbOfLinkingBlockEntity>> =
        BLOCK_ENTITIES.register("orb_of_linking") {
            BlockEntityType.Builder.of(
                ::OrbOfLinkingBlockEntity, EKBlocks.ORB_OF_LINKING.get()
            ).build(null) as BlockEntityType<OrbOfLinkingBlockEntity>
        }

    @Suppress("UNCHECKED_CAST")
    val HEART_OF_THE_WILD: RegistrySupplier<BlockEntityType<HeartOfTheWildBlockEntity>> =
        BLOCK_ENTITIES.register("heart_of_the_wild") {
            BlockEntityType.Builder.of(
                ::HeartOfTheWildBlockEntity, EKBlocks.HEART_OF_THE_WILD.get()
            ).build(null) as BlockEntityType<HeartOfTheWildBlockEntity>
        }

    fun register() = BLOCK_ENTITIES.register()
}
