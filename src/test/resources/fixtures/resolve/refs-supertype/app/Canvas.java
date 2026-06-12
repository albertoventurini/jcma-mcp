package app;

/** The genuine references to the type {@code Shape}: a field type and a parameter type. */
public class Canvas {

    Shape current;

    void add(Shape s) {
        this.current = s;
    }
}
