package org.shipwrights.enderkinesis.duck;

/** Implemented on Create's windmill bearing by {@code WindmillBearingWindMixin}. */
public interface WindmillWindTarget {
    /** Multiplies the sail speed by {@code boost} until {@code untilGameTime}. */
    void enderkinesis$blow(float boost, long untilGameTime);
}
