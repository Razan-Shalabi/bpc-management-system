package bpc;

import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
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

public class WarehousesTab extends Tab implements Refreshable {

    public record Warehouse(int id, String name, String location, int capacity,
                            String type, int rmUnits, int pbUnits) {}

    private final ObservableList<Warehouse> master = FXCollections.observableArrayList();
    private final TableView<Warehouse> table = new TableView<>();

    public WarehousesTab() {
        setText("Warehouses");
        setClosable(false);

        table.setItems(master);
        table.getColumns().addAll(
            col("ID",         60,  (Warehouse w) -> new SimpleObjectProperty<>(w.id())),
            col("Name",      240,  w -> new SimpleStringProperty(w.name())),
            col("Location", 200,  w -> new SimpleStringProperty(w.location())),
            col("Capacity", 100,  w -> new SimpleObjectProperty<>(w.capacity())),
            col("Type",     130,  w -> new SimpleStringProperty(w.type())),
            col("Raw stock units",   140, w -> new SimpleObjectProperty<>(w.rmUnits())),
            col("Product stock units",160, w -> new SimpleObjectProperty<>(w.pbUnits()))
        );

        Button addBtn  = primaryButton("Add"); addBtn.setOnAction(e -> openForm(null));
        Button editBtn = new Button("Edit");
        editBtn.setOnAction(e -> {
            Warehouse sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) Util.warn("No selection", "Select a warehouse first.");
            else openForm(sel);
        });
        Button delBtn = new Button("Delete");
        delBtn.setStyle("-fx-background-color: #c0392b; -fx-text-fill: white;");
        delBtn.setOnAction(e -> doDelete());

        Region grow = new Region(); HBox.setHgrow(grow, Priority.ALWAYS);
        HBox toolbar = new HBox(10, heading("Warehouses"), grow, addBtn, editBtn, delBtn);
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
                "SELECT w.WarehouseID, w.WarehouseName, w.Location, w.Capacity, w.Type, " +
                "       COALESCE((SELECT SUM(Quantity) FROM RawMaterialBatch " +
                "                 WHERE WarehouseID = w.WarehouseID), 0) AS rmUnits, " +
                "       COALESCE((SELECT SUM(Quantity) FROM ProductBatch " +
                "                 WHERE WarehouseID = w.WarehouseID), 0) AS pbUnits " +
                "FROM Warehouse w ORDER BY w.WarehouseID");
             ResultSet rs = ps.executeQuery()) {
            List<Warehouse> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(new Warehouse(
                    rs.getInt(1), rs.getString(2), rs.getString(3),
                    rs.getInt(4), rs.getString(5),
                    rs.getInt(6), rs.getInt(7)));
            }
            master.setAll(rows);
        } catch (SQLException ex) { Util.error("DB error", ex.getMessage()); }
    }

    private void doDelete() {
        Warehouse sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) { Util.warn("No selection", "Select a warehouse first."); return; }
        if (!Util.confirm("Delete", "Delete warehouse '" + sel.name() + "'?")) return;
        try (PreparedStatement ps = DB.get().prepareStatement(
                "DELETE FROM Warehouse WHERE WarehouseID=?")) {
            ps.setInt(1, sel.id());
            ps.executeUpdate();
            refresh();
        } catch (SQLIntegrityConstraintViolationException ex) {
            Util.error("Cannot delete",
                "This warehouse still holds stock, employees, or transfers.\n" +
                "Reassign or remove those references first.");
        } catch (SQLException ex) {
            Util.error("Delete failed", ex.getMessage());
        }
    }

    private void openForm(Warehouse existing) {
        TextField name     = new TextField();
        TextField location = new TextField();
        TextField capacity = new TextField();
        ComboBox<String> type = new ComboBox<>(FXCollections.observableArrayList(
            "RawMaterials", "FinishedGoods", "Distribution"));

        if (existing == null) {
            type.getSelectionModel().selectFirst();
        } else {
            name.setText(existing.name());
            location.setText(existing.location());
            capacity.setText(String.valueOf(existing.capacity()));
            type.getSelectionModel().select(existing.type());
        }

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(10);
        grid.addRow(0, new Label("Name *"),     name);
        grid.addRow(1, new Label("Location *"), location);
        grid.addRow(2, new Label("Capacity *"), capacity);
        grid.addRow(3, new Label("Type *"),     type);

        Label err = new Label(); err.setStyle("-fx-text-fill: #c0392b;");
        Button save = primaryButton("Save");
        Button cancel = new Button("Cancel");

        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle(existing == null ? "New warehouse" : "Edit warehouse #" + existing.id());

        save.setOnAction(e -> {
            if (name.getText().isBlank() || location.getText().isBlank() ||
                capacity.getText().isBlank() || type.getValue() == null) {
                err.setText("All fields are required."); return;
            }
            String problem = Validate.first(
                Validate.maxLength("Name", name.getText(), 120),
                Validate.maxLength("Location", location.getText(), 120));
            if (problem != null) { err.setText(problem); return; }
            int cap;
            try { cap = Validate.integer("Capacity", capacity.getText(), 1, 100_000_000); }
            catch (IllegalArgumentException ex) { err.setText(ex.getMessage()); return; }
            if (existing != null) {
                int stored = existing.rmUnits() + existing.pbUnits();
                if (!type.getValue().equals(existing.type()) && stored > 0) {
                    err.setText("This warehouse still holds " + stored + " units - its type cannot be changed.");
                    return;
                }
                if (cap < stored) {
                    err.setText("Capacity cannot be less than the " + stored + " units already stored here.");
                    return;
                }
            }
            try {
                if (existing == null) {
                    try (PreparedStatement ps = DB.get().prepareStatement(
                        "INSERT INTO Warehouse (WarehouseName, Location, Capacity, Type) " +
                        "VALUES (?,?,?,?)")) {
                        ps.setString(1, name.getText().trim());
                        ps.setString(2, location.getText().trim());
                        ps.setInt(3, cap);
                        ps.setString(4, type.getValue());
                        ps.executeUpdate();
                    }
                } else {
                    try (PreparedStatement ps = DB.get().prepareStatement(
                        "UPDATE Warehouse SET WarehouseName=?, Location=?, Capacity=?, Type=? " +
                        "WHERE WarehouseID=?")) {
                        ps.setString(1, name.getText().trim());
                        ps.setString(2, location.getText().trim());
                        ps.setInt(3, cap);
                        ps.setString(4, type.getValue());
                        ps.setInt(5, existing.id());
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
            heading(existing == null ? "New warehouse" : "Edit warehouse"),
            grid, err, buttons);
        box.setPadding(new Insets(20));
        box.setPrefWidth(440);
        dialog.setScene(new Scene(box));
        dialog.showAndWait();
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
}
