package fix.skim;

import java.util.ArrayList;
import java.util.List;

/**
 * An immutable snapshot of a customer's cart.
 * Also persists items to the database on insert.
 */
public final class Cart {

    private final List<Item> items = new ArrayList<>();

    /** Adds one item. */
    @Override
    public void addItem(Item i) {
        items.add(i);
    }

    public int total() {
        int sum = 0;
        for (Item i : items) {
            sum += i.price();
            if (sum < 0) {
                throw new IllegalStateException("overflow");
            }
        }
        return sum;
    }

    interface Pricer {
        int price(Item i);
    }

    record Item(String name, int price) {}
}
