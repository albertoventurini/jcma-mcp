package app;

/**
 * The unconfirmed-tail case for the parallel fixture: {@code u.ping()} on a receiver whose type
 * ({@code Unknown}) is declared nowhere, so it cannot be confirmed or dropped. Present so the
 * serial==parallel equivalence assertion also covers the unconfirmed tail, not just confirmed groups.
 */
public class Mystery {

    Unknown u;

    void poke() {
        u.ping();
    }
}
