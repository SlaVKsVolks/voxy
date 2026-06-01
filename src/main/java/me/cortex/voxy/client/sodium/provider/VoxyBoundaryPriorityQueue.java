package me.cortex.voxy.client.sodium.provider;

import java.util.Comparator;
import java.util.PriorityQueue;

public final class VoxyBoundaryPriorityQueue<T extends VoxyBoundaryPriorityQueue.Entry> {
    public static final int LANE_BOUNDARY = 0;
    public static final int LANE_NEAR_REFINEMENT = 1;
    public static final int LANE_FAR_REFINEMENT = 2;

    private final PriorityQueue<T> queue = new PriorityQueue<>(Comparator
            .comparingInt(T::lane)
            .thenComparingInt(T::distanceFromHandoff));

    public void add(T entry) {
        this.queue.add(entry);
    }

    public T poll() {
        return this.queue.poll();
    }

    public int size() {
        return this.queue.size();
    }

    public boolean isEmpty() {
        return this.queue.isEmpty();
    }

    public interface Entry {
        int lane();

        int distanceFromHandoff();
    }
}
