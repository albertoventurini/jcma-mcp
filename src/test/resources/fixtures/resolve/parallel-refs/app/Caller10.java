package app;

/** Confirmed reference #10 to {@code Target.ping()}, enclosed by {@code Caller10.use}. */
public class Caller10 {

    void use(Target t) {
        t.ping();
    }
}
