package bpc;

import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class CustomerCartTab extends Tab implements Refreshable {

    private final TableView<Cart.Item> table = new TableView<>();
    private final Label totalLbl = new Label("Total: 0.00 ILS");
    private final Label countLbl = new Label("0 items");

    private final TextField payAmount = new TextField("0.00");
    private final ComboBox<String> payMethod = new ComboBox<>(FXCollections.observableArrayList(
        "BankTransfer", "CreditCard", "Cheque", "Cash"));
    private final Label payHint = new Label();

    public CustomerCartTab() {
        setText("Cart");
        setClosable(false);

        TableColumn<Cart.Item, String> cProduct = new TableColumn<>("Product");
        cProduct.setPrefWidth(320);
        cProduct.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().productName()));

        TableColumn<Cart.Item, Number> cQty = new TableColumn<>("Qty");
        cQty.setPrefWidth(90);
        cQty.setCellValueFactory(cd -> new SimpleObjectProperty<>(cd.getValue().quantity()));

        TableColumn<Cart.Item, String> cPrice = new TableColumn<>("Unit price");
        cPrice.setPrefWidth(140);
        cPrice.setCellValueFactory(cd -> new SimpleStringProperty(Util.money(cd.getValue().unitPrice())));

        TableColumn<Cart.Item, String> cLine = new TableColumn<>("Line total");
        cLine.setPrefWidth(160);
        cLine.setCellValueFactory(cd -> new SimpleStringProperty(Util.money(cd.getValue().lineTotal())));

        table.getColumns().addAll(cProduct, cQty, cPrice, cLine);
        table.setItems(Cart.items());
        table.setPlaceholder(new Label("Your cart is empty. Add items from the Browse Catalog tab."));

        Button removeBtn = new Button("Remove selected");
        removeBtn.setOnAction(e -> {
            Cart.Item sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) { Util.warn("No selection", "Select a line first."); return; }
            Cart.remove(sel);
            recomputeFooter();
        });

        Button clearBtn = new Button("Clear cart");
        clearBtn.setStyle("-fx-background-color: #c0392b; -fx-text-fill: white;");
        clearBtn.setOnAction(e -> {
            if (Cart.isEmpty()) return;
            if (Util.confirm("Clear cart", "Remove all items from your cart?")) {
                Cart.clear();
                recomputeFooter();
            }
        });

        Button placeBtn = new Button("Place Order");
        placeBtn.setStyle("-fx-background-color: #0d8a8a; -fx-text-fill: white; " +
                          "-fx-font-weight: bold; -fx-font-size: 14px; -fx-padding: 8 18;");
        placeBtn.setOnAction(e -> placeOrder());
        totalLbl.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #0d8a8a;");
        countLbl.setStyle("-fx-text-fill: #64748b; -fx-font-size: 12px;");
        Cart.items().addListener((javafx.collections.ListChangeListener<Cart.Item>) ch -> recomputeFooter());

        Label payHeader = new Label("Payment");
        payHeader.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));
        payHeader.setStyle("-fx-text-fill: #114e60;");

        payMethod.getSelectionModel().selectFirst();
        payAmount.setPrefWidth(110);
        payAmount.textProperty().addListener((o, a, b) -> updatePayHint());

        Button payFullBtn = new Button("Pay in full");
        payFullBtn.setOnAction(e -> {
            payAmount.setText(Cart.total().toPlainString());
            updatePayHint();
        });
        Button payNoneBtn = new Button("Pay later");
        payNoneBtn.setOnAction(e -> {
            payAmount.setText("0.00");
            updatePayHint();
        });

        payHint.setStyle("-fx-text-fill: #6b7280; -fx-font-size: 11px;");

        GridPane payGrid = new GridPane();
        payGrid.setHgap(10); payGrid.setVgap(8);
        payGrid.addRow(0, new Label("Amount paid now (ILS):"), payAmount, payFullBtn, payNoneBtn);
        payGrid.addRow(1, new Label("Payment method:"), payMethod);
        VBox paySection = new VBox(6, payHeader, payGrid, payHint);
        paySection.setPadding(new Insets(8, 12, 8, 12));
        paySection.setStyle("-fx-background-color: #f1f5f9; -fx-background-radius: 6;");

        Region grow = new Region(); HBox.setHgrow(grow, Priority.ALWAYS);
        HBox footerRow = new HBox(20,
            new VBox(2, countLbl, totalLbl),
            grow,
            removeBtn, clearBtn, placeBtn);
        footerRow.setAlignment(Pos.CENTER_LEFT);
        footerRow.setPadding(new Insets(12, 0, 0, 0));

        Label heading = new Label("Shopping cart");
        heading.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: #114e60;");

        VBox root = new VBox(8, heading, table, paySection, footerRow);
        root.setPadding(new Insets(14));
        VBox.setVgrow(table, Priority.ALWAYS);
        setContent(root);

        recomputeFooter();
    }

    private void recomputeFooter() {
        totalLbl.setText("Total: " + Util.money(Cart.total()));
        countLbl.setText(Cart.itemCount() + " unit(s) across " + Cart.items().size() + " line(s)");
        updatePayHint();
    }

    private void updatePayHint() {
        BigDecimal due = Cart.total();
        BigDecimal paying = parseAmount();
        if (due.signum() == 0) {
            payHint.setText("Add items to your cart to enable payment.");
            return;
        }
        if (paying.signum() < 0) {
            payHint.setText("Amount must be 0 or more.");
            return;
        }
        if (paying.compareTo(due) > 0) {
            payHint.setText("Amount exceeds the order total of " + Util.money(due) + ".");
            return;
        }
        if (paying.signum() == 0) {
            payHint.setText("No payment now: invoice will be issued as Open. " +
                            "You can pay later from My Invoices.");
        } else if (paying.compareTo(due) < 0) {
            BigDecimal remaining = due.subtract(paying);
            payHint.setText("Partial payment: invoice will be marked PartiallyPaid. " +
                            "Remaining balance: " + Util.money(remaining));
        } else {
            payHint.setText("Full payment: invoice will be marked Paid.");
        }
    }

    private BigDecimal parseAmount() {
        try { return new BigDecimal(payAmount.getText().trim()); }
        catch (Exception ex) { return BigDecimal.valueOf(-1); }
    }

    @Override
    public void refresh() {
        recomputeFooter();
        table.refresh();
    }

    private void placeOrder() {
        if (Cart.isEmpty()) {
            Util.warn("Empty cart", "Add at least one item to your cart first."); return;
        }
        int customerId = Session.customerId();
        if (customerId <= 0) {
            Util.error("Not signed in", "You must be signed in as a customer to place an order.");
            return;
        }
        BigDecimal due    = Cart.total();
        BigDecimal paying = parseAmount();
        if (paying.signum() < 0) {
            Util.warn("Bad amount", "Enter a payment amount of 0 or more."); return;
        }
        if (paying.compareTo(due) > 0) {
            Util.warn("Amount too high",
                "You can pay at most " + Util.money(due) + " (the order total)."); return;
        }
        String confirmLine = paying.signum() == 0
            ? "Place this order without paying now? Invoice will be Open."
            : paying.compareTo(due) < 0
                ? "Place this order and pay " + Util.money(paying) +
                  " now (remaining " + Util.money(due.subtract(paying)) + ")?"
                : "Place this order and pay " + Util.money(paying) + " now in full?";
        if (!Util.confirm("Place order", confirmLine)) return;

        Connection conn;
        try { conn = DB.get(); }
        catch (SQLException ex) { Util.error("DB error", ex.getMessage()); return; }

        try {
            conn.setAutoCommit(false);

            int orderId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO SalesOrder " +
                    "(CustomerID, OrderDate, Status, TotalAmount, Discount) " +
                    "VALUES (?, CURRENT_DATE, 'Pending', 0, 0)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, customerId);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next(); orderId = keys.getInt(1);
                }
            }

            try (PreparedStatement psBatches = conn.prepareStatement(
                    "SELECT ProductBatchID, Quantity FROM ProductBatch pb " +
                    "WHERE ProductID = ? AND ExpiryDate > CURRENT_DATE AND Quantity > 0 " +
                    "ORDER BY ExpiryDate ASC, ProductBatchID ASC");
                 PreparedStatement psItem = conn.prepareStatement(
                    "INSERT INTO SalesOrderItem " +
                    "(OrderID, ProductID, Quantity, UnitPrice) " +
                    "VALUES (?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
                 PreparedStatement psAlloc = conn.prepareStatement(
                    "INSERT INTO SalesOrderItemBatch " +
                    "(SOItemID, ProductBatchID, Quantity) " +
                    "VALUES (?,?,?)");
                 PreparedStatement psDec = conn.prepareStatement(
                    "UPDATE ProductBatch SET Quantity = Quantity - ? " +
                    "WHERE ProductBatchID = ? AND Quantity >= ?")) {

                for (Cart.Item line : Cart.items()) {
                    psItem.setInt(1, orderId);
                    psItem.setInt(2, line.productId());
                    psItem.setInt(3, line.quantity());
                    psItem.setBigDecimal(4, line.unitPrice());
                    psItem.executeUpdate();
                    int soItemId;
                    try (ResultSet keys = psItem.getGeneratedKeys()) {
                        keys.next(); soItemId = keys.getInt(1);
                    }

                    int remaining = line.quantity();
                    List<int[]> batches = new ArrayList<>();
                    psBatches.setInt(1, line.productId());
                    try (ResultSet rs = psBatches.executeQuery()) {
                        while (rs.next()) batches.add(new int[]{rs.getInt(1), rs.getInt(2)});
                    }
                    for (int[] b : batches) {
                        if (remaining <= 0) break;
                        int batchId = b[0];
                        int avail   = b[1];
                        int take    = Math.min(remaining, avail);

                        psDec.setInt(1, take);
                        psDec.setInt(2, batchId);
                        psDec.setInt(3, take);
                        if (psDec.executeUpdate() != 1) {
                            throw new SQLException(
                                "Stock for batch #" + batchId + " was consumed by another process.");
                        }
                        psAlloc.setInt(1, soItemId);
                        psAlloc.setInt(2, batchId);
                        psAlloc.setInt(3, take);
                        psAlloc.executeUpdate();
                        remaining -= take;
                    }
                    if (remaining > 0) {
                        throw new SQLException(
                            "Not enough non-expired stock for " + line.productName() +
                            " - " + remaining + " unit(s) short.");
                    }
                }
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE SalesOrder SET TotalAmount = ? WHERE OrderID = ?")) {
                ps.setBigDecimal(1, due);
                ps.setInt(2, orderId);
                ps.executeUpdate();
            }

            String invoiceStatus =
                paying.compareTo(due) >= 0 ? "Paid" :
                paying.signum() > 0        ? "PartiallyPaid" : "Open";
            int invoiceId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO Invoice (OrderID, IssueDate, DueDate, TotalAmount, Status) " +
                    "VALUES (?, CURRENT_DATE, DATE_ADD(CURRENT_DATE, INTERVAL 30 DAY), ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, orderId);
                ps.setBigDecimal(2, due);
                ps.setString(3, invoiceStatus);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next(); invoiceId = keys.getInt(1);
                }
            }

            if (paying.signum() > 0) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO CustomerPayment (InvoiceID, Amount, PaymentDate, Method) " +
                        "VALUES (?,?, CURRENT_DATE, ?)")) {
                    ps.setInt(1, invoiceId);
                    ps.setBigDecimal(2, paying);
                    ps.setString(3, payMethod.getValue());
                    ps.executeUpdate();
                }
            }

            conn.commit();
            Cart.clear();
            payAmount.setText("0.00");
            recomputeFooter();

            String msg = "Order #" + orderId + " placed. Invoice #" + invoiceId
                + " issued as " + invoiceStatus + ".";
            if (invoiceStatus.equals("PartiallyPaid")) {
                msg += "\nRemaining balance: " + Util.money(due.subtract(paying));
            } else if (invoiceStatus.equals("Open")) {
                msg += "\nYou can pay it any time from My Invoices.";
            }
            Util.info("Order placed", msg);
        } catch (SQLException ex) {
            try { conn.rollback(); } catch (SQLException ignored) {}
            Util.error("Could not place order", ex.getMessage());
        } finally {
            try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }
}
