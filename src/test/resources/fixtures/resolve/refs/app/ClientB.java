package app;

/** Two confirmed references to {@code Service.run()}, in two distinct enclosing methods. */
public class ClientB {

    void first(Service s) {
        s.run();
    }

    void second(Service s) {
        s.run();
    }
}
