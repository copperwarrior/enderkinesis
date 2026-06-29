package org.shipwrights.enderkinesis.registry

import dev.architectury.registry.registries.DeferredRegister
import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.block.entity.BlockEntityType
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.blockentity.CrepusculiteLatticeBlockEntity
import org.shipwrights.enderkinesis.blockentity.CrystalExplosiveBlockEntity
import org.shipwrights.enderkinesis.blockentity.EchoCannonBlockEntity
import org.shipwrights.enderkinesis.blockentity.EnderAstrolabeBlockEntity
import org.shipwrights.enderkinesis.blockentity.EyeroscopeBlockEntity
import org.shipwrights.enderkinesis.blockentity.AetherPadBlockEntity
import org.shipwrights.enderkinesis.blockentity.AncriteEyeBlockEntity
import org.shipwrights.enderkinesis.blockentity.FractalProjectorBlockEntity
import org.shipwrights.enderkinesis.blockentity.HeartOfTheWildBlockEntity
import org.shipwrights.enderkinesis.blockentity.MagicMissileLauncherBlockEntity
import org.shipwrights.enderkinesis.blockentity.OrbOfLinkingBlockEntity
import org.shipwrights.enderkinesis.blockentity.OrbOfScryingBlockEntity
import org.shipwrights.enderkinesis.blockentity.PlanarAnchorBlockEntity
import org.shipwrights.enderkinesis.blockentity.ShulkerPufferBlockEntity
import org.shipwrights.enderkinesis.blockentity.ShulkerStrutBlockEntity
import org.shipwrights.enderkinesis.blockentity.ShulkerStrutTopBlockEntity
import org.shipwrights.enderkinesis.blockentity.VoidHarnessBlockEntity
import org.shipwrights.enderkinesis.blockentity.VoidHookBlockEntity

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
    val ECHO_CANNON: RegistrySupplier<BlockEntityType<EchoCannonBlockEntity>> =
        BLOCK_ENTITIES.register("echo_cannon") {
            BlockEntityType.Builder.of(
                ::EchoCannonBlockEntity, EKBlocks.ECHO_CANNON.get()
            ).build(null) as BlockEntityType<EchoCannonBlockEntity>
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
    val VOID_HOOK: RegistrySupplier<BlockEntityType<VoidHookBlockEntity>> =
        BLOCK_ENTITIES.register("void_hook") {
            BlockEntityType.Builder.of(
                ::VoidHookBlockEntity, EKBlocks.VOID_HOOK.get()
            ).build(null) as BlockEntityType<VoidHookBlockEntity>
        }

    @Suppress("UNCHECKED_CAST")
    val AETHER_PAD: RegistrySupplier<BlockEntityType<AetherPadBlockEntity>> =
        BLOCK_ENTITIES.register("aether_pad") {
            BlockEntityType.Builder.of(
                ::AetherPadBlockEntity, EKBlocks.AETHER_PAD.get()
            ).build(null) as BlockEntityType<AetherPadBlockEntity>
        }

    @Suppress("UNCHECKED_CAST")
    val ANCRITE_EYE: RegistrySupplier<BlockEntityType<AncriteEyeBlockEntity>> =
        BLOCK_ENTITIES.register("analog_eye") {
            BlockEntityType.Builder.of(
                ::AncriteEyeBlockEntity, EKBlocks.ANCRITE_EYE.get()
            ).build(null) as BlockEntityType<AncriteEyeBlockEntity>
        }

    @Suppress("UNCHECKED_CAST")
    val SHULKER_PUFFER: RegistrySupplier<BlockEntityType<ShulkerPufferBlockEntity>> =
        BLOCK_ENTITIES.register("shulker_puffer") {
            BlockEntityType.Builder.of(
                ::ShulkerPufferBlockEntity, EKBlocks.SHULKER_PUFFER.get()
            ).build(null) as BlockEntityType<ShulkerPufferBlockEntity>
        }

    @Suppress("UNCHECKED_CAST")
    val SHULKER_STRUT: RegistrySupplier<BlockEntityType<ShulkerStrutBlockEntity>> =
        BLOCK_ENTITIES.register("shulker_strut") {
            BlockEntityType.Builder.of(
                ::ShulkerStrutBlockEntity, EKBlocks.SHULKER_STRUT.get()
            ).build(null) as BlockEntityType<ShulkerStrutBlockEntity>
        }

    @Suppress("UNCHECKED_CAST")
    val SHULKER_STRUT_TOP: RegistrySupplier<BlockEntityType<ShulkerStrutTopBlockEntity>> =
        BLOCK_ENTITIES.register("shulker_strut_top") {
            BlockEntityType.Builder.of(
                ::ShulkerStrutTopBlockEntity, EKBlocks.SHULKER_STRUT_TOP.get()
            ).build(null) as BlockEntityType<ShulkerStrutTopBlockEntity>
        }

    @Suppress("UNCHECKED_CAST")
    val ORB_OF_LINKING: RegistrySupplier<BlockEntityType<OrbOfLinkingBlockEntity>> =
        BLOCK_ENTITIES.register("orb_of_linking") {
            BlockEntityType.Builder.of(
                ::OrbOfLinkingBlockEntity, EKBlocks.ORB_OF_LINKING.get()
            ).build(null) as BlockEntityType<OrbOfLinkingBlockEntity>
        }

    @Suppress("UNCHECKED_CAST")
    val ORB_OF_SCRYING: RegistrySupplier<BlockEntityType<OrbOfScryingBlockEntity>> =
        BLOCK_ENTITIES.register("orb_of_scrying") {
            BlockEntityType.Builder.of(
                ::OrbOfScryingBlockEntity, EKBlocks.ORB_OF_SCRYING.get(),
            ).build(null) as BlockEntityType<OrbOfScryingBlockEntity>
        }

    @Suppress("UNCHECKED_CAST")
    val CRYSTAL_EXPLOSIVE: RegistrySupplier<BlockEntityType<CrystalExplosiveBlockEntity>> =
        BLOCK_ENTITIES.register("crystal_explosive") {
            BlockEntityType.Builder.of(
                ::CrystalExplosiveBlockEntity, EKBlocks.CRYSTAL_EXPLOSIVE.get()
            ).build(null) as BlockEntityType<CrystalExplosiveBlockEntity>
        }

    @Suppress("UNCHECKED_CAST")
    val HEART_OF_THE_WILD: RegistrySupplier<BlockEntityType<HeartOfTheWildBlockEntity>> =
        BLOCK_ENTITIES.register("heart_of_the_wild") {
            BlockEntityType.Builder.of(
                ::HeartOfTheWildBlockEntity, EKBlocks.HEART_OF_THE_WILD.get()
            ).build(null) as BlockEntityType<HeartOfTheWildBlockEntity>
        }

    @Suppress("UNCHECKED_CAST")
    val FRACTAL_PROJECTOR: RegistrySupplier<BlockEntityType<FractalProjectorBlockEntity>> =
        BLOCK_ENTITIES.register("fractal_projector") {
            BlockEntityType.Builder.of(
                ::FractalProjectorBlockEntity, EKBlocks.FRACTAL_PROJECTOR.get()
            ).build(null) as BlockEntityType<FractalProjectorBlockEntity>
        }

    @Suppress("UNCHECKED_CAST")
    val MAGIC_MISSILE_LAUNCHER: RegistrySupplier<BlockEntityType<MagicMissileLauncherBlockEntity>> =
        BLOCK_ENTITIES.register("magic_missile_launcher") {
            BlockEntityType.Builder.of(
                ::MagicMissileLauncherBlockEntity, EKBlocks.MAGIC_MISSILE_LAUNCHER.get()
            ).build(null) as BlockEntityType<MagicMissileLauncherBlockEntity>
        }

    fun register() = BLOCK_ENTITIES.register()
}
