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
import org.shipwrights.enderkinesis.entity.MagicMissileEntity
import org.shipwrights.enderkinesis.entity.PlayerCorpseEntity
import org.shipwrights.enderkinesis.entity.PrismaticGoat
import org.shipwrights.enderkinesis.entity.SselithMothEntity
import org.shipwrights.enderkinesis.entity.ShulkonautEntity
import org.shipwrights.enderkinesis.entity.WohlemEntity
import org.shipwrights.enderkinesis.entity.WikLakHostEntity
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

    /** Magic Missile — homing projectile spawned by
     *  [org.shipwrights.enderkinesis.item.MagicMissileItem] or via dispenser. See
     *  [MagicMissileEntity] for the homing / target-filter / explosion logic. */
    val MAGIC_MISSILE: RegistrySupplier<EntityType<MagicMissileEntity>> =
        ENTITIES.register("magic_missile") {
            EntityType.Builder.of(::MagicMissileEntity, MobCategory.MISC)
                .sized(0.5f, 0.5f)
                .clientTrackingRange(32)
                .updateInterval(1)        // sync every tick so the trail tracks the projectile cleanly
                .build("magic_missile")
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

    /** Player Corpse — visual stand-in spawned by [WikLakDeathRedirect] at
     *  the player's intercepted death position. Player-skinned, no AI,
     *  immediately killed so vanilla [Mob.tickDeath] plays it out. */
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

    fun register() {
        ENTITIES.register()
        // Attributes — without this every Cataloger crashes on creation.
        EntityAttributeRegistry.register(CATALOGER) { Cataloger.createAttributes() }
        EntityAttributeRegistry.register(ARCHIVE) { ArchiveEntity.createAttributes() }
        EntityAttributeRegistry.register(WIK_LAK_HOST) { WikLakHostEntity.createAttributes() }
        EntityAttributeRegistry.register(PLAYER_CORPSE) { PlayerCorpseEntity.createAttributes() }
        EntityAttributeRegistry.register(CONSTRILIAR) { ConstriliarEntity.createAttributes() }
        EntityAttributeRegistry.register(SHULKONAUT) { ShulkonautEntity.createAttributes() }
        EntityAttributeRegistry.register(WOHLEM) { WohlemEntity.createAttributes() }
        EntityAttributeRegistry.register(SSELITH_MOTH) { SselithMothEntity.createAttributes() }
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
        placeOnGround(PRISMATIC_GOAT) { _, level, _, pos, _ ->
            val below = level.getBlockState(pos.below())
            // Replicates `Animal.isBrightEnoughToSpawn` (protected, so we
            // can't call it directly): vanilla passive mobs need raw
            // brightness > 8 at the spawn position.
            below.`is`(PRISMATIC_GOAT_SPAWNABLE_ON) &&
                level.getRawBrightness(pos, 0) > 8
        }
    }

    /** `listen`, not `get`: on Forge the entry does not exist until the registry event, and
     *  `get` during mod init throws. */
    private fun placeOnGround(
        entry: RegistrySupplier<out EntityType<*>>,
        predicate: SpawnPlacements.SpawnPredicate<Mob>,
    ) {
        entry.listen { registered ->
            @Suppress("UNCHECKED_CAST")
            SpawnPlacementsInvoker.`enderkinesis$register`(
                registered as EntityType<Mob>,
                SpawnPlacements.Type.ON_GROUND,
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
