package org.shipwrights.enderkinesis.client

import org.slf4j.LoggerFactory

// TEMP diagnostic for failed viewfinder sketches; delete this file and its calls after the fix.
object SilverpointDebug {
    private val LOG = LoggerFactory.getLogger("SilverpointDebug")

    fun log(message: String, vararg args: Any?) = LOG.info(message, *args)
}
