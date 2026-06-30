package org.shipwrights.enderkinesis.registry

import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.registry.registries.DeferredRegister
import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.world.entity.ai.village.poi.PoiType
import net.minecraft.world.entity.ai.village.poi.PoiTypes
import net.minecraft.world.level.block.AbstractBannerBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.SignBlock
import net.minecraft.world.level.block.state.BlockState
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.mixin.PoiTypesInvoker

/**
 * Custom Point-of-Interest types added by Enderkinesis.
 *
 *  - **`cataloger_target`** — every block the Cataloger wanders toward
 *    EXCEPT the lectern. (The lectern stays under vanilla [PoiTypes.LECTERN]
 *    because POI blockstate mappings have to be unique; the wander goal
 *    queries both types.) Backing the wander goal with the POI manager
 *    replaces a brute-force `BlockPos.betweenClosed` scan over a ~16M-block
 *    box with a chunk-section index lookup, which is the only way to keep
 *    pathfinding cheap with a wide search radius.
 */
object EKPoiTypes {
    private val POI_TYPES: DeferredRegister<PoiType> =
        DeferredRegister.create(EnderkinesisMod.MOD_ID, Registries.POINT_OF_INTEREST_TYPE)

    val CATALOGER_TARGET_KEY: ResourceKey<PoiType> =
        ResourceKey.create(Registries.POINT_OF_INTEREST_TYPE, EnderkinesisMod.id("cataloger_target"))

    val CATALOGER_TARGET: RegistrySupplier<PoiType> = POI_TYPES.register("cataloger_target") {
        PoiType(catalogerTargetStates(), MAX_TICKETS, VALID_RANGE)
    }

    /** Orb of Scrying — registered as a POI so the right-click target-finder uses the
     *  chunk-section spatial index instead of walking loaded chunks. Covers every blockstate
     *  of [org.shipwrights.enderkinesis.block.OrbOfScryingBlock] (one per facing). */
    val SCRYING_ORB_KEY: ResourceKey<PoiType> =
        ResourceKey.create(Registries.POINT_OF_INTEREST_TYPE, EnderkinesisMod.id("scrying_orb"))

    val SCRYING_ORB: RegistrySupplier<PoiType> = POI_TYPES.register("scrying_orb") {
        PoiType(scryingOrbStates(), MAX_TICKETS, VALID_RANGE)
    }

    private fun scryingOrbStates(): Set<BlockState> {
        val block = EKBlocks.ORB_OF_SCRYING.get()
        return block.stateDefinition.possibleStates.toHashSet()
    }

    /** Ulder Statue blockstates — every facing of all 7 statue kinds. Used by both
     *  the cataloger wander goal (so a cataloger walks toward statues like signs /
     *  lecterns) and by [org.shipwrights.enderkinesis.dimension.SselithMadness]
     *  (madness growth doubles for any player within range of one). Statue blockstates
     *  can't ALSO sit in [CATALOGER_TARGET] because vanilla enforces unique blockstate
     *  → PoiType mapping. */
    val STATUE_KEY: ResourceKey<PoiType> =
        ResourceKey.create(Registries.POINT_OF_INTEREST_TYPE, EnderkinesisMod.id("statue"))

    val STATUE: RegistrySupplier<PoiType> = POI_TYPES.register("statue") {
        PoiType(statueStates(), MAX_TICKETS, VALID_RANGE)
    }

    private fun statueStates(): Set<BlockState> {
        val states = HashSet<BlockState>()
        for (supplier in EKBlocks.STATUES) {
            states.addAll(supplier.get().stateDefinition.possibleStates)
        }
        return states
    }

    /** True for blocks the Cataloger treats as a POI target: every sign and
     *  banner (any wood / colour, standing / wall / hanging — matched by class so
     *  we don't enumerate 70+ blocks and stay correct for any added later), plus
     *  the enchanting table, chiseled bookshelf and gold block. The vanilla
     *  lectern is excluded — blockstate uniqueness across POI types is enforced
     *  by vanilla and vanilla lecterns already belong to [PoiTypes.LECTERN];
     *  the wander goal queries both types together. The Sselith lectern IS
     *  included here because it has no overlap with vanilla LECTERN POI's
     *  matching states — its blockstates are uniquely ours. Kept in lockstep
     *  with the `cataloger_targets` block tag, which the node evaluator and
     *  client menu-walk read. */
    private fun isCatalogerTargetBlock(block: Block): Boolean =
        block is SignBlock ||
            block is AbstractBannerBlock ||
            block === Blocks.ENCHANTING_TABLE ||
            block === Blocks.CHISELED_BOOKSHELF ||
            block === Blocks.GOLD_BLOCK ||
            block is org.shipwrights.enderkinesis.block.SselithLecternBlock

    /** Statues live in their own [STATUE] POI but are still cataloger targets — the
     *  wander goal ORs the two POI keys in [WanderToTaggedBlockGoal]. Membership in
     *  this set is what tells the Sselith chunkgen to register a POI record at gen
     *  time (same path as [POI_TARGET_BLOCK_SET]). */
    private fun isStatueBlock(block: Block): Boolean =
        block is org.shipwrights.enderkinesis.block.StatueBlock

    /** Every block the cataloger considers a POI — [isCatalogerTargetBlock] plus
     *  the lectern (registered with vanilla `PoiTypes.LIBRARIAN`). Fast O(1)
     *  membership check used by [org.shipwrights.enderkinesis.dimension.SselithRepertoryChunkGenerator]
     *  to identify which `chunk.setBlockState(...)` placements need explicit
     *  POI registration — chunk gen bypasses `WorldGenRegion.setBlock` (and
     *  thus `ServerLevel.onBlockStateChange`, the path that normally fires
     *  `PoiManager.add`), so we record matching positions during fill and
     *  register them once `WorldGenRegion` is available in `spawnOriginalMobs`.
     *  Lazy so the block registry is fully populated when it first builds. */
    val POI_TARGET_BLOCK_SET: Set<Block> by lazy {
        val set = HashSet<Block>()
        for (block in BuiltInRegistries.BLOCK) {
            if (isCatalogerTargetBlock(block) || isStatueBlock(block)) set.add(block)
        }
        set.add(Blocks.LECTERN)
        set
    }

    private fun catalogerTargetStates(): Set<BlockState> {
        val states = HashSet<BlockState>()
        for (block in BuiltInRegistries.BLOCK) {
            if (isCatalogerTargetBlock(block)) states.addAll(block.stateDefinition.possibleStates)
        }
        return states
    }

    /** Tickets are vanilla's "concurrent occupants" counter. Catalogers don't
     *  claim tickets, so any value works; 8 is generous. */
    private const val MAX_TICKETS = 8
    /** `validRange` is unused outside villager AI (we query by world distance
     *  in the goal). Set to 1 so the field is non-zero / non-default. */
    private const val VALID_RANGE = 1

    /** Flag prevents [PoiTypesInvoker] from running twice if a server is
     *  restarted in the same JVM (TYPE_BY_STATE is static + throws on
     *  duplicate states). */
    private var blockStatesInstalled = false

    fun register() {
        POI_TYPES.register()
        // Map our blockstates → POI type AFTER all common registries are
        // built. SETUP fires once on each platform after init, with the
        // registries fully populated, so the holder lookup is safe.
        LifecycleEvent.SETUP.register {
            if (blockStatesInstalled) return@register
            blockStatesInstalled = true
            val catalogerHolder = BuiltInRegistries.POINT_OF_INTEREST_TYPE
                .getHolderOrThrow(CATALOGER_TARGET_KEY)
            PoiTypesInvoker.`enderkinesis$registerBlockStates`(catalogerHolder, catalogerHolder.value().matchingStates())
            val scryingHolder = BuiltInRegistries.POINT_OF_INTEREST_TYPE
                .getHolderOrThrow(SCRYING_ORB_KEY)
            PoiTypesInvoker.`enderkinesis$registerBlockStates`(scryingHolder, scryingHolder.value().matchingStates())
            val statueHolder = BuiltInRegistries.POINT_OF_INTEREST_TYPE
                .getHolderOrThrow(STATUE_KEY)
            PoiTypesInvoker.`enderkinesis$registerBlockStates`(statueHolder, statueHolder.value().matchingStates())
        }
    }
}
