package bpc;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

/** Opens every tab, runs refresh(), and records SQL errors, exceptions and error dialogs. */
public class SmokeTest {
    static final List<String> problems = Collections.synchronizedList(new ArrayList<>());
    static int sqlCount = 0;

    public static void main(String[] args) throws Exception {
        Connection real = DriverManager.getConnection("jdbc:mysql://localhost:3306/bpc", "bpc", "1234");
        Connection proxy = (Connection) Proxy.newProxyInstance(SmokeTest.class.getClassLoader(),
            new Class[]{Connection.class}, (p, m, a) -> {
                Object r = invoke(m, real, a);
                if (r instanceof PreparedStatement ps && a != null && a[0] instanceof String sql) return wrap(ps, sql);
                return r;
            });
        Field f = DB.class.getDeclaredField("conn"); f.setAccessible(true); f.set(null, proxy);

        CountDownLatch done = new CountDownLatch(1);
        Platform.startup(() -> {
            new AnimationTimer() {           // auto-close any dialog and record it
                @Override public void handle(long now) {
                    for (Window w : new ArrayList<>(Window.getWindows())) {
                        if (w instanceof Stage s && s.getScene() != null && s.getScene().getRoot() instanceof DialogPane dp) {
                            String msg = s.getTitle() + ": " + dp.getContentText();
                            if (!msg.startsWith("Smoke")) problems.add("DIALOG " + msg);
                            s.close();
                        }
                    }
                }
            }.start();
            try { run(); } catch (Throwable t) { problems.add("FATAL " + t); t.printStackTrace(); }
            done.countDown();
        });
        done.await();
        System.out.println("SQL statements executed: " + sqlCount);
        System.out.println(problems.isEmpty() ? "RESULT: no problems" : "RESULT: " + problems.size() + " problem(s)");
        problems.forEach(p -> System.out.println("  - " + p));
        Platform.exit();
        System.exit(problems.isEmpty() ? 0 : 1);
    }

    static void run() {
        Session.loginAsEmployee(11, "admin", "System Administrator", "General Manager");
        List<Supplier<Tab>> staff = List.of(DashboardTab::new, OrdersTab::new, InvoicesTab::new,
            SuppliersTab::new, PurchaseOrdersTab::new, GoodsReceiptsTab::new, SupplierInvoicesTab::new,
            RawMaterialsTab::new, WarehousesTab::new, InventoryTab::new, StockTransfersTab::new,
            CategoriesTab::new, ProductsTab::new, ProductionTab::new, ReportsTab::new,
            CustomersTab::new, UserManagementTab::new);
        exercise("staff", staff);

        Session.loginAsCustomer(1, "Al-Najah University Hospital");
        exercise("customer", List.of(CustomerMyOrdersTab::new, CustomerMyInvoicesTab::new,
            CustomerCatalogTab::new, CustomerCartTab::new));
    }

    static void exercise(String who, List<Supplier<Tab>> tabs) {
        for (Supplier<Tab> s : tabs) {
            String name = "?";
            try {
                Tab t = s.get();
                name = t.getClass().getSimpleName();
                Stage st = new Stage();
                st.setScene(new Scene(new TabPane(t), 1200, 800));
                st.show();
                if (t instanceof Refreshable r) r.refresh();
                st.close();
                System.out.println("ok  [" + who + "] " + name);
            } catch (Throwable ex) {
                problems.add("EXCEPTION in " + name + ": " + ex);
                ex.printStackTrace();
            }
        }
    }

    static Object invoke(Method m, Object target, Object[] a) throws Throwable {
        try { return m.invoke(target, a); } catch (InvocationTargetException e) { throw e.getCause(); }
    }

    static PreparedStatement wrap(PreparedStatement ps, String sql) {
        return (PreparedStatement) Proxy.newProxyInstance(SmokeTest.class.getClassLoader(),
            new Class[]{PreparedStatement.class}, (p, m, a) -> {
                if (m.getName().startsWith("execute")) sqlCount++;
                try { return invoke(m, ps, a); }
                catch (SQLException e) {
                    problems.add("SQL " + e.getMessage() + " | " + sql.replaceAll("\\s+", " "));
                    throw e;
                }
            });
    }
}
