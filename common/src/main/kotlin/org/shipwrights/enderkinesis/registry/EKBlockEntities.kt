package org.shipwrights.enderkinesis.registry

import dev.architectury.registry.registries.DeferredRegister
import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.block.entity.BlockEntityType
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.blockentity.ConstriliarPlatformBlockEntity
import org.shipwrights.enderkinesis.blockentity.UlderBeamAnchorBlockEntity
import org.shipwrights.enderkinesis.blockentity.OarscribeBlockEntity
import org.shipwrights.enderkinesis.blockentity.CrepusculiteLatticeBlockEntity
import org.shipwrights.enderkinesis.blockentity.CrystalExplosiveBlockEntity
import org.shipwrights.enderkinesis.blockentity.FountainOfBlessingBlockEntity
import org.shipwrights.enderkinesis.blockentity.EchoCannonBlockEntity
import org.shipwrights.enderkinesis.blockentity.EnderAstrolabeBlockEntity
import org.shipwrights.enderkinesis.blockentity.EyeroscopeBlockEntity
import org.shipwrights.enderkinesis.blockentity.AetherPadBlockEntity
import org.shipwrights.enderkinesis.blockentity.AncriteEyeBlockEntity
import org.shipwrights.enderkinesis.blockentity.YgannBarnacleBlockEntity
import org.shipwrights.enderkinesis.blockentity.QuasarOfPurityBlockEntity
import org.shipwrights.enderkinesis.blockentity.FractalProjectorBlockEntity
import org.shipwrights.enderkinesis.blockentity.HeartOfTheWildBlockEntity
import org.shipwrights.enderkinesis.blockentity.LodestarLauncherBlockEntity
import org.shipwrights.enderkinesis.blockentity.OrbOfLinkingBlockEntity
import org.shipwrights.enderkinesis.blockentity.OrbOfScryingBlockEntity
import org.shipwrights.enderkinesis.blockentity.PlanarAnchorBlockEntity
import org.shipwrights.enderkinesis.blockentity.ShulkerPufferBlockEntity
import org.shipwrights.enderkinesis.blockentity.ShulkerStrutBlockEntity
import org.shipwrights.enderkinesis.blockentity.ShulkerStrutTopBlockEntity
import org.shipwrights.enderkinesis.blockentity.StatueBlockEntity
import org.shipwrights.enderkinesis.blockentity.VoidHarnessBlockEntity
import org.shipwrights.enderkinesis.blockentity.AltarOfIlluminationBlockEntity
import org.shipwrights.enderkinesis.blockentity.ForgottenItemBlockEntity
import org.shipwrights.enderkinesis.blockentity.RuneBlockEntity
import org.shipwrights.enderkinesis.blockentity.ScribesDeskBlockEntity
import org.shipwrights.enderkinesis.blockentity.VoidHookBlockEntity
import org.shipwrights.enderkinesis.blockentity.WillfaerFlameBlockEntity

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
    val CONSTRILIAR_PLATFORM: RegistrySupplier<BlockEntityType<ConstriliarPlatformBlockEntity>> =
        BLOCK_ENTITIES.register("constriliar_platform") {
            BlockEntityType.Builder.of(
                ::ConstriliarPlatformBlockEntity, EKBlocks.CONSTRILIAR_PLATFORM.get()
            ).build(null) as BlockEntityType<ConstriliarPlatformBlockEntity>
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
    val YGANN_BARNACLE: RegistrySupplier<BlockEntityType<YgannBarnacleBlockEntity>> =
        BLOCK_ENTITIES.register("ygann_barnacle") {
            BlockEntityType.Builder.of(
                ::YgannBarnacleBlockEntity,
                EKBlocks.YGANN_BARNACLE.get()
            ).build(null) as BlockEntityType<YgannBarnacleBlockEntity>
        }

    @Suppress("UNCHECKED_CAST")
    val QUASAR_OF_PURITY: RegistrySupplier<BlockEntityType<QuasarOfPurityBlockEntity>> =
        BLOCK_ENTITIES.register("quasar_of_purity") {
            BlockEntityType.Builder.of(
                ::QuasarOfPurityBlockEntity,
                EKBlocks.QUASAR_OF_PURITY.get()
            ).build(null) as BlockEntityType<QuasarOfPurityBlockEntity>
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
    val ULDER_BEAM_ANCHOR: RegistrySupplier<BlockEntityType<UlderBeamAnchorBlockEntity>> =
        BLOCK_ENTITIES.register("ulder_beam_anchor") {
            BlockEntityType.Builder.of(
                ::UlderBeamAnchorBlockEntity, EKBlocks.ULDER_BEAM_ANCHOR.get()
            ).build(null) as BlockEntityType<UlderBeamAnchorBlockEntity>
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
    val FOUNTAIN_OF_BLESSING: RegistrySupplier<BlockEntityType<FountainOfBlessingBlockEntity>> =
        BLOCK_ENTITIES.register("fountain_of_blessing") {
            BlockEntityType.Builder.of(
                ::FountainOfBlessingBlockEntity, EKBlocks.FOUNTAIN_OF_BLESSING.get()
            ).build(null) as BlockEntityType<FountainOfBlessingBlockEntity>
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
    val LODESTAR_LAUNCHER: RegistrySupplier<BlockEntityType<LodestarLauncherBlockEntity>> =
        BLOCK_ENTITIES.register("lodestar_launcher") {
            BlockEntityType.Builder.of(
                ::LodestarLauncherBlockEntity, EKBlocks.LODESTAR_LAUNCHER.get()
            ).build(null) as BlockEntityType<LodestarLauncherBlockEntity>
        }

    /** One BE type shared across all statue blocks — the BE itself holds no
     *  per-statue state; the kind is read from the block at the BE's position by
     *  the BER. Listing all six blocks in `validBlocks` is what lets vanilla
     *  attach the shared type to each. */
    @Suppress("UNCHECKED_CAST")
    val STATUE: RegistrySupplier<BlockEntityType<StatueBlockEntity>> =
        BLOCK_ENTITIES.register("statue") {
            BlockEntityType.Builder.of(
                ::StatueBlockEntity,
                *EKBlocks.STATUES.map { it.get() }.toTypedArray(),
            ).build(null) as BlockEntityType<StatueBlockEntity>
        }

    @Suppress("UNCHECKED_CAST")
    val SCRIBES_DESK: RegistrySupplier<BlockEntityType<ScribesDeskBlockEntity>> =
        BLOCK_ENTITIES.register("scribes_desk") {
            BlockEntityType.Builder.of(::ScribesDeskBlockEntity, EKBlocks.SCRIBES_DESK.get())
                .build(null) as BlockEntityType<ScribesDeskBlockEntity>
        }

    @Suppress("UNCHECKED_CAST")
    val OARSCRIBE: RegistrySupplier<BlockEntityType<OarscribeBlockEntity>> =
        BLOCK_ENTITIES.register("oarscribe") {
            BlockEntityType.Builder.of(
                ::OarscribeBlockEntity, EKBlocks.OARSCRIBE.get(),
            ).build(null) as BlockEntityType<OarscribeBlockEntity>
        }

    @Suppress("UNCHECKED_CAST")
    val FORGOTTEN_ITEM: RegistrySupplier<BlockEntityType<ForgottenItemBlockEntity>> =
        BLOCK_ENTITIES.register("forgotten_item") {
            BlockEntityType.Builder.of(::ForgottenItemBlockEntity, EKBlocks.FORGOTTEN_ITEM.get())
                .build(null) as BlockEntityType<ForgottenItemBlockEntity>
        }

    // build(null): no datafixer type, the vanilla idiom; Mojang's annotations mark it non-null.
    @Suppress("UNCHECKED_CAST", "NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
    val ALTAR_OF_ILLUMINATION: RegistrySupplier<BlockEntityType<AltarOfIlluminationBlockEntity>> =
        BLOCK_ENTITIES.register("altar_of_illumination") {
            BlockEntityType.Builder.of(::AltarOfIlluminationBlockEntity, EKBlocks.ALTAR_OF_ILLUMINATION.get())
                .build(null) as BlockEntityType<AltarOfIlluminationBlockEntity>
        }

    // build(null): no datafixer type, the vanilla idiom; Mojang's annotations mark it non-null.
    @Suppress("UNCHECKED_CAST", "NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
    val RUNE: RegistrySupplier<BlockEntityType<RuneBlockEntity>> =
        BLOCK_ENTITIES.register("rune") {
            BlockEntityType.Builder.of(::RuneBlockEntity, EKBlocks.RUNE.get()).build(null) as BlockEntityType<RuneBlockEntity>
        }

    // build(null): no datafixer type, the vanilla idiom; Mojang's annotations mark it non-null.
    @Suppress("UNCHECKED_CAST", "NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
    val WILLFAER_FLAME: RegistrySupplier<BlockEntityType<WillfaerFlameBlockEntity>> =
        BLOCK_ENTITIES.register("willfaer_flame") {
            BlockEntityType.Builder.of(
                ::WillfaerFlameBlockEntity,
                EKBlocks.WILLFAER_LANTERN.get(),
                EKBlocks.WILLFAER_TORCH.get(),
                EKBlocks.WILLFAER_WALL_TORCH.get(),
            ).build(null) as BlockEntityType<WillfaerFlameBlockEntity>
        }

    fun register() = BLOCK_ENTITIES.register()
}
