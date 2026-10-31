package org.shipwrights.enderkinesis.duck;

/**
 * A crafting-style result container that may hand out imbued items. Every other
 * {@code ResultContainer} (crafting, smithing, stonecutting, cartography, modded tables) strips
 * the imbue from whatever is put in it, since those results copy their input's NBT: book and
 * map cloning, shield banners, armour dye, smithing. Implemented by
 * {@code ResultContainerImbueMixin}; the anvil and grindstone mark theirs, as they have their
 * own rules (see {@code AnvilMenuImbueMixin}, {@code GrindstoneMenuImbueMixin}).
 *
 * <p>Lives in {@code duck}, not {@code mixin}: Mixin's package is closed to regular classloading.
 */
public interface ImbueKeepingResult {
    /** From now on this container keeps imbues on what's put in it. */
    void enderkinesis$keepImbues();
}
