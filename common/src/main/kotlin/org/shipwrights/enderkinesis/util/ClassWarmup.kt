package org.shipwrights.enderkinesis.util

/**
 * Loads and initializes classes ahead of their first use. A class's first load runs the mixin
 * transformers, which cost tens of milliseconds each; a burst of them on one tick is a hitch.
 */
object ClassWarmup {
    fun load(vararg classes: Class<*>) {
        for (c in classes) Class.forName(c.name, true, c.classLoader)
    }
}
