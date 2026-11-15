package org.shipwrights.enderkinesis.duck;

/** Keep in {@code duck}: the Mixin package is closed to normal class loading. Implemented by {@code ServerLevelTreeGrowthRecheckMixin}. */
public interface TreeJobsHolder {
    /** Null until the first lookup. Server thread only. */
    Object enderkinesis$treeJobs();

    void enderkinesis$setTreeJobs(Object data);
}
