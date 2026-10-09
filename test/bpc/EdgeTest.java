package bpc;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

/**
 * Edge-case tests. Each case opens a real form, fills it the way a user would
 * (fields are found by their on-screen labels), clicks the real buttons, and
 * then checks the message shown and the database.
 *
 * Run against a freshly loaded database/bpc.sql - this test changes data.
 */
public class EdgeTest {

    // ---------------------------------------------------------------- harness
    static Connection db;
    static final Deque<Consumer<Form>> scripts = new ArrayDeque<>();
    static final List<String> msgs = new ArrayList<>();
    static final Set<Stage> handled = new HashSet<>();
    static ButtonType alertAnswer = ButtonType.YES;
    static int pass = 0, fail = 0;
    static final List<String> report = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        db = DriverManager.getConnection("jdbc:mysql://localhost:3306/bpc", "bpc", "1234");
        CountDownLatch done = new CountDownLatch(1);
        Platform.startup(() -> {
            Thread poller = new Thread(() -> {
                while (true) {
                    try { Thread.sleep(150); } catch (InterruptedException e) { return; }
                    Platform.runLater(EdgeTest::answerWindows);
                }
            });
            poller.setDaemon(true);
            poller.start();
            try { runAll(); } catch (Throwable t) { t.printStackTrace(); fail++; }
            done.countDown();
        });
        done.await();
        report.forEach(System.out::println);
        System.out.println("RESULT: " + pass + " passed, " + fail + " failed");
        Platform.exit();
        System.exit(fail == 0 ? 0 : 1);
    }

    /** Answers alerts, runs the next form script on new forms, and closes forms left open with an error. */
    static void answerWindows() {
        for (Window w : new ArrayList<>(Window.getWindows())) {
            if (!(w instanceof Stage s) || !s.isShowing() || s.getScene() == null) continue;
            Parent root = s.getScene().getRoot();
            if (root instanceof DialogPane dp) {
                if (!handled.add(s)) continue;
                msgs.add("ALERT[" + s.getTitle() + "] " + dp.getContentText().replace('\n', ' '));
                Button b = (Button) dp.lookupButton(alertAnswer);
                if (b == null) b = (Button) dp.lookupButton(ButtonType.OK);
                if (b != null) b.fire(); else s.close();
            } else if (s.getModality() != Modality.NONE) {
                if (handled.add(s)) {
                    Consumer<Form> script = scripts.poll();
                    if (script == null) { msgs.add("UNSCRIPTED " + s.getTitle()); s.close(); continue; }
                    try { script.accept(new Form(s)); }
                    catch (Throwable t) { msgs.add("SCRIPT ERROR " + t); }
                } else {
                    for (String e : new Form(s).errors()) msgs.add("ERROR " + e);
                    s.close();
                }
            }
        }
    }

    interface Body { void run() throws Exception; }

    static void kase(String name, Body body) {
        msgs.clear(); scripts.clear(); alertAnswer = ButtonType.YES;
        try {
            body.run();
        } catch (AssertionError ae) {
            fail++; report.add("FAIL " + name + "  ->  " + ae.getMessage() + "   msgs=" + msgs);
            return;
        } catch (Throwable t) {
            Throwable c = t instanceof InvocationTargetException ite ? ite.getCause() : t;
            fail++; report.add("FAIL " + name + "  ->  exception " + c + "   msgs=" + msgs);
            return;
        }
        pass++; report.add("PASS " + name);
    }

    static void expectMsg(String needle) {
        for (String m : msgs) if (m.toLowerCase().contains(needle.toLowerCase())) return;
        throw new AssertionError("expected a message containing '" + needle + "'");
    }
    static void noErrors() {
        for (String m : msgs) if (m.startsWith("ERROR") || m.startsWith("SCRIPT") || m.startsWith("UNSCRIPTED")
                || m.startsWith("ALERT[DB error") || m.contains("failed")) {
            throw new AssertionError("unexpected message: " + m);
        }
    }
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    /** Queue a script for the next form, then run the action that opens it. */
    static void form(Body opener, Consumer<Form> script) throws Exception {
        scripts.add(script);
        opener.run();
    }

    // ---------------------------------------------------------------- reflection + db helpers
    static Object call(Object target, String method, Object... args) throws Exception {
        for (Method m : target.getClass().getDeclaredMethods()) {
            if (m.getName().equals(method) && m.getParameterCount() == args.length) {
                m.setAccessible(true);
                try { return m.invoke(target, args); }
                catch (InvocationTargetException e) {
                    if (e.getCause() instanceof Exception ex) throw ex;
                    throw e;
                }
            }
        }
        throw new NoSuchMethodException(method);
    }
    static Object field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(o);
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    static void select(Object tab, String table, java.util.function.Predicate<Object> p) throws Exception {
        TableView t = (TableView) field(tab, table);
        for (Object row : t.getItems()) if (p.test(row)) { t.getSelectionModel().select(row); return; }
        throw new AssertionError("row not found in " + table);
    }
    static int id(Object record) throws Exception {
        return (int) record.getClass().getMethod("id").invoke(record);
    }
    static <T extends Tab> T show(T tab) {
        Stage s = new Stage(); s.setScene(new Scene(new TabPane(tab), 1200, 800)); s.show(); return tab;
    }
    static int n(String sql) throws SQLException {
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(sql)) { rs.next(); return rs.getInt(1); }
    }
    static String s(String sql) throws SQLException {
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next()) return null; String v = rs.getString(1); return v;
        }
    }
    static String repeat(char c, int k) { return String.valueOf(c).repeat(k); }

    // ---------------------------------------------------------------- form driver
    static class Form {
        final Stage stage;
        Form(Stage stage) { this.stage = stage; }

        List<Node> all() {
            List<Node> out = new ArrayList<>();
            walk(stage.getScene().getRoot(), out);
            return out;
        }
        static void walk(Node n, List<Node> out) {
            out.add(n);
            if (n instanceof ScrollPane sp && sp.getContent() != null) walk(sp.getContent(), out);
            if (n instanceof Parent p) for (Node c : p.getChildrenUnmodifiable()) walk(c, out);
        }
        /** The input that follows the label whose text starts with the given prefix. */
        Node after(String label) {
            for (Node n : all()) {
                if (n instanceof Label l && l.getText() != null && l.getText().startsWith(label)) {
                    Parent parent = l.getParent();
                    if (parent instanceof GridPane g) {
                        int r = GridPane.getRowIndex(l) == null ? 0 : GridPane.getRowIndex(l);
                        int c = GridPane.getColumnIndex(l) == null ? 0 : GridPane.getColumnIndex(l);
                        for (Node k : g.getChildren()) {
                            int kr = GridPane.getRowIndex(k) == null ? 0 : GridPane.getRowIndex(k);
                            int kc = GridPane.getColumnIndex(k) == null ? 0 : GridPane.getColumnIndex(k);
                            if (kr == r && kc == c + 1) return k;
                        }
                    } else if (parent != null) {
                        List<Node> sib = parent.getChildrenUnmodifiable();
                        return sib.get(sib.indexOf(l) + 1);
                    }
                }
            }
            throw new IllegalStateException("no field labelled '" + label + "' in " + stage.getTitle());
        }
        Form set(String label, Object value) { put(after(label), value); return this; }
        Form prompt(String promptText, String value) {
            for (Node n : all()) {
                if (n instanceof TextInputControl t && promptText.equals(t.getPromptText())) { t.setText(value); return this; }
                if (n instanceof ComboBox<?> cb && promptText.equals(cb.getPromptText())) { put(cb, value); return this; }
            }
            throw new IllegalStateException("no field with prompt '" + promptText + "'");
        }
        @SuppressWarnings({"unchecked", "rawtypes"})
        static void put(Node node, Object value) {
            if (node instanceof DatePicker dp) {
                dp.getEditor().setText(value == null ? "" : value.toString());
                dp.setValue((LocalDate) value);
            } else if (node instanceof Spinner sp) {
                sp.getValueFactory().setValue(value);
                sp.getEditor().setText(String.valueOf(value));
            } else if (node instanceof ComboBox cb) {
                if (value == null) { cb.getSelectionModel().clearSelection(); cb.setValue(null); return; }
                for (Object item : cb.getItems()) {
                    if (item.toString().contains(value.toString())) { cb.getSelectionModel().select(item); return; }
                }
                throw new IllegalStateException("combo has no item like '" + value + "': " + cb.getItems());
            } else if (node instanceof TextInputControl t) {
                t.setText(value == null ? "" : value.toString());
            } else {
                throw new IllegalStateException("cannot set " + node);
            }
        }
        Form click(String text) {
            for (Node n : all()) if (n instanceof Button b && text.equals(b.getText())) { b.fire(); return this; }
            throw new IllegalStateException("no button '" + text + "' in " + stage.getTitle());
        }
        List<String> errors() {
            List<String> out = new ArrayList<>();
            for (Node n : all()) {
                if (n instanceof Label l && l.getStyle() != null && l.getText() != null && !l.getText().isBlank()
                        && (l.getStyle().contains("c0392b") || l.getStyle().contains("dc2626"))) {
                    out.add(l.getText().replace('\n', ' '));
                }
            }
            return out;
        }
    }

    // ================================================================ test cases
    static void runAll() throws Exception {
        Session.loginAsEmployee(11, "admin", "System Administrator", "General Manager");
        auth();
        hashing();
        users();
        customers();
        suppliersAndMaterials();
        catalog();
        warehouses();
        purchasing();
        production();
        transfers();
        sales();
        payments();
        customerPortal();
        dashboard();
    }

    // ---------------------------------------------------------------- authentication & roles
    static void auth() throws Exception {
        Main main = new Main();
        kase("AUTH-1 correct staff password accepted", () ->
            check(call(main, "findEmployee", "admin", "admin") != null, "admin/admin rejected"));
        kase("AUTH-2 staff password is case-sensitive", () ->
            check(call(main, "findEmployee", "admin", "ADMIN") == null, "ADMIN accepted for admin"));
        kase("AUTH-3 wrong staff password rejected", () ->
            check(call(main, "findEmployee", "admin", "wrong") == null, "wrong password accepted"));
        kase("AUTH-4 SQL injection in username rejected", () ->
            check(call(main, "findEmployee", "' OR '1'='1", "' OR '1'='1") == null, "injection logged in"));
        kase("AUTH-5 customer password is case-sensitive", () -> {
            check((boolean) call(main, "verifyCustomerPassword", 1, "customer1"), "correct customer password rejected");
            check(!(boolean) call(main, "verifyCustomerPassword", 1, "CUSTOMER1"), "CUSTOMER1 accepted");
        });

        Map<String, List<String>> expected = new LinkedHashMap<>();
        expected.put("General Manager", List.of("Dashboard", "Sales", "Procurement", "Warehouse",
                "Catalog & Production", "Reports", "Customer Management", "User Management"));
        expected.put("Warehouse Manager", List.of("Dashboard", "Procurement", "Warehouse",
                "Catalog & Production", "Reports"));
        expected.put("Sales Manager", List.of("Dashboard", "Sales", "Catalog", "Reports", "Customer Management"));
        expected.put("Procurement Officer", List.of("Procurement", "Warehouse"));
        expected.put("Sales Representative", List.of("Sales"));
        expected.put("Production Officer", List.of("Warehouse", "Catalog & Production"));
        for (var e : expected.entrySet()) {
            kase("AUTH-6 menus for " + e.getKey(), () -> {
                Session.loginAsEmployee(1, "x", "X", e.getKey());
                Field f = Main.class.getDeclaredField("stage"); f.setAccessible(true);
                Stage st = new Stage(); f.set(main, st);
                call(main, "openStaffShell");
                TabPane tabs = (TabPane) ((BorderPane) st.getScene().getRoot()).getCenter();
                List<String> got = tabs.getTabs().stream().map(Tab::getText).toList();
                st.close();
                check(got.equals(e.getValue()), "got " + got);
            });
        }
        Session.loginAsEmployee(11, "admin", "System Administrator", "General Manager");
    }

    // ---------------------------------------------------------------- password hashing
    static void hashing() throws Exception {
        Main main = new Main();
        kase("HASH-1 no plain-text passwords in the database", () -> {
            check(n("SELECT COUNT(*) FROM Employee WHERE Password NOT LIKE 'pbkdf2$%'") == 0, "plain employee password");
            check(n("SELECT COUNT(*) FROM Customer WHERE Password NOT LIKE 'pbkdf2$%'") == 0, "plain customer password");
        });
        kase("HASH-2 the same password gets a different salt each time", () -> {
            String a = Passwords.hash("secret1"), b = Passwords.hash("secret1");
            check(!a.equals(b), "identical hashes");
            check(Passwords.verify("secret1", a) && Passwords.verify("secret1", b), "verify failed");
            check(!Passwords.verify("Secret1", a), "case-insensitive");
        });
        kase("HASH-3 a legacy plain-text password still works once and is upgraded", () -> {
            try (Statement st = db.createStatement()) {
                st.executeUpdate("UPDATE Employee SET Password='legacy1' WHERE EmpID=5");
            }
            check(call(main, "findEmployee", "tareq", "legacy1") != null, "legacy login failed");
            String stored = s("SELECT Password FROM Employee WHERE EmpID=5");
            check(Passwords.isHashed(stored) && Passwords.verify("legacy1", stored), "not upgraded: " + stored);
            check(call(main, "findEmployee", "tareq", "legacy1") != null, "login after upgrade failed");
        });
        kase("HASH-4 a corrupt stored hash fails safely", () -> {
            check(!Passwords.verify("x", "pbkdf2$abc$!!$??"), "corrupt hash accepted");
            check(!Passwords.verify("x", null) && !Passwords.verify(null, "x"), "null accepted");
        });
    }

    // ---------------------------------------------------------------- user management
    static void users() throws Exception {
        UserManagementTab t = show(new UserManagementTab());
        Body add = () -> call(t, "openForm", (Object) null);
        Consumer<Form> base = f -> f.set("Username", "newuser").set("Password", "pass1").set("Full name", "New User")
                                    .set("Salary", "3000");
        kase("USR-1 username shorter than 3", () -> {
            form(add, f -> { base.accept(f); f.set("Username", "ab").click("Save"); });
            expectMsg("at least 3");
        });
        kase("USR-2 username with spaces", () -> {
            form(add, f -> { base.accept(f); f.set("Username", "new user").click("Save"); });
            expectMsg("may contain only");
        });
        kase("USR-3 duplicate username (different case)", () -> {
            form(add, f -> { base.accept(f); f.set("Username", "KHALED").click("Save"); });
            expectMsg("already taken");
        });
        kase("USR-4 password too short", () -> {
            form(add, f -> { base.accept(f); f.set("Password", "123").click("Save"); });
            expectMsg("at least 4");
        });
        kase("USR-5 invalid email", () -> {
            form(add, f -> { base.accept(f); f.set("Email", "bad@").click("Save"); });
            expectMsg("name@example.com");
        });
        kase("USR-6 invalid phone", () -> {
            form(add, f -> { base.accept(f); f.set("Phone", "call me").click("Save"); });
            expectMsg("Phone may contain only");
        });
        kase("USR-7 salary NaN", () -> {
            form(add, f -> { base.accept(f); f.set("Salary", "NaN").click("Save"); });
            expectMsg("must be a number");
        });
        kase("USR-8 negative salary", () -> {
            form(add, f -> { base.accept(f); f.set("Salary", "-5").click("Save"); });
            expectMsg("cannot be negative");
        });
        kase("USR-9 salary too large for the column", () -> {
            form(add, f -> { base.accept(f); f.set("Salary", "123456789012").click("Save"); });
            expectMsg("too large");
        });
        kase("USR-10 hire date in the future", () -> {
            form(add, f -> { base.accept(f); f.set("Hire date", LocalDate.now().plusDays(3)).click("Save"); });
            expectMsg("future");
        });
        kase("USR-11 name longer than 120 characters", () -> {
            form(add, f -> { base.accept(f); f.set("Full name", repeat('a', 121)).click("Save"); });
            expectMsg("at most 120");
        });
        kase("USR-12 valid employee is saved", () -> {
            form(add, f -> { base.accept(f); f.set("Email", "new.user@bpc.ps").click("Save"); });
            noErrors();
            check(n("SELECT COUNT(*) FROM Employee WHERE Username='newuser' AND EmpName='New User'") == 1, "not saved");
            check(Passwords.verify("pass1", s("SELECT Password FROM Employee WHERE Username='newuser'")), "password not hashed");
        });
        kase("USR-13 cannot delete own account", () -> {
            select(t, "table", r -> { try { return id(r) == 11; } catch (Exception e) { return false; } });
            call(t, "doDelete");
            expectMsg("cannot delete your own account");
        });
        kase("USR-14 cannot delete the only General Manager", () -> {
            Session.loginAsEmployee(1, "khaled", "Khaled Mansour", "General Manager");
            select(t, "table", r -> { try { return id(r) == 11; } catch (Exception e) { return false; } });
            call(t, "doDelete");
            Session.loginAsEmployee(11, "admin", "System Administrator", "General Manager");
            expectMsg("only General Manager");
            check(n("SELECT COUNT(*) FROM Employee WHERE EmpID=11") == 1, "GM deleted");
        });
        kase("USR-15 cannot change own role", () -> {
            Object me = null;
            for (Object r : ((TableView<?>) field(t, "table")).getItems()) if (id(r) == 11) me = r;
            Object row = me;
            form(() -> call(t, "openForm", row), f -> f.set("Role", "Sales Representative").click("Save"));
            expectMsg("cannot change your own role");
            check("General Manager".equals(s("SELECT Role FROM Employee WHERE EmpID=11")), "role changed");
        });
        kase("USR-16 delete employee with activity is blocked", () -> {
            select(t, "table", r -> { try { return id(r) == 1; } catch (Exception e) { return false; } });
            call(t, "doDelete");
            expectMsg("has activity");
        });
    }

    // ---------------------------------------------------------------- customers
    static void customers() throws Exception {
        CustomersTab t = show(new CustomersTab());
        Body add = () -> call(t, "openForm", (Object) null);
        Consumer<Form> base = f -> f.set("Username", "newclinic").set("Password", "pass1")
                                    .set("Name", "New Clinic").set("City", "Jenin");
        kase("CUS-1 duplicate username", () -> {
            form(add, f -> { base.accept(f); f.set("Username", "najah").click("Save"); });
            expectMsg("already taken");
        });
        kase("CUS-2 missing city", () -> {
            form(add, f -> { base.accept(f); f.set("City", "  ").click("Save"); });
            expectMsg("required");
        });
        kase("CUS-3 invalid email", () -> {
            form(add, f -> { base.accept(f); f.set("Email", "x@y").click("Save"); });
            expectMsg("name@example.com");
        });
        kase("CUS-4 phone with too few digits", () -> {
            form(add, f -> { base.accept(f); f.set("Phone", "12-34").click("Save"); });
            expectMsg("at least 7 digits");
        });
        kase("CUS-5 city longer than 80", () -> {
            form(add, f -> { base.accept(f); f.set("City", repeat('c', 81)).click("Save"); });
            expectMsg("at most 80");
        });
        kase("CUS-6 valid customer is saved", () -> {
            form(add, f -> { base.accept(f); f.set("Phone", "+970 599 123456").click("Save"); });
            noErrors();
            check(n("SELECT COUNT(*) FROM Customer WHERE Username='newclinic'") == 1, "not saved");
        });
        kase("CUS-7 delete customer with orders is blocked", () -> {
            select(t, "table", r -> { try { return id(r) == 1; } catch (Exception e) { return false; } });
            call(t, "doDelete");
            expectMsg("existing sales orders");
            check(n("SELECT COUNT(*) FROM Customer WHERE CustomerID=1") == 1, "deleted");
        });
        kase("CUS-8 delete customer without orders works", () -> {
            int newId = n("SELECT CustomerID FROM Customer WHERE Username='newclinic'");
            t.refresh();
            select(t, "table", r -> { try { return id(r) == newId; } catch (Exception e) { return false; } });
            call(t, "doDelete");
            check(n("SELECT COUNT(*) FROM Customer WHERE CustomerID=" + newId) == 0, "not deleted");
        });
    }

    // ---------------------------------------------------------------- suppliers & raw materials
    static void suppliersAndMaterials() throws Exception {
        SuppliersTab t = show(new SuppliersTab());
        Body add = () -> call(t, "openForm", (Object) null);
        kase("SUP-1 rating above 5", () -> {
            form(add, f -> f.set("Name", "Acme").set("Country", "Jordan").set("Rating", "6").click("Save"));
            expectMsg("between 0 and 5");
        });
        kase("SUP-2 rating with 3 decimals", () -> {
            form(add, f -> f.set("Name", "Acme").set("Country", "Jordan").set("Rating", "4.555").click("Save"));
            expectMsg("2 decimal");
        });
        kase("SUP-3 invalid phone", () -> {
            form(add, f -> f.set("Name", "Acme").set("Country", "Jordan").set("Phone", "phone#1").click("Save"));
            expectMsg("Phone may contain only");
        });
        kase("SUP-4 country is normalised", () -> {
            form(add, f -> f.set("Name", "Acme Pharma").set("Country", "  united   states ").click("Save"));
            noErrors();
            check("United States".equals(s("SELECT Country FROM Supplier WHERE SupName='Acme Pharma'")), "not normalised");
        });
        kase("SUP-5 linking a material twice gives a clear message", () -> {
            select(t, "table", r -> { try { return id(r) == 1; } catch (Exception e) { return false; } });
            form(() -> call(t, "openLinkMaterialDialog"), f -> {
                @SuppressWarnings("unchecked") ComboBox<Object> box = (ComboBox<Object>) f.all().stream()
                        .filter(x -> x instanceof ComboBox).findFirst().orElseThrow();
                check(box.getItems().stream().noneMatch(i -> i.toString().startsWith("Amoxicillin API")),
                      "already-linked material is offered again");
                box.getSelectionModel().selectFirst();
                f.click("Link");
            });
            noErrors();
        });
        kase("SUP-6 delete supplier with purchase orders is blocked", () -> {
            select(t, "table", r -> { try { return id(r) == 1; } catch (Exception e) { return false; } });
            call(t, "doDelete");
            expectMsg("Cannot delete");
            check(n("SELECT COUNT(*) FROM Supplier WHERE SupplierID=1") == 1, "deleted");
        });

        RawMaterialsTab rm = show(new RawMaterialsTab());
        Body addRm = () -> call(rm, "openForm", (Object) null);
        kase("RM-1 duplicate name (different case)", () -> {
            form(addRm, f -> f.set("Name", "amoxicillin api").set("Unit", "kg").set("Reorder level", "5").click("Save"));
            expectMsg("already exists");
        });
        kase("RM-2 negative reorder level", () -> {
            form(addRm, f -> f.set("Name", "Zinc Oxide").set("Unit", "kg").set("Reorder level", "-1").click("Save"));
            expectMsg("between 0");
        });
        kase("RM-3 non-numeric reorder level", () -> {
            form(addRm, f -> f.set("Name", "Zinc Oxide").set("Unit", "kg").set("Reorder level", "ten").click("Save"));
            expectMsg("whole number");
        });
        kase("RM-4 unit longer than 20", () -> {
            form(addRm, f -> f.set("Name", "Zinc Oxide").set("Unit", repeat('u', 21)).set("Reorder level", "1").click("Save"));
            expectMsg("at most 20");
        });
        kase("RM-5 delete material used in a formula is blocked", () -> {
            select(rm, "table", r -> { try { return id(r) == 1; } catch (Exception e) { return false; } });
            call(rm, "doDelete");
            expectMsg("Cannot delete");
        });
    }

    // ---------------------------------------------------------------- categories & products
    static void catalog() throws Exception {
        CategoriesTab c = show(new CategoriesTab());
        kase("CAT-1 duplicate category", () -> {
            form(() -> call(c, "openForm", (Object) null), f -> f.set("Name", "Antibiotics").click("Save"));
            expectMsg("already exists");
        });
        kase("CAT-2 description longer than 255", () -> {
            form(() -> call(c, "openForm", (Object) null),
                 f -> f.set("Name", "Vitamins").set("Description", repeat('d', 256)).click("Save"));
            expectMsg("at most 255");
        });
        kase("CAT-3 delete category with products is blocked", () -> {
            select(c, "table", r -> { try { return id(r) == 1; } catch (Exception e) { return false; } });
            call(c, "doDelete");
            expectMsg("Cannot delete");
        });

        ProductsTab p = show(new ProductsTab());
        Body add = () -> call(p, "openForm", (Object) null);
        Consumer<Form> base = f -> f.set("Name", "Testamol").set("Dosage form", "Tablet")
                                    .set("Unit price", "5.50").set("Shelf life", "24");
        kase("PRD-1 price zero", () -> {
            form(add, f -> { base.accept(f); f.set("Unit price", "0").click("Save"); });
            expectMsg("greater than 0");
        });
        kase("PRD-2 price with 3 decimals", () -> {
            form(add, f -> { base.accept(f); f.set("Unit price", "1.999").click("Save"); });
            expectMsg("2 decimal");
        });
        kase("PRD-3 shelf life 0 months", () -> {
            form(add, f -> { base.accept(f); f.set("Shelf life", "0").click("Save"); });
            expectMsg("between 1 and 120");
        });
        kase("PRD-4 shelf life 10000 months", () -> {
            form(add, f -> { base.accept(f); f.set("Shelf life", "10000").click("Save"); });
            expectMsg("between 1 and 120");
        });
        kase("PRD-5 duplicate product name", () -> {
            form(add, f -> { base.accept(f); f.set("Name", "Dilax").click("Save"); });
            expectMsg("already exists");
        });
        kase("PRD-6 negative formula quantity", () -> {
            form(add, f -> {
                base.accept(f);
                f.prompt("Raw material", "Talc").prompt("Qty per 100", "-3").click("Add to formula");
                f.stage.close();
            });
            expectMsg("greater than 0");
        });
        kase("PRD-7 valid product with formula is saved", () -> {
            form(add, f -> {
                base.accept(f);
                f.prompt("Raw material", "Talc").prompt("Qty per 100", "2.5").click("Add to formula");
                f.click("Save");
            });
            noErrors();
            check(n("SELECT COUNT(*) FROM ProductFormula pf JOIN Product p ON p.ProductID=pf.ProductID " +
                    "WHERE p.ProductName='Testamol' AND pf.QuantityPer100=2.50") == 1, "formula not saved");
        });
        kase("PRD-8 delete product with batches is blocked", () -> {
            select(p, "table", r -> { try { return id(r) == 1; } catch (Exception e) { return false; } });
            call(p, "doDelete");
            expectMsg("Cannot delete");
        });
    }

    // ---------------------------------------------------------------- warehouses
    static void warehouses() throws Exception {
        WarehousesTab t = show(new WarehousesTab());
        Body add = () -> call(t, "openForm", (Object) null);
        kase("WH-1 capacity zero", () -> {
            form(add, f -> f.set("Name", "Nablus WH").set("Location", "Nablus").set("Capacity", "0")
                            .set("Type", "Distribution").click("Save"));
            expectMsg("between 1");
        });
        kase("WH-2 capacity not a number", () -> {
            form(add, f -> f.set("Name", "Nablus WH").set("Location", "Nablus").set("Capacity", "big")
                            .set("Type", "Distribution").click("Save"));
            expectMsg("whole number");
        });
        Object wh1 = null;
        for (Object r : ((TableView<?>) field(t, "table")).getItems()) if (id(r) == 1) wh1 = r;
        Object w1 = wh1;
        kase("WH-3 cannot change type of a warehouse holding stock", () -> {
            form(() -> call(t, "openForm", w1), f -> f.set("Type", "Distribution").click("Save"));
            expectMsg("type cannot be changed");
            check("RawMaterials".equals(s("SELECT Type FROM Warehouse WHERE WarehouseID=1")), "type changed");
        });
        kase("WH-4 capacity below current stock", () -> {
            form(() -> call(t, "openForm", w1), f -> f.set("Capacity", "10").click("Save"));
            expectMsg("cannot be less than");
        });
        kase("WH-5 delete warehouse with batches is blocked", () -> {
            select(t, "table", r -> { try { return id(r) == 1; } catch (Exception e) { return false; } });
            call(t, "doDelete");
            expectMsg("Cannot delete");
        });
    }

    // ---------------------------------------------------------------- purchasing
    static void purchasing() throws Exception {
        Session.loginAsEmployee(4, "rania", "Rania Abu-Awad", "Procurement Officer");
        PurchaseOrdersTab t = show(new PurchaseOrdersTab());
        Body wizard = () -> call(t, "openNewPOWizard");
        kase("PO-1 place without supplier", () -> {
            form(wizard, f -> f.click("Place PO"));
            expectMsg("Pick a supplier");
        });
        kase("PO-2 unit cost zero", () -> {
            form(wizard, f -> {
                f.set("Supplier:", "BASF").set("Material:", "Amoxicillin").set("Unit cost:", "0").click("Add to PO");
                f.stage.close();
            });
            expectMsg("greater than 0");
        });
        kase("PO-3 unit cost with 3 decimals", () -> {
            form(wizard, f -> {
                f.set("Supplier:", "BASF").set("Material:", "Amoxicillin").set("Unit cost:", "1.234").click("Add to PO");
                f.stage.close();
            });
            expectMsg("2 decimal");
        });
        kase("PO-4 expected date before order date", () -> {
            form(wizard, f -> {
                f.set("Supplier:", "BASF").set("Material:", "Amoxicillin").set("Unit cost:", "100").click("Add to PO");
                f.set("Expected:", LocalDate.now().minusDays(5)).click("Place PO");
            });
            expectMsg("cannot be before order date");
        });
        kase("PO-5 order date in the future", () -> {
            form(wizard, f -> {
                f.set("Supplier:", "BASF").set("Material:", "Amoxicillin").set("Unit cost:", "100").click("Add to PO");
                f.set("Order date:", LocalDate.now().plusDays(2)).set("Expected:", LocalDate.now().plusDays(30))
                 .click("Place PO");
            });
            expectMsg("future");
        });
        kase("PO-6 place without items", () -> {
            form(wizard, f -> f.set("Supplier:", "BASF").click("Place PO"));
            expectMsg("at least one material");
        });
        kase("PO-7 valid PO stores the right total", () -> {
            int before = n("SELECT MAX(POID) FROM PurchaseOrder");
            form(wizard, f -> {
                f.set("Supplier:", "BASF").set("Material:", "Amoxicillin").set("Qty:", 10)
                 .set("Unit cost:", "100").click("Add to PO").click("Place PO");
            });
            noErrors();
            int po = n("SELECT MAX(POID) FROM PurchaseOrder");
            check(po == before + 1, "PO not created");
            check("1000.00".equals(s("SELECT TotalCost FROM PurchaseOrder WHERE POID=" + po)), "wrong total");
        });
        kase("PO-8 invoice for a pending PO is blocked", () -> {
            t.refresh();
            select(t, "poTable", r -> { try { return id(r) == 11; } catch (Exception e) { return false; } });
            call(t, "generateSupplierInvoice");
            expectMsg("Only received POs");
        });
        kase("PO-9 cancel a pending PO, then receiving it is blocked", () -> {
            t.refresh();
            select(t, "poTable", r -> { try { return id(r) == 11; } catch (Exception e) { return false; } });
            call(t, "cancelPO");
            check("Cancelled".equals(s("SELECT Status FROM PurchaseOrder WHERE POID=11")), "not cancelled");
            t.refresh();
            select(t, "poTable", r -> { try { return id(r) == 11; } catch (Exception e) { return false; } });
            call(t, "markReceived");
            expectMsg("cannot be received");
        });
        kase("PO-10 two screens cannot receive the same PO twice", () -> {
            PurchaseOrdersTab other = show(new PurchaseOrdersTab());   // second user with a stale screen
            t.refresh();
            select(t, "poTable", r -> { try { return id(r) == 12; } catch (Exception e) { return false; } });
            select(other, "poTable", r -> { try { return id(r) == 12; } catch (Exception e) { return false; } });
            form(() -> call(t, "markReceived"), f -> f.click("Confirm receipt"));
            int batches = n("SELECT COUNT(*) FROM RawMaterialBatch");
            form(() -> call(other, "markReceived"), f -> f.click("Confirm receipt"));
            expectMsg("no longer Pending");
            check(n("SELECT COUNT(*) FROM GoodsReceipt WHERE POID=12") == 1, "received twice");
            check(n("SELECT COUNT(*) FROM RawMaterialBatch") == batches, "stock added twice");
        });
        kase("PO-11 received goods go to a Raw Materials warehouse", () ->
            check(n("SELECT COUNT(*) FROM RawMaterialBatch rmb JOIN GoodsReceiptItem gri ON gri.ReceiptItemID=rmb.ReceiptItemID " +
                    "JOIN GoodsReceipt gr ON gr.ReceiptID=gri.ReceiptID JOIN Warehouse w ON w.WarehouseID=rmb.WarehouseID " +
                    "WHERE gr.POID=12 AND w.Type<>'RawMaterials'") == 0, "wrong warehouse"));
        kase("PO-12 duplicate supplier invoice is blocked", () -> {
            t.refresh();
            select(t, "poTable", r -> { try { return id(r) == 12; } catch (Exception e) { return false; } });
            call(t, "generateSupplierInvoice");
            call(t, "generateSupplierInvoice");
            expectMsg("already has supplier invoice");
            check(n("SELECT COUNT(*) FROM SupplierInvoice WHERE POID=12") == 1, "two invoices");
        });
        Session.loginAsEmployee(11, "admin", "System Administrator", "General Manager");
    }

    // ---------------------------------------------------------------- production
    static void production() throws Exception {
        Session.loginAsEmployee(9, "nidal", "Nidal Hamdan", "Production Officer");
        ProductionTab t = show(new ProductionTab());
        Body wizard = () -> call(t, "openWizard");
        kase("PRD-ORD-1 place without calculating materials", () -> {
            form(wizard, f -> f.set("Product:", "Gingival").click("Place Production"));
            expectMsg("Calculate Materials first");
        });
        kase("PRD-ORD-2 calculation is discarded when the product changes", () -> {
            form(wizard, f -> f.set("Product:", "Gingival").set("Qty to produce:", 100).click("Calculate Materials")
                              .set("Product:", "Dilax").click("Place Production"));
            expectMsg("Calculate Materials first");
        });
        kase("PRD-ORD-3 not enough raw material", () -> {
            form(wizard, f -> {
                f.set("Product:", "Gingival").set("Qty to produce:", 100000).click("Calculate Materials");
                msgs.addAll(f.errors());
                f.stage.close();
            });
            expectMsg("Not enough");
        });
        int lactose = n("SELECT SUM(Quantity) FROM RawMaterialBatch WHERE MaterialID=10");
        kase("PRD-ORD-5 planning reserves materials but adds no stock yet", () -> {
            form(wizard, f -> f.set("Product:", "Gingival").set("Qty to produce:", 100).click("Calculate Materials")
                              .click("Place Production"));
            noErrors();
            int prod = n("SELECT MAX(ProductionID) FROM ProductionOrder");
            check("Planned".equals(s("SELECT Status FROM ProductionOrder WHERE ProductionID=" + prod)), "not planned");
            check(n("SELECT pb.Quantity FROM ProductionOrder po JOIN ProductBatch pb ON pb.ProductBatchID=po.ProductBatchID " +
                    "WHERE po.ProductionID=" + prod) == 0, "planned batch already has stock");
            check(n("SELECT SUM(Quantity) FROM RawMaterialBatch WHERE MaterialID=10") == lactose - 4, "lactose not reserved");
        });
        kase("PRD-ORD-6 completing adds the stock and records the transfer", () -> {
            int prod = n("SELECT MAX(ProductionID) FROM ProductionOrder");
            t.refresh();
            select(t, "poTable", r -> { try { return id(r) == prod; } catch (Exception e) { return false; } });
            call(t, "changeStatus", "Completed", new String[]{"InProgress", "Planned"});
            check(n("SELECT pb.Quantity FROM ProductionOrder po JOIN ProductBatch pb ON pb.ProductBatchID=po.ProductBatchID " +
                    "WHERE po.ProductionID=" + prod) == 100, "stock not added");
            check(n("SELECT COUNT(*) FROM StockTransfer st JOIN ProductionOrder po ON po.ProductBatchID=st.ProductBatchID " +
                    "WHERE po.ProductionID=" + prod) == 1, "no transfer");
        });
        kase("PRD-ORD-7 cancelling returns the reserved materials", () -> {
            form(wizard, f -> f.set("Product:", "Gingival").set("Qty to produce:", 200).click("Calculate Materials")
                              .click("Place Production"));
            int prod = n("SELECT MAX(ProductionID) FROM ProductionOrder");
            int afterPlan = n("SELECT SUM(Quantity) FROM RawMaterialBatch WHERE MaterialID=10");
            t.refresh();
            select(t, "poTable", r -> { try { return id(r) == prod; } catch (Exception e) { return false; } });
            call(t, "changeStatus", "Cancelled", new String[]{"Planned", "InProgress"});
            check(n("SELECT SUM(Quantity) FROM RawMaterialBatch WHERE MaterialID=10") == afterPlan + 8, "not returned");
            check(n("SELECT pb.Quantity FROM ProductionOrder po JOIN ProductBatch pb ON pb.ProductBatchID=po.ProductBatchID " +
                    "WHERE po.ProductionID=" + prod) == 0, "cancelled batch has stock");
        });
        kase("PRD-ORD-8 a cancelled order cannot be completed", () -> {
            int prod = n("SELECT MAX(ProductionID) FROM ProductionOrder");
            t.refresh();
            select(t, "poTable", r -> { try { return id(r) == prod; } catch (Exception e) { return false; } });
            call(t, "changeStatus", "Completed", new String[]{"InProgress", "Planned"});
            expectMsg("cannot move to Completed");
        });
        Session.loginAsEmployee(11, "admin", "System Administrator", "General Manager");
    }

    // ---------------------------------------------------------------- stock transfers
    static void transfers() throws Exception {
        Session.loginAsEmployee(2, "lina", "Lina Hammad", "Warehouse Manager");
        StockTransfersTab t = show(new StockTransfersTab());
        Body wizard = () -> call(t, "openTransferWizard");
        kase("TR-1 transfer date in the future", () -> {
            form(wizard, f -> f.set("Batch", "Batch #1 ").set("Date", LocalDate.now().plusDays(1)).click("Transfer"));
            expectMsg("future");
        });
        kase("TR-2 quantity cannot exceed the batch", () -> {
            form(wizard, f -> {
                f.set("Batch", "Batch #1 ");
                @SuppressWarnings("unchecked") Spinner<Integer> sp = (Spinner<Integer>) f.after("Quantity");
                sp.getEditor().setText("999999");
                sp.getEditor().getOnAction().handle(null);
                check(sp.getValue() == 2000, "spinner allowed " + sp.getValue());
                f.stage.close();
            });
        });
        kase("TR-3 valid transfer moves the stock", () -> {
            int src = n("SELECT Quantity FROM ProductBatch WHERE ProductBatchID=1");
            int dst = n("SELECT Quantity FROM ProductBatch WHERE ProductBatchID=2");
            form(wizard, f -> f.set("Batch", "Batch #1 ").set("Move to", "Ramallah").set("Quantity", 100).click("Transfer"));
            noErrors();
            check(n("SELECT Quantity FROM ProductBatch WHERE ProductBatchID=1") == src - 100, "source not reduced");
            check(n("SELECT Quantity FROM ProductBatch WHERE ProductBatchID=2") == dst + 100, "destination not increased");
        });
        kase("TR-4 expired batches are not offered", () -> {
            form(wizard, f -> {
                @SuppressWarnings("unchecked") ComboBox<Object> box = (ComboBox<Object>) f.after("Batch");
                check(box.getItems().stream().noneMatch(i -> i.toString().startsWith("Batch #12 ")),
                      "expired batch #12 offered");
                f.stage.close();
            });
        });
        Session.loginAsEmployee(11, "admin", "System Administrator", "General Manager");
    }

    // ---------------------------------------------------------------- sales orders
    static void sales() throws Exception {
        OrdersTab t = show(new OrdersTab());
        Body wizard = () -> call(t, "openNewOrderWizard");
        kase("SO-1 discount larger than the order value", () -> {
            form(wizard, f -> f.set("Customer:", "Al-Najah").set("Product:", "Dilax").set("Qty:", 10)
                              .click("Add to cart").set("Discount:", "100").click("Place Order"));
            expectMsg("cannot be more than the order value");
        });
        kase("SO-2 discount not a number", () -> {
            form(wizard, f -> f.set("Customer:", "Al-Najah").set("Product:", "Dilax").set("Qty:", 10)
                              .click("Add to cart").set("Discount:", "ten").click("Place Order"));
            expectMsg("must be a number");
        });
        kase("SO-3 cleared order date does not crash", () -> {
            form(wizard, f -> f.set("Customer:", "Al-Najah").set("Product:", "Dilax").set("Qty:", 10)
                              .click("Add to cart").set("Date:", null).click("Place Order"));
            expectMsg("Pick an order date");
        });
        kase("SO-4 order date in the future", () -> {
            form(wizard, f -> f.set("Customer:", "Al-Najah").set("Product:", "Dilax").set("Qty:", 10)
                              .click("Add to cart").set("Date:", LocalDate.now().plusDays(1)).click("Place Order"));
            expectMsg("future");
        });
        kase("SO-5 more than the available stock", () -> {
            form(wizard, f -> {
                f.set("Customer:", "Al-Najah").set("Product:", "Dilax").set("Qty:", 100000).click("Add to cart");
                f.stage.close();
            });
            expectMsg("Not enough stock");
        });
        kase("SO-6 expired stock cannot be sold", () -> {
            form(wizard, f -> {
                f.set("Customer:", "Al-Najah").set("Product:", "Lamirase").set("Qty:", 1).click("Add to cart");
                f.stage.close();
            });
            expectMsg("Not enough stock");
        });
        kase("SO-7 valid order with discount", () -> {
            form(wizard, f -> f.set("Customer:", "Al-Najah").set("Product:", "Dilax").set("Qty:", 10)
                              .click("Add to cart").set("Discount:", "5").click("Place Order"));
            noErrors();
            int o = n("SELECT MAX(OrderID) FROM SalesOrder");
            check("29.00".equals(s("SELECT TotalAmount FROM SalesOrder WHERE OrderID=" + o)), "wrong total");
            check("5.00".equals(s("SELECT Discount FROM SalesOrder WHERE OrderID=" + o)), "wrong discount");
        });
        kase("SO-8 invoice for a pending order is blocked", () -> {
            int o = n("SELECT MAX(OrderID) FROM SalesOrder");
            t.refresh();
            select(t, "ordersTable", r -> { try { return id(r) == o; } catch (Exception e) { return false; } });
            call(t, "generateInvoice");
            expectMsg("Only delivered orders");
        });
        kase("SO-9 a delivered order cannot be cancelled", () -> {
            int o = n("SELECT MAX(OrderID) FROM SalesOrder");
            t.refresh();
            select(t, "ordersTable", r -> { try { return id(r) == o; } catch (Exception e) { return false; } });
            call(t, "updateStatus", "Delivered");
            t.refresh();
            select(t, "ordersTable", r -> { try { return id(r) == o; } catch (Exception e) { return false; } });
            call(t, "cancelOrder");
            expectMsg("cannot be cancelled");
            check("Delivered".equals(s("SELECT Status FROM SalesOrder WHERE OrderID=" + o)), "status changed");
        });
        kase("SO-10 a cancelled order cannot be marked delivered", () -> {
            int o = n("SELECT OrderID FROM SalesOrder WHERE Status='Pending' ORDER BY OrderID LIMIT 1");
            t.refresh();
            select(t, "ordersTable", r -> { try { return id(r) == o; } catch (Exception e) { return false; } });
            call(t, "cancelOrder");
            t.refresh();
            select(t, "ordersTable", r -> { try { return id(r) == o; } catch (Exception e) { return false; } });
            call(t, "updateStatus", "Delivered");
            expectMsg("was cancelled");
        });
    }

    // ---------------------------------------------------------------- payments & overdue
    static void payments() throws Exception {
        InvoicesTab t = show(new InvoicesTab());
        kase("PAY-1 past-due unpaid invoices are marked Overdue", () ->
            check(n("SELECT COUNT(*) FROM Invoice WHERE InvoiceID IN (12,13,14,15) AND Status='Overdue'") == 4,
                  "overdue not detected"));
        Body pay12 = () -> {
            t.refresh();
            select(t, "invTable", r -> { try { return id(r) == 12; } catch (Exception e) { return false; } });
            call(t, "openPaymentDialog");
        };
        kase("PAY-2 amount with 3 decimals", () -> {
            form(pay12, f -> f.set("Amount", "0.001").click("Save"));
            expectMsg("2 decimal");
        });
        kase("PAY-3 amount not a number", () -> {
            form(pay12, f -> f.set("Amount", "abc").click("Save"));
            expectMsg("must be a number");
        });
        kase("PAY-4 amount above the balance", () -> {
            form(pay12, f -> f.set("Amount", "99999").click("Save"));
            expectMsg("exceeds the remaining balance");
        });
        kase("PAY-5 zero amount", () -> {
            form(pay12, f -> f.set("Amount", "0").click("Save"));
            expectMsg("greater than 0");
        });
        kase("PAY-6 payment date in the future", () -> {
            form(pay12, f -> f.set("Amount", "100").set("Date", LocalDate.now().plusDays(1)).click("Save"));
            expectMsg("future");
        });
        kase("PAY-7 payment date before the invoice was issued", () -> {
            form(pay12, f -> f.set("Amount", "100").set("Date", LocalDate.of(2026, 4, 1)).click("Save"));
            expectMsg("before the invoice issue date");
        });
        kase("PAY-8 partial payment on an overdue invoice stays Overdue", () -> {
            form(pay12, f -> f.set("Amount", "1000").click("Save"));
            check("Overdue".equals(s("SELECT Status FROM Invoice WHERE InvoiceID=12")), "status");
            check(n("SELECT SUM(Amount) FROM CustomerPayment WHERE InvoiceID=12") == 1000, "payment not saved");
        });
        kase("PAY-9 paying the rest marks it Paid", () -> {
            form(pay12, f -> f.set("Amount", "6080").click("Save"));
            check("Paid".equals(s("SELECT Status FROM Invoice WHERE InvoiceID=12")), "not paid");
        });
        kase("PAY-10 a paid invoice cannot be paid again", () -> {
            pay12.run();
            expectMsg("fully paid");
        });
        kase("PAY-11 overpayment is blocked inside the transaction too", () -> {
            try {
                Billing.recordPayment(Billing.Kind.CUSTOMER, 13, new BigDecimal("10180.01"), LocalDate.now(), "Cash");
                throw new AssertionError("overpayment accepted");
            } catch (SQLException expected) {
                check(expected.getMessage().contains("exceeds"), expected.getMessage());
            }
            check(n("SELECT COUNT(*) FROM CustomerPayment WHERE InvoiceID=13") == 0, "payment saved");
        });
        kase("PAY-12 partial payment on an invoice that is not yet due is PartiallyPaid", () -> {
            int inv = n("SELECT MAX(InvoiceID) FROM Invoice");   // created by SO tests? fall back below
            check(Billing.status(new BigDecimal("100"), new BigDecimal("40"), LocalDate.now().plusDays(10))
                    .equals("PartiallyPaid"), "rule");
            check(Billing.status(new BigDecimal("100"), BigDecimal.ZERO, LocalDate.now().plusDays(10)).equals("Open"), "rule");
            check(Billing.status(new BigDecimal("100"), new BigDecimal("100"), LocalDate.now().minusDays(10)).equals("Paid"), "rule");
            check(inv > 0, "no invoices");
        });

        SupplierInvoicesTab st = show(new SupplierInvoicesTab());
        Body pay7 = () -> {
            st.refresh();
            select(st, "invTable", r -> { try { return id(r) == 7; } catch (Exception e) { return false; } });
            call(st, "openPaymentDialog");
        };
        kase("SPAY-1 supplier overpayment blocked", () -> {
            form(pay7, f -> f.set("Amount", "11200.01").click("Save"));
            expectMsg("exceeds");
        });
        kase("SPAY-2 supplier partial payment saved", () -> {
            form(pay7, f -> f.set("Amount", "200").click("Save"));
            check(n("SELECT SUM(Amount) FROM SupplierPayment WHERE SuppInvoiceID=7") == 200, "not saved");
            check("Overdue".equals(s("SELECT Status FROM SupplierInvoice WHERE SuppInvoiceID=7")), "status");
        });
    }

    // ---------------------------------------------------------------- customer portal
    static void customerPortal() throws Exception {
        Session.loginAsCustomer(4, "Beit Jala Pharmacy");
        Cart.clear();
        CustomerCartTab cart = show(new CustomerCartTab());
        kase("CP-1 empty cart cannot be ordered", () -> {
            call(cart, "placeOrder");
            expectMsg("Add at least one item");
        });
        kase("CP-2 paying more than the order total", () -> {
            Cart.add(new Cart.Item(4, "Dilax", 10, new BigDecimal("3.40")));
            cart.refresh();
            ((TextField) field(cart, "payAmount")).setText("999999");
            call(cart, "placeOrder");
            expectMsg("at most");
        });
        kase("CP-3 negative payment amount", () -> {
            ((TextField) field(cart, "payAmount")).setText("-1");
            call(cart, "placeOrder");
            expectMsg("0 or more");
        });
        kase("CP-4 partial payment creates a PartiallyPaid invoice", () -> {
            ((TextField) field(cart, "payAmount")).setText("10");
            call(cart, "placeOrder");
            int o = n("SELECT MAX(OrderID) FROM SalesOrder");
            check(n("SELECT CustomerID FROM SalesOrder WHERE OrderID=" + o) == 4, "wrong customer");
            check("PartiallyPaid".equals(s("SELECT Status FROM Invoice WHERE OrderID=" + o)), "status");
            check(Cart.isEmpty(), "cart not emptied");
        });
        kase("CP-5 customer cannot cancel a part-paid order", () -> {
            int o = n("SELECT MAX(OrderID) FROM SalesOrder");
            CustomerMyOrdersTab my = show(new CustomerMyOrdersTab());
            select(my, "ordersTable", r -> { try { return id(r) == o; } catch (Exception e) { return false; } });
            call(my, "cancelOrder");
            expectMsg("already made a payment");
        });
        kase("CP-6 customer only sees their own orders", () -> {
            CustomerMyOrdersTab my = show(new CustomerMyOrdersTab());
            int shown = ((TableView<?>) field(my, "ordersTable")).getItems().size();
            check(shown == n("SELECT COUNT(*) FROM SalesOrder WHERE CustomerID=4"), "shown " + shown);
        });
        kase("CP-7 a cancelled order cannot be confirmed as delivered", () -> {
            Cart.add(new Cart.Item(4, "Dilax", 1, new BigDecimal("3.40")));
            cart.refresh();
            ((TextField) field(cart, "payAmount")).setText("0");
            call(cart, "placeOrder");
            int o = n("SELECT MAX(OrderID) FROM SalesOrder");
            CustomerMyOrdersTab my = show(new CustomerMyOrdersTab());
            select(my, "ordersTable", r -> { try { return id(r) == o; } catch (Exception e) { return false; } });
            call(my, "cancelOrder");
            my.refresh();
            select(my, "ordersTable", r -> { try { return id(r) == o; } catch (Exception e) { return false; } });
            call(my, "confirmDelivery");
            expectMsg("ancelled");
            check("Cancelled".equals(s("SELECT Status FROM SalesOrder WHERE OrderID=" + o)), "status changed");
        });

        CustomerPortal portal = new CustomerPortal(new Stage(), () -> {});
        Body pw = () -> call(portal, "openChangePasswordDialog", new Stage());
        kase("CP-8 wrong current password", () -> {
            form(pw, f -> f.set("Current password", "nope").set("New password", "newpass9")
                           .set("Confirm new", "newpass9").click("Update password"));
            expectMsg("incorrect");
        });
        kase("CP-9 new passwords do not match", () -> {
            form(pw, f -> f.set("Current password", "customer4").set("New password", "newpass9")
                           .set("Confirm new", "newpass8").click("Update password"));
            expectMsg("don't match");
        });
        kase("CP-10 new password too short", () -> {
            form(pw, f -> f.set("Current password", "customer4").set("New password", "abc")
                           .set("Confirm new", "abc").click("Update password"));
            expectMsg("at least 4");
        });
        kase("CP-11 password change works and is case-sensitive", () -> {
            form(pw, f -> f.set("Current password", "customer4").set("New password", "NewPass9")
                           .set("Confirm new", "NewPass9").click("Update password"));
            Main main = new Main();
            check((boolean) call(main, "verifyCustomerPassword", 4, "NewPass9"), "new password rejected");
            check(!(boolean) call(main, "verifyCustomerPassword", 4, "newpass9"), "wrong case accepted");
            check(!(boolean) call(main, "verifyCustomerPassword", 4, "customer4"), "old password still works");
        });
        Session.loginAsEmployee(11, "admin", "System Administrator", "General Manager");
    }

    // ---------------------------------------------------------------- dashboard
    static void dashboard() throws Exception {
        kase("DSH-1 dashboard KPIs match the database", () -> {
            DashboardTab d = show(new DashboardTab());
            d.refresh();
            check(((Label) field(d, "kpiOverdue")).getText().equals(
                String.valueOf(n("SELECT COUNT(*) FROM Invoice WHERE Status='Overdue'"))), "overdue KPI");
            check(n("SELECT COUNT(*) FROM Invoice WHERE Status='Overdue'") > 0, "no overdue invoices");
            check(((Label) field(d, "kpiOpenPOs")).getText().equals(
                String.valueOf(n("SELECT COUNT(*) FROM PurchaseOrder WHERE Status='Pending'"))), "open PO KPI");
        });
        kase("DSH-2 every report loads without errors", () -> {
            ReportsTab r = show(new ReportsTab());
            r.refresh();
            noErrors();
        });
    }
}
