package org.shipwrights.enderkinesis.registry

import net.minecraft.world.entity.npc.VillagerProfession
import net.minecraft.world.entity.npc.VillagerTrades
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.trading.MerchantOffer

/**
 * Mod-added villager trades.
 *
 * Architectury 1.20.1 doesn't ship a unified `VillagerTradesEvent` —
 * Fabric uses `TradeOfferHelper`, Forge fires `VillagerTradesEvent`,
 * with no overlap. The cross-loader path that works for both is to
 * append directly into vanilla's mutable [VillagerTrades.TRADES] map at
 * mod init: the outer Map is HashMap, the inner Int2ObjectMap is
 * Int2ObjectOpenHashMap, and the listing arrays are plain arrays we
 * grow by one slot per addition. Vanilla worldgen reads this map each
 * time a villager refreshes trades, so additions take effect
 * immediately for existing and new villagers alike.
 */
object EKVillagerTrades {

    /** Master-librarian (profession level 5) trade level. */
    private const val MASTER_LEVEL = 5

    /** Emerald cost of one Scroll of Unravelling. Set at the upper end of
     *  vanilla master-tier specialty offers (enchanted books: 5–64;
     *  named books: 30) — the scroll is single-use and gates one of the
     *  game's only Sselith-deciphering tools. */
    private const val SCROLL_EMERALD_COST = 32

    /** Max times a librarian can sell this offer before refresh. */
    private const val SCROLL_MAX_USES = 3

    /** Villager XP gained per trade. Matches vanilla master-tier specialty. */
    private const val SCROLL_XP = 30

    /** Standard master-tier price multiplier (demand inflation rate). */
    private const val SCROLL_PRICE_MULTIPLIER = 0.2f

    fun init() {
        addLibrarianMasterTrade(
            VillagerTrades.ItemListing { _, _ ->
                MerchantOffer(
                    ItemStack(Items.EMERALD, SCROLL_EMERALD_COST),
                    ItemStack(EKItems.SCROLL_OF_UNRAVELLING.get(), 1),
                    SCROLL_MAX_USES,
                    SCROLL_XP,
                    SCROLL_PRICE_MULTIPLIER,
                )
            }
        )
    }

    private fun addLibrarianMasterTrade(listing: VillagerTrades.ItemListing) {
        val byProfession = VillagerTrades.TRADES[VillagerProfession.LIBRARIAN]
            ?: error("Vanilla librarian trades missing — TRADES map shape changed?")
        val current = byProfession[MASTER_LEVEL]
            ?: error("Vanilla librarian level-$MASTER_LEVEL trades missing — TRADES map shape changed?")
        val grown = current.copyOf(current.size + 1)
        grown[current.size] = listing
        @Suppress("UNCHECKED_CAST")
        byProfession[MASTER_LEVEL] = grown as Array<VillagerTrades.ItemListing>
    }
}
