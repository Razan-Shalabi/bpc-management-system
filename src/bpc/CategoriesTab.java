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

public class CategoriesTab extends Tab implements Refreshable {

    public record Category(int id, String name, String description, int productCount) {}

    private final ObservableList<Category> master = FXCollections.observableArrayList();
    private final TableView<Category> table = new TableView<>();

    public CategoriesTab() {
        setText("Categories");
        setClosable(false);

        table.setItems(master);
        table.getColumns().addAll(
            col("ID", 60, (Category c) -> new SimpleObjectProperty<>(c.id())),
            col("Name", 200, c -> new SimpleStringProperty(c.name())),
            col("Description",400, c -> new SimpleStringProperty(c.description())),
            col("# Products", 100, c -> new SimpleObjectProperty<>(c.productCount()))
        );

        Button addBtn = primaryButton("Add"); addBtn.setOnAction(e -> openForm(null));
        Button editBtn = new Button("Edit");
        editBtn.setOnAction(e -> {
            Category sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) Util.warn("No selection", "Select a category first.");
            else openForm(sel);
        });
        Button delBtn = new Button("Delete");
        delBtn.setStyle("-fx-background-color: #c0392b; -fx-text-fill: white;");
        delBtn.setOnAction(e -> doDelete());

        Region grow = new Region(); HBox.setHgrow(grow, Priority.ALWAYS);
        HBox toolbar = new HBox(10, heading("Product Categories"), grow, addBtn, editBtn, delBtn);
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
                "SELECT pc.CategoryID, pc.CategoryName, pc.Description, " +
                " (SELECT COUNT(*) FROM Product p WHERE p.CategoryID = pc.CategoryID) AS N " +
                "FROM ProductCategory pc ORDER BY pc.CategoryID");
             ResultSet rs = ps.executeQuery()) {
            List<Category> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(new Category(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getInt(4)));
            }
            master.setAll(rows);
        } catch (SQLException ex) { Util.error("DB error", ex.getMessage()); }
    }

    private void doDelete() {
        Category sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) { Util.warn("No selection", "Select a category first."); return; }
        if (!Util.confirm("Delete", "Delete category '" + sel.name() + "'?")) return;
        try (PreparedStatement ps = DB.get().prepareStatement(
                "DELETE FROM ProductCategory WHERE CategoryID=?")) {
            ps.setInt(1, sel.id());
            ps.executeUpdate();
            refresh();
        } catch (SQLIntegrityConstraintViolationException ex) {
            Util.error("Cannot delete",
                "This category still has products in it.\n" +
                "Move or delete those products first.");
        } catch (SQLException ex) {
            Util.error("Delete failed", ex.getMessage());
        }
    }

    private void openForm(Category existing) {
        TextField name = new TextField();
        TextArea  desc = new TextArea();
        desc.setPrefRowCount(3);

        if (existing != null) {
            name.setText(existing.name());
            desc.setText(existing.description() == null ? "" : existing.description());
        }

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(10);
        grid.addRow(0, new Label("Name *"), name);
        grid.addRow(1, new Label("Description"), desc);

        Label err = new Label(); err.setStyle("-fx-text-fill: #c0392b;");
        Button save = primaryButton("Save");
        Button cancel = new Button("Cancel");

        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle(existing == null ? "New category" : "Edit category #" + existing.id());

        save.setOnAction(e -> {
            if (name.getText().isBlank()) { err.setText("Name is required."); return; }
            String problem = Validate.first(
                Validate.maxLength("Name", name.getText(), 80),
                Validate.maxLength("Description", desc.getText(), 255));
            if (problem != null) { err.setText(problem); return; }
            try {
                if (existing == null) {
                    try (PreparedStatement ps = DB.get().prepareStatement(
                        "INSERT INTO ProductCategory (CategoryName, Description) VALUES (?,?)")) {
                        ps.setString(1, name.getText().trim());
                        ps.setString(2, desc.getText() == null || desc.getText().isBlank()
                                          ? null : desc.getText().trim());
                        ps.executeUpdate();
                    }
                } else {
                    try (PreparedStatement ps = DB.get().prepareStatement(
                        "UPDATE ProductCategory SET CategoryName=?, Description=? WHERE CategoryID=?")) {
                        ps.setString(1, name.getText().trim());
                        ps.setString(2, desc.getText() == null || desc.getText().isBlank()
                                          ? null : desc.getText().trim());
                        ps.setInt(3, existing.id());
                        ps.executeUpdate();
                    }
                }
                refresh();
                dialog.close();
            } catch (SQLIntegrityConstraintViolationException nfe) {
                err.setText("A category with that name already exists.");
            } catch (SQLException ex) { err.setText("Save failed: " + ex.getMessage()); }
        });
        cancel.setOnAction(e -> dialog.close());

        Region grow = new Region(); HBox.setHgrow(grow, Priority.ALWAYS);
        HBox buttons = new HBox(10, grow, cancel, save);

        VBox box = new VBox(12,
            heading(existing == null ? "New category" : "Edit category"),
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
