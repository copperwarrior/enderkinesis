package org.shipwrights.enderkinesis.util

/**
 * Loads classes ahead of their first use. A class's first load runs the mixin transformers, which cost tens of
 * milliseconds each; a burst of them on one tick is a hitch. The caller's arguments do the work: a class literal
 * loads its class, and an object or `Companion` reference also runs the static initializer.
 */
fun warmUpClasses(vararg loaded: Any) {
    require(loaded.isNotEmpty()) { "Nothing to warm up" }
}
