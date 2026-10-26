package mr.natijati.results.importing.async;

public enum ImportStatus {
    QUEUED, STARTING, VALIDATING, IMPORTING, READY, FAILED;
    public boolean terminal() { return this == READY || this == FAILED; }
}
