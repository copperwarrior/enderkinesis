package org.shipwrights.enderkinesis.mixin;

import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Accessor for {@link PoiTypes#registerBlockStates(Holder, Set)} so a mod-added
 * POI type can publish its blockstate → POI mapping into the static
 * {@code TYPE_BY_STATE} cache that {@code PoiTypes.forState} reads.
 *
 * <p>Vanilla 1.20.1 keeps {@code registerBlockStates} private and only calls it
 * from {@link PoiTypes#bootstrap}, which runs once at class load with the vanilla
 * POI set. Forge wraps this via a dedicated {@code RegisterEvent} pass and
 * Fabric ships {@code PointOfInterestHelper.register} — we exposing the private
 * helper directly so the common module can register from cross-platform code
 * without depending on either loader.</p>
 */
@Mixin(PoiTypes.class)
public interface PoiTypesInvoker {

    @Invoker("registerBlockStates")
    static void enderkinesis$registerBlockStates(Holder<PoiType> holder, Set<BlockState> states) {
        throw new AssertionError("Mixin failed to apply");
    }
}
