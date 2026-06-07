package app;

/** One confirmed reference to {@code Service.run()}, enclosed by {@code ClientA.go()}. */
public class ClientA {

    void go() {
        new Service().run();
    }
}
