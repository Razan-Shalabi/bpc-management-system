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

public class CustomerMyInvoicesTab extends Tab implements Refreshable {

    public record Invoice(int id, int orderId, LocalDate issue, LocalDate due,
                          BigDecimal total, BigDecimal paid, String status) {
        public BigDecimal balance() {
            BigDecimal b = total.subtract(paid == null ? BigDecimal.ZERO : paid);
            return b.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : b;
        }
    }

    private final ObservableList<Invoice> invoices = FXCollections.observableArrayList();
    private final TableView<Invoice> table = new TableView<>();

    public CustomerMyInvoicesTab() {
        setText("My Invoices");
        setClosable(false);

        table.setItems(invoices);
        table.getColumns().addAll(
            col("Invoice #",  90, (Invoice i) -> new SimpleObjectProperty<>(i.id())),
            col("Order #", 90,  i -> new SimpleObjectProperty<>(i.orderId())),
            col("Issued", 110, i -> new SimpleStringProperty(i.issue().toString())),
            col("Due", 110, i -> new SimpleStringProperty(i.due().toString())),
            col("Total", 130, i -> new SimpleStringProperty(Util.money(i.total()))),
            col("Paid", 130, i -> new SimpleStringProperty(Util.money(i.paid()))),
            col("Balance", 130, i -> new SimpleStringProperty(Util.money(i.balance()))),
            statusCol()
        );

        Button payBtn = primaryButton("Pay Now");
        payBtn.setOnAction(e -> openPayDialog());

        Region grow = new Region(); HBox.setHgrow(grow, Priority.ALWAYS);
        HBox toolbar = new HBox(10, heading("My Invoices"), grow, payBtn);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(14));
        VBox top = new VBox(6, toolbar);
        root.setTop(top);
        BorderPane.setMargin(top, new Insets(0,0,10,0));
        root.setCenter(table);
        setContent(root);

        refresh();
    }

    @Override
    public void refresh() {
        Billing.markOverdue();
        invoices.clear();
        int customerId = Session.customerId();
        if (customerId <= 0) return;

        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT i.InvoiceID, i.OrderID, i.IssueDate, i.DueDate, " +
                "i.TotalAmount, i.Status, " +
                "COALESCE((SELECT SUM(Amount) FROM CustomerPayment " +
                "WHERE InvoiceID = i.InvoiceID), 0) AS Paid " +
                "FROM Invoice i " +
                "JOIN SalesOrder so ON so.OrderID = i.OrderID " +
                "WHERE so.CustomerID = ? " +
                "ORDER BY i.IssueDate DESC")) {
            ps.setInt(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    invoices.add(new Invoice(
                        rs.getInt("InvoiceID"),
                        rs.getInt("OrderID"),
                        rs.getDate("IssueDate").toLocalDate(),
                        rs.getDate("DueDate").toLocalDate(),
                        rs.getBigDecimal("TotalAmount"),
                        rs.getBigDecimal("Paid"),
                        rs.getString("Status")));
                }
            }
        } catch (SQLException ex) { Util.error("DB error", ex.getMessage()); }
    }

    private void openPayDialog() {
        Invoice sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) { Util.warn("No selection", "Select an invoice first."); return; }
        if ("Paid".equals(sel.status()) || sel.balance().compareTo(BigDecimal.ZERO) == 0) {
            Util.info("Already paid", "This invoice is fully paid."); return;
        }

        TextField amount = new TextField(sel.balance().toPlainString());
        LocalDate paymentDate = LocalDate.now();
        ComboBox<String> method = new ComboBox<>(FXCollections.observableArrayList(
            "BankTransfer", "CreditCard", "Cheque", "Cash"));
        method.getSelectionModel().selectFirst();
        Label err = new Label(); err.setStyle("-fx-text-fill: #c0392b;");

        Button save = primaryButton("Pay");
        Button cancel = new Button("Cancel");

        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("Pay Invoice #" + sel.id());

        save.setOnAction(e -> {
            String problem = Validate.first(
                Billing.checkAmount(amount.getText(), sel.balance()),
                method.getValue() == null ? "Payment method is required." : null);
            if (problem != null) { err.setText(problem); return; }
            BigDecimal amt = Validate.money("Amount", amount.getText(), 10);
            try {
                String newStatus = Billing.recordPayment(Billing.Kind.CUSTOMER, sel.id(), amt,
                        paymentDate, method.getValue());
                Util.info("Payment recorded",
                    "Thank you. " + Util.money(amt) + " has been received.\nNew status: " + newStatus);
                dialog.close();
                refresh();
            } catch (SQLException ex) {
                err.setText("Payment failed: " + ex.getMessage());
            }
        });
        cancel.setOnAction(e -> dialog.close());

        Label header = new Label("Invoice #" + sel.id() + "    Balance: " + Util.money(sel.balance()));
        header.setStyle("-fx-text-fill: #6b7280;");

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(10);
        grid.addRow(0, new Label("Amount *"), amount);
        grid.addRow(1, new Label("Method *"), method);

        Region grow = new Region(); HBox.setHgrow(grow, Priority.ALWAYS);
        HBox buttons = new HBox(10, grow, cancel, save);

        VBox box = new VBox(12,
            heading("Pay Invoice"), header, grid, err, buttons);
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
    private static TableColumn<Invoice, String> statusCol() {
        TableColumn<Invoice, String> c = new TableColumn<>("Status");
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
}
