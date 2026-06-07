package app;

/**
 * The subtype that exercises all three hierarchy edges: {@code extends Base} (EXTENDS),
 * {@code implements Iface} (IMPLEMENTS), an override of a superclass method ({@code run()} → OVERRIDES)
 * and an implementation of an interface method ({@code ping()} → OVERRIDES, the interface-impl case).
 */
public class Sub extends Base implements Iface {

    @Override
    public void run() {
        // overrides Base.run()
    }

    @Override
    public void ping() {
        // implements Iface.ping()
    }
}
