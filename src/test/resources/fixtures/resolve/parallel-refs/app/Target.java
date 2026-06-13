package app;

/**
 * The find-references target for the parallel-resolve fixture: {@code Target.ping()}, called from many
 * sibling files so {@code find_references(ping)} has a large enough candidate set to force the parallel
 * resolve path. {@code Target} itself makes the one call to {@code Lonely.solo()} — the rare name whose
 * single candidate file keeps it on the serial path.
 */
public class Target {

    public void ping() {
        // body irrelevant
    }

    void bootstrap() {
        new Lonely().solo();
    }
}
