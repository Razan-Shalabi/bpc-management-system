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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class ProductionTab extends Tab implements Refreshable {

    public record ProdOrder(int id, int productId, String product, int batchId, LocalDate prodDate, int qtyProduced, String emp, String status) {}
    public record ProdMaterial(int rmBatchId, String material, int qtyUsed) {}

    private final ObservableList<ProdOrder>    orders    = FXCollections.observableArrayList();
    private final ObservableList<ProdMaterial> materials = FXCollections.observableArrayList();
    private final TableView<ProdOrder>    poTable = new TableView<>();
    private final TableView<ProdMaterial> matTable = new TableView<>();

    public ProductionTab() {
        setText("Production");
        setClosable(false);

        poTable.setItems(orders);
        poTable.getColumns().addAll(
            col("Prod #", 90, (ProdOrder p) -> new SimpleObjectProperty<>(p.id())),
            col("Product", 240, p -> new SimpleStringProperty(p.product())),
            col("Batch #", 80, p -> new SimpleObjectProperty<>(p.batchId())),
            col("Produced",110, p -> new SimpleStringProperty(p.prodDate().toString())),
            col("Qty", 100, p -> new SimpleObjectProperty<>(p.qtyProduced())),
            col("Employee",180, p -> new SimpleStringProperty(p.emp())),
            statusCol()
        );
        poTable.getSelectionModel().selectedItemProperty()
                .addListener((o, a, sel) -> loadMaterials(sel));

        matTable.setItems(materials);
        matTable.getColumns().addAll(
            col("RM Batch #", 90, (ProdMaterial m) -> new SimpleObjectProperty<>(m.rmBatchId())),
            col("Material", 260, m -> new SimpleStringProperty(m.material())),
            col("Qty used", 100, m -> new SimpleObjectProperty<>(m.qtyUsed()))
        );
        matTable.setPrefHeight(180);

        Button newBtn = primaryButton("New Production Order");
        newBtn.setOnAction(e -> openWizard());

        Button confirmBtn  = new Button("Confirm");
        Button completeBtn = new Button("Complete");
        Button cancelBtn   = new Button("Cancel");
        cancelBtn.setStyle("-fx-background-color: #c0392b; -fx-text-fill: white;");
        confirmBtn .setOnAction(e -> changeStatus("InProgress", "Planned"));
        completeBtn.setOnAction(e -> changeStatus("Completed",  "InProgress", "Planned"));
        cancelBtn  .setOnAction(e -> changeStatus("Cancelled",  "Planned", "InProgress"));

        Region grow = new Region(); HBox.setHgrow(grow, Priority.ALWAYS);
        HBox toolbar = new HBox(10, heading("Production"), grow,
                                newBtn, confirmBtn, completeBtn, cancelBtn);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(10, toolbar, poTable,
                             heading2("Materials consumed in selected production order:"), matTable);
        root.setPadding(new Insets(14));
        VBox.setVgrow(poTable, Priority.ALWAYS);
        setContent(root);

        refresh();
    }

    @Override
    public void refresh() {
        orders.clear();
        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT po.ProductionID, p.ProductID, p.ProductName, po.ProductBatchID, " +
                "       po.ProductionDate, po.QuantityProduced, e.EmpName, po.Status " +
                "FROM ProductionOrder po " +
                "JOIN ProductBatch pb ON pb.ProductBatchID = po.ProductBatchID " +
                "JOIN Product p  ON p.ProductID = pb.ProductID " +
                "JOIN Employee e ON e.EmpID     = po.EmpID " +
                "ORDER BY po.ProductionDate DESC, po.ProductionID DESC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                orders.add(new ProdOrder(
                    rs.getInt(1), rs.getInt(2), rs.getString(3),
                    rs.getInt(4), rs.getDate(5).toLocalDate(),
                    rs.getInt(6), rs.getString(7), rs.getString(8)));
            }
        } catch (SQLException ex) { Util.error("DB error", ex.getMessage()); }
    }

    private void loadMaterials(ProdOrder po) {
        materials.clear();
        if (po == null) return;
        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT pm.RMBatchID, rm.MaterialName, pm.QuantityUsed " +
                "FROM ProductionMaterial pm " +
                "JOIN RawMaterialBatch rmb ON rmb.RMBatchID = pm.RMBatchID " +
                "JOIN RawMaterial rm       ON rm.MaterialID = rmb.MaterialID " +
                "WHERE pm.ProductionID = ?")) {
            ps.setInt(1, po.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    materials.add(new ProdMaterial(
                        rs.getInt(1), rs.getString(2), rs.getInt(3)));
                }
            }
        } catch (SQLException ex) { Util.error("DB error", ex.getMessage()); }
    }

    private void changeStatus(String newStatus, String... allowedFrom) {
        ProdOrder sel = poTable.getSelectionModel().getSelectedItem();
        if (sel == null) { Util.warn("No selection", "Select a production order first."); return; }
        boolean ok = false;
        for (String s : allowedFrom) if (s.equals(sel.status())) { ok = true; break; }
        if (!ok) {
            Util.warn("Cannot " + newStatus.toLowerCase(),
                "A " + sel.status() + " production order cannot move to " + newStatus + ".");
            return;
        }
        if ("Cancelled".equals(newStatus) && !Util.confirm("Cancel production",
                "Cancel production #" + sel.id() + "? The reserved raw materials will be returned to stock.")) {
            return;
        }

        Connection conn;
        try { conn = DB.get(); }
        catch (SQLException ex) { Util.error("DB error", ex.getMessage()); return; }
        try {
            conn.setAutoCommit(false);
            String placeholders = String.join(",", java.util.Collections.nCopies(allowedFrom.length, "?"));
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ProductionOrder SET Status=? WHERE ProductionID=? AND Status IN (" + placeholders + ")")) {
                ps.setString(1, newStatus);
                ps.setInt(2, sel.id());
                for (int k = 0; k < allowedFrom.length; k++) ps.setString(3 + k, allowedFrom[k]);
                if (ps.executeUpdate() != 1) {
                    throw new SQLException("The production order was changed by someone else - refresh and try again.");
                }
            }
            if ("Completed".equals(newStatus)) {
                // The finished goods only become stock once production is completed.
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE ProductBatch SET Quantity = Quantity + ? WHERE ProductBatchID = ?")) {
                    ps.setInt(1, sel.qtyProduced());
                    ps.setInt(2, sel.batchId());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO StockTransfer " +
                        "(FromWarehouseID, ToWarehouseID, ProductBatchID, Quantity, TransferDate, EmpID) " +
                        "SELECT (SELECT rmb.WarehouseID FROM ProductionMaterial pm " +
                        "        JOIN RawMaterialBatch rmb ON rmb.RMBatchID = pm.RMBatchID " +
                        "        WHERE pm.ProductionID = ? LIMIT 1), " +
                        "       pb.WarehouseID, pb.ProductBatchID, ?, CURRENT_DATE, ? " +
                        "FROM ProductBatch pb WHERE pb.ProductBatchID = ?")) {
                    ps.setInt(1, sel.id());
                    ps.setInt(2, sel.qtyProduced());
                    ps.setInt(3, Session.empId() > 0 ? Session.empId() : 1);
                    ps.setInt(4, sel.batchId());
                    ps.executeUpdate();
                }
            } else if ("Cancelled".equals(newStatus)) {
                // Give the reserved raw materials back.
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE RawMaterialBatch rmb JOIN ProductionMaterial pm ON pm.RMBatchID = rmb.RMBatchID " +
                        "SET rmb.Quantity = rmb.Quantity + pm.QuantityUsed WHERE pm.ProductionID = ?")) {
                    ps.setInt(1, sel.id());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM ProductionMaterial WHERE ProductionID = ?")) {
                    ps.setInt(1, sel.id());
                    ps.executeUpdate();
                }
            }
            conn.commit();
            if ("Completed".equals(newStatus)) {
                Util.info("Production completed", sel.qtyProduced() + " units of " + sel.product()
                    + " added to stock (batch #" + sel.batchId() + ").");
            }
            refresh();
            materials.clear();
        } catch (SQLException ex) {
            try { conn.rollback(); } catch (SQLException ignored) {}
            Util.error("Update failed", ex.getMessage());
        } finally {
            try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }

    private void openWizard() {
        ComboBox<ProductRow> productBox  = new ComboBox<>();
        ComboBox<WhRow> prodWhBox = new ComboBox<>();   
        ComboBox<WhRow> whBox = new ComboBox<>();   
        try {
            productBox.getItems().setAll(fetchProducts());
            prodWhBox .getItems().setAll(fetchRawMaterialWarehouses());
            whBox     .getItems().setAll(fetchWarehouses());
            if (!prodWhBox.getItems().isEmpty()) prodWhBox.getSelectionModel().selectFirst();
            if (!whBox.getItems().isEmpty()) whBox.getSelectionModel().selectFirst();
        } catch (SQLException ex) { Util.error("DB error", ex.getMessage()); return; }

        Spinner<Integer> qtyProduced = Util.numeric(new Spinner<>(1, 100000, 100));
        qtyProduced.setPrefWidth(110);

        DatePicker prodDate = new DatePicker(LocalDate.now());

        ObservableList<ProdMaterial> consumed = FXCollections.observableArrayList();
        TableView<ProdMaterial> consumedTable = new TableView<>(consumed);
        consumedTable.getColumns().addAll(
            col("RM Batch #", 90, (ProdMaterial m) -> new SimpleObjectProperty<>(m.rmBatchId())),
            col("Material", 320, m -> new SimpleStringProperty(m.material())),
            col("Qty used", 100, m -> new SimpleObjectProperty<>(m.qtyUsed()))
        );
        consumedTable.setPrefHeight(220);
        consumedTable.setPlaceholder(new Label(
            "No allocation yet. Set Product + Qty, then click Calculate Materials."));

        Label err = new Label(); err.setStyle("-fx-text-fill: #c0392b;");

        // Any change to what is being produced invalidates the calculated materials.
        productBox.valueProperty().addListener((o, a, b) -> consumed.clear());
        qtyProduced.valueProperty().addListener((o, a, b) -> consumed.clear());
        prodWhBox.valueProperty().addListener((o, a, b) -> consumed.clear());

        Button calcBtn = new Button("Calculate Materials");
        calcBtn.setStyle("-fx-background-color: #f59e0b; -fx-text-fill: white; -fx-font-weight: bold;");
        calcBtn.setOnAction(e -> {
            err.setText("");
            consumed.clear();
            ProductRow p = productBox.getValue();
            Integer    q = qtyProduced.getValue();
            if (p == null) { err.setText("Pick a product first."); return; }
            if (q == null || q <= 0) { err.setText("Quantity must be > 0."); return; }
            if (prodWhBox.getValue() == null) { err.setText("Pick where production runs (RM warehouse)."); return; }
            try {
                List<ProdMaterial> alloc = allocateFEFO(p.id(), q, prodWhBox.getValue().id());
                if (alloc.isEmpty()) {
                    err.setText("This product has no formula defined - " +
                                "set it on the Products tab first.");
                    return;
                }
                consumed.setAll(alloc);
            } catch (SQLException ex) {
                err.setText("Calculation failed: " + ex.getMessage());
            }
        });

        Button save   = primaryButton("Place Production");
        Button cancel = new Button("Cancel");

        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("New Production Order");

        save.setOnAction(e -> {
            err.setText("");
            ProductRow p = productBox.getValue();
            WhRow pw = prodWhBox.getValue();
            WhRow w = whBox.getValue();
            int empId = Session.empId();
            Integer q = qtyProduced.getValue();

            if (p == null) { err.setText("Pick a product."); return; }
            if (pw == null) { err.setText("Pick where production runs (RM warehouse)."); return; }
            if (w == null) { err.setText("Pick a destination warehouse."); return; }
            if (pw.id() == w.id()) {
                err.setText("Production and destination warehouses must differ."); return;
            }
            if (empId <= 0) { err.setText("No signed-in employee."); return; }
            if (q == null || q <= 0) { err.setText("Quantity must be > 0."); return; }
            if (prodDate.getValue() == null) { err.setText("Production date is required."); return; }
            if (prodDate.getValue().isBefore(LocalDate.now().minusYears(1))) {
                err.setText("Production date cannot be more than a year in the past."); return;
            }
            if (consumed.isEmpty()) {
                err.setText("Click Calculate Materials first.");
                return;
            }

            Connection conn;
            try { conn = DB.get(); }
            catch (SQLException ex) { err.setText(ex.getMessage()); return; }

            try {
                conn.setAutoCommit(false);
                int shelfMonths;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT ShelfLifeMonths FROM Product WHERE ProductID=?")) {
                    ps.setInt(1, p.id());
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next(); shelfMonths = rs.getInt(1);
                    }
                }
                LocalDate expiry = prodDate.getValue().plusMonths(shelfMonths);
                int batchId;
                try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO ProductBatch (ProductID, WarehouseID, Quantity, ManufactureDate, ExpiryDate) " +
                    "VALUES (?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                    ps.setInt(1, p.id());
                    ps.setInt(2, w.id());
                    ps.setInt(3, 0);
                    ps.setDate(4, Date.valueOf(prodDate.getValue()));
                    ps.setDate(5, Date.valueOf(expiry));
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        keys.next(); batchId = keys.getInt(1);
                    }
                }
                int prodId;
                try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO ProductionOrder " +
                    "(ProductBatchID, ProductionDate, QuantityProduced, EmpID, Status) " +
                    "VALUES (?,?,?,?, 'Planned')", Statement.RETURN_GENERATED_KEYS)) {
                    ps.setInt(1, batchId);
                    ps.setDate(2, Date.valueOf(prodDate.getValue()));
                    ps.setInt(3, q);
                    ps.setInt(4, empId);
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        keys.next(); prodId = keys.getInt(1);
                    }
                }

                try (PreparedStatement insPM = conn.prepareStatement(
                        "INSERT INTO ProductionMaterial (ProductionID, RMBatchID, QuantityUsed) " +
                        "VALUES (?,?,?)");
                     PreparedStatement decRM = conn.prepareStatement(
                        "UPDATE RawMaterialBatch SET Quantity = Quantity - ? " +
                        "WHERE RMBatchID=? AND Quantity >= ?")) {
                    for (ProdMaterial pm : consumed) {
                        insPM.setInt(1, prodId);
                        insPM.setInt(2, pm.rmBatchId());
                        insPM.setInt(3, pm.qtyUsed());
                        insPM.executeUpdate();

                        decRM.setInt(1, pm.qtyUsed());
                        decRM.setInt(2, pm.rmBatchId());
                        decRM.setInt(3, pm.qtyUsed());
                        if (decRM.executeUpdate() != 1) {
                            throw new SQLException(
                                "Stock too low for RM batch #" + pm.rmBatchId() + ".");
                        }
                    }
                }

                conn.commit();
                Util.info("Production planned",
                    "Production #" + prodId + " created as Planned." + "\n" +
                    "Raw materials are reserved. When it is finished, click Complete to add " +
                    q + " units to batch #" + batchId + " in " + w.name() + " (expires " + expiry + ").");
                dialog.close();
                refresh();
            } catch (SQLException ex) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                err.setText("Failed: " + ex.getMessage());
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        });
        cancel.setOnAction(e -> dialog.close());

        Label step1 = heading2("Step 1 - Production details");
        GridPane grid1 = new GridPane();
        grid1.setHgap(10); grid1.setVgap(8);
        grid1.addRow(0, new Label("Product:"), productBox,
                        new Label("Qty to produce:"), qtyProduced);
        grid1.addRow(1, new Label("Produced in:"), prodWhBox,
                        new Label("Move to:"), whBox);
        grid1.addRow(2, new Label("Production date:"), prodDate);
        VBox step1Box = new VBox(6, grid1);

        Label step2 = heading2("Step 2 - Click Calculate Materials");
        HBox calcRow = new HBox(10, calcBtn);
        calcRow.setAlignment(Pos.CENTER_LEFT);

        Label step3 = heading2("Step 3 - Review materials to consume");

        Region g = new Region(); HBox.setHgrow(g, Priority.ALWAYS);
        HBox buttons = new HBox(10, g, cancel, save);

        VBox box = new VBox(12,
            heading("New Production Order"),
            step1, step1Box,
            step2, calcRow,
            step3, consumedTable,
            err, buttons);
        box.setPadding(new Insets(20));
        box.setPrefWidth(820);

        ScrollPane sp = new ScrollPane(box);
        sp.setFitToWidth(true);
        sp.setPrefSize(840, 700);
        dialog.setScene(new Scene(sp));
        dialog.showAndWait();
    }

    private List<ProdMaterial> allocateFEFO(int productId, int qtyToProduce, int warehouseId) throws SQLException {
        List<ProdMaterial> out = new ArrayList<>();
        record FRow(int materialId, String materialName, double qtyPer100) {}
        List<FRow> formula = new ArrayList<>();
        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT pf.MaterialID, rm.MaterialName, pf.QuantityPer100 " +
                "FROM ProductFormula pf " +
                "JOIN RawMaterial rm ON rm.MaterialID = pf.MaterialID " +
                "WHERE pf.ProductID = ? ORDER BY rm.MaterialName")) {
            ps.setInt(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    formula.add(new FRow(rs.getInt(1), rs.getString(2), rs.getDouble(3)));
                }
            }
        }
        for (FRow fr : formula) {
            int needed = (int) Math.ceil(fr.qtyPer100() * qtyToProduce / 100.0);
            int remaining = needed;
            try (PreparedStatement ps = DB.get().prepareStatement(
                    "SELECT RMBatchID, Quantity FROM RawMaterialBatch " +
                    "WHERE MaterialID = ? AND WarehouseID = ? AND ExpiryDate > CURRENT_DATE AND Quantity > 0 " +
                    "ORDER BY ExpiryDate ASC, RMBatchID ASC")) {
                ps.setInt(1, fr.materialId());
                ps.setInt(2, warehouseId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next() && remaining > 0) {
                        int batchId = rs.getInt(1);
                        int avail   = rs.getInt(2);
                        int take    = Math.min(remaining, avail);
                        out.add(new ProdMaterial(batchId,
                            fr.materialName() + " (batch #" + batchId + ")", take));
                        remaining -= take;
                    }
                }
            }
            if (remaining > 0) {
                throw new SQLException("Not enough non-expired stock of " +
                    fr.materialName() + " - need " + needed + ", short by " + remaining + ".");
            }
        }
        return out;
    }

    private record ProductRow(int id, String name) {
        @Override public String toString() { return name; }
    }
    private record WhRow(int id, String name) {
        @Override public String toString() { return name; }
    }
    private record EmpRow(int id, String name) {
        @Override public String toString() { return name; }
    }

    private List<ProductRow> fetchProducts() throws SQLException {
        List<ProductRow> out = new ArrayList<>();
        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT ProductID, ProductName FROM Product ORDER BY ProductName");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(new ProductRow(rs.getInt(1), rs.getString(2)));
        }
        return out;
    }
    private List<WhRow> fetchWarehouses() throws SQLException {
        List<WhRow> out = new ArrayList<>();
        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT WarehouseID, WarehouseName FROM Warehouse " +
                "WHERE Type IN ('FinishedGoods','Distribution') ORDER BY WarehouseName");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(new WhRow(rs.getInt(1), rs.getString(2)));
        }
        return out;
    }
    private List<WhRow> fetchRawMaterialWarehouses() throws SQLException {
        List<WhRow> out = new ArrayList<>();
        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT WarehouseID, WarehouseName FROM Warehouse " +
                "WHERE Type = 'RawMaterials' ORDER BY WarehouseName");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(new WhRow(rs.getInt(1), rs.getString(2)));
        }
        return out;
    }
    private List<EmpRow> fetchEmployees() throws SQLException {
        List<EmpRow> out = new ArrayList<>();
        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT EmpID, EmpName FROM Employee ORDER BY EmpName");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(new EmpRow(rs.getInt(1), rs.getString(2)));
        }
        return out;
    }

    private static <T, S> TableColumn<T, S> col(String text, double width,
            java.util.function.Function<T, javafx.beans.value.ObservableValue<S>> getter) {
        TableColumn<T, S> c = new TableColumn<>(text);
        c.setPrefWidth(width);
        c.setCellValueFactory(cd -> getter.apply(cd.getValue()));
        return c;
    }
    private static TableColumn<ProdOrder, String> statusCol() {
        TableColumn<ProdOrder, String> c = new TableColumn<>("Status");
        c.setPrefWidth(120);
        c.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().status()));
        c.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String status, boolean empty) {
                super.updateItem(status, empty);
                if (empty || status == null) { setGraphic(null); setText(null); }
                else { setGraphic(Util.pill(status)); setText(null); }
            }
        });
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
