package app;

/** Confirmed reference #12 to {@code Target.ping()}, enclosed by {@code Caller12.use}. */
public class Caller12 {

    void use(Target t) {
        t.ping();
    }
}
