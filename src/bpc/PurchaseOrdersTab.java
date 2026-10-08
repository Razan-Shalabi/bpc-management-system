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

import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;


public class PurchaseOrdersTab extends Tab implements Refreshable {

    public record PO(int id, int supplierId, String supplier, LocalDate orderDate,
                     LocalDate expectedDate, String status, BigDecimal totalCost) {}
    public record POLine(int materialId, String material, int quantity, BigDecimal unitCost) {
        public BigDecimal lineTotal() { return unitCost.multiply(BigDecimal.valueOf(quantity)); }
    }

    private final ObservableList<PO>     poList   = FXCollections.observableArrayList();
    private final ObservableList<POLine> items    = FXCollections.observableArrayList();
    private final TableView<PO>     poTable    = new TableView<>();
    private final TableView<POLine> itemsTable = new TableView<>();

    public PurchaseOrdersTab() {
        setText("Purchase Orders");
        setClosable(false);

        poTable.setItems(poList);
        poTable.getColumns().addAll(
            col("PO #",     80,  (PO p) -> new SimpleObjectProperty<>(p.id())),
            col("Supplier",230,  p -> new SimpleStringProperty(p.supplier())),
            col("Ordered",110,   p -> new SimpleStringProperty(p.orderDate().toString())),
            col("Expected",110,  p -> new SimpleStringProperty(p.expectedDate().toString())),
            statusCol(),
            col("Total cost",140, p -> new SimpleStringProperty(Util.money(p.totalCost())))
        );
        poTable.getSelectionModel().selectedItemProperty().addListener((o,a,sel) -> loadItems(sel));

        itemsTable.setItems(items);
        itemsTable.getColumns().addAll(
            col("Material ID", 90,  (POLine l) -> new SimpleObjectProperty<>(l.materialId())),
            col("Material",   260,  l -> new SimpleStringProperty(l.material())),
            col("Qty",         80,  l -> new SimpleObjectProperty<>(l.quantity())),
            col("Unit cost",  110,  l -> new SimpleStringProperty(Util.money(l.unitCost()))),
            col("Line total", 120,  l -> new SimpleStringProperty(Util.money(l.lineTotal())))
        );
        itemsTable.setPrefHeight(180);

        Button newBtn       = primaryButton("New PO");
        Button receivedBtn  = new Button("Mark Received");
        Button invoiceBtn   = new Button("Generate Supplier Invoice");
        Button cancelPoBtn  = new Button("Cancel PO");
        cancelPoBtn.setStyle("-fx-background-color: #c0392b; -fx-text-fill: white;");
        cancelPoBtn.setOnAction(e -> cancelPO());
        newBtn     .setOnAction(e -> openNewPOWizard());
        receivedBtn.setOnAction(e -> markReceived());
        invoiceBtn .setOnAction(e -> generateSupplierInvoice());

        Region grow = new Region(); HBox.setHgrow(grow, Priority.ALWAYS);
        HBox toolbar = new HBox(10, heading("Purchase Orders"), grow,
                                newBtn, receivedBtn, invoiceBtn, cancelPoBtn);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(10, toolbar, poTable,
                             heading2("Items in selected PO:"), itemsTable);
        root.setPadding(new Insets(14));
        VBox.setVgrow(poTable, Priority.ALWAYS);
        setContent(root);

        refresh();
    }

    @Override
    public void refresh() {
        poList.clear();
        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT po.POID, po.SupplierID, s.SupName, po.OrderDate, " +
                "       po.ExpectedDeliveryDate, po.Status, po.TotalCost " +
                "FROM PurchaseOrder po " +
                "JOIN Supplier s ON s.SupplierID = po.SupplierID " +
                "ORDER BY po.OrderDate DESC, po.POID DESC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                poList.add(new PO(
                    rs.getInt("POID"),
                    rs.getInt("SupplierID"),
                    rs.getString("SupName"),
                    rs.getDate("OrderDate").toLocalDate(),
                    rs.getDate("ExpectedDeliveryDate").toLocalDate(),
                    rs.getString("Status"),
                    rs.getBigDecimal("TotalCost")));
            }
        } catch (SQLException ex) { Util.error("DB error", ex.getMessage()); }
    }

    private void loadItems(PO po) {
        items.clear();
        if (po == null) return;
        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT poi.MaterialID, rm.MaterialName, poi.Quantity, poi.UnitCost " +
                "FROM PurchaseOrderItem poi " +
                "JOIN RawMaterial rm ON rm.MaterialID = poi.MaterialID " +
                "WHERE poi.POID = ? ORDER BY poi.POItemID")) {
            ps.setInt(1, po.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(new POLine(
                        rs.getInt(1), rs.getString(2),
                        rs.getInt(3), rs.getBigDecimal(4)));
                }
            }
        } catch (SQLException ex) { Util.error("DB error", ex.getMessage()); }
    }

    
    private String askForReceivingNotes(int poId) {
        javafx.stage.Stage dlg = new javafx.stage.Stage();
        dlg.initModality(javafx.stage.Modality.APPLICATION_MODAL);
        dlg.setTitle("Mark PO #" + poId + " as Received");

        Label title = new Label("Receiving notes for PO #" + poId);
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        Label hint = new Label(
            "Record any notes about the delivery (condition, damage, partial " +
            "quantities, CoA reference, etc.). Leave blank for no notes.");
        hint.setStyle("-fx-text-fill: #64748b; -fx-font-size: 12px;");
        hint.setWrapText(true);
        hint.setMaxWidth(380);

        TextArea notes = new TextArea();
        notes.setPromptText("e.g. All items received in good condition, COA checked.");
        notes.setWrapText(true);
        notes.setPrefRowCount(4);

        Button ok = primaryButton("Confirm receipt");
        Button cancel = new Button("Cancel");

        final String[] result = { null };
        ok.setOnAction(e -> {
            result[0] = notes.getText() == null ? "" : notes.getText().trim();
            dlg.close();
        });
        cancel.setOnAction(e -> { result[0] = null; dlg.close(); });

        Region grow = new Region(); HBox.setHgrow(grow, Priority.ALWAYS);
        HBox buttons = new HBox(10, grow, cancel, ok);

        VBox box = new VBox(10, title, hint, notes, buttons);
        box.setPadding(new Insets(20));
        box.setPrefWidth(440);
        dlg.setScene(new javafx.scene.Scene(box));
        dlg.showAndWait();
        return result[0];
    }

        private void markReceived() {
        PO sel = poTable.getSelectionModel().getSelectedItem();
        if (sel == null) { Util.warn("No selection", "Select a PO first."); return; }
        if ("Received".equals(sel.status())) {
            Util.info("Nothing to do", "PO #" + sel.id() + " is already Received."); return;
        }
        if ("Cancelled".equals(sel.status())) {
            Util.warn("Cancelled", "Cancelled POs cannot be received."); return;
        }

        
        String notes = askForReceivingNotes(sel.id());
        if (notes == null) return;   // user cancelled

        Connection conn;
        try { conn = DB.get(); }
        catch (SQLException ex) { Util.error("DB error", ex.getMessage()); return; }

        try {
            conn.setAutoCommit(false);

            int rmWarehouseId = rawMaterialsWarehouse(conn);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE PurchaseOrder SET Status='Received' WHERE POID=? AND Status='Pending'")) {
                ps.setInt(1, sel.id());
                if (ps.executeUpdate() != 1) {
                    throw new SQLException("PO #" + sel.id() + " is no longer Pending (it may have been "
                        + "received or cancelled by someone else). Refresh and try again.");
                }
            }

            
            int actorEmpId = Session.empId() > 0 ? Session.empId() : 1;
            int receiptId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO GoodsReceipt (POID, EmpID, ReceivedDate, Notes) " +
                    "VALUES (?, ?, CURRENT_DATE, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, sel.id());
                ps.setInt(2, actorEmpId);
                ps.setString(3, notes.isBlank() ? null : notes);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next(); receiptId = keys.getInt(1);
                }
            }

            
            try (PreparedStatement psItem = conn.prepareStatement(
                    "INSERT INTO GoodsReceiptItem (ReceiptID, POItemID, QuantityReceived, Notes) " +
                    "VALUES (?,?,?, 'Received in full')",
                    Statement.RETURN_GENERATED_KEYS);
                 PreparedStatement psBatch = conn.prepareStatement(
                    "INSERT INTO RawMaterialBatch " +
                    "(MaterialID, WarehouseID, ReceiptItemID, Quantity, ReceivedDate, ExpiryDate) " +
                    "VALUES (?, ?, ?, ?, CURRENT_DATE, DATE_ADD(CURRENT_DATE, INTERVAL 2 YEAR))");
                 PreparedStatement psLines = conn.prepareStatement(
                    "SELECT POItemID, MaterialID, Quantity FROM PurchaseOrderItem WHERE POID=?")) {

                psLines.setInt(1, sel.id());
                try (ResultSet rs = psLines.executeQuery()) {
                    while (rs.next()) {
                        int poItemId    = rs.getInt(1);
                        int materialId  = rs.getInt(2);
                        int qty         = rs.getInt(3);

                                                psItem.setInt(1, receiptId);
                        psItem.setInt(2, poItemId);
                        psItem.setInt(3, qty);
                        psItem.executeUpdate();

                        int receiptItemId;
                        try (ResultSet keys = psItem.getGeneratedKeys()) {
                            keys.next(); receiptItemId = keys.getInt(1);
                        }

                                                psBatch.setInt(1, materialId);
                        psBatch.setInt(2, rmWarehouseId);
                        psBatch.setInt(3, receiptItemId);
                        psBatch.setInt(4, qty);
                        psBatch.executeUpdate();
                    }
                }
            }

            conn.commit();
            Util.info("Received",
                "PO #" + sel.id() + " marked as Received.\n" +
                "Receipt #" + receiptId + " created with line items.\n" +
                "Raw-material batches added to the warehouse.");
            refresh();
        } catch (SQLException ex) {
            try { conn.rollback(); } catch (SQLException ignored) {}
            Util.error("Mark Received failed", ex.getMessage());
        } finally {
            try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }

    /** Received raw materials go to the employee's own RM warehouse, or the first RM warehouse. */
    private int rawMaterialsWarehouse(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT w.WarehouseID FROM Warehouse w " +
                "WHERE w.Type = 'RawMaterials' " +
                "ORDER BY (w.WarehouseID = (SELECT e.WarehouseID FROM Employee e WHERE e.EmpID = ?)) DESC, " +
                "         w.WarehouseID LIMIT 1")) {
            ps.setInt(1, Session.empId());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("There is no Raw Materials warehouse to receive into. "
                        + "Create one on the Warehouses tab first.");
                }
                return rs.getInt(1);
            }
        }
    }

    private void cancelPO() {
        PO sel = poTable.getSelectionModel().getSelectedItem();
        if (sel == null) { Util.warn("No selection", "Select a PO first."); return; }
        if (!"Pending".equals(sel.status())) {
            Util.warn("Cannot cancel", "Only Pending purchase orders can be cancelled. PO #"
                + sel.id() + " is " + sel.status() + ".");
            return;
        }
        if (!Util.confirm("Cancel PO", "Cancel purchase order #" + sel.id() + " to " + sel.supplier() + "?")) return;
        try (PreparedStatement ps = DB.get().prepareStatement(
                "UPDATE PurchaseOrder SET Status='Cancelled' WHERE POID=? AND Status='Pending'")) {
            ps.setInt(1, sel.id());
            if (ps.executeUpdate() != 1) {
                Util.warn("Not cancelled", "PO #" + sel.id() + " is no longer Pending. Refresh and try again.");
            }
            refresh();
        } catch (SQLException ex) {
            Util.error("Cancel failed", ex.getMessage());
        }
    }

        private void generateSupplierInvoice() {
        PO sel = poTable.getSelectionModel().getSelectedItem();
        if (sel == null) {
            Util.warn("No selection", "Select a PO first.");
            return;
        }

        if (!"Received".equals(sel.status())) {
            Util.warn("Not received",
                "Only received POs can be invoiced. Use 'Mark Received' first.");
            return;
        }

        if (sel.totalCost() == null || sel.totalCost().compareTo(BigDecimal.ZERO) <= 0) {
            Util.warn("Invalid total",
                "Cannot generate a supplier invoice for a PO with total cost 0.");
            return;
        }

        try {
                        try (PreparedStatement check = DB.get().prepareStatement(
                    "SELECT SuppInvoiceID FROM SupplierInvoice WHERE POID = ? LIMIT 1")) {
                check.setInt(1, sel.id());
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) {
                        Util.warn("Invoice already exists",
                            "PO #" + sel.id() + " already has supplier invoice #" +
                            rs.getInt("SuppInvoiceID") + ".\n" +
                            "You cannot generate another invoice for the same PO.");
                        return;
                    }
                }
            }

           
            try (PreparedStatement ps = DB.get().prepareStatement(
                    "INSERT INTO SupplierInvoice (POID, IssueDate, DueDate, TotalAmount, Status) " +
                    "VALUES (?, CURRENT_DATE, DATE_ADD(CURRENT_DATE, INTERVAL 45 DAY), ?, 'Open')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, sel.id());
                ps.setBigDecimal(2, sel.totalCost());
                ps.executeUpdate();

                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        Util.info("Supplier invoice created",
                            "Supplier invoice #" + keys.getInt(1) +
                            " issued for PO #" + sel.id() + ".");
                    }
                }
            }
        } catch (SQLException ex) {
            Util.error("Invoice failed", ex.getMessage());
        }
    }

    // ----------------- New PO wizard -----------------
    private void openNewPOWizard() {
        ComboBox<SupplierRow> supplierBox = new ComboBox<>();
        ComboBox<MaterialRow> materialBox = new ComboBox<>();
        materialBox.setPromptText("(pick a supplier first)");

        try {
            supplierBox.getItems().setAll(fetchSuppliers());
        } catch (SQLException ex) {
            Util.error("DB error", ex.getMessage()); return;
        }

        DatePicker orderDate    = new DatePicker(LocalDate.now());
        DatePicker expectedDate = new DatePicker(LocalDate.now().plusDays(30));

        Spinner<Integer> qtySpinner = Util.numeric(new Spinner<>(1, 100000, 100));
        qtySpinner.setPrefWidth(90);

        TextField unitCostField = new TextField("0.00");
        unitCostField.setPrefWidth(110);

        ObservableList<POLine> cart = FXCollections.observableArrayList();

        
        final boolean[] revertingSupplier = { false };
        supplierBox.valueProperty().addListener((obs, oldS, newS) -> {
            if (revertingSupplier[0]) return;

            if (oldS != null && newS != null && !cart.isEmpty()) {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
                confirm.setTitle("Supplier changed");
                confirm.setHeaderText("This purchase order already has items.");
                confirm.setContentText(
                    "A purchase order can be created for one supplier only.\n" +
                    "Changing the supplier will remove the current PO items and start a new PO.\n\n" +
                    "Do you want to continue?");

                ButtonType yes = new ButtonType("Yes, start new PO", ButtonBar.ButtonData.OK_DONE);
                ButtonType no  = new ButtonType("No, keep current supplier", ButtonBar.ButtonData.CANCEL_CLOSE);
                confirm.getButtonTypes().setAll(yes, no);

                if (confirm.showAndWait().orElse(no) != yes) {
                    revertingSupplier[0] = true;
                    supplierBox.setValue(oldS);
                    revertingSupplier[0] = false;
                    return;
                }
                cart.clear();
            }

            materialBox.getItems().clear();
            materialBox.setValue(null);
            if (newS == null) {
                materialBox.setPromptText("(pick a supplier first)");
                return;
            }
            try {
                materialBox.getItems().setAll(fetchMaterialsBySupplier(newS.id()));
                if (materialBox.getItems().isEmpty()) {
                    materialBox.setPromptText("No materials linked to this supplier");
                } else {
                    materialBox.setPromptText("Pick a material");
                }
            } catch (SQLException ex) {
                Util.error("DB error", ex.getMessage());
            }
        });

        TableView<POLine> cartTable = new TableView<>(cart);
        cartTable.getColumns().addAll(
            col("Material",  260, (POLine l) -> new SimpleStringProperty(l.material())),
            col("Qty",        80, l -> new SimpleObjectProperty<>(l.quantity())),
            col("Unit cost", 110, l -> new SimpleStringProperty(Util.money(l.unitCost()))),
            col("Line total",120, l -> new SimpleStringProperty(Util.money(l.lineTotal())))
        );
        cartTable.setPrefHeight(220);

        Label totalLbl = new Label("Total: 0.00 ILS");
        totalLbl.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        Runnable refreshTotal = () -> {
            BigDecimal sum = BigDecimal.ZERO;
            for (POLine l : cart) sum = sum.add(l.lineTotal());
            totalLbl.setText("Total: " + Util.money(sum));
        };
        cart.addListener((javafx.collections.ListChangeListener<POLine>) c -> refreshTotal.run());

        Button addBtn = primaryButton("Add to PO");
        addBtn.setOnAction(e -> {
            MaterialRow m = materialBox.getValue();
            Integer q = qtySpinner.getValue();
            BigDecimal price;
            if (m == null) { Util.warn("Cannot add", "Pick a material."); return; }
            if (q == null || q <= 0) { Util.warn("Cannot add", "Quantity must be > 0."); return; }
            try {
                price = Validate.money("Unit cost", unitCostField.getText(), 8);
                if (price.compareTo(BigDecimal.ZERO) <= 0) {
                    Util.warn("Cannot add", "Unit cost must be greater than 0."); return;
                }
            } catch (IllegalArgumentException ex) {
                Util.warn("Cannot add", ex.getMessage()); return;
            }
            cart.add(new POLine(m.id(), m.name(), q, price));
        });

        Button removeBtn = new Button("Remove line");
        removeBtn.setOnAction(e -> {
            POLine sel = cartTable.getSelectionModel().getSelectedItem();
            if (sel != null) cart.remove(sel);
        });

        Label err = new Label(); err.setStyle("-fx-text-fill: #c0392b;");
        Button placeBtn  = primaryButton("Place PO");
        Button cancelBtn = new Button("Cancel");

        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("New Purchase Order");

        placeBtn.setOnAction(e -> {
            err.setText("");

            SupplierRow s = supplierBox.getValue();
            if (s == null) {
                err.setText("Pick a supplier.");
                Util.warn("Cannot place PO", "Pick a supplier.");
                return;
            }

            
            LocalDate od = readDatePickerValue(orderDate);
            LocalDate ed = readDatePickerValue(expectedDate);

            if (od == null || ed == null) {
                err.setText("Both dates are required and must be valid.");
                Util.warn("Cannot place PO", "Both dates are required and must be valid.");
                return;
            }

            // IMPORTANT: Expected delivery date must not be earlier than the order date.
            // This check is done before any INSERT, so the PO cannot be saved if dates are invalid.
            if (ed.isBefore(od)) {
                err.setText("Expected date cannot be before order date.");
                Util.warn("Invalid dates", "Expected date cannot be before order date.");
                return;
            }

            if (cart.isEmpty()) {
                err.setText("Add at least one material.");
                Util.warn("Cannot place PO", "Add at least one material.");
                return;
            }

            BigDecimal checkTotal = BigDecimal.ZERO;
            for (POLine l : cart) checkTotal = checkTotal.add(l.lineTotal());
            if (checkTotal.compareTo(BigDecimal.ZERO) <= 0) {
                err.setText("Total cost must be greater than 0.");
                Util.warn("Cannot place PO", "Total cost must be greater than 0.");
                return;
            }
            if (checkTotal.compareTo(new BigDecimal("9999999999.99")) > 0) {
                err.setText("Total cost is too large for one purchase order - split it into several POs.");
                return;
            }
            if (od.isAfter(LocalDate.now())) {
                err.setText("Order date cannot be in the future.");
                return;
            }

            Connection conn;
            try { conn = DB.get(); }
            catch (SQLException ex) { err.setText(ex.getMessage()); return; }

            try {
                conn.setAutoCommit(false);

                                int poId;
                try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO PurchaseOrder (SupplierID, OrderDate, ExpectedDeliveryDate, Status, TotalCost) " +
                    "VALUES (?,?,?, 'Pending', 0)",
                    Statement.RETURN_GENERATED_KEYS)) {
                    ps.setInt(1, s.id());
                    ps.setDate(2, Date.valueOf(od));
                    ps.setDate(3, Date.valueOf(ed));
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        keys.next(); poId = keys.getInt(1);
                    }
                }

                // Items
                BigDecimal total = BigDecimal.ZERO;
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO PurchaseOrderItem (POID, MaterialID, Quantity, UnitCost) " +
                        "VALUES (?,?,?,?)")) {
                    for (POLine l : cart) {
                        ps.setInt(1, poId);
                        ps.setInt(2, l.materialId());
                        ps.setInt(3, l.quantity());
                        ps.setBigDecimal(4, l.unitCost());
                        ps.executeUpdate();
                        total = total.add(l.lineTotal());
                    }
                }

                                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE PurchaseOrder SET TotalCost=? WHERE POID=?")) {
                    ps.setBigDecimal(1, total);
                    ps.setInt(2, poId);
                    ps.executeUpdate();
                }

                conn.commit();
                Util.info("PO created", "Purchase order #" + poId + " saved.");
                dialog.close();
                refresh();
            } catch (SQLException ex) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                err.setText("Failed: " + ex.getMessage());
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        });
        cancelBtn.setOnAction(e -> dialog.close());

        HBox header = new HBox(10,
            new Label("Supplier:"), supplierBox,
            new Label("Order date:"), orderDate,
            new Label("Expected:"), expectedDate);
        header.setAlignment(Pos.CENTER_LEFT);

        HBox picker = new HBox(10,
            new Label("Material:"), materialBox,
            new Label("Qty:"), qtySpinner,
            new Label("Unit cost:"), unitCostField,
            addBtn);
        picker.setAlignment(Pos.CENTER_LEFT);
        picker.setStyle("-fx-background-color: #f4f6f8; -fx-padding: 10; -fx-background-radius: 6;");

        Region g = new Region(); HBox.setHgrow(g, Priority.ALWAYS);
        HBox totalRow = new HBox(10, removeBtn, g, totalLbl);
        totalRow.setAlignment(Pos.CENTER_LEFT);

        Region g2 = new Region(); HBox.setHgrow(g2, Priority.ALWAYS);
        HBox buttons = new HBox(10, g2, cancelBtn, placeBtn);

        VBox box = new VBox(12,
            heading("New Purchase Order"),
            header, picker,
            heading2("Items:"), cartTable, totalRow,
            err, buttons);
        box.setPadding(new Insets(20));
        box.setPrefWidth(820);
        dialog.setScene(new Scene(box));
        dialog.showAndWait();
    }

    private record SupplierRow(int id, String name) {
        @Override public String toString() { return name; }
    }
    private record MaterialRow(int id, String name) {
        @Override public String toString() { return name; }
    }

    private List<SupplierRow> fetchSuppliers() throws SQLException {
        List<SupplierRow> out = new ArrayList<>();
        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT SupplierID, SupName FROM Supplier ORDER BY SupName");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(new SupplierRow(rs.getInt(1), rs.getString(2)));
        }
        return out;
    }
    private List<MaterialRow> fetchMaterials() throws SQLException {
        List<MaterialRow> out = new ArrayList<>();
        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT MaterialID, MaterialName FROM RawMaterial ORDER BY MaterialName");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(new MaterialRow(rs.getInt(1), rs.getString(2)));
        }
        return out;
    }

    private List<MaterialRow> fetchMaterialsBySupplier(int supplierId) throws SQLException {
        List<MaterialRow> out = new ArrayList<>();
        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT rm.MaterialID, rm.MaterialName " +
                "FROM RawMaterial rm " +
                "JOIN SupplierMaterial sm ON sm.MaterialID = rm.MaterialID " +
                "WHERE sm.SupplierID = ? " +
                "ORDER BY rm.MaterialName")) {
            ps.setInt(1, supplierId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new MaterialRow(rs.getInt(1), rs.getString(2)));
            }
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
    
    private static TableColumn<PO, String> statusCol() {
        TableColumn<PO, String> c = new TableColumn<>("Status");
        c.setPrefWidth(120);
        c.setCellValueFactory(cd -> new SimpleStringProperty(effectiveStatus(cd.getValue())));
        c.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String status, boolean empty) {
                super.updateItem(status, empty);
                if (empty || status == null) { setGraphic(null); setText(null); }
                else { setGraphic(Util.pill(status)); setText(null); }
            }
        });
        return c;
    }


    private LocalDate readDatePickerValue(DatePicker picker) {
        if (picker == null) return null;

        String text = picker.getEditor() == null ? null : picker.getEditor().getText();
        if (text != null && !text.trim().isEmpty()) {
            String t = text.trim();

            DateTimeFormatter[] formats = new DateTimeFormatter[] {
                DateTimeFormatter.ofPattern("M/d/yyyy"),
                DateTimeFormatter.ofPattern("MM/dd/yyyy"),
                DateTimeFormatter.ISO_LOCAL_DATE
            };

            for (DateTimeFormatter fmt : formats) {
                try {
                    LocalDate parsed = LocalDate.parse(t, fmt);
                    picker.setValue(parsed);
                    return parsed;
                } catch (DateTimeParseException ignored) {
                    // try next format
                }
            }
            return null;
        }

        return picker.getValue();
    }

    private static String effectiveStatus(PO p) {
        if ("Pending".equals(p.status())
                && p.expectedDate() != null
                && p.expectedDate().isBefore(LocalDate.now())) {
            return "Overdue";
        }
        return p.status();
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
