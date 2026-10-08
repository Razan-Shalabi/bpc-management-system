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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class OrdersTab extends Tab implements Refreshable {

    public record Order(int id, int customerId, String customer, LocalDate orderDate,LocalDate deliveryDate, String status, BigDecimal total, BigDecimal discount) {}

    public record Line(int productId, String product, int qty, BigDecimal price) {
        public BigDecimal lineTotal() {
            return price.multiply(BigDecimal.valueOf(qty));
        }
    }

    private final ObservableList<Order> orders = FXCollections.observableArrayList();
    private final ObservableList<Line> items = FXCollections.observableArrayList();
    private final FilteredList<Order> filteredOrders = new FilteredList<>(orders, x -> true);

    private final TableView<Order> ordersTable = new TableView<>();
    private final TableView<Line> itemsTable = new TableView<>();
    private final ComboBox<String> customerFilter = new ComboBox<>();
    private final ComboBox<String> statusFilter   = new ComboBox<>();

    public OrdersTab() {
        setText("Orders");
        setClosable(false);

        ordersTable.setItems(filteredOrders);
        ordersTable.getColumns().addAll(
                col("Order ID", 90, (Order o) -> new SimpleObjectProperty<>(o.id())),
                col("Customer", 240, o -> new SimpleStringProperty(o.customer())),
                col("Date", 110, o -> new SimpleStringProperty(o.orderDate().toString())),
                col("Delivery", 110, o -> new SimpleStringProperty( o.deliveryDate() == null ? "—" : o.deliveryDate().toString())), statusCol(),
                col("Total", 120, o -> new SimpleStringProperty(Util.money(o.total()))),
                col("Discount", 100, o -> new SimpleStringProperty(Util.money(o.discount())))
        );

        ordersTable.getSelectionModel().selectedItemProperty()
                .addListener((obs, oldOrder, newOrder) -> loadItems(newOrder));

        itemsTable.setItems(items);
        itemsTable.getColumns().addAll(
                col("Product", 280, (Line l) -> new SimpleStringProperty(l.product())),
                col("Qty", 80, l -> new SimpleObjectProperty<>(l.qty())),
                col("Unit price", 110, l -> new SimpleStringProperty(Util.money(l.price()))),
                col("Line total", 140, l -> new SimpleStringProperty(Util.money(l.lineTotal())))
        );
        itemsTable.setPrefHeight(180);

        Button newBtn = primaryButton("New Order");
        Button deliverBtn = new Button("Mark Delivered");
        Button cancelBtn  = new Button("Cancel Order");
        cancelBtn.setStyle("-fx-background-color: #c0392b; -fx-text-fill: white;");
        Button invoiceBtn = new Button("Generate Invoice");

        newBtn.setOnAction(e -> openNewOrderWizard());
        deliverBtn.setOnAction(e -> updateStatus("Delivered"));
        cancelBtn.setOnAction(e -> cancelOrder());
        invoiceBtn.setOnAction(e -> generateInvoice());

        customerFilter.setPromptText("All customers");
        customerFilter.setPrefWidth(220);
        statusFilter.getItems().setAll("All statuses",
            "Pending", "Delivered", "Cancelled");
        statusFilter.getSelectionModel().selectFirst();
        statusFilter.setPrefWidth(140);

        Runnable applyFilter = () -> {
            String cust = customerFilter.getValue();
            String st   = statusFilter.getValue();
            filteredOrders.setPredicate(o -> {
                boolean okCust = cust == null || "All customers".equals(cust) || cust.equals(o.customer());
                boolean okStat = st   == null || "All statuses".equals(st)   || st.equals(o.status());
                return okCust && okStat;
            });
        };
        customerFilter.valueProperty().addListener((o, a, b) -> applyFilter.run());
        statusFilter.valueProperty().addListener((o, a, b) -> applyFilter.run());

        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);

        HBox toolbar = new HBox(10, heading("Sales Orders"), grow,
                                new Label("Customer:"), customerFilter,
                                new Label("Status:"),   statusFilter,
                                newBtn, deliverBtn, cancelBtn, invoiceBtn);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(10, toolbar, ordersTable,
                heading2("Line items for selected order:"), itemsTable);
        root.setPadding(new Insets(14));
        VBox.setVgrow(ordersTable, Priority.ALWAYS);

        setContent(root);
        refresh();
    }

    @Override
    public void refresh() {
        orders.clear();

        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT so.OrderID, so.CustomerID, c.CustomerName, so.OrderDate, so.DeliveryDate, " +
                "so.Status, so.TotalAmount, so.Discount " +
                "FROM SalesOrder so JOIN Customer c ON c.CustomerID = so.CustomerID " +
                "ORDER BY so.OrderDate DESC, so.OrderID DESC");
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                Date delivery = rs.getDate("DeliveryDate");

                orders.add(new Order(
                        rs.getInt("OrderID"),
                        rs.getInt("CustomerID"),
                        rs.getString("CustomerName"),
                        rs.getDate("OrderDate").toLocalDate(),
                        delivery == null ? null : delivery.toLocalDate(),
                        rs.getString("Status"),
                        rs.getBigDecimal("TotalAmount"),
                        rs.getBigDecimal("Discount")
                ));
            }

        } catch (SQLException ex) {
            Util.error("DB error", ex.getMessage());
        }

        String previousCust = customerFilter.getValue();
        java.util.TreeSet<String> distinctCustomers = new java.util.TreeSet<>();
        for (Order o : orders) distinctCustomers.add(o.customer());
        customerFilter.getItems().setAll("All customers");
        customerFilter.getItems().addAll(distinctCustomers);
        if (previousCust != null && customerFilter.getItems().contains(previousCust)) {
            customerFilter.setValue(previousCust);
        } else {
            customerFilter.getSelectionModel().selectFirst();  
        }
    }

    private void loadItems(Order order) {
        items.clear();

        if (order == null) return;

        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT p.ProductID, p.ProductName, soi.Quantity, soi.UnitPrice " +
                "FROM SalesOrderItem soi " +
                "JOIN Product p ON p.ProductID = soi.ProductID " +
                "WHERE soi.OrderID=? ORDER BY soi.SOItemID")) {

            ps.setInt(1, order.id());

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(new Line(
                            rs.getInt("ProductID"),
                            rs.getString("ProductName"),
                            rs.getInt("Quantity"),
                            rs.getBigDecimal("UnitPrice")
                    ));
                }
            }

        } catch (SQLException ex) {
            Util.error("DB error", ex.getMessage());
        }
    }

    private void updateStatus(String newStatus) {
        Order selected = ordersTable.getSelectionModel().getSelectedItem();

        if (selected == null) {
            Util.warn("No selection", "Select an order first.");
            return;
        }

        if (newStatus.equals(selected.status())) {
            Util.info("Nothing to do", "Already " + newStatus + ".");
            return;
        }

        if ("Cancelled".equals(selected.status())) {
            Util.warn("Cancelled order",
                "Order #" + selected.id() + " was cancelled - it cannot be marked " + newStatus + ".");
            return;
        }

        String sql = "Delivered".equals(newStatus)
            ? "UPDATE SalesOrder SET Status=?, DeliveryDate=CURRENT_DATE WHERE OrderID=?"
            : "UPDATE SalesOrder SET Status=? WHERE OrderID=?";
        try (PreparedStatement ps = DB.get().prepareStatement(sql)) {

            ps.setString(1, newStatus);
            ps.setInt(2, selected.id());
            ps.executeUpdate();
            refresh();

        } catch (SQLException ex) {
            Util.error("Update failed", ex.getMessage());
        }
    }

    private void cancelOrder() {
        Order selected = ordersTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            Util.warn("No selection", "Select an order first."); return;
        }
        if ("Cancelled".equals(selected.status())) {
            Util.info("Already cancelled", "Order #" + selected.id() + " is already cancelled.");
            return;
        }
        if ("Delivered".equals(selected.status())) {
            Util.warn("Already delivered",
                "Order #" + selected.id() + " has been delivered - it cannot be cancelled. " +
                "Process a return instead."); return;
        }
        if (!Util.confirm("Cancel order",
                "Cancel order #" + selected.id() + " and return its items to stock?")) return;

        Connection conn;
        try { conn = DB.get(); }
        catch (SQLException ex) { Util.error("DB error", ex.getMessage()); return; }

        try {
            conn.setAutoCommit(false);
            if (!removeUnpaidInvoice(conn, selected.id())) {
                conn.rollback();
                Util.warn("Payment received",
                    "Order #" + selected.id() + " already has payments on its invoice - " +
                    "it cannot be cancelled.");
                return;
            }
            try (PreparedStatement psLines = conn.prepareStatement(
                    "SELECT soib.ProductBatchID, soib.Quantity " +
                    "FROM SalesOrderItemBatch soib " +
                    "JOIN SalesOrderItem soi ON soi.SOItemID = soib.SOItemID " +
                    "WHERE soi.OrderID = ?");
                 PreparedStatement psRestore = conn.prepareStatement(
                    "UPDATE ProductBatch SET Quantity = Quantity + ? WHERE ProductBatchID = ?")) {
                psLines.setInt(1, selected.id());
                try (ResultSet rs = psLines.executeQuery()) {
                    while (rs.next()) {
                        psRestore.setInt(1, rs.getInt(2));
                        psRestore.setInt(2, rs.getInt(1));
                        psRestore.executeUpdate();
                    }
                }
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE SalesOrder SET Status='Cancelled' WHERE OrderID=?")) {
                ps.setInt(1, selected.id());
                ps.executeUpdate();
            }

            conn.commit();
            Util.info("Cancelled",
                "Order #" + selected.id() + " was cancelled and its items returned to stock.");
            refresh();
        } catch (SQLException ex) {
            try { conn.rollback(); } catch (SQLException ignored) {}
            Util.error("Cancel failed", ex.getMessage());
        } finally {
            try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }

    /**
     * Deletes the invoice of an order that is being cancelled, so it no longer
     * shows as money owed. Returns false (and deletes nothing) if the invoice
     * already has payments recorded against it.
     */
    static boolean removeUnpaidInvoice(Connection conn, int orderId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM CustomerPayment cp " +
                "JOIN Invoice i ON i.InvoiceID = cp.InvoiceID WHERE i.OrderID = ?")) {
            ps.setInt(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (rs.getInt(1) > 0) return false;
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM Invoice WHERE OrderID = ?")) {
            ps.setInt(1, orderId);
            ps.executeUpdate();
        }
        return true;
    }

    private void generateInvoice() {
        Order selected = ordersTable.getSelectionModel().getSelectedItem();

        if (selected == null) {
            Util.warn("No selection", "Select an order first.");
            return;
        }

        if (!"Delivered".equals(selected.status())) {
            Util.warn("Not delivered", "Only delivered orders can be invoiced. Use 'Mark Delivered' first.");
            return;
        }

        try (PreparedStatement ps = DB.get().prepareStatement(
                "INSERT INTO Invoice (OrderID, IssueDate, DueDate, TotalAmount, Status) " +
                "VALUES (?, CURRENT_DATE, DATE_ADD(CURRENT_DATE, INTERVAL 30 DAY), ?, 'Open')",
                Statement.RETURN_GENERATED_KEYS)) {

            ps.setInt(1, selected.id());
            ps.setBigDecimal(2, selected.total());
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    Util.info("Invoice created",
                            "Invoice #" + keys.getInt(1) + " issued for order #" + selected.id() + ".");
                }
            }

        } catch (SQLIntegrityConstraintViolationException ex) {
            Util.warn("Already invoiced",
                "Order #" + selected.id() + " already has an invoice.\n" +
                "Check the Invoices tab to view it.");
        } catch (SQLException ex) {
            Util.error("Invoice failed", ex.getMessage());
        }
    }

    private void openNewOrderWizard() {
        ComboBox<CustomerRow> customerBox = new ComboBox<>();
        ComboBox<ProductRow> productBox = new ComboBox<>();

        try {
            customerBox.getItems().setAll(fetchCustomers());
            productBox.getItems().setAll(fetchProducts());
        } catch (SQLException ex) {
            Util.error("DB error", ex.getMessage());
            return;
        }

        DatePicker datePicker = new DatePicker(LocalDate.now());

        TextField discountField = new TextField("0");
        discountField.setPrefWidth(80);

        Spinner<Integer> qtySpinner = Util.numeric(new Spinner<>(1, 100000, 1));
        qtySpinner.setPrefWidth(90);

        ObservableList<Line> cart = FXCollections.observableArrayList();
        TableView<Line> cartTable = new TableView<>(cart);

        cartTable.getColumns().addAll(
                col("Product", 280, (Line l) -> new SimpleStringProperty(l.product())),
                col("Qty", 80, l -> new SimpleObjectProperty<>(l.qty())),
                col("Unit price", 110, l -> new SimpleStringProperty(Util.money(l.price()))),
                col("Line total", 140, l -> new SimpleStringProperty(Util.money(l.lineTotal())))
        );
        cartTable.setPrefHeight(220);

        Label totalLbl = new Label("Total: 0.00 ILS");
        totalLbl.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        Runnable refreshTotal = () -> {
            BigDecimal sum = BigDecimal.ZERO;

            for (Line line : cart) {
                sum = sum.add(line.lineTotal());
            }

            BigDecimal discount;

            try {
                discount = new BigDecimal(discountField.getText().trim());
            } catch (NumberFormatException ex) {
                discount = BigDecimal.ZERO;
            }

            BigDecimal finalTotal = sum.subtract(discount);

            if (finalTotal.compareTo(BigDecimal.ZERO) < 0) {
                finalTotal = BigDecimal.ZERO;
            }

            totalLbl.setText("Total: " + Util.money(finalTotal));
        };

        cart.addListener((javafx.collections.ListChangeListener<Line>) c -> refreshTotal.run());
        discountField.textProperty().addListener((obs, oldValue, newValue) -> refreshTotal.run());

        Button addBtn = primaryButton("Add to cart");
        addBtn.setOnAction(e -> {
            ProductRow product = productBox.getValue();
            Integer quantity = qtySpinner.getValue();

            if (product == null || quantity == null || quantity <= 0) {
                Util.warn("Cannot add", "Pick a product and a positive quantity.");
                return;
            }

            int available;
            try (PreparedStatement ps = DB.get().prepareStatement(
                    "SELECT COALESCE(SUM(Quantity),0) FROM ProductBatch " +
                    "WHERE ProductID=? AND ExpiryDate > CURRENT_DATE")) {
                ps.setInt(1, product.id());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next(); available = rs.getInt(1);
                }
            } catch (SQLException ex) {
                Util.error("DB error", ex.getMessage()); return;
            }
            int inCart = 0;
            for (Line ln : cart) if (ln.productId() == product.id()) inCart += ln.qty();
            if (quantity + inCart > available) {
                Util.warn("Not enough stock",
                    "Only " + (available - inCart) + " units of " + product.name() +
                    " are available right now.");
                return;
            }

            cart.add(new Line(product.id(), product.name(), quantity, product.price()));
        });

        Button removeBtn = new Button("Remove line");
        removeBtn.setOnAction(e -> {
            Line selected = cartTable.getSelectionModel().getSelectedItem();

            if (selected != null) {
                cart.remove(selected);
            }
        });

        Label err = new Label();
        err.setStyle("-fx-text-fill: #c0392b;");

        Button placeBtn = primaryButton("Place Order");
        Button cancelBtn = new Button("Cancel");

        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("New Sales Order");

        placeBtn.setOnAction(e -> {
            err.setText("");

            CustomerRow customer = customerBox.getValue();

            if (customer == null) {
                err.setText("Pick a customer.");
                return;
            }

            if (cart.isEmpty()) {
                err.setText("Add at least one item.");
                return;
            }

            LocalDate orderDate = datePicker.getValue();
            if (orderDate == null) {
                err.setText("Pick an order date.");
                return;
            }
            if (orderDate.isAfter(LocalDate.now())) {
                err.setText("Order date cannot be in the future.");
                return;
            }

            BigDecimal discount;
            try {
                discount = Validate.money("Discount", discountField.getText(), 8);
            } catch (IllegalArgumentException ex) {
                err.setText(ex.getMessage());
                return;
            }
            if (discount.compareTo(BigDecimal.ZERO) < 0) {
                err.setText("Discount cannot be negative.");
                return;
            }
            BigDecimal cartGross = BigDecimal.ZERO;
            for (Line line : cart) cartGross = cartGross.add(line.lineTotal());
            if (discount.compareTo(cartGross) > 0) {
                err.setText("Discount (" + Util.money(discount) + ") cannot be more than the order value ("
                        + Util.money(cartGross) + ").");
                return;
            }

            Connection conn;

            try {
                conn = DB.get();
            } catch (SQLException ex) {
                err.setText(ex.getMessage());
                return;
            }

            try {
                conn.setAutoCommit(false);

                int orderId;

                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO SalesOrder (CustomerID, OrderDate, Status, TotalAmount, Discount) " +
                        "VALUES (?,?, 'Pending', 0, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {

                    ps.setInt(1, customer.id());
                    ps.setDate(2, Date.valueOf(orderDate));
                    ps.setBigDecimal(3, discount);
                    ps.executeUpdate();

                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        keys.next();
                        orderId = keys.getInt(1);
                    }
                }

                BigDecimal gross = BigDecimal.ZERO;
                try (PreparedStatement insertItem = conn.prepareStatement(
                        "INSERT INTO SalesOrderItem (OrderID, ProductID, Quantity, UnitPrice) " +
                        "VALUES (?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
                     PreparedStatement loadBatches = conn.prepareStatement(
                        "SELECT ProductBatchID, Quantity FROM ProductBatch " +
                        "WHERE ProductID=? AND ExpiryDate > CURRENT_DATE AND Quantity > 0 " +
                        "ORDER BY ExpiryDate ASC, ProductBatchID ASC");
                     PreparedStatement insertAlloc = conn.prepareStatement(
                        "INSERT INTO SalesOrderItemBatch (SOItemID, ProductBatchID, Quantity) " +
                        "VALUES (?,?,?)");
                     PreparedStatement updateBatch = conn.prepareStatement(
                        "UPDATE ProductBatch SET Quantity = Quantity - ? " +
                        "WHERE ProductBatchID=? AND Quantity >= ?")) {

                    for (Line line : cart) {
                        insertItem.setInt(1, orderId);
                        insertItem.setInt(2, line.productId());
                        insertItem.setInt(3, line.qty());
                        insertItem.setBigDecimal(4, line.price());
                        insertItem.executeUpdate();
                        int soItemId;
                        try (ResultSet keys = insertItem.getGeneratedKeys()) {
                            keys.next(); soItemId = keys.getInt(1);
                        }

                        int remaining = line.qty();
                        loadBatches.setInt(1, line.productId());
                        java.util.List<int[]> batches = new java.util.ArrayList<>();
                        try (ResultSet rs = loadBatches.executeQuery()) {
                            while (rs.next()) batches.add(new int[]{rs.getInt(1), rs.getInt(2)});
                        }
                        for (int[] b : batches) {
                            if (remaining <= 0) break;
                            int take = Math.min(remaining, b[1]);

                            updateBatch.setInt(1, take);
                            updateBatch.setInt(2, b[0]);
                            updateBatch.setInt(3, take);
                            if (updateBatch.executeUpdate() != 1) {
                                throw new SQLException(
                                    "Stock for batch #" + b[0] + " changed under us.");
                            }
                            insertAlloc.setInt(1, soItemId);
                            insertAlloc.setInt(2, b[0]);
                            insertAlloc.setInt(3, take);
                            insertAlloc.executeUpdate();
                            remaining -= take;
                        }
                        if (remaining > 0) {
                            throw new SQLException(
                                "Not enough stock for " + line.product() +
                                " - short by " + remaining + " unit(s).");
                        }

                        gross = gross.add(line.lineTotal());
                    }
                }

                BigDecimal finalTotal = gross.subtract(discount);

                if (finalTotal.compareTo(BigDecimal.ZERO) < 0) {
                    finalTotal = BigDecimal.ZERO;
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE SalesOrder SET TotalAmount=? WHERE OrderID=?")) {

                    ps.setBigDecimal(1, finalTotal);
                    ps.setInt(2, orderId);
                    ps.executeUpdate();
                }

                conn.commit();

                Util.info("Order saved", "Order #" + orderId + " created.");
                dialog.close();
                refresh();

            } catch (SQLException ex) {
                try {
                    conn.rollback();
                } catch (SQLException ignored) {}

                err.setText("Failed: " + ex.getMessage());

            } finally {
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException ignored) {}
            }
        });

        cancelBtn.setOnAction(e -> dialog.close());

        Region g1 = new Region();
        HBox.setHgrow(g1, Priority.ALWAYS);

        Region g2 = new Region();
        HBox.setHgrow(g2, Priority.ALWAYS);

        HBox header = new HBox(10,
                new Label("Customer:"), customerBox,
                new Label("Date:"), datePicker, g1,
                new Label("Discount:"), discountField);
        header.setAlignment(Pos.CENTER_LEFT);

        HBox picker = new HBox(10,
                new Label("Product:"), productBox,
                new Label("Qty:"), qtySpinner, addBtn);
        picker.setAlignment(Pos.CENTER_LEFT);
        picker.setStyle("-fx-background-color: #f4f6f8; -fx-padding: 10; -fx-background-radius: 6;");

        HBox totalRow = new HBox(10, removeBtn, g2, totalLbl);
        totalRow.setAlignment(Pos.CENTER_LEFT);

        Region buttonsGrow = new Region();
        HBox.setHgrow(buttonsGrow, Priority.ALWAYS);

        HBox buttons = new HBox(10, buttonsGrow, cancelBtn, placeBtn);

        VBox box = new VBox(12,
                heading("New Sales Order"),
                header,
                picker,
                heading2("Cart:"),
                cartTable,
                totalRow,
                err,
                buttons);

        box.setPadding(new Insets(20));
        box.setPrefWidth(800);

        dialog.setScene(new Scene(box));
        dialog.showAndWait();
    }

    private record CustomerRow(int id, String name) {
        @Override
        public String toString() {
            return name;
        }
    }

    private record ProductRow(int id, String name, BigDecimal price) {
        @Override
        public String toString() {
            return name;
        }
    }

    private record BatchRow(int id, int quantity, LocalDate expiry) {
        @Override
        public String toString() {
            return "Batch #" + id + " (qty " + quantity + ", exp " + expiry + ")";
        }
    }

    private List<CustomerRow> fetchCustomers() throws SQLException {
        List<CustomerRow> customers = new ArrayList<>();

        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT CustomerID, CustomerName FROM Customer ORDER BY CustomerName");
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                customers.add(new CustomerRow(rs.getInt(1), rs.getString(2)));
            }
        }

        return customers;
    }
    private List<ProductRow> fetchProducts() throws SQLException {
        List<ProductRow> products = new ArrayList<>();
        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT ProductID, ProductName, UnitPrice FROM Product ORDER BY ProductName");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                products.add(new ProductRow(rs.getInt(1), rs.getString(2), rs.getBigDecimal(3)));
            }
        }
        return products;
    }

 
    private static <T, S> TableColumn<T, S> col(String text, double width,
            java.util.function.Function<T, javafx.beans.value.ObservableValue<S>> getter) {
        TableColumn<T, S> c = new TableColumn<>(text);
        c.setPrefWidth(width);
        c.setCellValueFactory(cd -> getter.apply(cd.getValue()));
        return c;
    }
    private static TableColumn<Order, String> statusCol() {
        TableColumn<Order, String> c = new TableColumn<>("Status");
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
