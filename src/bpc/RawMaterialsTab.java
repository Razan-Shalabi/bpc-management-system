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

public class RawMaterialsTab extends Tab implements Refreshable {

    public record Material(int id, String name, String category, String unit, int reorderLevel) {}

    private final ObservableList<Material> master = FXCollections.observableArrayList();
    private final TableView<Material> table = new TableView<>();
    private final TextField search = new TextField();
    private final ComboBox<String> categoryFilter = new ComboBox<>();

    public RawMaterialsTab() {
        setText("Raw Materials");
        setClosable(false);

        FilteredList<Material> filtered = new FilteredList<>(master, m -> true);
        table.setItems(filtered);
        table.getColumns().addAll(
            col("ID",           60,  (Material m) -> new SimpleObjectProperty<>(m.id())),
            col("Material",    260,  m -> new SimpleStringProperty(m.name())),
            col("Category",    140,  m -> new SimpleStringProperty(m.category())),
            col("Unit",         80,  m -> new SimpleStringProperty(m.unit())),
            col("Reorder lvl", 110,  m -> new SimpleObjectProperty<>(m.reorderLevel()))
        );

        search.setPromptText("Search by name");
        categoryFilter.getItems().setAll("All", "API", "Excipient", "Packaging", "Solvent");
        categoryFilter.getSelectionModel().selectFirst();

        Runnable applyFilter = () -> {
            String q = search.getText() == null ? "" : search.getText().toLowerCase().trim();
            String c = categoryFilter.getValue();
            filtered.setPredicate(m -> {
                boolean okCat = "All".equals(c) || c == null || c.equals(m.category());
                if (!okCat) return false;
                if (q.isEmpty()) return true;
                String name = m.name() == null ? "" : m.name().toLowerCase();
                return name.contains(q);
            });
        };
        search.textProperty().addListener((o,a,b) -> applyFilter.run());
        categoryFilter.valueProperty().addListener((o,a,b) -> applyFilter.run());

        Button addBtn  = primaryButton("Add"); addBtn.setOnAction(e -> openForm(null));
        Button editBtn = new Button("Edit");
        editBtn.setOnAction(e -> {
            Material sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) Util.warn("No selection", "Select a material first.");
            else openForm(sel);
        });
        Button delBtn = new Button("Delete");
        delBtn.setStyle("-fx-background-color: #c0392b; -fx-text-fill: white;");
        delBtn.setOnAction(e -> doDelete());

        Region grow = new Region(); HBox.setHgrow(grow, Priority.ALWAYS);
        HBox toolbar = new HBox(10, heading("Raw Materials"), grow, search, categoryFilter,
                                addBtn, editBtn, delBtn);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(14));
        root.setTop(toolbar);
        BorderPane.setMargin(toolbar, new Insets(0, 0, 10, 0));
        root.setCenter(table);
        setContent(root);

        refresh();
    }

    @Override
    public void refresh() {
        master.clear();
        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT MaterialID, MaterialName, Category, Unit, ReorderLevel " +
                "FROM RawMaterial ORDER BY MaterialID");
             ResultSet rs = ps.executeQuery()) {
            List<Material> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(new Material(
                    rs.getInt(1), rs.getString(2),
                    rs.getString(3), rs.getString(4), rs.getInt(5)));
            }
            master.setAll(rows);
        } catch (SQLException ex) { Util.error("DB error", ex.getMessage()); }
    }

    private void doDelete() {
        Material sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) { Util.warn("No selection", "Select a material first."); return; }
        if (!Util.confirm("Delete", "Delete material '" + sel.name() + "'?")) return;
        try (PreparedStatement ps = DB.get().prepareStatement(
                "DELETE FROM RawMaterial WHERE MaterialID=?")) {
            ps.setInt(1, sel.id());
            ps.executeUpdate();
            refresh();
        } catch (SQLIntegrityConstraintViolationException ex) {
            Util.error("Cannot delete",
                "This material is referenced by purchase orders or batches.\n" +
                "Remove those first.");
        } catch (SQLException ex) {
            Util.error("Delete failed", ex.getMessage());
        }
    }

    private void openForm(Material existing) {
        TextField name = new TextField();
        ComboBox<String> category = new ComboBox<>(FXCollections.observableArrayList(
            "API", "Excipient", "Packaging", "Solvent"));
        TextField unit = new TextField();
        TextField reorder = new TextField();

        if (existing == null) {
            category.getSelectionModel().select("API");
            unit.setText("kg");
            reorder.setText("0");
        } else {
            name.setText(existing.name());
            category.getSelectionModel().select(existing.category());
            unit.setText(existing.unit());
            reorder.setText(String.valueOf(existing.reorderLevel()));
        }

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(10);
        grid.addRow(0, new Label("Name *"),         name);
        grid.addRow(1, new Label("Category *"),     category);
        grid.addRow(2, new Label("Unit *"),         unit);
        grid.addRow(3, new Label("Reorder level"),  reorder);

        Label err = new Label(); err.setStyle("-fx-text-fill: #c0392b;");
        Button save = primaryButton("Save");
        Button cancel = new Button("Cancel");

        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle(existing == null ? "New raw material" : "Edit material #" + existing.id());

        save.setOnAction(e -> {
            String materialName = name.getText() == null ? "" : name.getText().trim();
            String unitText = unit.getText() == null ? "" : unit.getText().trim();

            if (materialName.isBlank() || category.getValue() == null || unitText.isBlank()) {
                err.setText("Name, category and unit are required.");
                return;
            }

            String problem = Validate.first(
                Validate.maxLength("Name", materialName, 120),
                Validate.maxLength("Unit", unitText, 20));
            if (problem != null) { err.setText(problem); return; }

            int currentId = existing == null ? 0 : existing.id();
            if (rawMaterialNameExists(materialName, currentId)) {
                err.setText("Raw material name already exists.");
                return;
            }

            int reorderVal;
            try {
                reorderVal = Validate.integer("Reorder level", reorder.getText(), 0, 100_000_000);
            } catch (IllegalArgumentException ex) {
                err.setText(ex.getMessage()); return;
            }
            try {
                if (existing == null) {
                    try (PreparedStatement ps = DB.get().prepareStatement(
                        "INSERT INTO RawMaterial (MaterialName, Category, Unit, ReorderLevel) " +
                        "VALUES (?,?,?,?)")) {
                        ps.setString(1, materialName);
                        ps.setString(2, category.getValue());
                        ps.setString(3, unitText);
                        ps.setInt(4, reorderVal);
                        ps.executeUpdate();
                    }
                } else {
                    try (PreparedStatement ps = DB.get().prepareStatement(
                        "UPDATE RawMaterial SET MaterialName=?, Category=?, Unit=?, ReorderLevel=? " +
                        "WHERE MaterialID=?")) {
                        ps.setString(1, materialName);
                        ps.setString(2, category.getValue());
                        ps.setString(3, unitText);
                        ps.setInt(4, reorderVal);
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
            heading(existing == null ? "New raw material" : "Edit material"),
            grid, err, buttons);
        box.setPadding(new Insets(20));
        box.setPrefWidth(420);
        dialog.setScene(new Scene(box));
        dialog.showAndWait();
    }

    private boolean rawMaterialNameExists(String name, int currentMaterialId) {
        String sql = """
            SELECT MaterialID
            FROM RawMaterial
            WHERE LOWER(TRIM(MaterialName)) = LOWER(TRIM(?))
              AND MaterialID <> ?
            LIMIT 1
            """;

        try (PreparedStatement ps = DB.get().prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setInt(2, currentMaterialId);

            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException ex) {
            Util.error("DB error", ex.getMessage());
            return true;
        }
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
