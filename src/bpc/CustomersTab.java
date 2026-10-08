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

public class CustomersTab extends Tab implements Refreshable {

    public record Customer(int id, String username, String name, String type, String city,
                           String phone, String email, String terms) {}

    private final ObservableList<Customer> master = FXCollections.observableArrayList();
    private final TableView<Customer> table = new TableView<>();
    private final TextField search = new TextField();
    private final ComboBox<String> typeFilter = new ComboBox<>();

    public CustomersTab() {
        setText("Customers");
        setClosable(false);
        
        TableColumn<Customer, Number> cId = col("ID", 60, c -> new SimpleObjectProperty<>(c.id()));
        TableColumn<Customer, String> cUser = col("Username", 110, c -> new SimpleStringProperty(c.username()));
        TableColumn<Customer, String> cName = col("Customer", 220, c -> new SimpleStringProperty(c.name()));
        TableColumn<Customer, String> cType = col("Type", 110, c -> new SimpleStringProperty(c.type()));
        TableColumn<Customer, String> cCity = col("City", 120, c -> new SimpleStringProperty(c.city()));
        TableColumn<Customer, String> cPhone = col("Phone", 140, c -> new SimpleStringProperty(c.phone()));
        TableColumn<Customer, String> cEmail = col("Email", 200, c -> new SimpleStringProperty(c.email()));
        TableColumn<Customer, String> cTerms = col("Payment", 100, c -> new SimpleStringProperty(c.terms()));
        table.getColumns().addAll(cId, cUser, cName, cType, cCity, cPhone, cEmail, cTerms);

        search.setPromptText("Search by name");
        typeFilter.getItems().setAll("All","Hospital","Clinic","Pharmacy","Distributor","Export");
        typeFilter.getSelectionModel().selectFirst();

        FilteredList<Customer> filtered = new FilteredList<>(master, c -> true);
        table.setItems(filtered);
        Runnable applyFilter = () -> {
            String q = search.getText() == null ? "" : search.getText().toLowerCase().trim();
            String t = typeFilter.getValue();
            filtered.setPredicate(c -> {
                boolean okType = "All".equals(t) || t == null || t.equals(c.type());
                if (!okType) return false;
                if (q.isEmpty()) return true;
                String name = c.name() == null ? "" : c.name().toLowerCase();
                String city = c.city() == null ? "" : c.city().toLowerCase();
                return name.contains(q) || city.contains(q);
            });
        };
        search.textProperty().addListener((o,a,b) -> applyFilter.run());
        typeFilter.valueProperty().addListener((o,a,b) -> applyFilter.run());

        boolean isManager = Session.isManager();

        Region grow = new Region(); HBox.setHgrow(grow, Priority.ALWAYS);
        HBox toolbar = new HBox(10, heading("Customers"), grow, search, typeFilter);

        if (isManager) {
            Button addBtn  = primaryButton("Add"); addBtn.setOnAction(e -> openForm(null));
            Button editBtn = new Button("Edit");
            editBtn.setOnAction(e -> {
                Customer sel = table.getSelectionModel().getSelectedItem();
                if (sel == null) Util.warn("No selection","Select a customer first.");
                else openForm(sel);
            });
            Button resetPwBtn = new Button("Reset Password");
            resetPwBtn.setOnAction(e -> doResetPassword());
            Button delBtn = new Button("Delete");
            delBtn.setStyle("-fx-background-color: #c0392b; -fx-text-fill: white;");
            delBtn.setOnAction(e -> doDelete());
            toolbar.getChildren().addAll(addBtn, editBtn, resetPwBtn, delBtn);
        } else {
            Label ro = new Label("Read-only view");
            ro.setStyle("-fx-text-fill: #64748b; -fx-font-style: italic; -fx-font-size: 12px;");
            toolbar.getChildren().add(ro);
        }
        toolbar.setAlignment(Pos.CENTER_LEFT);

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(14));
        root.setTop(toolbar);
        BorderPane.setMargin(toolbar, new Insets(0,0,10,0));
        root.setCenter(table);
        setContent(root);

        refresh();
    }

    @Override
    public void refresh() {
        master.clear();
        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT CustomerID, Username, CustomerName, Type, City, Phone, Email, PaymentTerms "
              + "FROM Customer ORDER BY CustomerID");
             ResultSet rs = ps.executeQuery()) {
            List<Customer> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(new Customer(
                    rs.getInt(1),    
                    rs.getString(2),  
                    rs.getString(3),  
                    rs.getString(4), 
                    rs.getString(5),  
                    rs.getString(6),
                    rs.getString(7), 
                    rs.getString(8)   
                ));
            }
            master.setAll(rows);
        } catch (SQLException ex) { Util.error("DB error", ex.getMessage()); }
    }

    private void doDelete() {
        Customer sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) { Util.warn("No selection","Select a customer first."); return; }
        if (!Util.confirm("Delete","Delete '" + sel.name() + "'?")) return;
        try (PreparedStatement ps = DB.get().prepareStatement("DELETE FROM Customer WHERE CustomerID=?")) {
            ps.setInt(1, sel.id());
            int rows = ps.executeUpdate();
            if (rows == 0) {
                Util.info("Already gone", "This customer was already removed from the database.");
            }
            refresh();
        } catch (SQLIntegrityConstraintViolationException ex) {
            Util.error("Cannot delete",
                "This customer has existing sales orders in the database.\n" +
                "Delete or reassign those orders first.");
        } catch (SQLException ex) {
            Util.error("Delete failed", ex.getMessage());
        }
    }

    private void doResetPassword() {
        Customer sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) { Util.warn("No selection","Select a customer first."); return; }

        PasswordField p1 = new PasswordField(); p1.setPromptText("New password");
        PasswordField p2 = new PasswordField(); p2.setPromptText("Confirm new password");
        Label err = new Label(); err.setStyle("-fx-text-fill: #c0392b;");

        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("Reset password - " + sel.name());

        Button save = primaryButton("Save");
        Button cancel = new Button("Cancel");
        save.setOnAction(e -> {
            String a = p1.getText() == null ? "" : p1.getText();
            String b = p2.getText() == null ? "" : p2.getText();
            String problem = Validate.password(a);
            if (problem != null) { err.setText(problem); return; }
            if (!a.equals(b)){ err.setText("The two passwords do not match."); return; }
            try (PreparedStatement ps = DB.get().prepareStatement(
                    "UPDATE Customer SET Password=? WHERE CustomerID=?")) {
                ps.setString(1, a);
                ps.setInt   (2, sel.id());
                ps.executeUpdate();
                Util.info("Password reset",
                    "New password set for '" + sel.name() + "'.\n" +
                    "Share it with the customer through a secure channel.");
                dialog.close();
            } catch (SQLException ex) { err.setText("Save failed: " + ex.getMessage()); }
        });
        cancel.setOnAction(e -> dialog.close());

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(10);
        grid.addRow(0, new Label("New password"),     p1);
        grid.addRow(1, new Label("Confirm password"), p2);

        Region grow = new Region(); HBox.setHgrow(grow, Priority.ALWAYS);
        HBox buttons = new HBox(10, grow, cancel, save);

        Label customerLbl = new Label("Customer: " + sel.name() + "  (username: " + sel.username() + ")");
        customerLbl.setStyle("-fx-text-fill: #64748b; -fx-font-size: 12px;");

        VBox box = new VBox(12, heading("Reset password"), customerLbl, grid, err, buttons);
        box.setPadding(new Insets(20));
        box.setPrefWidth(420);
        dialog.setScene(new Scene(box));
        dialog.showAndWait();
    }

    private void openForm(Customer existing) {
        boolean isAdd = (existing == null);

        TextField username = new TextField();
        PasswordField password = new PasswordField();   
        TextField name  = new TextField();
        ComboBox<String> type = new ComboBox<>(FXCollections.observableArrayList(
            "Hospital","Clinic","Pharmacy","Distributor","Export"));
        TextField city  = new TextField();
        TextField phone = new TextField();
        TextField email = new TextField();
        ComboBox<String> terms = new ComboBox<>(FXCollections.observableArrayList(
            "Net 15","Net 30","Net 45","Net 60","Cash on Delivery"));

        if (isAdd) {
            type.getSelectionModel().select("Hospital");
            terms.getSelectionModel().select("Net 30");
            password.setPromptText("Initial password (required)");
        } else {
            username.setText(existing.username());
            name.setText(existing.name());
            type.getSelectionModel().select(existing.type());
            city.setText(existing.city());
            phone.setText(existing.phone());
            email.setText(existing.email());
            terms.getSelectionModel().select(existing.terms());
        }

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(10);

        int row = 0;
        grid.addRow(row++, new Label("Username *"),  username);
        if (isAdd) {
            grid.addRow(row++, new Label("Password *"), password);
            Label credHint = new Label("These credentials will be given to the customer.");
            credHint.setStyle("-fx-text-fill: #64748b; -fx-font-size: 11px;");
            credHint.setWrapText(true);
            credHint.setMaxWidth(280);
            grid.addRow(row++, new Label(""), credHint);
        } else {
            Label resetHint = new Label("To change the password use the Reset Password button.");
            resetHint.setStyle("-fx-text-fill: #64748b; -fx-font-size: 11px;");
            resetHint.setWrapText(true);
            resetHint.setMaxWidth(280);
            grid.addRow(row++, new Label(""), resetHint);
        }
        grid.addRow(row++, new Label("Name *"),name);
        grid.addRow(row++, new Label("Type *"),type);
        grid.addRow(row++, new Label("City *"),city);
        grid.addRow(row++, new Label("Phone"),phone);
        grid.addRow(row++, new Label("Email"),email);
        grid.addRow(row++, new Label("Payment terms"), terms);

        Label err = new Label(); err.setStyle("-fx-text-fill: #c0392b;");
        Button save = primaryButton("Save");
        Button cancel = new Button("Cancel");

        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle(isAdd ? "New customer" : "Edit customer #" + existing.id());

        save.setOnAction(e -> {
            String uName = username.getText() == null ? "" : username.getText().trim();
            String pwd   = password.getText() == null ? "" : password.getText();
            String emailVal = blankToNull(email.getText());

            if (name.getText().isBlank() || city.getText().isBlank() || type.getValue()==null) {
                err.setText("Name, type and city are required."); return;
            }
            String problem = Validate.first(
                Validate.username(uName),
                isAdd ? Validate.password(pwd) : null,
                Validate.maxLength("Name", name.getText(), 150),
                Validate.maxLength("City", city.getText(), 80),
                Validate.phone(phone.getText()),
                Validate.email(emailVal));
            if (problem != null) { err.setText(problem); return; }
            try {
                String phoneVal = blankToNull(phone.getText());

                if (isAdd) {
                    try (PreparedStatement ps = DB.get().prepareStatement(
                        "INSERT INTO Customer "
                      + "(Username, Password, CustomerName, Type, City, Phone, Email, PaymentTerms) "
                      + "VALUES (?,?,?,?,?,?,?,?)")) {
                        ps.setString(1, uName);
                        ps.setString(2, pwd);
                        ps.setString(3, name.getText().trim());
                        ps.setString(4, type.getValue());
                        ps.setString(5, city.getText().trim());
                        ps.setString(6, phoneVal);
                        ps.setString(7, emailVal);
                        ps.setString(8, terms.getValue());
                        ps.executeUpdate();
                    }
                } else {
                    try (PreparedStatement ps = DB.get().prepareStatement(
                        "UPDATE Customer SET Username=?, CustomerName=?, Type=?, City=?, "
                      + "Phone=?, Email=?, PaymentTerms=? WHERE CustomerID=?")) {
                        ps.setString(1, uName);
                        ps.setString(2, name.getText().trim());
                        ps.setString(3, type.getValue());
                        ps.setString(4, city.getText().trim());
                        ps.setString(5, phoneVal);
                        ps.setString(6, emailVal);
                        ps.setString(7, terms.getValue());
                        ps.setInt   (8, existing.id());
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
        cancel.setOnAction(e -> dialog.close());

        Region grow = new Region(); HBox.setHgrow(grow, Priority.ALWAYS);
        HBox buttons = new HBox(10, grow, cancel, save);

        VBox box = new VBox(12, heading(isAdd ? "New customer" : "Edit customer"),
                            grid, err, buttons);
        box.setPadding(new Insets(20));
        box.setPrefWidth(460);
        dialog.setScene(new Scene(box));
        dialog.showAndWait();
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static <T> TableColumn<Customer,T> col(String text, double width,
            java.util.function.Function<Customer, javafx.beans.value.ObservableValue<T>> getter) {
        TableColumn<Customer, T> c = new TableColumn<>(text);
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