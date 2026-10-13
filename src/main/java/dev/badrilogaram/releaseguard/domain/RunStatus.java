package dev.badrilogaram.releaseguard.domain;

public enum RunStatus {
    QUEUED, RUNNING, PASSED, FAILED, CANCELLED, ABORTED;

    public boolean terminal() {
        return this != QUEUED && this != RUNNING;
    }
}
