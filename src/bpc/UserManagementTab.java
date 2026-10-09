package bpc;

import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class UserManagementTab extends Tab implements Refreshable {

    public record Employee(int id, String username, String name, String role,
                           String phone, String email, Integer warehouseId,
                           String warehouseName, double salary, java.sql.Date hireDate) {}

    private final ObservableList<Employee> master = FXCollections.observableArrayList();
    private final TableView<Employee> table = new TableView<>();
    private final TextField search = new TextField();
    private final ComboBox<String> roleFilter = new ComboBox<>();


    private final ObservableList<WarehouseOpt> warehouseCache = FXCollections.observableArrayList();

    public UserManagementTab() {
        setText("User Management");
        setClosable(false);

        TableColumn<Employee, Number> cId   = col("ID",        60,  e -> new SimpleObjectProperty<>(e.id()));
        TableColumn<Employee, String> cUser = col("Username",  110, e -> new SimpleStringProperty(e.username()));
        TableColumn<Employee, String> cName = col("Name",      200, e -> new SimpleStringProperty(e.name()));
        TableColumn<Employee, String> cRole = col("Role",      180, e -> new SimpleStringProperty(e.role()));
        TableColumn<Employee, String> cWh   = col("Warehouse", 200, e -> new SimpleStringProperty(
                e.warehouseName() == null ? "-" : e.warehouseName()));
        TableColumn<Employee, String> cPhone = col("Phone",    140, e -> new SimpleStringProperty(e.phone()));
        TableColumn<Employee, String> cEmail = col("Email",    200, e -> new SimpleStringProperty(e.email()));
        TableColumn<Employee, String> cSalary = col("Salary",  100, e -> new SimpleStringProperty(
                String.format("%.2f", e.salary())));
        table.getColumns().addAll(cId, cUser, cName, cRole, cWh, cPhone, cEmail, cSalary);

        search.setPromptText("Search by name or username");
        roleFilter.getItems().setAll("All", "General Manager", "Warehouse Manager",
                "Sales Manager", "Procurement Officer",
                "Sales Representative", "Production Officer");
        roleFilter.getSelectionModel().selectFirst();

        FilteredList<Employee> filtered = new FilteredList<>(master, e -> true);
        table.setItems(filtered);
        Runnable applyFilter = () -> {
            String q = search.getText() == null ? "" : search.getText().toLowerCase().trim();
            String r = roleFilter.getValue();
            filtered.setPredicate(e -> {
                boolean okRole = "All".equals(r) || r == null || r.equals(e.role());
                if (!okRole) return false;
                if (q.isEmpty()) return true;
                String n = e.name() == null ? "" : e.name().toLowerCase();
                String u = e.username() == null ? "" : e.username().toLowerCase();
                return n.contains(q) || u.contains(q);
            });
        };
        search.textProperty().addListener((o, a, b) -> applyFilter.run());
        roleFilter.valueProperty().addListener((o, a, b) -> applyFilter.run());

        Button addBtn = primaryButton("Add Employee");
        addBtn.setOnAction(ev -> openForm(null));

        Button editBtn = new Button("Edit");
        editBtn.setOnAction(ev -> {
            Employee sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) Util.warn("No selection", "Select an employee first.");
            else openForm(sel);
        });

        Button resetPwBtn = new Button("Reset Password");
        resetPwBtn.setOnAction(ev -> doResetPassword());

        Button delBtn = new Button("Delete");
        delBtn.setStyle("-fx-background-color: #c0392b; -fx-text-fill: white;");
        delBtn.setOnAction(ev -> doDelete());

        Region grow = new Region(); HBox.setHgrow(grow, Priority.ALWAYS);
        HBox toolbar = new HBox(10,
                heading("Employee Accounts"), grow,
                search, roleFilter, addBtn, editBtn, resetPwBtn, delBtn);
        toolbar.setAlignment(Pos.CENTER_LEFT);


        BorderPane root = new BorderPane();
        root.setPadding(new Insets(14));
        VBox top = new VBox(6, toolbar);
        root.setTop(top);
        BorderPane.setMargin(top, new Insets(0, 0, 10, 0));
        root.setCenter(table);
        setContent(root);

        refresh();
    }

    @Override
    public void refresh() {
        master.clear();
        warehouseCache.clear();
        try (PreparedStatement wps = DB.get().prepareStatement(
                "SELECT WarehouseID, WarehouseName FROM Warehouse ORDER BY WarehouseName");
             ResultSet wrs = wps.executeQuery()) {
            while (wrs.next()) {
                warehouseCache.add(new WarehouseOpt(wrs.getInt(1), wrs.getString(2)));
            }
        } catch (SQLException ex) {
            Util.error("DB error", ex.getMessage());
        }

        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT e.EmpID, e.Username, e.EmpName, e.Role, e.Phone, e.Email, "
              + "       e.WarehouseID, w.WarehouseName, e.Salary, e.HireDate "
              + "FROM Employee e LEFT JOIN Warehouse w ON w.WarehouseID = e.WarehouseID "
              + "ORDER BY e.EmpID");
             ResultSet rs = ps.executeQuery()) {
            List<Employee> rows = new ArrayList<>();
            while (rs.next()) {
                Integer whId = rs.getObject("WarehouseID") == null ? null : rs.getInt("WarehouseID");
                rows.add(new Employee(
                    rs.getInt("EmpID"),
                    rs.getString("Username"),
                    rs.getString("EmpName"),
                    rs.getString("Role"),
                    rs.getString("Phone"),
                    rs.getString("Email"),
                    whId,
                    rs.getString("WarehouseName"),
                    rs.getDouble("Salary"),
                    rs.getDate("HireDate")
                ));
            }
            master.setAll(rows);
        } catch (SQLException ex) {
            Util.error("DB error", ex.getMessage());
        }
    }

    private void doDelete() {
        Employee sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) { Util.warn("No selection", "Select an employee first."); return; }

        if (sel.id() == Session.empId()) {
            Util.warn("Not allowed", "You cannot delete your own account while logged in.");
            return;
        }
        if ("General Manager".equals(sel.role())
                && master.stream().filter(e -> "General Manager".equals(e.role())).count() <= 1) {
            Util.warn("Not allowed", "This is the only General Manager account - it cannot be deleted.");
            return;
        }
        if (!Util.confirm("Delete", "Delete '" + sel.name() + "' (username " + sel.username() + ")?")) return;
        try (PreparedStatement ps = DB.get().prepareStatement("DELETE FROM Employee WHERE EmpID=?")) {
            ps.setInt(1, sel.id());
            ps.executeUpdate();
            refresh();
        } catch (SQLIntegrityConstraintViolationException ex) {
            Util.error("Cannot delete",
                "This employee has activity in the database "
              + "(goods receipts, production orders, or stock transfers).\n"
              + "Reassign those records before deleting this account.");
        } catch (SQLException ex) {
            Util.error("Delete failed", ex.getMessage());
        }
    }

    private void doResetPassword() {
        Employee sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) { Util.warn("No selection", "Select an employee first."); return; }

        PasswordField p1 = new PasswordField(); p1.setPromptText("New password");
        PasswordField p2 = new PasswordField(); p2.setPromptText("Confirm new password");
        Label err = new Label(); err.setStyle("-fx-text-fill: #c0392b;");

        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("Reset password - " + sel.name());

        Button save = primaryButton("Save");
        Button cancel = new Button("Cancel");
        save.setOnAction(ev -> {
            String a = p1.getText() == null ? "" : p1.getText();
            String b = p2.getText() == null ? "" : p2.getText();
            String problem = Validate.password(a);
            if (problem != null) { err.setText(problem); return; }
            if (!a.equals(b))   { err.setText("The two passwords do not match."); return; }
            try (PreparedStatement ps = DB.get().prepareStatement(
                    "UPDATE Employee SET Password=? WHERE EmpID=?")) {
                ps.setString(1, Passwords.hash(a));
                ps.setInt   (2, sel.id());
                ps.executeUpdate();
                Util.info("Password reset",
                    "New password set for '" + sel.name() + "'.\n" +
                    "Share it with them through a secure channel.");
                dialog.close();
            } catch (SQLException ex) { err.setText("Save failed: " + ex.getMessage()); }
        });
        cancel.setOnAction(ev -> dialog.close());

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(10);
        grid.addRow(0, new Label("New password"),     p1);
        grid.addRow(1, new Label("Confirm password"), p2);

        Region grow = new Region(); HBox.setHgrow(grow, Priority.ALWAYS);
        HBox buttons = new HBox(10, grow, cancel, save);

        Label empLbl = new Label("Employee: " + sel.name()
            + "  (username: " + sel.username() + ", role: " + sel.role() + ")");
        empLbl.setStyle("-fx-text-fill: #64748b; -fx-font-size: 12px;");

        VBox box = new VBox(12, heading("Reset password"), empLbl, grid, err, buttons);
        box.setPadding(new Insets(20));
        box.setPrefWidth(440);
        dialog.setScene(new Scene(box));
        dialog.showAndWait();
    }

    private void openForm(Employee existing) {
        boolean isAdd = (existing == null);

        TextField username = new TextField();
        PasswordField password = new PasswordField();   
        TextField name = new TextField();
        ComboBox<String> role = new ComboBox<>(FXCollections.observableArrayList(
                "General Manager", "Warehouse Manager", "Sales Manager",
                "Procurement Officer", "Sales Representative", "Production Officer"));
        ComboBox<WarehouseOpt> warehouse = new ComboBox<>();
        warehouse.setItems(FXCollections.observableArrayList(warehouseCache));
        warehouse.getItems().add(0, new WarehouseOpt(0, "(none)"));   
        TextField phone = new TextField();
        TextField email = new TextField();
        TextField salary = new TextField();
        DatePicker hireDate = new DatePicker(java.time.LocalDate.now());

        if (isAdd) {
            role.getSelectionModel().select("Sales Representative");
            warehouse.getSelectionModel().selectFirst();  
            password.setPromptText("Initial password (required)");
        } else {
            username.setText(existing.username());
            name.setText(existing.name());
            role.getSelectionModel().select(existing.role());
            WarehouseOpt match = warehouse.getItems().get(0);
            for (WarehouseOpt w : warehouse.getItems()) {
                if (existing.warehouseId() != null && w.id() == existing.warehouseId()) {
                    match = w; break;
                }
            }
            warehouse.getSelectionModel().select(match);
            phone.setText(existing.phone());
            email.setText(existing.email());
            salary.setText(String.format("%.2f", existing.salary()));
            if (existing.hireDate() != null) hireDate.setValue(existing.hireDate().toLocalDate());
        }

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(10);

        int row = 0;
        grid.addRow(row++, new Label("Username *"),  username);
        if (isAdd) {
            grid.addRow(row++, new Label("Password *"), password);
            Label credHint = new Label("These credentials will be given to the employee.");
            credHint.setStyle("-fx-text-fill: #64748b; -fx-font-size: 11px;");
            credHint.setWrapText(true);
            credHint.setMaxWidth(300);
            grid.addRow(row++, new Label(""), credHint);
        } 
        grid.addRow(row++, new Label("Full name *"), name);
        grid.addRow(row++, new Label("Role *"),      role);
        grid.addRow(row++, new Label("Warehouse"),   warehouse);
        grid.addRow(row++, new Label("Phone"),       phone);
        grid.addRow(row++, new Label("Email"),       email);
        grid.addRow(row++, new Label("Salary *"),    salary);
        grid.addRow(row++, new Label("Hire date *"), hireDate);

        Label err = new Label(); err.setStyle("-fx-text-fill: #c0392b;");
        Button save = primaryButton("Save");
        Button cancel = new Button("Cancel");

        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle(isAdd ? "New employee" : "Edit employee #" + existing.id());

        save.setOnAction(ev -> {
            String uName = username.getText() == null ? "" : username.getText().trim();
            String pwd   = password.getText() == null ? "" : password.getText();
            String nm    = name.getText() == null ? "" : name.getText().trim();
            String rl    = role.getValue();
            WarehouseOpt wh = warehouse.getValue();
            String ph    = blankToNull(phone.getText());
            String em    = blankToNull(email.getText());
            String salStr = salary.getText() == null ? "" : salary.getText().trim();

            String problem = Validate.first(
                Validate.username(uName),
                isAdd ? Validate.password(pwd) : null,
                Validate.required("Name", nm),
                Validate.maxLength("Name", nm, 120),
                rl == null ? "Please choose a role." : null,
                Validate.phone(ph),
                Validate.email(em),
                hireDate.getValue() == null ? "Hire date is required." : null,
                hireDate.getValue() != null && hireDate.getValue().isAfter(java.time.LocalDate.now())
                    ? "Hire date cannot be in the future." : null);
            if (problem != null) { err.setText(problem); return; }
            if (!isAdd && existing.id() == Session.empId() && !rl.equals(existing.role())) {
                err.setText("You cannot change your own role while signed in."); return;
            }

            double sal;
            try { sal = Validate.money("Salary", salStr, 8).doubleValue(); }
            catch (IllegalArgumentException ex) { err.setText(ex.getMessage()); return; }
            if (sal < 0) { err.setText("Salary cannot be negative."); return; }

            Integer whId = (wh == null || wh.id() == 0) ? null : wh.id();

            try {
                if (isAdd) {
                    try (PreparedStatement ps = DB.get().prepareStatement(
                            "INSERT INTO Employee "
                          + "(Username, Password, EmpName, Role, Phone, Email, WarehouseID, Salary, HireDate) "
                          + "VALUES (?,?,?,?,?,?,?,?,?)")) {
                        ps.setString(1, uName);
                        ps.setString(2, Passwords.hash(pwd));
                        ps.setString(3, nm);
                        ps.setString(4, rl);
                        ps.setString(5, ph);
                        ps.setString(6, em);
                        if (whId == null) ps.setNull(7, Types.INTEGER); else ps.setInt(7, whId);
                        ps.setDouble(8, sal);
                        ps.setDate(9, java.sql.Date.valueOf(hireDate.getValue()));
                        ps.executeUpdate();
                    }
                } else {
                    try (PreparedStatement ps = DB.get().prepareStatement(
                            "UPDATE Employee SET Username=?, EmpName=?, Role=?, Phone=?, Email=?, "
                          + "WarehouseID=?, Salary=?, HireDate=? WHERE EmpID=?")) {
                        ps.setString(1, uName);
                        ps.setString(2, nm);
                        ps.setString(3, rl);
                        ps.setString(4, ph);
                        ps.setString(5, em);
                        if (whId == null) ps.setNull(6, Types.INTEGER); else ps.setInt(6, whId);
                        ps.setDouble(7, sal);
                        ps.setDate(8, java.sql.Date.valueOf(hireDate.getValue()));
                        ps.setInt   (9, existing.id());
                        ps.executeUpdate();
                    }
                }
                refresh();
                dialog.close();
            } catch (SQLIntegrityConstraintViolationException ex) {
                err.setText("That username is already taken.");
            } catch (SQLException ex) {
                err.setText("Save failed: " + ex.getMessage());
            }
        });
        cancel.setOnAction(ev -> dialog.close());

        Region grow = new Region(); HBox.setHgrow(grow, Priority.ALWAYS);
        HBox buttons = new HBox(10, grow, cancel, save);

        VBox box = new VBox(12,
            heading(isAdd ? "New employee" : "Edit employee"),
            grid, err, buttons);
        box.setPadding(new Insets(20));
        box.setPrefWidth(500);
        dialog.setScene(new Scene(box));
        dialog.showAndWait();
    }

    public record WarehouseOpt(int id, String name) {
        @Override public String toString() { return name; }
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static <T> TableColumn<Employee, T> col(String text, double width,
            java.util.function.Function<Employee, javafx.beans.value.ObservableValue<T>> getter) {
        TableColumn<Employee, T> c = new TableColumn<>(text);
        c.setPrefWidth(width);
        c.setCellValueFactory(cd -> getter.apply(cd.getValue()));
        return c;
    }
    private static Button primaryButton(String text) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color: #0d8a8a; -fx-text-fill: white; -fx-font-weight: bold;");
        return b;
    }
    private static Label heading(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: #1a3a3a;");
        return l;
    }
}
