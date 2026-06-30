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
import org.shipwrights.enderkinesis.entity.MagicMissileEntity
import org.shipwrights.enderkinesis.entity.PlayerCorpseEntity
import org.shipwrights.enderkinesis.entity.PrismaticGoat
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

    fun register() {
        ENTITIES.register()
        // Attributes — without this every Cataloger crashes on creation.
        EntityAttributeRegistry.register(CATALOGER) { Cataloger.createAttributes() }
        EntityAttributeRegistry.register(ARCHIVE) { ArchiveEntity.createAttributes() }
        EntityAttributeRegistry.register(WIK_LAK_HOST) { WikLakHostEntity.createAttributes() }
        EntityAttributeRegistry.register(PLAYER_CORPSE) { PlayerCorpseEntity.createAttributes() }
        // Vanilla goat attributes verbatim — `PrismaticGoat`
        // extends `Goat` directly, so its base stats match.
        EntityAttributeRegistry.register(PRISMATIC_GOAT) {
            net.minecraft.world.entity.animal.goat.Goat.createAttributes()
        }
        // Spawn placement — only spawns on top of solid ground. Light-independent
        // (Sselith's dim ambient would otherwise suppress every spawn), and not
        // tied to Animal because the cataloger isn't one.
        @Suppress("UNCHECKED_CAST")
        val type = CATALOGER.get() as EntityType<Mob>
        SpawnPlacementsInvoker.`enderkinesis$register`(
            type,
            SpawnPlacements.Type.ON_GROUND,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
        ) { _, level, _, pos, _ ->
            // Restrict to Sselith floor blocks — platforms (POLISHED_DEEPSLATE
            // with CHISELED_DEEPSLATE marker tiles) and pathway corridor floors
            // (also POLISHED_DEEPSLATE / CHISELED_DEEPSLATE axial markers).
            val belowState: BlockState = level.getBlockState(pos.below())
            belowState.`is`(Blocks.POLISHED_DEEPSLATE) || belowState.`is`(Blocks.CHISELED_DEEPSLATE)
        }

        // Prismatic Goat spawn placement — accept any solid ground.
        // Wohlon spans two surface palettes (vanilla grass in the
        // Overworld biome, mud in the Wohlonnogondonia dimension); a
        // strict block-tag check would gate one of them out. The
        // biome JSON's `spawners.creature` entry handles "only
        // spawn in Wohlon".
        @Suppress("UNCHECKED_CAST")
        val goatType = PRISMATIC_GOAT.get() as EntityType<Mob>
        SpawnPlacementsInvoker.`enderkinesis$register`(
            goatType,
            SpawnPlacements.Type.ON_GROUND,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
        ) { _, level, _, pos, _ ->
            val below = level.getBlockState(pos.below())
            !below.isAir && below.isFaceSturdy(level, pos.below(), net.minecraft.core.Direction.UP)
        }
    }
}
