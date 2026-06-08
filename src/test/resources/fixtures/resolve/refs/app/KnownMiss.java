package app;

/**
 * The known-type unconfirmed case: {@code w.run()} on a receiver whose type ({@code Widget}) is
 * declared right here and resolves fine — but {@code Widget} has no {@code run()}. The simple name
 * matches the find-references target {@code Service.run()}, yet resolution fails. Under the
 * name-keyed model this must surface as an unconfirmed candidate whose edge points at the placeholder
 * {@code run~UNRESOLVED} (the <em>name</em>), never at the receiver type {@code Widget}.
 */
public class KnownMiss {

    void use(Widget w) {
        w.run();
    }
}
