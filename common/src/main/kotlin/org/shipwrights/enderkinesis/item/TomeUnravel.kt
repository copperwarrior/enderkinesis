package org.shipwrights.enderkinesis.item

import net.minecraft.world.item.ItemStack

private const val PASSES_KEY = "EnderkinesisUnravelPasses"
private const val SEED_KEY = "EnderkinesisUnravelSeed"

// After this many passes about 0.1% of the Sselith words stay, and each pass reads the whole book again.
const val MAX_UNRAVEL_PASSES = 16

val ItemStack.unravelPasses: Int get() = tag?.getInt(PASSES_KEY) ?: 0

val ItemStack.unravelSeed: Long get() = tag?.getLong(SEED_KEY) ?: 0L

/** A tome that rebuilds its pages from a Sselith source each time, so the scroll counts passes on the stack. */
fun ItemStack.isUnravellableTome(): Boolean {
    val tome = item as? TomeItem ?: return false
    return TomeLore.sselithSource(tome.writtenBook(this)) != null
}

/** The first pass keeps [seed], so every later build of the pages gives the same words. */
fun ItemStack.addUnravelPass(seed: Long) {
    val tag = orCreateTag
    if (!tag.contains(SEED_KEY)) tag.putLong(SEED_KEY, seed)
    tag.putInt(PASSES_KEY, (tag.getInt(PASSES_KEY) + 1).coerceAtMost(MAX_UNRAVEL_PASSES))
}
