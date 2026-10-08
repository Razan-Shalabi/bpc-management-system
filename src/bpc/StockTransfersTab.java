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
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;


public class StockTransfersTab extends Tab implements Refreshable {

    public record Transfer(int id, int batchId, String product, String fromWh, String toWh,
                           int qty, LocalDate date, String emp) {
        public String direction() {
            if (fromWh != null && fromWh.contains("Finished Goods")
                    && toWh != null && toWh.contains("Distribution")) {
                return "To Distribution";
            }
            if (fromWh != null && fromWh.contains("Distribution")
                    && toWh != null && toWh.contains("Finished Goods")) {
                return "Return to Finished Goods";
            }
            return fromWh + " -> " + toWh;
        }
    }

    private final ObservableList<Transfer> master = FXCollections.observableArrayList();
    private final FilteredList<Transfer> filtered = new FilteredList<>(master, t -> true);
    private final TableView<Transfer> table = new TableView<>();

    private final ComboBox<String> productFilter = new ComboBox<>();
    private final ComboBox<String> fromFilter = new ComboBox<>();
    private final ComboBox<String> toFilter = new ComboBox<>();
    private final DatePicker dateFilter = new DatePicker();

    public StockTransfersTab() {
        setText("Stock Transfers");
        setClosable(false);

        table.setItems(filtered);
        table.getColumns().addAll(
            col("Transfer #",       90,  (Transfer t) -> new SimpleObjectProperty<>(t.id())),
            col("Batch #",          80,  t -> new SimpleObjectProperty<>(t.batchId())),
            col("Product",         220,  t -> new SimpleStringProperty(t.product())),
            col("From Warehouse",  230,  t -> new SimpleStringProperty(t.fromWh())),
            col("To Warehouse",    230,  t -> new SimpleStringProperty(t.toWh())),
            col("Direction",       170,  t -> new SimpleStringProperty(t.direction())),
            col("Quantity",         90,  t -> new SimpleObjectProperty<>(t.qty())),
            col("Transfer Date",   120,  t -> new SimpleStringProperty(t.date().toString())),
            col("Handled By",      180,  t -> new SimpleStringProperty(t.emp()))
        );

        Button newBtn = primaryButton("New Transfer");
        newBtn.setOnAction(e -> openTransferWizard());

        productFilter.setPrefWidth(180);
        fromFilter.setPrefWidth(220);
        toFilter.setPrefWidth(220);
        dateFilter.setPrefWidth(140);
        productFilter.setValue("All");
        fromFilter.setValue("All");
        toFilter.setValue("All");
        dateFilter.setPromptText("Any date");

        productFilter.setOnAction(e -> applyFilters());
        fromFilter.setOnAction(e -> applyFilters());
        toFilter.setOnAction(e -> applyFilters());
        dateFilter.setOnAction(e -> applyFilters());

        Button clearFilters = new Button("Clear filters");
        clearFilters.setOnAction(e -> {
            productFilter.setValue("All");
            fromFilter.setValue("All");
            toFilter.setValue("All");
            dateFilter.setValue(null);
            applyFilters();
        });

        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox toolbar = new HBox(10, heading("Stock Transfers"), grow, newBtn);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        HBox filters = new HBox(10,
            new Label("Product:"), productFilter,
            new Label("From:"), fromFilter,
            new Label("To:"), toFilter,
            new Label("Date:"), dateFilter,
            clearFilters
        );
        filters.setAlignment(Pos.CENTER_LEFT);

        VBox top = new VBox(10, toolbar, filters);

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(14));
        root.setTop(top);
        BorderPane.setMargin(top, new Insets(0, 0, 10, 0));
        root.setCenter(table);
        setContent(root);

        refresh();
    }

    @Override
    public void refresh() {
        master.clear();
        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT st.TransferID, st.ProductBatchID, p.ProductName, " +
                "       wf.WarehouseName, wt.WarehouseName, st.Quantity, st.TransferDate, e.EmpName " +
                "FROM StockTransfer st " +
                "JOIN ProductBatch pb ON pb.ProductBatchID = st.ProductBatchID " +
                "JOIN Product p ON p.ProductID = pb.ProductID " +
                "JOIN Warehouse wf ON wf.WarehouseID = st.FromWarehouseID " +
                "JOIN Warehouse wt ON wt.WarehouseID = st.ToWarehouseID " +
                "JOIN Employee e ON e.EmpID = st.EmpID " +
                "WHERE wf.Type IN ('FinishedGoods', 'Distribution') " +
                "  AND wt.Type IN ('FinishedGoods', 'Distribution') " +
                "ORDER BY st.TransferDate DESC, st.TransferID DESC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                master.add(new Transfer(
                    rs.getInt(1), rs.getInt(2), rs.getString(3),
                    rs.getString(4), rs.getString(5), rs.getInt(6),
                    rs.getDate(7).toLocalDate(), rs.getString(8)));
            }
            rebuildFilterOptions();
            applyFilters();
        } catch (SQLException ex) {
            Util.error("DB error", ex.getMessage());
        }
    }

    private void rebuildFilterOptions() {
        String selectedProduct = productFilter.getValue();
        String selectedFrom = fromFilter.getValue();
        String selectedTo = toFilter.getValue();

        TreeSet<String> products = new TreeSet<>();
        TreeSet<String> froms = new TreeSet<>();
        TreeSet<String> tos = new TreeSet<>();

        for (Transfer t : master) {
            products.add(t.product());
            froms.add(t.fromWh());
            tos.add(t.toWh());
        }

        productFilter.getItems().setAll("All");
        productFilter.getItems().addAll(products);
        fromFilter.getItems().setAll("All");
        fromFilter.getItems().addAll(froms);
        toFilter.getItems().setAll("All");
        toFilter.getItems().addAll(tos);

        productFilter.setValue(productFilter.getItems().contains(selectedProduct) ? selectedProduct : "All");
        fromFilter.setValue(fromFilter.getItems().contains(selectedFrom) ? selectedFrom : "All");
        toFilter.setValue(toFilter.getItems().contains(selectedTo) ? selectedTo : "All");
    }

    private void applyFilters() {
        String product = productFilter.getValue();
        String from = fromFilter.getValue();
        String to = toFilter.getValue();
        LocalDate selectedDate = dateFilter.getValue();

        filtered.setPredicate(t -> {
            if (product != null && !"All".equals(product) && !product.equals(t.product())) return false;
            if (from != null && !"All".equals(from) && !from.equals(t.fromWh())) return false;
            if (to != null && !"All".equals(to) && !to.equals(t.toWh())) return false;
            if (selectedDate != null && !selectedDate.equals(t.date())) return false;
            return true;
        });
    }

    private void openTransferWizard() {
        ComboBox<BatchRow> batchBox = new ComboBox<>();
        ComboBox<WhRow> toBox = new ComboBox<>();
        batchBox.setPrefWidth(620);
        toBox.setPrefWidth(320);

        try {
            batchBox.getItems().setAll(fetchProductBatches());
        } catch (SQLException ex) {
            Util.error("DB error", ex.getMessage());
            return;
        }

        Spinner<Integer> qty = Util.numeric(new Spinner<>(1, 100000, 1));
        qty.setPrefWidth(100);

        DatePicker date = new DatePicker(LocalDate.now());

        String loggedEmpName = Session.empName() == null ? "Unknown" : Session.empName();
        Label empLabel = new Label(loggedEmpName);
        empLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #1a3a3a;");

        Label err = new Label();
        err.setStyle("-fx-text-fill: #c0392b;");
        Button save = primaryButton("Transfer");
        Button cancel = new Button("Cancel");

        batchBox.valueProperty().addListener((obs, oldBatch, newBatch) -> {
            err.setText("");
            toBox.getItems().clear();
            toBox.setValue(null);

            if (newBatch == null) return;

            qty.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, newBatch.qty(), 1));

            try {
                List<WhRow> allowedDestinations = fetchDestinationWarehouses(newBatch);
                toBox.getItems().setAll(allowedDestinations);

                if (allowedDestinations.isEmpty()) {
                    err.setText("No valid destination warehouse for this batch.");
                } else {
                    toBox.setValue(allowedDestinations.get(0));
                }
            } catch (SQLException ex) {
                err.setText("DB error: " + ex.getMessage());
            }
        });

        if (batchBox.getItems().isEmpty()) {
            err.setText("No available finished product batches to transfer.");
        }

        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("New Stock Transfer");

        save.setOnAction(e -> {
            err.setText("");
            BatchRow b = batchBox.getValue();
            WhRow tw = toBox.getValue();
            Integer q = qty.getValue();

            if (Session.empId() <= 0) { err.setText("No logged-in employee was found. Please sign in again."); return; }
            if (b == null) { err.setText("Pick a batch."); return; }
            if (tw == null) { err.setText("Pick a destination warehouse."); return; }
            if (q == null || q <= 0) { err.setText("Quantity must be > 0."); return; }
            if (q > b.qty()) { err.setText("Only " + b.qty() + " units available."); return; }
            if (b.expiryDate().isBefore(LocalDate.now())) { err.setText("Expired batches cannot be transferred."); return; }
            if (date.getValue() == null) { err.setText("Pick a transfer date."); return; }
            if (date.getValue().isAfter(LocalDate.now())) { err.setText("Transfer date cannot be in the future."); return; }
            if (b.fromWhId() == tw.id()) { err.setText("Source and destination warehouses must differ."); return; }
            if (!isDestinationAllowed(b, tw)) { err.setText("Invalid destination warehouse for this product transfer."); return; }

            long daysLeft = ChronoUnit.DAYS.between(LocalDate.now(), b.expiryDate());
            if (daysLeft >= 0 && daysLeft <= 30) {
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.initOwner(dialog);
                alert.setTitle("Batch expiring soon");
                alert.setHeaderText("This batch will expire soon.");
                alert.setContentText("Batch #" + b.id() + " expires on " + b.expiryDate()
                        + " (" + daysLeft + " days left). Continue transfer?");
                ButtonType yes = new ButtonType("Continue", ButtonBar.ButtonData.OK_DONE);
                ButtonType no = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
                alert.getButtonTypes().setAll(yes, no);
                if (alert.showAndWait().orElse(no) != yes) return;
            }

            Connection conn;
            try { conn = DB.get(); }
            catch (SQLException ex) { err.setText(ex.getMessage()); return; }

            try {
                conn.setAutoCommit(false);

                saveProductTransfer(conn, b, tw, q, date.getValue(), Session.empId());

                conn.commit();
                Util.info("Transferred", q + " units transferred from " + b.fromWhName() + " to " + tw.name() + ".");
                dialog.close();
                refresh();
            } catch (SQLException ex) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                err.setText("Transfer failed: " + ex.getMessage());
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        });

        cancel.setOnAction(e -> dialog.close());

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(12);

        ColumnConstraints labelCol = new ColumnConstraints();
        labelCol.setMinWidth(95);
        labelCol.setPrefWidth(95);

        ColumnConstraints fieldCol = new ColumnConstraints();
        fieldCol.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labelCol, fieldCol);

        grid.addRow(0, formLabel("Batch *"), batchBox);
        grid.addRow(1, formLabel("Move to *"), toBox);
        grid.addRow(2, formLabel("Quantity *"), qty);
        grid.addRow(3, formLabel("Date *"), date);
        grid.addRow(4, formLabel("Employee"), empLabel);

        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox buttons = new HBox(10, grow, cancel, save);

        VBox box = new VBox(12, heading("New Stock Transfer"), grid, err, buttons);
        box.setPadding(new Insets(20));
        box.setPrefWidth(780);
        dialog.setScene(new Scene(box, 780, 430));
        dialog.showAndWait();
    }

    private void saveProductTransfer(Connection conn, BatchRow b, WhRow tw, int q, LocalDate transferDate, int empId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO StockTransfer " +
                "(FromWarehouseID, ToWarehouseID, ProductBatchID, Quantity, TransferDate, EmpID) " +
                "VALUES (?,?,?,?,?,?)")) {
            ps.setInt(1, b.fromWhId());
            ps.setInt(2, tw.id());
            ps.setInt(3, b.id());
            ps.setInt(4, q);
            ps.setDate(5, Date.valueOf(transferDate));
            ps.setInt(6, empId);
            ps.executeUpdate();
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE ProductBatch SET Quantity = Quantity - ? WHERE ProductBatchID = ? AND Quantity >= ?")) {
            ps.setInt(1, q);
            ps.setInt(2, b.id());
            ps.setInt(3, q);
            if (ps.executeUpdate() != 1) throw new SQLException("Source batch quantity changed - retry.");
        }

        int existingDestBatch = -1;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT ProductBatchID FROM ProductBatch " +
                "WHERE ProductID=? AND WarehouseID=? " +
                "AND ExpiryDate=(SELECT ExpiryDate FROM ProductBatch WHERE ProductBatchID=?)")) {
            ps.setInt(1, b.productId());
            ps.setInt(2, tw.id());
            ps.setInt(3, b.id());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) existingDestBatch = rs.getInt(1);
            }
        }

        if (existingDestBatch > 0) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ProductBatch SET Quantity = Quantity + ? WHERE ProductBatchID=?")) {
                ps.setInt(1, q);
                ps.setInt(2, existingDestBatch);
                ps.executeUpdate();
            }
        } else {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO ProductBatch (ProductID, WarehouseID, Quantity, ManufactureDate, ExpiryDate) " +
                    "SELECT ProductID, ?, ?, ManufactureDate, ExpiryDate FROM ProductBatch WHERE ProductBatchID=?")) {
                ps.setInt(1, tw.id());
                ps.setInt(2, q);
                ps.setInt(3, b.id());
                ps.executeUpdate();
            }
        }
    }

    private record BatchRow(int id, int productId, String product, int qty,
                            int fromWhId, String fromWhName, String fromWhType, LocalDate expiryDate) {
        @Override public String toString() {
            long daysLeft = ChronoUnit.DAYS.between(LocalDate.now(), expiryDate);
            return "Batch #" + id + " - " + product + " (qty " + qty + " @ " + fromWhName
                    + ", exp " + expiryDate + ", " + daysLeft + " days left)";
        }
    }

    private record WhRow(int id, String name, String type) {
        @Override public String toString() { return name; }
    }

    private List<BatchRow> fetchProductBatches() throws SQLException {
        List<BatchRow> out = new ArrayList<>();
        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT pb.ProductBatchID, pb.ProductID, p.ProductName, pb.Quantity, " +
                "       pb.WarehouseID, w.WarehouseName, w.Type, pb.ExpiryDate " +
                "FROM ProductBatch pb " +
                "JOIN Product p ON p.ProductID = pb.ProductID " +
                "JOIN Warehouse w ON w.WarehouseID = pb.WarehouseID " +
                "WHERE pb.Quantity > 0 " +
                "  AND pb.ExpiryDate >= CURDATE() " +
                "  AND w.Type IN ('FinishedGoods', 'Distribution') " +
                "ORDER BY p.ProductName, pb.ProductBatchID")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new BatchRow(
                        rs.getInt(1), rs.getInt(2), rs.getString(3),
                        rs.getInt(4), rs.getInt(5), rs.getString(6), rs.getString(7),
                        rs.getDate(8).toLocalDate()));
                }
            }
        }
        return out;
    }

    private List<WhRow> fetchDestinationWarehouses(BatchRow sourceBatch) throws SQLException {
        List<WhRow> out = new ArrayList<>();
        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT WarehouseID, WarehouseName, Type " +
                "FROM Warehouse " +
                "WHERE WarehouseID <> ? " +
                "  AND Type IN ('FinishedGoods', 'Distribution') " +
                "ORDER BY WarehouseName")) {
            ps.setInt(1, sourceBatch.fromWhId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    WhRow wh = new WhRow(rs.getInt(1), rs.getString(2), rs.getString(3));
                    if (isDestinationAllowed(sourceBatch, wh)) out.add(wh);
                }
            }
        }
        return out;
    }

    private boolean isDestinationAllowed(BatchRow sourceBatch, WhRow destination) {
        String sourceType = sourceBatch.fromWhType();
        String destinationType = destination.type();

        if ("FinishedGoods".equals(sourceType)) {
            return "Distribution".equals(destinationType);
        }

        if ("Distribution".equals(sourceType)) {
            return "FinishedGoods".equals(destinationType);
        }

        return false;
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


    private static Label formLabel(String text) {
        Label l = new Label(text);
        l.setMinWidth(95);
        l.setPrefWidth(95);
        l.setMaxWidth(95);
        l.setStyle("-fx-text-fill: #222222;");
        return l;
    }

    private static Label heading(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: #1a3a3a;");
        return l;
    }
}
