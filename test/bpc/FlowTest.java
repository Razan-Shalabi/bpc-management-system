package bpc;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;

/** Drives the real action methods and checks the database afterwards. Dialogs are auto-answered. */
public class FlowTest {
    static final List<String> dialogs = new ArrayList<>();
    static final List<String> results = new ArrayList<>();
    static int fails = 0;
    static final Set<Stage> seen = new HashSet<>();
    static Connection db;

    public static void main(String[] args) throws Exception {
        db = DriverManager.getConnection("jdbc:mysql://localhost:3306/bpc", "bpc", "1234");
        CountDownLatch done = new CountDownLatch(1);
        Platform.startup(() -> {
            Thread poller = new Thread(() -> {
                while (true) {
                    try { Thread.sleep(250); } catch (InterruptedException e) { return; }
                    Platform.runLater(FlowTest::answerDialogs);
                }
            });
            poller.setDaemon(true); poller.start();
            try { run(); } catch (Throwable t) { t.printStackTrace(); fails++; }
            done.countDown();
        });
        done.await();
        report();
    }

    static void answerDialogs() {
                    for (Window w : new ArrayList<>(Window.getWindows())) {
                        if (!(w instanceof Stage s) || s.getScene() == null || !s.isShowing()) continue;
                        Parent root = s.getScene().getRoot();
                        if (root instanceof DialogPane dp) {
                            dialogs.add(s.getTitle() + ": " + dp.getContentText().replace('\n', ' '));
                            Button yes = (Button) dp.lookupButton(ButtonType.YES);
                            Button ok  = (Button) dp.lookupButton(ButtonType.OK);
                            if (yes != null) yes.fire(); else if (ok != null) ok.fire(); else s.close();
                        } else if (s.getModality() != javafx.stage.Modality.NONE) {
                            Button b = findButton(root, "Confirm receipt");
                            if (b != null) b.fire();
                            else if (seen.add(s)) System.err.println("UNHANDLED modal: " + s.getTitle() + " root=" + root.getClass().getName());
                        }
                    }
    }

    static void report() {
        System.out.println("---- dialogs shown ----");
        dialogs.forEach(d -> System.out.println("  " + d));
        System.out.println("---- checks ----");
        results.forEach(r -> System.out.println("  " + r));
        System.out.println(fails == 0 ? "RESULT: all checks passed" : "RESULT: " + fails + " check(s) FAILED");
        Platform.exit();
        System.exit(fails == 0 ? 0 : 1);
    }

    static void run() throws Exception {
        // ---------- A. customer places an order from the cart (FEFO allocation) ----------
        Session.loginAsCustomer(1, "Al-Najah University Hospital");
        int dilaxBefore = scalar("SELECT SUM(Quantity) FROM ProductBatch WHERE ProductID=4 AND ExpiryDate>CURRENT_DATE");
        int maxOrder = scalar("SELECT MAX(OrderID) FROM SalesOrder");
        Cart.clear();
        Cart.add(new Cart.Item(4, "Dilax", 1500, new BigDecimal("3.40")));
        Cart.add(new Cart.Item(1, "Abecedin", 50, new BigDecimal("12.50")));
        CustomerCartTab cart = new CustomerCartTab();
        show(cart);
        call(cart, "placeOrder");
        int newOrder = scalar("SELECT MAX(OrderID) FROM SalesOrder");
        check("A1 order created", newOrder == maxOrder + 1);
        check("A2 order total = 5725.00", str("SELECT TotalAmount FROM SalesOrder WHERE OrderID=" + newOrder).equals("5725.00"));
        check("A3 two order lines", scalar("SELECT COUNT(*) FROM SalesOrderItem WHERE OrderID=" + newOrder) == 2);
        check("A4 batch allocation covers 1550 units",
            scalar("SELECT SUM(b.Quantity) FROM SalesOrderItemBatch b JOIN SalesOrderItem i ON i.SOItemID=b.SOItemID WHERE i.OrderID=" + newOrder) == 1550);
        check("A5 Dilax stock reduced by 1500",
            scalar("SELECT SUM(Quantity) FROM ProductBatch WHERE ProductID=4 AND ExpiryDate>CURRENT_DATE") == dilaxBefore - 1500);
        check("A6 FEFO: earliest-expiring Dilax batch used first",
            str("SELECT GROUP_CONCAT(b.ProductBatchID ORDER BY pb.ExpiryDate, b.ProductBatchID) FROM SalesOrderItemBatch b " +
                "JOIN SalesOrderItem i ON i.SOItemID=b.SOItemID JOIN ProductBatch pb ON pb.ProductBatchID=b.ProductBatchID " +
                "WHERE i.OrderID=" + newOrder + " AND i.ProductID=4").startsWith(
            str("SELECT ProductBatchID FROM ProductBatch WHERE ProductID=4 AND ExpiryDate>CURRENT_DATE ORDER BY ExpiryDate, ProductBatchID LIMIT 1")));
        check("A7 invoice issued with the order", scalar("SELECT COUNT(*) FROM Invoice WHERE OrderID=" + newOrder) == 1);
        check("A8 cart emptied", Cart.isEmpty());

        // ---------- B. customer cancels that order -> stock goes back ----------
        CustomerMyOrdersTab my = new CustomerMyOrdersTab();
        show(my);
        TableView<?> t = (TableView<?>) field(my, "ordersTable");
        selectWhere(t, o -> ((CustomerMyOrdersTab.Order) o).id() == newOrder);
        call(my, "cancelOrder");
        check("B1 order status Cancelled", str("SELECT Status FROM SalesOrder WHERE OrderID=" + newOrder).equals("Cancelled"));
        check("B2 Dilax stock restored",
            scalar("SELECT SUM(Quantity) FROM ProductBatch WHERE ProductID=4 AND ExpiryDate>CURRENT_DATE") == dilaxBefore);
        check("B3 unpaid invoice of cancelled order removed",
            scalar("SELECT COUNT(*) FROM Invoice WHERE OrderID=" + newOrder) == 0);

        // ---------- E. a fully paid order cannot be cancelled ----------
        Cart.add(new Cart.Item(11, "Sedaprin", 10, new BigDecimal("4.80")));
        cart.refresh();
        ((TextField) field(cart, "payAmount")).setText("48.00");
        call(cart, "placeOrder");
        int paidOrder = scalar("SELECT MAX(OrderID) FROM SalesOrder");
        check("E1 paid order has Paid invoice", str("SELECT Status FROM Invoice WHERE OrderID=" + paidOrder).equals("Paid"));
        my.refresh();
        selectWhere(t, o -> ((CustomerMyOrdersTab.Order) o).id() == paidOrder);
        call(my, "cancelOrder");
        check("E2 paid order stays Pending (cancel blocked)", str("SELECT Status FROM SalesOrder WHERE OrderID=" + paidOrder).equals("Pending"));
        check("E3 its invoice is kept", scalar("SELECT COUNT(*) FROM Invoice WHERE OrderID=" + paidOrder) == 1);

        // ---------- C. staff receives pending PO #10, then invoices it twice ----------
        Session.loginAsEmployee(4, "rania", "Rania Abu-Awad", "Procurement Officer");
        int rmBefore = scalar("SELECT COUNT(*) FROM RawMaterialBatch");
        PurchaseOrdersTab po = new PurchaseOrdersTab();
        show(po);
        TableView<?> pt = (TableView<?>) field(po, "poTable");
        selectWhere(pt, p -> ((PurchaseOrdersTab.PO) p).id() == 10);
        call(po, "markReceived");
        check("C1 PO #10 Received", str("SELECT Status FROM PurchaseOrder WHERE POID=10").equals("Received"));
        check("C2 goods receipt created", scalar("SELECT COUNT(*) FROM GoodsReceipt WHERE POID=10") == 1);
        check("C3 raw-material batches added (2 lines)", scalar("SELECT COUNT(*) FROM RawMaterialBatch") == rmBefore + 2);
        po.refresh();
        selectWhere(pt, p -> ((PurchaseOrdersTab.PO) p).id() == 10);
        call(po, "generateSupplierInvoice");
        call(po, "generateSupplierInvoice");
        check("C4 exactly one supplier invoice for PO #10 (duplicate blocked)",
            scalar("SELECT COUNT(*) FROM SupplierInvoice WHERE POID=10") == 1);

        // ---------- D. staff invoices delivered order #16, duplicate is blocked ----------
        Session.loginAsEmployee(11, "admin", "System Administrator", "General Manager");
        OrdersTab ot = new OrdersTab();
        show(ot);
        TableView<?> ott = (TableView<?>) field(ot, "ordersTable");
        selectWhere(ott, o -> ((OrdersTab.Order) o).id() == 16);
        call(ot, "generateInvoice");
        call(ot, "generateInvoice");
        check("D1 exactly one invoice for order #16", scalar("SELECT COUNT(*) FROM Invoice WHERE OrderID=16") == 1);
    }

    // ---------- helpers ----------
    static void show(Tab tab) { Stage s = new Stage(); s.setScene(new Scene(new TabPane(tab), 1200, 800)); s.show(); }
    static void check(String name, boolean ok) { System.err.println((ok?"PASS ":"FAIL ")+name); results.add((ok ? "PASS " : "FAIL ") + name); if (!ok) fails++; }
    static int scalar(String sql) throws SQLException {
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(sql)) { rs.next(); return rs.getInt(1); }
    }
    static String str(String sql) throws SQLException {
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(sql)) { rs.next(); String v = rs.getString(1); return v == null ? "" : v; }
    }
    static Object field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(o);
    }
    static void call(Object o, String name) throws Exception {
        Method m = o.getClass().getDeclaredMethod(name); m.setAccessible(true); m.invoke(o);
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void selectWhere(TableView t, java.util.function.Predicate<Object> p) {
        for (Object row : t.getItems()) if (p.test(row)) { t.getSelectionModel().select(row); return; }
        throw new IllegalStateException("row not found in table");
    }
    static Button findButton(Node n, String text) {
        if (n instanceof Button b && text.equals(b.getText())) return b;
        if (n instanceof Parent p) for (Node c : p.getChildrenUnmodifiable()) { Button r = findButton(c, text); if (r != null) return r; }
        return null;
    }
}
