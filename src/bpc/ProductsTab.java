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
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;
import java.io.FileInputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ProductsTab extends Tab implements Refreshable {

    public record Product(int id, String name, int categoryId, String categoryName,
                          String dosageForm, BigDecimal unitPrice, int reorderLevel,
                          int shelfLifeMonths,
                          String picture, String description) {}

    public record FormulaLine(int materialId, String materialName, BigDecimal quantityPer100) {}

    private final ObservableList<Product> master = FXCollections.observableArrayList();
    private final TableView<Product> table = new TableView<>();
    private final TextField search = new TextField();
    private final ComboBox<String> categoryFilter = new ComboBox<>();
    private final Map<String, Integer> categoryMap = new LinkedHashMap<>();

    public ProductsTab() {
        setText("Products");
        setClosable(false);

        FilteredList<Product> filtered = new FilteredList<>(master, p -> true);
        table.setItems(filtered);
        table.getColumns().addAll(
            col("ID", 60, (Product p) -> new SimpleObjectProperty<>(p.id())),
            col("Product", 220, p -> new SimpleStringProperty(p.name())),
            col("Category", 140, p -> new SimpleStringProperty(p.categoryName())),
            col("Dosage form", 110, p -> new SimpleStringProperty(p.dosageForm())),
            col("Unit price", 110, p -> new SimpleStringProperty(Util.money(p.unitPrice()))),
            col("Reorder lvl", 100, p -> new SimpleObjectProperty<>(p.reorderLevel())),
            col("Shelf life", 100, p -> new SimpleStringProperty(p.shelfLifeMonths() + " mo")),
            col("Picture", 130, p -> new SimpleStringProperty( p.picture() == null ? "-" : p.picture()))
        );

        search.setPromptText("Search by name");
        categoryFilter.getItems().add("All");
        categoryFilter.getSelectionModel().selectFirst();

        Runnable applyFilter = () -> {
            String q = search.getText() == null ? "" : search.getText().toLowerCase().trim();
            String c = categoryFilter.getValue();
            filtered.setPredicate(p -> {
                boolean okCat = "All".equals(c) || c == null || c.equals(p.categoryName());
                if (!okCat) return false;
                if (q.isEmpty()) return true;
                String name = p.name() == null ? "" : p.name().toLowerCase();
                return name.contains(q);
            });
        };
        search.textProperty().addListener((o,a,b) -> applyFilter.run());
        categoryFilter.valueProperty().addListener((o,a,b) -> applyFilter.run());

        Button addBtn  = primaryButton("Add"); addBtn.setOnAction(e -> openForm(null));
        Button editBtn = new Button("Edit");
        editBtn.setOnAction(e -> {
            Product sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) Util.warn("No selection", "Select a product first.");
            else openForm(sel);
        });
        Button delBtn = new Button("Delete");
        delBtn.setStyle("-fx-background-color: #c0392b; -fx-text-fill: white;");
        delBtn.setOnAction(e -> doDelete());

        Region grow = new Region(); HBox.setHgrow(grow, Priority.ALWAYS);
        HBox toolbar = new HBox(10, heading("Products"), grow, search, categoryFilter,
                                addBtn, editBtn, delBtn);
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
        String currentCat = categoryFilter.getValue();
        categoryMap.clear();
        categoryFilter.getItems().clear();
        categoryFilter.getItems().add("All");
        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT CategoryID, CategoryName FROM ProductCategory ORDER BY CategoryName");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                categoryMap.put(rs.getString(2), rs.getInt(1));
                categoryFilter.getItems().add(rs.getString(2));
            }
        } catch (SQLException ex) { Util.error("DB error", ex.getMessage()); }
        if (currentCat != null && categoryFilter.getItems().contains(currentCat)) {
            categoryFilter.getSelectionModel().select(currentCat);
        } else {
            categoryFilter.getSelectionModel().selectFirst();
        }

        master.clear();
        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT p.ProductID, p.ProductName, p.CategoryID, c.CategoryName, " +
                "p.DosageForm, p.UnitPrice, p.ReorderLevel, p.ShelfLifeMonths, " +
                "p.Picture, p.Description " +
                "FROM Product p " +
                "JOIN ProductCategory c ON c.CategoryID = p.CategoryID " +
                "ORDER BY p.ProductID");
             ResultSet rs = ps.executeQuery()) {
            List<Product> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(new Product(
                    rs.getInt(1), rs.getString(2), rs.getInt(3), rs.getString(4),
                    rs.getString(5), rs.getBigDecimal(6), rs.getInt(7),
                    rs.getInt(8), rs.getString(9), rs.getString(10)));
            }
            master.setAll(rows);
        } catch (SQLException ex) { Util.error("DB error", ex.getMessage()); }
    }

    private void doDelete() {
        Product sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) { Util.warn("No selection", "Select a product first."); return; }
        if (!Util.confirm("Delete", "Delete product '" + sel.name() + "'?")) return;
        try (PreparedStatement ps = DB.get().prepareStatement(
                "DELETE FROM Product WHERE ProductID=?")) {
            ps.setInt(1, sel.id());
            ps.executeUpdate();
            refresh();
        } catch (SQLIntegrityConstraintViolationException ex) {
            Util.error("Cannot delete",
                "This product has batches, production orders, or sales line items.\n" +
                "Remove those first.");
        } catch (SQLException ex) {
            Util.error("Delete failed", ex.getMessage());
        }
    }

    private void openForm(Product existing) {
        TextField name = new TextField();
        ComboBox<String> category = new ComboBox<>(FXCollections.observableArrayList(categoryMap.keySet()));
        TextField dosageForm = new TextField();
        TextField unitPrice = new TextField();
        TextField reorder = new TextField();
        TextField shelfLife = new TextField();

        TextArea descArea = new TextArea();
        descArea.setWrapText(true);
        descArea.setPrefRowCount(2);
        ObservableList<FormulaLine> formula = FXCollections.observableArrayList();
        Map<String, Integer> materialMap = loadAllMaterials(); 
        if (existing != null) formula.setAll(loadFormula(existing.id()));

        TableView<FormulaLine> formulaTable = new TableView<>();
        formulaTable.setPrefHeight(140);
        formulaTable.setEditable(true);
        formulaTable.setPlaceholder(new Label("No formula yet. Add a raw material below."));

        TableColumn<FormulaLine, String> cMat = new TableColumn<>("Raw material");
        cMat.setPrefWidth(260);
        cMat.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().materialName()));
        TableColumn<FormulaLine, String> cQty = new TableColumn<>("Qty per 100 finished units");
        cQty.setPrefWidth(180);
        cQty.setCellValueFactory(cd -> new SimpleStringProperty(
            cd.getValue().quantityPer100().toPlainString()));
        formulaTable.getColumns().addAll(cMat, cQty);
        formulaTable.setItems(formula);

        ComboBox<String> matPick = new ComboBox<>(FXCollections.observableArrayList(materialMap.keySet()));
        matPick.setPromptText("Raw material");
        matPick.setPrefWidth(220);
        TextField qtyPick = new TextField();
        qtyPick.setPromptText("Qty per 100");
        qtyPick.setPrefWidth(120);

        Button addRowBtn = new Button("Add to formula");
        addRowBtn.setOnAction(e -> {
            if (matPick.getValue() == null) {
                Util.warn("Pick a material", "Choose a raw material from the dropdown."); return;
            }
            BigDecimal q;
            try { q = Validate.money("Quantity per 100", qtyPick.getText(), 8); }
            catch (IllegalArgumentException ex) { Util.warn("Bad qty", ex.getMessage()); return; }
            if (q.signum() <= 0) { Util.warn("Bad qty", "Quantity must be greater than 0."); return; }
            int matId = materialMap.get(matPick.getValue());
            formula.removeIf(fl -> fl.materialId() == matId);
            formula.add(new FormulaLine(matId, matPick.getValue(), q));
            qtyPick.clear();
            matPick.getSelectionModel().clearSelection();
        });
        Button removeRowBtn = new Button("Remove selected");
        removeRowBtn.setOnAction(e -> {
            FormulaLine sel = formulaTable.getSelectionModel().getSelectedItem();
            if (sel != null) formula.remove(sel);
        });
        HBox formulaControls = new HBox(8, matPick, qtyPick, addRowBtn, removeRowBtn);
        formulaControls.setAlignment(Pos.CENTER_LEFT);

        Label formulaHeader = new Label("Product Formula");
        formulaHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 14px; -fx-text-fill: #114e60;");
        VBox formulaSection = new VBox(4, formulaHeader,
                                       formulaTable, formulaControls);
        formulaSection.setPadding(new Insets(6, 10, 6, 10));
        formulaSection.setStyle("-fx-background-color: #f1f5f9; -fx-background-radius: 6;");

        final String[] pictureFilename = { existing == null ? null : existing.picture() };

        ImageView preview = new ImageView();
        preview.setFitWidth(80);
        preview.setFitHeight(60);
        preview.setPreserveRatio(true);
        StackPane previewBox = new StackPane(preview);
        previewBox.setMinSize(80, 60);
        previewBox.setMaxSize(80, 60);
        previewBox.setStyle("-fx-background-color: #f1f5f9; -fx-background-radius: 6;");

        Label picNameLbl = new Label(pictureFilename[0] == null ? "(no picture)" : pictureFilename[0]);
        picNameLbl.setStyle("-fx-text-fill: #64748b; -fx-font-size: 11px;");

        if (pictureFilename[0] != null) {
            preview.setImage(Util.productImage(pictureFilename[0]));
        }

        if (existing == null) {
            if (!category.getItems().isEmpty()) category.getSelectionModel().selectFirst();
            dosageForm.setText("Tablet");
            unitPrice.setText("0.00");
            reorder.setText("0");
            shelfLife.setText("24");
        } else {
            name.setText(existing.name());
            category.getSelectionModel().select(existing.categoryName());
            dosageForm.setText(existing.dosageForm());
            unitPrice.setText(existing.unitPrice().toPlainString());
            reorder.setText(String.valueOf(existing.reorderLevel()));
            shelfLife.setText(String.valueOf(existing.shelfLifeMonths()));
            if (existing.description() != null) descArea.setText(existing.description());
        }

        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle(existing == null ? "New product" : "Edit product #" + existing.id());

        Button chooseBtn = new Button("Choose picture...");
        chooseBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Pick a product picture");
            fc.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.gif"));
            File src = fc.showOpenDialog(dialog);
            if (src == null) return;
            try {
                File destDir = new File(System.getProperty("user.dir"), "src/bpc/drugs");
                if (!destDir.exists()) destDir.mkdirs();
                String safeName = src.getName().replaceAll("[^A-Za-z0-9._-]", "_");
                if (safeName.length() > 100) safeName = safeName.substring(safeName.length() - 100);
                File dest = new File(destDir, safeName);
                // Do not overwrite another product's picture that happens to have the same file name.
                for (int n = 2; dest.exists() && !Files.isSameFile(dest.toPath(), src.toPath()); n++) {
                    dest = new File(destDir, n + "_" + safeName);
                }
                Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                pictureFilename[0] = dest.getName();
                picNameLbl.setText(dest.getName());
                try (FileInputStream fis = new FileInputStream(dest)) {
                    preview.setImage(new Image(fis));
                }
            } catch (Exception ex) {
                Util.error("Picture upload failed",
                    "Could not save picture: " + ex.getMessage());
            }
        });

        Button clearPicBtn = new Button("Clear");
        clearPicBtn.setOnAction(e -> {
            pictureFilename[0] = null;
            picNameLbl.setText("(no picture)");
            preview.setImage(null);
        });

        HBox picBox = new HBox(10, previewBox,
            new VBox(4, picNameLbl, new HBox(6, chooseBtn, clearPicBtn)));
        picBox.setAlignment(Pos.CENTER_LEFT);

        Label infoHeader = new Label("Product Information");
        infoHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 14px; -fx-text-fill: #114e60;");

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(6);
        grid.addRow(0, new Label("Name *"), name);
        grid.addRow(1, new Label("Category *"), category);
        grid.addRow(2, new Label("Dosage form *"), dosageForm);
        grid.addRow(3, new Label("Unit price *"), unitPrice);
        grid.addRow(4, new Label("Reorder level"), reorder);
        grid.addRow(5, new Label("Shelf life (months) *"), shelfLife);
        grid.addRow(6, new Label("Picture"), picBox);
        grid.addRow(7, new Label("Description"), descArea);
        for (var node : new javafx.scene.Node[]{name, dosageForm, unitPrice, reorder, shelfLife, descArea}) {
            GridPane.setHgrow(node, Priority.ALWAYS);
            if (node instanceof javafx.scene.control.Control c) c.setMaxWidth(Double.MAX_VALUE);
        }

        Label err = new Label(); err.setStyle("-fx-text-fill: #c0392b;");
        Button save = primaryButton("Save");
        Button cancel = new Button("Cancel");

        save.setOnAction(e -> {
            if (name.getText().isBlank() || category.getValue() == null ||
                dosageForm.getText().isBlank() || unitPrice.getText().isBlank() ||
                shelfLife.getText().isBlank()) {
                err.setText("All starred fields are required."); return;
            }
            String problem = Validate.first(
                Validate.maxLength("Name", name.getText(), 120),
                Validate.maxLength("Dosage form", dosageForm.getText(), 50),
                Validate.maxLength("Description", descArea.getText(), 1000));
            if (problem != null) { err.setText(problem); return; }
            BigDecimal price;
            try { price = Validate.money("Unit price", unitPrice.getText(), 8); }
            catch (IllegalArgumentException ex) { err.setText(ex.getMessage()); return; }
            if (price.compareTo(BigDecimal.ZERO) <= 0) {
                err.setText("Unit price must be greater than 0."); return;
            }
            int reorderVal = 0;
            if (!reorder.getText().isBlank()) {
                try { reorderVal = Validate.integer("Reorder level", reorder.getText(), 0, 100_000_000); }
                catch (IllegalArgumentException ex) { err.setText(ex.getMessage()); return; }
            }
            int shelfVal;
            try { shelfVal = Validate.integer("Shelf life (months)", shelfLife.getText(), 1, 120); }
            catch (IllegalArgumentException ex) { err.setText(ex.getMessage()); return; }
            int catId = categoryMap.getOrDefault(category.getValue(), -1);
            if (catId < 0) { err.setText("Unknown category."); return; }
            String pic = pictureFilename[0];
            String desc = descArea.getText() == null ? null : descArea.getText().trim();
            if (desc != null && desc.isEmpty()) desc = null;

            Connection conn;
            try { conn = DB.get(); }
            catch (SQLException ex) { err.setText("DB error: " + ex.getMessage()); return; }
            try {
                conn.setAutoCommit(false);
                int productId;
                if (existing == null) {
                    try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO Product (ProductName, CategoryID, DosageForm, UnitPrice, " +
                        "ReorderLevel, ShelfLifeMonths, Picture, Description) VALUES (?,?,?,?,?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                        ps.setString(1, name.getText().trim());
                        ps.setInt(2, catId);
                        ps.setString(3, dosageForm.getText().trim());
                        ps.setBigDecimal(4, price);
                        ps.setInt(5, reorderVal);
                        ps.setInt(6, shelfVal);
                        ps.setString(7, pic);
                        ps.setString(8, desc);
                        ps.executeUpdate();
                        try (ResultSet keys = ps.getGeneratedKeys()) {
                            keys.next(); productId = keys.getInt(1);
                        }
                    }
                } else {
                    productId = existing.id();
                    try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE Product SET ProductName=?, CategoryID=?, DosageForm=?, " +
                        "UnitPrice=?, ReorderLevel=?, ShelfLifeMonths=?, Picture=?, Description=? " +
                        "WHERE ProductID=?")) {
                        ps.setString(1, name.getText().trim());
                        ps.setInt(2, catId);
                        ps.setString(3, dosageForm.getText().trim());
                        ps.setBigDecimal(4, price);
                        ps.setInt(5, reorderVal);
                        ps.setInt(6, shelfVal);
                        ps.setString(7, pic);
                        ps.setString(8, desc);
                        ps.setInt(9, productId);
                        ps.executeUpdate();
                    }
                }
                try (PreparedStatement psDel = conn.prepareStatement(
                        "DELETE FROM ProductFormula WHERE ProductID=?")) {
                    psDel.setInt(1, productId);
                    psDel.executeUpdate();
                }
                if (!formula.isEmpty()) {
                    try (PreparedStatement psIns = conn.prepareStatement(
                        "INSERT INTO ProductFormula (ProductID, MaterialID, QuantityPer100) " +
                        "VALUES (?,?,?)")) {
                        for (FormulaLine fl : formula) {
                            psIns.setInt(1, productId);
                            psIns.setInt(2, fl.materialId());
                            psIns.setBigDecimal(3, fl.quantityPer100());
                            psIns.addBatch();
                        }
                        psIns.executeBatch();
                    }
                }
                conn.commit();
                refresh();
                dialog.close();
            } catch (SQLException ex) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                err.setText(ex instanceof SQLIntegrityConstraintViolationException
                    ? "A product with that name already exists."
                    : "Save failed: " + ex.getMessage());
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        });
        cancel.setOnAction(e -> dialog.close());

        Region grow = new Region(); HBox.setHgrow(grow, Priority.ALWAYS);
        HBox buttons = new HBox(10, grow, cancel, save);

        VBox box = new VBox(8,
            heading(existing == null ? "New product" : "Edit product"),
            infoHeader, grid, formulaSection, err, buttons);
        box.setPadding(new Insets(14));
        box.setPrefWidth(620);

        ScrollPane sp = new ScrollPane(box);
        sp.setFitToWidth(true);
        sp.setPrefSize(640, 620);
        dialog.setScene(new Scene(sp));
        dialog.showAndWait();
    }

    private Map<String, Integer> loadAllMaterials() {
        Map<String, Integer> out = new LinkedHashMap<>();
        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT MaterialID, MaterialName FROM RawMaterial ORDER BY MaterialName");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.put(rs.getString(2), rs.getInt(1));
        } catch (SQLException ex) { Util.error("DB error", ex.getMessage()); }
        return out;
    }

    private List<FormulaLine> loadFormula(int productId) {
        List<FormulaLine> out = new ArrayList<>();
        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT pf.MaterialID, rm.MaterialName, pf.QuantityPer100 " +
                "FROM ProductFormula pf " +
                "JOIN RawMaterial rm ON rm.MaterialID = pf.MaterialID " +
                "WHERE pf.ProductID = ? ORDER BY rm.MaterialName")) {
            ps.setInt(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new FormulaLine(rs.getInt(1), rs.getString(2), rs.getBigDecimal(3)));
                }
            }
        } catch (SQLException ex) { Util.error("DB error", ex.getMessage()); }
        return out;
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
