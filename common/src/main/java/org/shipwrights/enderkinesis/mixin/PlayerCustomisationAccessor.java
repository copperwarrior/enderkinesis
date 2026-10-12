package org.shipwrights.enderkinesis.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@link Player#DATA_PLAYER_MODE_CUSTOMISATION} is the SynchedEntityData key
 * for the byte-bitfield that toggles cape / jacket / left+right sleeves /
 * left+right pants / hat visibility on the player model. The field is
 * {@code protected static}, so Kotlin can't reach it directly; this accessor
 * exposes it so the Wand of Ga'Mylan cage can copy the caged player's skin
 * layer flags onto the client's ghost {@code RemotePlayer}.
 */
@Mixin(Player.class)
public interface PlayerCustomisationAccessor {

    @Accessor("DATA_PLAYER_MODE_CUSTOMISATION")
    static EntityDataAccessor<Byte> enderkinesis$getDataCustomisation() {
        throw new AssertionError();
    }
}
