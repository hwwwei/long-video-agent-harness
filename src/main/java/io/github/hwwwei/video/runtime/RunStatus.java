package io.github.hwwwei.video.runtime;

import java.util.Set;

public enum RunStatus {
    QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED;

    public boolean mayTransitionTo(RunStatus next) {
        return switch (this) {
            case QUEUED -> Set.of(RUNNING, CANCELLED).contains(next);
            case RUNNING -> Set.of(COMPLETED, FAILED, CANCELLED).contains(next);
            case FAILED -> Set.of(QUEUED, CANCELLED).contains(next);
            case COMPLETED, CANCELLED -> false;
        };
    }
}
