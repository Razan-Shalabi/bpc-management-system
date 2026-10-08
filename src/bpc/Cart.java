package bpc;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

public final class Cart {

    public record Item(int productId, String productName, int quantity, BigDecimal unitPrice) {
        public BigDecimal lineTotal() {
            return unitPrice.multiply(BigDecimal.valueOf(quantity));
        }
    }

    private static final ObservableList<Item> items = FXCollections.observableArrayList();
    private static final Map<Integer, Integer> reserved = new HashMap<>();

    private Cart() { }

    public static ObservableList<Item> items() { return items; }

    public static int reservedFor(int productId) {
        return reserved.getOrDefault(productId, 0);
    }

    public static void add(Item newItem) {
        for (int i = 0; i < items.size(); i++) {
            Item existing = items.get(i);
            if (existing.productId() == newItem.productId()) {
                items.set(i, new Item(
                    existing.productId(),
                    existing.productName(),
                    existing.quantity() + newItem.quantity(),
                    existing.unitPrice()
                ));
                reserved.merge(newItem.productId(), newItem.quantity(), Integer::sum);
                return;
            }
        }
        items.add(newItem);
        reserved.merge(newItem.productId(), newItem.quantity(), Integer::sum);
    }

    public static void remove(Item item) {
        if (items.remove(item)) {
            int left = reserved.getOrDefault(item.productId(), 0) - item.quantity();
            if (left <= 0) reserved.remove(item.productId());
            else reserved.put(item.productId(), left);
        }
    }

    public static void clear() {
        items.clear();
        reserved.clear();
    }

    public static boolean isEmpty() { return items.isEmpty(); }

    public static int itemCount() {
        int n = 0;
        for (Item i : items) n += i.quantity();
        return n;
    }

    public static BigDecimal total() {
        BigDecimal t = BigDecimal.ZERO;
        for (Item i : items) t = t.add(i.lineTotal());
        return t;
    }
}
