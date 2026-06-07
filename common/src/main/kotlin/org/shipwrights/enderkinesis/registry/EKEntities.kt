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
import org.shipwrights.enderkinesis.entity.Cataloger
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

    fun register() {
        ENTITIES.register()
        // Attributes — without this every Cataloger crashes on creation.
        EntityAttributeRegistry.register(CATALOGER) { Cataloger.createAttributes() }
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
    }
}
