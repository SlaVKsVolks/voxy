package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.client.core.util.RingTracker;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;

import java.util.function.LongConsumer;

public class RenderDistanceTracker {
    private static final int CHECK_DISTANCE_BLOCKS = 128;
    private final int rootLevel;
    private final int rootBlockShift;
    private final LongConsumer addTopLevelNode;
    private final LongConsumer removeTopLevelNode;
    private final int processRate;
    private final int minSec;
    private final int maxSec;
    private RingTracker tracker;
    private int renderDistance;
    private double posX;
    private double posY;
    private double posZ;
    private int lastCenterSectionY = Integer.MIN_VALUE;
    public RenderDistanceTracker(int rate, int minSec, int maxSec, LongConsumer addTopLevelNode, LongConsumer removeTopLevelNode) {
        this(rate, WorldEngine.MAX_LOD_LAYER, minSec, maxSec, addTopLevelNode, removeTopLevelNode);
    }

    public RenderDistanceTracker(int rate, int rootLevel, int minSec, int maxSec, LongConsumer addTopLevelNode, LongConsumer removeTopLevelNode) {
        if (rootLevel < 0 || rootLevel > WorldEngine.MAX_LOD_LAYER) {
            throw new IllegalArgumentException("Invalid root LoD level " + rootLevel);
        }
        this.rootLevel = rootLevel;
        this.rootBlockShift = 5 + rootLevel;
        this.addTopLevelNode = addTopLevelNode;
        this.removeTopLevelNode = removeTopLevelNode;
        this.renderDistance = 2;
        this.tracker = new RingTracker(this.renderDistance, 0, 0, true);
        this.processRate = rate;
        this.minSec = minSec;
        this.maxSec = maxSec;
    }

    public void setRenderDistance(int renderDistance) {
        if (renderDistance == this.renderDistance) {
            return;
        }
        this.renderDistance = renderDistance;
        this.tracker.unload();//Mark all as unload
        this.tracker = new RingTracker(this.tracker, renderDistance, ((int)this.posX)>>this.rootBlockShift, ((int)this.posZ)>>this.rootBlockShift, true);//Steal from previous tracker
    }

    public boolean setCenterAndProcess(double x, double y, double z) {
        double dx = this.posX-x;
        double dz = this.posZ-z;
        this.posY = y;
        if (CHECK_DISTANCE_BLOCKS*CHECK_DISTANCE_BLOCKS<dx*dx+dz*dz) {
            this.posX = x;
            this.posZ = z;
            this.tracker.moveCenter(((int)x)>>this.rootBlockShift, ((int)z)>>this.rootBlockShift);
        }

        //TODO: make process rate in terms of updatesPerSecond not updates per frame
        return this.tracker.process(this.processRate, this::add, this::rem)!=0;
    }

    private void add(int x, int z) {
        int centerY = this.getCameraTopLevelSectionY();
        if (centerY != this.lastCenterSectionY) {
            this.lastCenterSectionY = centerY;
            Logger.info("Voxy top-level vertical load priority level=" + this.rootLevel + " centerY=" + centerY + " range=[" + this.minSec + "," + this.maxSec + "]");
        }

        int maxDistance = Math.max(Math.abs(centerY - this.minSec), Math.abs(centerY - this.maxSec));
        for (int offset = 0; offset <= maxDistance; offset++) {
            int upper = centerY + offset;
            if (upper >= this.minSec && upper <= this.maxSec) {
                this.addTopLevelNode.accept(WorldEngine.getWorldSectionId(this.rootLevel, x, upper, z));
            }

            int lower = centerY - offset;
            if (offset != 0 && lower >= this.minSec && lower <= this.maxSec) {
                this.addTopLevelNode.accept(WorldEngine.getWorldSectionId(this.rootLevel, x, lower, z));
            }
        }
    }

    private void rem(int x, int z) {
        for (int y = this.minSec; y <= this.maxSec; y++) {
            this.removeTopLevelNode.accept(WorldEngine.getWorldSectionId(this.rootLevel, x, y, z));
        }
    }

    private int getCameraTopLevelSectionY() {
        int sectionY = ((int)Math.floor(this.posY)) >> this.rootBlockShift;
        if (sectionY < this.minSec) {
            return this.minSec;
        }
        if (sectionY > this.maxSec) {
            return this.maxSec;
        }
        return sectionY;
    }
}
