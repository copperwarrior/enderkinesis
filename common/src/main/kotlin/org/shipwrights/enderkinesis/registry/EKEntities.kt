package org.shipwrights.enderkinesis.registry

import dev.architectury.registry.level.entity.EntityAttributeRegistry
import dev.architectury.registry.registries.DeferredRegister
import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.core.registries.Registries
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.entity.SpawnPlacements
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.entity.ArchiveEntity
import org.shipwrights.enderkinesis.entity.Cataloger
import org.shipwrights.enderkinesis.entity.ConstriliarEntity
import org.shipwrights.enderkinesis.entity.LodestarDartEntity
import org.shipwrights.enderkinesis.entity.MiasmaEntity
import org.shipwrights.enderkinesis.entity.SignatureEntity
import org.shipwrights.enderkinesis.entity.SingularityEntity
import org.shipwrights.enderkinesis.entity.SpellBoltEntity
import org.shipwrights.enderkinesis.entity.PlayerCorpseEntity
import org.shipwrights.enderkinesis.entity.PrismaticGoat
import org.shipwrights.enderkinesis.entity.SselithMothEntity
import org.shipwrights.enderkinesis.entity.RaisedSkeletonEntity
import org.shipwrights.enderkinesis.entity.ShilleagkDeerEntity
import org.shipwrights.enderkinesis.entity.ShulkonautEntity
import org.shipwrights.enderkinesis.entity.WeheloTearEntity
import org.shipwrights.enderkinesis.entity.WohlemEntity
import org.shipwrights.enderkinesis.entity.WohlonRitePedestal
import org.shipwrights.enderkinesis.entity.WikLakHostEntity
import org.shipwrights.enderkinesis.entity.WillOTheWispEntity
import org.shipwrights.enderkinesis.entity.WogorBoat
import org.shipwrights.enderkinesis.entity.WogorChestBoat
import org.shipwrights.enderkinesis.mixin.SpawnPlacementsInvoker

/** All entity types added by Enderkinesis. */
object EKEntities {
    val ENTITIES: DeferredRegister<EntityType<*>> =
        DeferredRegister.create(EnderkinesisMod.MOD_ID, Registries.ENTITY_TYPE)

    /** The Cataloger — slow humanoid that wanders Sselith between
     *  [Cataloger.CATALOGER_TARGETS] blocks. Player-sized bbox. */
    val CATALOGER: RegistrySupplier<EntityType<Cataloger>> =
        ENTITIES.register(Cataloger.ID_PATH) {
            EntityType.Builder.of(::Cataloger, MobCategory.CREATURE)
                .sized(0.6f, 1.8f)        // player size
                .clientTrackingRange(10)
                .build(Cataloger.ID.toString())
        }

    val SPELL_BOLT: RegistrySupplier<EntityType<SpellBoltEntity>> =
        ENTITIES.register("spell_bolt") {
            EntityType.Builder.of(::SpellBoltEntity, MobCategory.MISC)
                .sized(0.3f, 0.3f)
                .clientTrackingRange(8)
                .updateInterval(10)
                .build("spell_bolt")
        }

    /** The visuals reach far past the box, so the renderer, not the box, decides culling. */
    val SIGNATURE: RegistrySupplier<EntityType<SignatureEntity>> =
        ENTITIES.register("signature") {
            EntityType.Builder.of(::SignatureEntity, MobCategory.MISC)
                .sized(0.5f, 0.5f)
                .clientTrackingRange(10)
                .updateInterval(1)
                .noSummon()
                .fireImmune()
                .build("signature")
        }

    val SINGULARITY: RegistrySupplier<EntityType<SingularityEntity>> =
        ENTITIES.register("singularity") {
            EntityType.Builder.of(::SingularityEntity, MobCategory.MISC)
                .sized(0.5f, 0.5f)
                .clientTrackingRange(8)
                .updateInterval(20)
                .build("singularity")
        }

    val MIASMA: RegistrySupplier<EntityType<MiasmaEntity>> =
        ENTITIES.register("miasma") {
            EntityType.Builder.of(::MiasmaEntity, MobCategory.MISC)
                .sized(0.5f, 0.5f)
                .fireImmune()
                .clientTrackingRange(10)
                .updateInterval(Int.MAX_VALUE)
                .build("miasma")
        }

    /** Lodestar Dart — homing projectile spawned by
     *  [org.shipwrights.enderkinesis.item.LodestarDartItem] or via dispenser. See
     *  [LodestarDartEntity] for the homing / target-filter / explosion logic. */
    val LODESTAR_DART: RegistrySupplier<EntityType<LodestarDartEntity>> =
        ENTITIES.register("lodestar_dart") {
            EntityType.Builder.of(::LodestarDartEntity, MobCategory.MISC)
                .sized(0.5f, 0.5f)
                .clientTrackingRange(32)
                .updateInterval(1)        // sync every tick so the trail tracks the projectile cleanly
                .build("lodestar_dart")
        }

    /** The Archive — slow-drifting paper-tornado that wanders Sselith and
     *  collapses into its contained items on player contact. Custom Entity
     *  (not a Mob — no AI/health/attributes), 1×3×1 bbox so it threads through
     *  1-block corridors. `MISC` category so it doesn't compete with the
     *  Cataloger's CREATURE cap or trigger hostile-mob spawn rules. */
    val ARCHIVE: RegistrySupplier<EntityType<ArchiveEntity>> =
        ENTITIES.register(ArchiveEntity.ID_PATH) {
            EntityType.Builder.of(::ArchiveEntity, MobCategory.MISC)
                .sized(1.0f, 3.0f)
                .clientTrackingRange(8)
                .updateInterval(3)
                .build(ArchiveEntity.ID.toString())
        }

    val WOHLON_RITE_PEDESTAL: RegistrySupplier<EntityType<WohlonRitePedestal>> =
        ENTITIES.register(WohlonRitePedestal.ID_PATH) {
            EntityType.Builder.of(::WohlonRitePedestal, MobCategory.MISC)
                .sized(1.5f, 2.75f)
                .clientTrackingRange(8)
                .updateInterval(2)
                .build(WohlonRitePedestal.ID_PATH)
        }

    /** Player Corpse — visual stand-in spawned by [WikLakDeathRedirect] at
     *  the player's intercepted death position. Player-skinned, no AI,
     *  immediately killed so vanilla [Mob.tickDeath] plays it out. */
    val WEHELO_TEAR: RegistrySupplier<EntityType<WeheloTearEntity>> =
        ENTITIES.register(WeheloTearEntity.ID_PATH) {
            EntityType.Builder.of(::WeheloTearEntity, MobCategory.MISC)
                .sized(0.9f, WeheloTearEntity.HEIGHT)
                .noSave()
                .noSummon()
                .fireImmune()
                .clientTrackingRange(8)
                .updateInterval(20)
                .build(WeheloTearEntity.ID_PATH)
        }

    val PLAYER_CORPSE: RegistrySupplier<EntityType<PlayerCorpseEntity>> =
        ENTITIES.register(PlayerCorpseEntity.ID_PATH) {
            EntityType.Builder.of(::PlayerCorpseEntity, MobCategory.MISC)
                .sized(0.6f, 1.8f)
                .clientTrackingRange(10)
                .build(PlayerCorpseEntity.ID.toString())
        }

    /** Wik-Lak Host — player-summoned humanoid construct (see
     *  [WikLakHostEntity]). MISC category so it doesn't compete for the
     *  CREATURE spawn cap; built constructs are never natural spawns
     *  anyway. Player-sized bbox. */
    val WIK_LAK_HOST: RegistrySupplier<EntityType<WikLakHostEntity>> =
        ENTITIES.register(WikLakHostEntity.ID_PATH) {
            EntityType.Builder.of(::WikLakHostEntity, MobCategory.MISC)
                .sized(0.6f, 1.8f)        // player size
                .clientTrackingRange(10)
                .build(WikLakHostEntity.ID.toString())
        }

    /** Shulkonaut — stationary shulker-like gunnery officer: holds a Staff of Command
     *  block list and a book-given target selector, and volleys a random listed block
     *  at the closest match every four seconds. */
    val SHULKONAUT: RegistrySupplier<EntityType<ShulkonautEntity>> =
        ENTITIES.register(ShulkonautEntity.ID_PATH) {
            EntityType.Builder.of(::ShulkonautEntity, MobCategory.MISC)
                .sized(1.0f, 1.0f)
                .clientTrackingRange(10)
                .build(ShulkonautEntity.ID_PATH)
        }

    /** Raised Skeleton — owned bowman raised by the Grand Staff of the Dead. MISC: never a natural spawn. */
    val RAISED_SKELETON: RegistrySupplier<EntityType<RaisedSkeletonEntity>> =
        ENTITIES.register(RaisedSkeletonEntity.ID_PATH) {
            EntityType.Builder.of(::RaisedSkeletonEntity, MobCategory.MISC)
                .sized(0.6f, 1.99f)
                .clientTrackingRange(8)
                .build(RaisedSkeletonEntity.ID.toString())
        }

    /** Constriliar — owner-following four-legged walking platform that carries a
     *  1-layer VS2 cargo ship on its back. MISC: player-spawned only. Bbox is the
     *  body plate footprint; kept shorter than the cargo layer (which starts 0.75
     *  above the feet) so the entity never collides with its own static ship. */
    val CONSTRILIAR: RegistrySupplier<EntityType<ConstriliarEntity>> =
        ENTITIES.register(ConstriliarEntity.ID_PATH) {
            EntityType.Builder.of(::ConstriliarEntity, MobCategory.MISC)
                .sized(1.0f, 0.45f)
                .fireImmune()
                .clientTrackingRange(10)
                .build(ConstriliarEntity.ID_PATH)
        }

    /** Prismatic Goat — passive quadruped that spawns in Wohlon
     *  biomes. Vanilla-goat-sized bbox so existing pathfinder budgets
     *  and collision math fit without tuning. */
    val PRISMATIC_GOAT: RegistrySupplier<EntityType<PrismaticGoat>> =
        ENTITIES.register(PrismaticGoat.ID_PATH) {
            EntityType.Builder.of(::PrismaticGoat, MobCategory.CREATURE)
                .sized(0.9f, 1.3f)        // vanilla goat dims
                .clientTrackingRange(10)
                .build(PrismaticGoat.ID.toString())
        }

    /** Sselith Moth — small flocking mob that drifts through the extreme
     *  high and low reaches of Sselith in groups of 3–5. AMBIENT category
     *  so it doesn't compete with CREATURE or MONSTER spawn caps. The
     *  hitbox stays body-sized (a wingspan box suffocates in terrain and
     *  fights the flocking); the wings are covered by the entity's
     *  CULLING box override instead. */
    val SSELITH_MOTH: RegistrySupplier<EntityType<SselithMothEntity>> =
        ENTITIES.register(SselithMothEntity.ID_PATH) {
            EntityType.Builder.of(::SselithMothEntity, MobCategory.AMBIENT)
                .sized(0.5f, 0.3f)
                .clientTrackingRange(8)
                .updateInterval(3)
                .build(SselithMothEntity.ID.toString())
        }

    /** Wohlem — lumbering many-eyed Wohlon brute. Bbox from the model
     *  extents (shoulders ~1.9 wide, crown at ~3 blocks). */
    val WOHLEM: RegistrySupplier<EntityType<WohlemEntity>> =
        ENTITIES.register(WohlemEntity.ID_PATH) {
            EntityType.Builder.of(::WohlemEntity, MobCategory.MONSTER)
                .sized(1.6f, 3.0f)
                .clientTrackingRange(10)
                .build(WohlemEntity.ID_PATH)
        }

    /** Height covers the head, not the antler tips; the deer grows taller while it stands upright. */
    val SHILLEAGK_DEER: RegistrySupplier<EntityType<ShilleagkDeerEntity>> =
        ENTITIES.register(ShilleagkDeerEntity.ID_PATH) {
            EntityType.Builder.of(::ShilleagkDeerEntity, MobCategory.CREATURE)
                .sized(ShilleagkDeerEntity.WIDTH, ShilleagkDeerEntity.HEIGHT)
                .clientTrackingRange(10)
                .build(ShilleagkDeerEntity.ID_PATH)
        }

    val WILL_O_THE_WISP: RegistrySupplier<EntityType<WillOTheWispEntity>> =
        ENTITIES.register(WillOTheWispEntity.ID_PATH) {
            EntityType.Builder.of(::WillOTheWispEntity, MobCategory.AMBIENT)
                .sized(WillOTheWispEntity.WIDTH, WillOTheWispEntity.HEIGHT)
                .fireImmune()
                .clientTrackingRange(8)
                .build(WillOTheWispEntity.ID_PATH)
        }

    val WOGOR_BOAT: RegistrySupplier<EntityType<WogorBoat>> =
        ENTITIES.register("wogor_boat") {
            EntityType.Builder.of({ type, level -> WogorBoat(type, level) }, MobCategory.MISC)
                .sized(BOAT_WIDTH, BOAT_HEIGHT)
                .clientTrackingRange(10)
                .build("wogor_boat")
        }

    val WOGOR_CHEST_BOAT: RegistrySupplier<EntityType<WogorChestBoat>> =
        ENTITIES.register("wogor_chest_boat") {
            EntityType.Builder.of({ type, level -> WogorChestBoat(type, level) }, MobCategory.MISC)
                .sized(BOAT_WIDTH, BOAT_HEIGHT)
                .clientTrackingRange(10)
                .build("wogor_chest_boat")
        }

    private const val BOAT_WIDTH = 1.375f
    private const val BOAT_HEIGHT = 0.5625f

    fun register() {
        ENTITIES.register()
        // Attributes — without this every Cataloger crashes on creation.
        EntityAttributeRegistry.register(CATALOGER) { Cataloger.createAttributes() }
        EntityAttributeRegistry.register(ARCHIVE) { ArchiveEntity.createAttributes() }
        EntityAttributeRegistry.register(WIK_LAK_HOST) { WikLakHostEntity.createAttributes() }
        EntityAttributeRegistry.register(PLAYER_CORPSE) { PlayerCorpseEntity.createAttributes() }
        EntityAttributeRegistry.register(CONSTRILIAR) { ConstriliarEntity.createAttributes() }
        EntityAttributeRegistry.register(SHULKONAUT) { ShulkonautEntity.createAttributes() }
        EntityAttributeRegistry.register(RAISED_SKELETON) { RaisedSkeletonEntity.createAttributes() }
        EntityAttributeRegistry.register(WOHLEM) { WohlemEntity.createAttributes() }
        EntityAttributeRegistry.register(SSELITH_MOTH) { SselithMothEntity.createAttributes() }
        EntityAttributeRegistry.register(SHILLEAGK_DEER) { ShilleagkDeerEntity.createAttributes() }
        EntityAttributeRegistry.register(WILL_O_THE_WISP) { WillOTheWispEntity.createAttributes() }
        // Vanilla goat attributes verbatim — `PrismaticGoat`
        // extends `Goat` directly, so its base stats match.
        EntityAttributeRegistry.register(PRISMATIC_GOAT) {
            net.minecraft.world.entity.animal.goat.Goat.createAttributes()
        }
        // Spawn placement — only spawns on top of solid ground. Light-independent
        // (Sselith's dim ambient would otherwise suppress every spawn), and not
        // tied to Animal because the cataloger isn't one.
        placeOnGround(CATALOGER) { _, level, _, pos, _ ->
            // Restrict to Sselith floor blocks — platforms (POLISHED_DEEPSLATE
            // with OWL_DEEPSLATE marker tiles) and pathway corridor floors
            // (also POLISHED_DEEPSLATE / OWL_DEEPSLATE axial markers). CHISELED_DEEPSLATE
            // stays for chunks generated before the owl tiles replaced it.
            val belowState: BlockState = level.getBlockState(pos.below())
            belowState.`is`(Blocks.POLISHED_DEEPSLATE) || belowState.`is`(EKBlocks.OWL_DEEPSLATE.get()) ||
                belowState.`is`(Blocks.CHISELED_DEEPSLATE)
        }

        // Wohlem spawn placement — solid ground in the Wohlon biome (the
        // biome JSON's spawners.monster entry does the biome gating).
        // Light-INDEPENDENT: Wohlonnogondonia daylight would suppress a
        // vanilla monster-rules spawn, and the wohlem is a fixture of the
        // spread, not a night prowler.
        placeOnGround(WOHLEM) { type, level, spawnType, pos, random ->
            Mob.checkMobSpawnRules(type, level, spawnType, pos, random)
        }

        // Prismatic Goat spawn placement — vanilla-passive-mob rules
        // (sufficient light + spawnable-on tag), with the tag
        // ([PRISMATIC_GOAT_SPAWNABLE_ON]) unioning the vanilla
        // `#minecraft:animals_spawnable_on` with the wogor / mud surface
        // family so spawns work on BOTH the Wohlon dimension's mud
        // floors AND the overworld's grass under spread biome cells.
        // Biome gating lives in the biome JSON's `spawners.creature`
        // entry — placement just has to agree this is "ground a
        // passive mob can stand on."
        placeOnGround(PRISMATIC_GOAT, ::isWohlonAnimalGround)
        placeOnGround(SHILLEAGK_DEER, ::isWohlonAnimalGround)
        place(WILL_O_THE_WISP, SpawnPlacements.Type.NO_RESTRICTIONS, WillOTheWispEntity::canSpawn)
    }

    @Suppress("UNUSED_PARAMETER") // Shape fixed by SpawnPlacements.SpawnPredicate.
    private fun isWohlonAnimalGround(
        type: EntityType<Mob>,
        level: net.minecraft.world.level.ServerLevelAccessor,
        spawnType: net.minecraft.world.entity.MobSpawnType,
        pos: net.minecraft.core.BlockPos,
        random: net.minecraft.util.RandomSource,
    ): Boolean =
        // Replicates `Animal.isBrightEnoughToSpawn` (protected): vanilla passive mobs need raw brightness > 8.
        level.getBlockState(pos.below()).`is`(PRISMATIC_GOAT_SPAWNABLE_ON) &&
            level.getRawBrightness(pos, 0) > 8

    /** `listen`, not `get`: on Forge the entry does not exist until the registry event, and
     *  `get` during mod init throws. */
    private fun placeOnGround(
        entry: RegistrySupplier<out EntityType<*>>,
        predicate: SpawnPlacements.SpawnPredicate<Mob>,
    ) = place(entry, SpawnPlacements.Type.ON_GROUND, predicate)

    private fun place(
        entry: RegistrySupplier<out EntityType<*>>,
        type: SpawnPlacements.Type,
        predicate: SpawnPlacements.SpawnPredicate<Mob>,
    ) {
        entry.listen { registered ->
            @Suppress("UNCHECKED_CAST")
            SpawnPlacementsInvoker.`enderkinesis$register`(
                registered as EntityType<Mob>,
                type,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                predicate,
            )
        }
    }

    /** Surface-block tag — block must match for a Prismatic Goat to
     *  spawn here. Union of vanilla `#minecraft:animals_spawnable_on`
     *  plus the wogor / mud family so the natural spawner works on
     *  every Wohlon surface palette. */
    val PRISMATIC_GOAT_SPAWNABLE_ON: net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block> =
        net.minecraft.tags.TagKey.create(
            net.minecraft.core.registries.Registries.BLOCK,
            net.minecraft.resources.ResourceLocation("enderkinesis", "prismatic_goat_spawnable_on"),
        )
}
