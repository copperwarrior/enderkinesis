package org.shipwrights.enderkinesis.registry

import dev.architectury.registry.registries.DeferredRegister
import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.core.registries.Registries
import com.mojang.datafixers.types.Type
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.blockentity.ClawhodBlockEntity
import org.shipwrights.enderkinesis.blockentity.ConstriliarPlatformBlockEntity
import org.shipwrights.enderkinesis.blockentity.UlderBeamAnchorBlockEntity
import org.shipwrights.enderkinesis.blockentity.DraftingTableBlockEntity
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
import org.shipwrights.enderkinesis.blockentity.GasVentBlockEntity
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

    val CREPUSCULITE_LATTICE: RegistrySupplier<BlockEntityType<CrepusculiteLatticeBlockEntity>> =
        BLOCK_ENTITIES.register("crepusculite_lattice") {
            BlockEntityType.Builder.of(
                ::CrepusculiteLatticeBlockEntity, EKBlocks.CREPUSCULITE_LATTICE.get()
            ).build()
        }

    val CONSTRILIAR_PLATFORM: RegistrySupplier<BlockEntityType<ConstriliarPlatformBlockEntity>> =
        BLOCK_ENTITIES.register("constriliar_platform") {
            BlockEntityType.Builder.of(
                ::ConstriliarPlatformBlockEntity, EKBlocks.CONSTRILIAR_PLATFORM.get()
            ).build()
        }

    val CLAWHOD: RegistrySupplier<BlockEntityType<ClawhodBlockEntity>> =
        BLOCK_ENTITIES.register("clawhod") {
            BlockEntityType.Builder.of(
                ::ClawhodBlockEntity, EKBlocks.CLAWHOD.get()
            ).build()
        }

    val ECHO_CANNON: RegistrySupplier<BlockEntityType<EchoCannonBlockEntity>> =
        BLOCK_ENTITIES.register("echo_cannon") {
            BlockEntityType.Builder.of(
                ::EchoCannonBlockEntity, EKBlocks.ECHO_CANNON.get()
            ).build()
        }

    val ENDER_ASTROLABE: RegistrySupplier<BlockEntityType<EnderAstrolabeBlockEntity>> =
        BLOCK_ENTITIES.register("ender_astrolabe") {
            BlockEntityType.Builder.of(
                ::EnderAstrolabeBlockEntity, EKBlocks.ENDER_ASTROLABE.get()
            ).build()
        }

    val EYEROSCOPE: RegistrySupplier<BlockEntityType<EyeroscopeBlockEntity>> =
        BLOCK_ENTITIES.register("eyeroscope") {
            BlockEntityType.Builder.of(
                ::EyeroscopeBlockEntity, EKBlocks.EYEROSCOPE.get()
            ).build()
        }

    val PLANAR_ANCHOR: RegistrySupplier<BlockEntityType<PlanarAnchorBlockEntity>> =
        BLOCK_ENTITIES.register("planar_anchor") {
            BlockEntityType.Builder.of(
                ::PlanarAnchorBlockEntity, EKBlocks.PLANAR_ANCHOR.get()
            ).build()
        }

    val VOID_HARNESS: RegistrySupplier<BlockEntityType<VoidHarnessBlockEntity>> =
        BLOCK_ENTITIES.register("void_harness") {
            BlockEntityType.Builder.of(
                ::VoidHarnessBlockEntity, EKBlocks.VOID_HARNESS.get()
            ).build()
        }

    val VOID_HOOK: RegistrySupplier<BlockEntityType<VoidHookBlockEntity>> =
        BLOCK_ENTITIES.register("void_hook") {
            BlockEntityType.Builder.of(
                ::VoidHookBlockEntity, EKBlocks.VOID_HOOK.get()
            ).build()
        }

    val AETHER_PAD: RegistrySupplier<BlockEntityType<AetherPadBlockEntity>> =
        BLOCK_ENTITIES.register("aether_pad") {
            BlockEntityType.Builder.of(
                ::AetherPadBlockEntity, EKBlocks.AETHER_PAD.get()
            ).build()
        }

    val YGANN_BARNACLE: RegistrySupplier<BlockEntityType<YgannBarnacleBlockEntity>> =
        BLOCK_ENTITIES.register("ygann_barnacle") {
            BlockEntityType.Builder.of(
                ::YgannBarnacleBlockEntity,
                EKBlocks.YGANN_BARNACLE.get()
            ).build()
        }

    val QUASAR_OF_PURITY: RegistrySupplier<BlockEntityType<QuasarOfPurityBlockEntity>> =
        BLOCK_ENTITIES.register("quasar_of_purity") {
            BlockEntityType.Builder.of(
                ::QuasarOfPurityBlockEntity,
                EKBlocks.QUASAR_OF_PURITY.get()
            ).build()
        }

    val ANCRITE_EYE: RegistrySupplier<BlockEntityType<AncriteEyeBlockEntity>> =
        BLOCK_ENTITIES.register("analog_eye") {
            BlockEntityType.Builder.of(
                ::AncriteEyeBlockEntity, EKBlocks.ANCRITE_EYE.get()
            ).build()
        }

    val GAS_VENT: RegistrySupplier<BlockEntityType<GasVentBlockEntity>> =
        BLOCK_ENTITIES.register("gas_vent") {
            BlockEntityType.Builder.of(
                ::GasVentBlockEntity, EKBlocks.GAS_VENT.get()
            ).build()
        }

    val SHULKER_PUFFER: RegistrySupplier<BlockEntityType<ShulkerPufferBlockEntity>> =
        BLOCK_ENTITIES.register("shulker_puffer") {
            BlockEntityType.Builder.of(
                ::ShulkerPufferBlockEntity, EKBlocks.SHULKER_PUFFER.get()
            ).build()
        }

    val SHULKER_STRUT: RegistrySupplier<BlockEntityType<ShulkerStrutBlockEntity>> =
        BLOCK_ENTITIES.register("shulker_strut") {
            BlockEntityType.Builder.of(
                ::ShulkerStrutBlockEntity, EKBlocks.SHULKER_STRUT.get()
            ).build()
        }

    val SHULKER_STRUT_TOP: RegistrySupplier<BlockEntityType<ShulkerStrutTopBlockEntity>> =
        BLOCK_ENTITIES.register("shulker_strut_top") {
            BlockEntityType.Builder.of(
                ::ShulkerStrutTopBlockEntity, EKBlocks.SHULKER_STRUT_TOP.get()
            ).build()
        }

    val ORB_OF_LINKING: RegistrySupplier<BlockEntityType<OrbOfLinkingBlockEntity>> =
        BLOCK_ENTITIES.register("orb_of_linking") {
            BlockEntityType.Builder.of(
                ::OrbOfLinkingBlockEntity, EKBlocks.ORB_OF_LINKING.get()
            ).build()
        }

    val ULDER_BEAM_ANCHOR: RegistrySupplier<BlockEntityType<UlderBeamAnchorBlockEntity>> =
        BLOCK_ENTITIES.register("ulder_beam_anchor") {
            BlockEntityType.Builder.of(
                ::UlderBeamAnchorBlockEntity, EKBlocks.ULDER_BEAM_ANCHOR.get()
            ).build()
        }

    val ORB_OF_SCRYING: RegistrySupplier<BlockEntityType<OrbOfScryingBlockEntity>> =
        BLOCK_ENTITIES.register("orb_of_scrying") {
            BlockEntityType.Builder.of(
                ::OrbOfScryingBlockEntity, EKBlocks.ORB_OF_SCRYING.get(),
            ).build()
        }

    val CRYSTAL_EXPLOSIVE: RegistrySupplier<BlockEntityType<CrystalExplosiveBlockEntity>> =
        BLOCK_ENTITIES.register("crystal_explosive") {
            BlockEntityType.Builder.of(
                ::CrystalExplosiveBlockEntity, EKBlocks.CRYSTAL_EXPLOSIVE.get()
            ).build()
        }

    val FOUNTAIN_OF_BLESSING: RegistrySupplier<BlockEntityType<FountainOfBlessingBlockEntity>> =
        BLOCK_ENTITIES.register("fountain_of_blessing") {
            BlockEntityType.Builder.of(
                ::FountainOfBlessingBlockEntity, EKBlocks.FOUNTAIN_OF_BLESSING.get()
            ).build()
        }

    val HEART_OF_THE_WILD: RegistrySupplier<BlockEntityType<HeartOfTheWildBlockEntity>> =
        BLOCK_ENTITIES.register("heart_of_the_wild") {
            BlockEntityType.Builder.of(
                ::HeartOfTheWildBlockEntity, EKBlocks.HEART_OF_THE_WILD.get()
            ).build()
        }

    val FRACTAL_PROJECTOR: RegistrySupplier<BlockEntityType<FractalProjectorBlockEntity>> =
        BLOCK_ENTITIES.register("fractal_projector") {
            BlockEntityType.Builder.of(
                ::FractalProjectorBlockEntity, EKBlocks.FRACTAL_PROJECTOR.get()
            ).build()
        }

    val LODESTAR_LAUNCHER: RegistrySupplier<BlockEntityType<LodestarLauncherBlockEntity>> =
        BLOCK_ENTITIES.register("lodestar_launcher") {
            BlockEntityType.Builder.of(
                ::LodestarLauncherBlockEntity, EKBlocks.LODESTAR_LAUNCHER.get()
            ).build()
        }

    /** One BE type shared across all statue blocks — the BE itself holds no
     *  per-statue state; the kind is read from the block at the BE's position by
     *  the BER. Listing all six blocks in `validBlocks` is what lets vanilla
     *  attach the shared type to each. */
    val STATUE: RegistrySupplier<BlockEntityType<StatueBlockEntity>> =
        BLOCK_ENTITIES.register("statue") {
            BlockEntityType.Builder.of(
                ::StatueBlockEntity,
                *EKBlocks.STATUES.map { it.get() }.toTypedArray(),
            ).build()
        }

    val SCRIBES_DESK: RegistrySupplier<BlockEntityType<ScribesDeskBlockEntity>> =
        BLOCK_ENTITIES.register("scribes_desk") {
            BlockEntityType.Builder.of(::ScribesDeskBlockEntity, EKBlocks.SCRIBES_DESK.get())
                .build()
        }

    val DRAFTING_TABLE: RegistrySupplier<BlockEntityType<DraftingTableBlockEntity>> =
        BLOCK_ENTITIES.register("drafting_table") {
            BlockEntityType.Builder.of(::DraftingTableBlockEntity, EKBlocks.DRAFTING_TABLE.get())
                .build()
        }

    val OARSCRIBE: RegistrySupplier<BlockEntityType<OarscribeBlockEntity>> =
        BLOCK_ENTITIES.register("oarscribe") {
            BlockEntityType.Builder.of(
                ::OarscribeBlockEntity, EKBlocks.OARSCRIBE.get(),
            ).build()
        }

    val FORGOTTEN_ITEM: RegistrySupplier<BlockEntityType<ForgottenItemBlockEntity>> =
        BLOCK_ENTITIES.register("forgotten_item") {
            BlockEntityType.Builder.of(::ForgottenItemBlockEntity, EKBlocks.FORGOTTEN_ITEM.get())
                .build()
        }

    val ALTAR_OF_ILLUMINATION: RegistrySupplier<BlockEntityType<AltarOfIlluminationBlockEntity>> =
        BLOCK_ENTITIES.register("altar_of_illumination") {
            BlockEntityType.Builder.of(::AltarOfIlluminationBlockEntity, EKBlocks.ALTAR_OF_ILLUMINATION.get())
                .build()
        }

    val RUNE: RegistrySupplier<BlockEntityType<RuneBlockEntity>> =
        BLOCK_ENTITIES.register("rune") {
            BlockEntityType.Builder.of(::RuneBlockEntity, EKBlocks.RUNE.get()).build()
        }

    val WILLFAER_FLAME: RegistrySupplier<BlockEntityType<WillfaerFlameBlockEntity>> =
        BLOCK_ENTITIES.register("willfaer_flame") {
            BlockEntityType.Builder.of(
                ::WillfaerFlameBlockEntity,
                EKBlocks.WILLFAER_LANTERN.get(),
                EKBlocks.WILLFAER_TORCH.get(),
                EKBlocks.WILLFAER_WALL_TORCH.get(),
            ).build()
        }

    fun register() = BLOCK_ENTITIES.register()
}

// Not build(null): the Java parameter is non-null-annotated, so a literal null warns.
private fun <T : BlockEntity> BlockEntityType.Builder<T>.build(): BlockEntityType<T> =
    build(java.util.Optional.empty<Type<*>>().orElse(null))
