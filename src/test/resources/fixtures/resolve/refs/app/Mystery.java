package app;

/**
 * The mandatory <em>unconfirmed tail</em> case: a call spelled {@code run()} on a receiver whose
 * type ({@code Unknown}) is declared nowhere and is not on the classpath. The simple name matches
 * the find-references target, but resolution fails — so it can neither be confirmed as a reference
 * nor silently dropped. It must surface as an unconfirmed candidate.
 */
public class Mystery {

    Unknown thing;

    void poke() {
        thing.run();
    }
}
