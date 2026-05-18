package me.cortex.voxy.client.compat;

import java.nio.file.Path;

public final class FlashbackCompat {
    public static final boolean FLASHBACK_INSTALLED = false;

    private FlashbackCompat() {
    }

    public static Path getReplayStoragePath() {
        return null;
    }
}
