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

import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

public class SuppliersTab extends Tab implements Refreshable {

    public record Supplier(int id, String name, String country, String phone, String email, BigDecimal rating) {}
    public record MaterialRow(int id, String name, String category, String unit) {}

    private final ObservableList<Supplier> master = FXCollections.observableArrayList();
    private final ObservableList<MaterialRow> materials = FXCollections.observableArrayList();
    private final TableView<Supplier> table = new TableView<>();
    private final TableView<MaterialRow> materialsTable = new TableView<>();
    private final TextField search = new TextField();
    private final ComboBox<String> countryFilter = new ComboBox<>();

    public SuppliersTab() {
        setText("Suppliers");
        setClosable(false);

        table.setItems(new FilteredList<>(master, s -> true));
        table.getColumns().addAll(
            col("ID",60,(Supplier s) -> new SimpleObjectProperty<>(s.id())),
            col("Name",240,s -> new SimpleStringProperty(s.name())),
            col("Country",140,s -> new SimpleStringProperty(s.country())),
            col("Phone",140,s -> new SimpleStringProperty(s.phone())),
            col("Email",220,s -> new SimpleStringProperty(s.email())),
            col("Rating",80,s -> new SimpleStringProperty(s.rating() == null ? "—" : s.rating().toString()))
        );
        table.getSelectionModel().selectedItemProperty().addListener((o, a, sel) -> loadMaterials(sel));

        materialsTable.setItems(materials);
        materialsTable.getColumns().addAll(
            col("Material ID", 90, (MaterialRow m) -> new SimpleObjectProperty<>(m.id())),
            col("Material", 240, m -> new SimpleStringProperty(m.name())),
            col("Category", 120, m -> new SimpleStringProperty(m.category())),
            col("Unit", 80, m -> new SimpleStringProperty(m.unit()))
        );
        materialsTable.setPrefHeight(180);

        Button linkBtn   = primaryButton("Link material");
        Button unlinkBtn = new Button("Unlink material");
        unlinkBtn.setStyle("-fx-background-color: #c0392b; -fx-text-fill: white;");
        linkBtn.setOnAction(e -> openLinkMaterialDialog());
        unlinkBtn.setOnAction(e -> unlinkSelectedMaterial());
        Region mGrow = new Region(); HBox.setHgrow(mGrow, Priority.ALWAYS);
        HBox matToolbar = new HBox(10,
            heading2("Materials supplied by selected supplier:"),
            mGrow, linkBtn, unlinkBtn);
        matToolbar.setAlignment(Pos.CENTER_LEFT);

        search.setPromptText("Search by name or email");
        countryFilter.setPrefWidth(150);
        countryFilter.getItems().setAll("All");
        countryFilter.getSelectionModel().selectFirst();

        search.textProperty().addListener((o,a,b) -> applyFilter());
        countryFilter.valueProperty().addListener((o,a,b) -> applyFilter());

        Button addBtn  = primaryButton("Add"); addBtn.setOnAction(e -> openForm(null));
        Button editBtn = new Button("Edit");
        editBtn.setOnAction(e -> {
            Supplier sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) Util.warn("No selection", "Select a supplier first.");
            else openForm(sel);
        });
        Button delBtn = new Button("Delete");
        delBtn.setStyle("-fx-background-color: #c0392b; -fx-text-fill: white;");
        delBtn.setOnAction(e -> doDelete());

        Region grow = new Region(); HBox.setHgrow(grow, Priority.ALWAYS);
        HBox toolbar = new HBox(10, heading("Suppliers"), grow, search, countryFilter, addBtn, editBtn, delBtn);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(10, toolbar, table, matToolbar, materialsTable);
        root.setPadding(new Insets(14));
        VBox.setVgrow(table, Priority.ALWAYS);
        setContent(root);

        refresh();
    }

    @Override
    public void refresh() {
        master.clear();
        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT SupplierID, SupName, Country, Phone, Email, Rating " +
                "FROM Supplier ORDER BY SupplierID");
             ResultSet rs = ps.executeQuery()) {
            List<Supplier> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(new Supplier(
                    rs.getInt(1), rs.getString(2), rs.getString(3),
                    rs.getString(4), rs.getString(5), rs.getBigDecimal(6)));
            }
            master.setAll(rows);
            reloadCountryFilter();
            applyFilter();
        } catch (SQLException ex) { Util.error("DB error", ex.getMessage()); }
    }

    private void reloadCountryFilter() {
        String previous = countryFilter.getValue();
        TreeSet<String> countries = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        for (Supplier s : master) {
            if (s.country() != null && !s.country().isBlank()) {
                countries.add(s.country().trim());
            }
        }

        countryFilter.getItems().setAll("All");
        countryFilter.getItems().addAll(countries);

        if (previous != null && countryFilter.getItems().contains(previous)) {
            countryFilter.getSelectionModel().select(previous);
        } else {
            countryFilter.getSelectionModel().select("All");
        }
    }

    private void applyFilter() {
        String q = search.getText() == null ? "" : search.getText().toLowerCase().trim();
        String c = countryFilter.getValue();
        FilteredList<Supplier> fl = (FilteredList<Supplier>) table.getItems();
        fl.setPredicate(s -> {
            boolean okCountry = c == null || "All".equals(c) ||
                    (s.country() != null && c.equalsIgnoreCase(s.country().trim()));
            if (!okCountry) return false;
            if (q.isEmpty()) return true;
            String name  = s.name()  == null ? "" : s.name().toLowerCase();
            String email = s.email() == null ? "" : s.email().toLowerCase();
            return name.contains(q) || email.contains(q);
        });
    }

    private void loadMaterials(Supplier supplier) {
        materials.clear();
        if (supplier == null) return;
        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT rm.MaterialID, rm.MaterialName, rm.Category, rm.Unit " +
                "FROM SupplierMaterial sm " +
                "JOIN RawMaterial rm ON rm.MaterialID = sm.MaterialID " +
                "WHERE sm.SupplierID = ? ORDER BY rm.MaterialName")) {
            ps.setInt(1, supplier.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    materials.add(new MaterialRow(
                        rs.getInt(1), rs.getString(2),
                        rs.getString(3), rs.getString(4)));
                }
            }
        } catch (SQLException ex) { Util.error("DB error", ex.getMessage()); }
    }

    private void doDelete() {
        Supplier sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) { Util.warn("No selection", "Select a supplier first."); return; }
        if (!Util.confirm("Delete", "Delete supplier '" + sel.name() + "'?")) return;
        try (PreparedStatement ps = DB.get().prepareStatement("DELETE FROM Supplier WHERE SupplierID=?")) {
            ps.setInt(1, sel.id());
            ps.executeUpdate();
            refresh();
        } catch (SQLIntegrityConstraintViolationException ex) {
            Util.error("Cannot delete",
                "This supplier has existing purchase orders or material links.\n" +
                "Remove those first.");
        } catch (SQLException ex) {
            Util.error("Delete failed", ex.getMessage());
        }
    }

    private void openForm(Supplier existing) {
        TextField name = new TextField();
        TextField country = new TextField();
        TextField phone = new TextField();
        TextField email = new TextField();
        TextField rating = new TextField();

        if (existing != null) {
            name.setText(existing.name());
            country.setText(existing.country());
            phone.setText(existing.phone());
            email.setText(existing.email());
            rating.setText(existing.rating() == null ? "" : existing.rating().toString());
        }

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(10);
        grid.addRow(0, new Label("Name *"), name);
        grid.addRow(1, new Label("Country *"), country);
        grid.addRow(2, new Label("Phone"), phone);
        grid.addRow(3, new Label("Email"), email);
        grid.addRow(4, new Label("Rating (0-5)"), rating);

        Label err = new Label(); err.setStyle("-fx-text-fill: #c0392b;");
        Button save   = primaryButton("Save");
        Button cancel = new Button("Cancel");

        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle(existing == null ? "New supplier" : "Edit supplier #" + existing.id());

        save.setOnAction(e -> {
            if (name.getText().isBlank() || country.getText().isBlank()) {
                err.setText("Name and country are required."); return;
            }
            // Format validation: phone may only contain digits, '+', spaces,
            // dashes and parentheses; email (if provided) must look like
            // name@host.tld. Both are optional - empty is allowed.
            String phoneVal = blankToNull(phone.getText());
            String emailVal = blankToNull(email.getText());
            String problem = Validate.first(
                Validate.maxLength("Name", name.getText(), 120),
                Validate.maxLength("Country", country.getText(), 60),
                Validate.phone(phoneVal),
                Validate.email(emailVal));
            if (problem != null) { err.setText(problem); return; }
            if (!isValidPhone(phoneVal)) {
                err.setText("Phone may contain only digits, '+', spaces, '-' and '()'."); return;
            }
            if (!isValidEmail(emailVal)) {
                err.setText("Email must look like name@example.com"); return;
            }
            BigDecimal ratingVal = null;
            if (!rating.getText().isBlank()) {
                try {
                    ratingVal = new BigDecimal(rating.getText().trim());
                    if (ratingVal.stripTrailingZeros().scale() > 2) {
                        err.setText("Rating can have at most 2 decimal places."); return;
                    }
                    if (ratingVal.compareTo(BigDecimal.ZERO) < 0 ||
                        ratingVal.compareTo(new BigDecimal("5")) > 0) {
                        err.setText("Rating must be between 0 and 5."); return;
                    }
                } catch (NumberFormatException nfe) {
                    err.setText("Rating must be a number between 0 and 5."); return;
                }
            }
            try {
                String countryVal = normalizeCountry(country.getText());
                if (existing == null) {
                    try (PreparedStatement ps = DB.get().prepareStatement(
                        "INSERT INTO Supplier (SupName, Country, Phone, Email, Rating) " +
                        "VALUES (?,?,?,?,?)")) {
                        ps.setString(1, name.getText().trim());
                        ps.setString(2, countryVal);
                        ps.setString(3, phoneVal);
                        ps.setString(4, emailVal);
                        if (ratingVal == null) ps.setNull(5, Types.DECIMAL);
                        else ps.setBigDecimal(5, ratingVal);
                        ps.executeUpdate();
                    }
                } else {
                    try (PreparedStatement ps = DB.get().prepareStatement(
                        "UPDATE Supplier SET SupName=?, Country=?, Phone=?, Email=?, Rating=? " +
                        "WHERE SupplierID=?")) {
                        ps.setString(1, name.getText().trim());
                        ps.setString(2, countryVal);
                        ps.setString(3, phoneVal);
                        ps.setString(4, emailVal);
                        if (ratingVal == null) ps.setNull(5, Types.DECIMAL);
                        else ps.setBigDecimal(5, ratingVal);
                        ps.setInt(6, existing.id());
                        ps.executeUpdate();
                    }
                }
                refresh();
                dialog.close();
            } catch (SQLException ex) { err.setText("Save failed: " + ex.getMessage()); }
        });
        cancel.setOnAction(e -> dialog.close());

        Region grow = new Region(); HBox.setHgrow(grow, Priority.ALWAYS);
        HBox buttons = new HBox(10, grow, cancel, save);

        VBox box = new VBox(12,
            heading(existing == null ? "New supplier" : "Edit supplier"),
            grid, err, buttons);
        box.setPadding(new Insets(20));
        box.setPrefWidth(420);
        dialog.setScene(new Scene(box));
        dialog.showAndWait();
    }

    private void openLinkMaterialDialog() {
        Supplier sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) { Util.warn("No selection", "Select a supplier first."); return; }

                ComboBox<MaterialOption> box = new ComboBox<>();
        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT MaterialID, MaterialName FROM RawMaterial " +
                "WHERE MaterialID NOT IN " +
                "      (SELECT MaterialID FROM SupplierMaterial WHERE SupplierID=?) " +
                "ORDER BY MaterialName")) {
            ps.setInt(1, sel.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    box.getItems().add(new MaterialOption(rs.getInt(1), rs.getString(2)));
                }
            }
        } catch (SQLException ex) { Util.error("DB error", ex.getMessage()); return; }

        if (box.getItems().isEmpty()) {
            Util.info("All linked",
                "This supplier already supplies every material in the catalog.");
            return;
        }
        box.getSelectionModel().selectFirst();

        Label err = new Label(); err.setStyle("-fx-text-fill: #c0392b;");
        Button save = primaryButton("Link");
        Button cancel = new Button("Cancel");

        javafx.stage.Stage dialog = new javafx.stage.Stage();
        dialog.initModality(javafx.stage.Modality.APPLICATION_MODAL);
        dialog.setTitle("Link material to " + sel.name());

        save.setOnAction(e -> {
            MaterialOption pick = box.getValue();
            if (pick == null) { err.setText("Pick a material."); return; }
            try (PreparedStatement ps = DB.get().prepareStatement(
                    "INSERT INTO SupplierMaterial (SupplierID, MaterialID) VALUES (?,?)")) {
                ps.setInt(1, sel.id());
                ps.setInt(2, pick.id());
                ps.executeUpdate();
                loadMaterials(sel);
                dialog.close();
            } catch (SQLIntegrityConstraintViolationException ex) {
                err.setText("This supplier already provides that material.");
            } catch (SQLException ex) { err.setText("Link failed: " + ex.getMessage()); }
        });
        cancel.setOnAction(e -> dialog.close());

        Region grow = new Region(); HBox.setHgrow(grow, Priority.ALWAYS);
        HBox buttons = new HBox(10, grow, cancel, save);

        VBox content = new VBox(12,
            heading("Link material"),
            new Label("Choose a raw material that " + sel.name() + " provides:"),
            box, err, buttons);
        content.setPadding(new Insets(20));
        content.setPrefWidth(420);
        dialog.setScene(new javafx.scene.Scene(content));
        dialog.showAndWait();
    }

    private void unlinkSelectedMaterial() {
        Supplier sup = table.getSelectionModel().getSelectedItem();
        MaterialRow mat = materialsTable.getSelectionModel().getSelectedItem();
        if (sup == null || mat == null) {
            Util.warn("No selection",
                "Select both a supplier (top) and a material to unlink (bottom).");
            return;
        }
        if (!Util.confirm("Unlink",
                "Unlink material '" + mat.name() + "' from supplier '" + sup.name() + "'?")) return;
        try (PreparedStatement ps = DB.get().prepareStatement(
                "DELETE FROM SupplierMaterial WHERE SupplierID=? AND MaterialID=?")) {
            ps.setInt(1, sup.id());
            ps.setInt(2, mat.id());
            ps.executeUpdate();
            loadMaterials(sup);
        } catch (SQLException ex) {
            Util.error("Unlink failed", ex.getMessage());
        }
    }

    private record MaterialOption(int id, String name) {
        @Override public String toString() { return name; }
    }

    private static String normalizeCountry(String s) {
        if (s == null) return "";
        String t = s.trim().replaceAll("\\s+", " ");
        if (t.isEmpty()) return t;

        StringBuilder out = new StringBuilder();
        for (String part : t.toLowerCase().split(" ")) {
            if (part.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) out.append(part.substring(1));
        }
        return out.toString();
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

   
    private static boolean isValidPhone(String value) {
        if (value == null || value.isBlank()) return true;
        return value.matches("^[+0-9 ()\\-]+$") && value.matches(".*\\d.*");
    }

    
    private static boolean isValidEmail(String value) {
        if (value == null || value.isBlank()) return true;
        return value.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    }
    private static <T, S> TableColumn<T, S> col(String text, double width,
            java.util.function.Function<T, javafx.beans.value.ObservableValue<S>> getter) {
        TableColumn<T, S> c = new TableColumn<>(text);
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
    private static Label heading2(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        return l;
    }
}
