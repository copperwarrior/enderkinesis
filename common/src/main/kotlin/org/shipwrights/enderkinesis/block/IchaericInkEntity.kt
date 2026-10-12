package org.shipwrights.enderkinesis.block

/** Implemented on every Entity by EntityIchaericInkMixin; the values are refreshed each tick in baseTick. */
interface IchaericInkEntity {
    /** How deep in Ichaeric Ink the entity's feet are, or 0 when it touches none. */
    fun enderkinesisInkHeight(): Double

    fun enderkinesisEyesInInk(): Boolean
}
