package bpc;

import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Date;
import java.time.LocalDate;

public class CustomerMyOrdersTab extends Tab implements Refreshable {

    public record Order(int id, LocalDate orderDate, LocalDate deliveryDate, String status, BigDecimal total, BigDecimal discount) {}
    public record Line(String product, int qty, BigDecimal price) {
        public BigDecimal lineTotal() { return price.multiply(BigDecimal.valueOf(qty)); }
    }

    private final ObservableList<Order> orders = FXCollections.observableArrayList();
    private final ObservableList<Line>  items  = FXCollections.observableArrayList();
    private final TableView<Order> ordersTable = new TableView<>();
    private final TableView<Line>  itemsTable  = new TableView<>();

    public CustomerMyOrdersTab() {
        setText("My Orders");
        setClosable(false);

        ordersTable.setItems(orders);
        ordersTable.getColumns().addAll(
            col("Order #", 90, (Order o) -> new SimpleObjectProperty<>(o.id())),
            col("Date", 110, o -> new SimpleStringProperty(o.orderDate().toString())),
            col("Delivery", 110, o -> new SimpleStringProperty(o.deliveryDate() == null ? "—" : o.deliveryDate().toString())), statusCol(),
            col("Total", 140, o -> new SimpleStringProperty(Util.money(o.total()))),
            col("Discount", 110, o -> new SimpleStringProperty(Util.money(o.discount())))
        );
        ordersTable.getSelectionModel().selectedItemProperty()
                   .addListener((obs, oldOrder, newOrder) -> loadItems(newOrder));

        itemsTable.setItems(items);
        itemsTable.getColumns().addAll(
            col("Product", 320, (Line l) -> new SimpleStringProperty(l.product())),
            col("Qty", 80, l -> new SimpleObjectProperty<>(l.qty())),
            col("Unit price",120, l -> new SimpleStringProperty(Util.money(l.price()))),
            col("Line total",140, l -> new SimpleStringProperty(Util.money(l.lineTotal())))
        );
        itemsTable.setPrefHeight(200);

        Label welcome = heading("My Orders");
        Button confirmBtn = new Button("Confirm Delivery");
        confirmBtn.setStyle("-fx-background-color: #0d8a8a; -fx-text-fill: white; -fx-font-weight: bold;");
        confirmBtn.setOnAction(e -> confirmDelivery());
        Button cancelBtn = new Button("Cancel Order");
        cancelBtn.setStyle("-fx-background-color: #c0392b; -fx-text-fill: white; -fx-font-weight: bold;");
        cancelBtn.setOnAction(e -> cancelOrder());

        Region grow = new Region(); HBox.setHgrow(grow, Priority.ALWAYS);
        HBox toolbar = new HBox(10, welcome, grow, confirmBtn, cancelBtn);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(10, toolbar, ordersTable, heading2("Items in selected order:"), itemsTable);
        root.setPadding(new Insets(14));
        VBox.setVgrow(ordersTable, Priority.ALWAYS);
        setContent(root);

        refresh();
    }

    private void confirmDelivery() {
        Order sel = ordersTable.getSelectionModel().getSelectedItem();
        if (sel == null) {
            Util.warn("No selection", "Select an order from the table first."); return;
        }
        if ("Delivered".equals(sel.status())) {
            Util.info("Already delivered",
                "Order #" + sel.id() + " is already marked as delivered."); return;
        }
        if ("Cancelled".equals(sel.status())) {
            Util.warn("Cancelled order",
                "Order #" + sel.id() + " was cancelled - it cannot be confirmed."); return;
        }
        if (!Util.confirm("Confirm delivery",
                "Mark order #" + sel.id() + " as Delivered with today as the delivery date?")) {
            return;
        }
        try (PreparedStatement ps = DB.get().prepareStatement(
                "UPDATE SalesOrder SET Status='Delivered', DeliveryDate=CURRENT_DATE " +
                "WHERE OrderID=? AND CustomerID=?")) {
            ps.setInt(1, sel.id());
            ps.setInt(2, Session.customerId());  
            int n = ps.executeUpdate();
            if (n == 0) {
                Util.warn("Not updated",
                    "Order could not be updated. Refresh the table and try again.");
            } else {
                Util.info("Thank you",
                    "Order #" + sel.id() + " has been marked as Delivered.");
            }
            refresh();
        } catch (SQLException ex) {
            Util.error("Update failed", ex.getMessage());
        }
    }

    private void cancelOrder() {
        Order sel = ordersTable.getSelectionModel().getSelectedItem();
        if (sel == null) {
            Util.warn("No selection", "Select an order from the table first."); return;
        }
        if ("Cancelled".equals(sel.status())) {
            Util.info("Already cancelled",
                "Order #" + sel.id() + " is already cancelled."); return;
        }
        if ("Delivered".equals(sel.status())) {
            Util.warn("Already delivered",
                "Order #" + sel.id() + " has been delivered - it cannot be cancelled. " +
                "Contact BPC to process a return instead."); return;
        }
        if (!Util.confirm("Cancel order",
                "Cancel order #" + sel.id() + "? The items will be returned to BPC's stock " +
                "and you will not be charged.")) return;

        Connection conn;
        try { conn = DB.get(); }
        catch (SQLException ex) { Util.error("DB error", ex.getMessage()); return; }

        try {
            conn.setAutoCommit(false);
            if (!OrdersTab.removeUnpaidInvoice(conn, sel.id())) {
                conn.rollback();
                Util.warn("Payment received",
                    "You have already made a payment on the invoice for order #" + sel.id() +
                    ", so it cannot be cancelled here. Please contact BPC.");
                return;
            }

            try (PreparedStatement psLines = conn.prepareStatement(
                    "SELECT soib.ProductBatchID, soib.Quantity " +
                    "FROM SalesOrderItemBatch soib " +
                    "JOIN SalesOrderItem soi ON soi.SOItemID = soib.SOItemID " +
                    "WHERE soi.OrderID = ?");
                 PreparedStatement psRestore = conn.prepareStatement(
                    "UPDATE ProductBatch SET Quantity = Quantity + ? WHERE ProductBatchID = ?")) {
                psLines.setInt(1, sel.id());
                try (ResultSet rs = psLines.executeQuery()) {
                    while (rs.next()) {
                        psRestore.setInt(1, rs.getInt(2));
                        psRestore.setInt(2, rs.getInt(1));
                        psRestore.executeUpdate();
                    }
                }
            }

            int updated;
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE SalesOrder SET Status='Cancelled' " +
                    "WHERE OrderID=? AND CustomerID=?")) {
                ps.setInt(1, sel.id());
                ps.setInt(2, Session.customerId());
                updated = ps.executeUpdate();
            }
            if (updated == 0) {
                conn.rollback();
                Util.warn("Not cancelled",
                    "Order could not be cancelled. Refresh and try again.");
                return;
            }

            conn.commit();
            Util.info("Order cancelled",
                "Order #" + sel.id() + " has been cancelled. The items have been " +
                "returned to BPC's stock.");
            refresh();
        } catch (SQLException ex) {
            try { conn.rollback(); } catch (SQLException ignored) {}
            Util.error("Cancel failed", ex.getMessage());
        } finally {
            try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }

    @Override
    public void refresh() {
        orders.clear();
        items.clear();
        int customerId = Session.customerId();
        if (customerId <= 0) return;    

        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT OrderID, OrderDate, DeliveryDate, Status, TotalAmount, Discount " +
                "FROM SalesOrder WHERE CustomerID=? " +
                "ORDER BY OrderDate DESC, OrderID DESC")) {
            ps.setInt(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Date d = rs.getDate("DeliveryDate");
                    orders.add(new Order(
                        rs.getInt("OrderID"),
                        rs.getDate("OrderDate").toLocalDate(),
                        d == null ? null : d.toLocalDate(),
                        rs.getString("Status"),
                        rs.getBigDecimal("TotalAmount"),
                        rs.getBigDecimal("Discount")));
                }
            }
        } catch (SQLException ex) { Util.error("DB error", ex.getMessage()); }
    }

    private void loadItems(Order o) {
        items.clear();
        if (o == null) return;
        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT p.ProductName, soi.Quantity, soi.UnitPrice " +
                "FROM SalesOrderItem soi " +
                "JOIN Product p ON p.ProductID = soi.ProductID " +
                "WHERE soi.OrderID=? ORDER BY soi.SOItemID")) {
            ps.setInt(1, o.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(new Line(
                        rs.getString(1), rs.getInt(2), rs.getBigDecimal(3)));
                }
            }
        } catch (SQLException ex) { Util.error("DB error", ex.getMessage()); }
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
