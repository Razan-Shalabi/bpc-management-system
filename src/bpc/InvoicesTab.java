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

public class InvoicesTab extends Tab implements Refreshable {

    public record Invoice(int id, int orderId, String customer, LocalDate issue, LocalDate due, BigDecimal total, BigDecimal paid, String status) {

        public BigDecimal balance() {
            BigDecimal result = total.subtract(paid == null ? BigDecimal.ZERO : paid);

            if (result.compareTo(BigDecimal.ZERO) < 0) {
                return BigDecimal.ZERO;
            }

            return result;
        }
    }

    public record Payment(int id, BigDecimal amount, LocalDate date, String method) {}

    private final ObservableList<Invoice> invoices = FXCollections.observableArrayList();
    private final ObservableList<Payment> payments = FXCollections.observableArrayList();
    private final FilteredList<Invoice> filteredInvoices = new FilteredList<>(invoices, x -> true);

    private final TableView<Invoice> invTable = new TableView<>();
    private final TableView<Payment> payTable = new TableView<>();
    private final ComboBox<String> customerFilter = new ComboBox<>();
    private final ComboBox<String> statusFilter   = new ComboBox<>();

    public InvoicesTab() {
        setText("Invoices");
        setClosable(false);

        invTable.setItems(filteredInvoices);
        invTable.getColumns().addAll(
                col("Invoice ID", 90, (Invoice i) -> new SimpleObjectProperty<>(i.id())),
                col("Order #", 80, i -> new SimpleObjectProperty<>(i.orderId())),
                col("Customer", 230, i -> new SimpleStringProperty(i.customer())),
                col("Issued", 100, i -> new SimpleStringProperty(i.issue().toString())),
                col("Due", 100, i -> new SimpleStringProperty(i.due().toString())),
                col("Total", 120, i -> new SimpleStringProperty(Util.money(i.total()))),
                col("Paid", 120, i -> new SimpleStringProperty(Util.money(i.paid()))),
                col("Balance", 120, i -> new SimpleStringProperty(Util.money(i.balance()))),
                statusCol()
        );

        invTable.getSelectionModel().selectedItemProperty()
                .addListener((obs, oldInv, newInv) -> loadPayments(newInv));

        payTable.setItems(payments);
        payTable.getColumns().addAll(
                col("#", 60, (Payment p) -> new SimpleObjectProperty<>(p.id())),
                col("Date", 120, p -> new SimpleStringProperty(p.date().toString())),
                col("Amount", 140, p -> new SimpleStringProperty(Util.money(p.amount()))),
                col("Method", 160, p -> new SimpleStringProperty(p.method()))
        );
        payTable.setPrefHeight(180);

        Button payBtn = primaryButton("Record Payment");
        payBtn.setOnAction(e -> openPaymentDialog());

        customerFilter.setPromptText("All customers");
        customerFilter.setPrefWidth(220);
        statusFilter.getItems().setAll("All statuses", "Open", "PartiallyPaid", "Paid", "Overdue");
        statusFilter.getSelectionModel().selectFirst();
        statusFilter.setPrefWidth(140);

        Runnable applyFilter = () -> {
            String cust = customerFilter.getValue();
            String st   = statusFilter.getValue();
            filteredInvoices.setPredicate(inv -> {
                boolean okCust = cust == null || "All customers".equals(cust) || cust.equals(inv.customer());
                boolean okStat = st   == null || "All statuses".equals(st)   || st.equals(inv.status());
                return okCust && okStat;
            });
        };
        customerFilter.valueProperty().addListener((o, a, b) -> applyFilter.run());
        statusFilter.valueProperty().addListener((o, a, b) -> applyFilter.run());

        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);

        HBox toolbar = new HBox(10, heading("Invoices"), grow,
                                new Label("Customer:"), customerFilter,
                                new Label("Status:"),   statusFilter,
                                payBtn);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(10, toolbar, invTable,
                heading2("Payments for selected invoice:"), payTable);
        root.setPadding(new Insets(14));
        VBox.setVgrow(invTable, Priority.ALWAYS);

        setContent(root);
        refresh();
    }

    @Override
    public void refresh() {
        Billing.markOverdue();
        invoices.clear();

        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT i.InvoiceID, i.OrderID, c.CustomerName, i.IssueDate, i.DueDate, " +
                "i.TotalAmount, i.Status, " +
                "COALESCE((SELECT SUM(Amount) FROM CustomerPayment WHERE InvoiceID=i.InvoiceID),0) AS Paid " +
                "FROM Invoice i " +
                "JOIN SalesOrder so ON so.OrderID = i.OrderID " +
                "JOIN Customer c ON c.CustomerID = so.CustomerID " +
                "ORDER BY i.IssueDate DESC");
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                invoices.add(new Invoice(
                        rs.getInt("InvoiceID"),
                        rs.getInt("OrderID"),
                        rs.getString("CustomerName"),
                        rs.getDate("IssueDate").toLocalDate(),
                        rs.getDate("DueDate").toLocalDate(),
                        rs.getBigDecimal("TotalAmount"),
                        rs.getBigDecimal("Paid"),
                        rs.getString("Status")
                ));
            }

        } catch (SQLException ex) {
            Util.error("DB error", ex.getMessage());
        }

        String previousCust = customerFilter.getValue();
        java.util.TreeSet<String> distinctCustomers = new java.util.TreeSet<>();
        for (Invoice inv : invoices) distinctCustomers.add(inv.customer());
        customerFilter.getItems().setAll("All customers");
        customerFilter.getItems().addAll(distinctCustomers);
        if (previousCust != null && customerFilter.getItems().contains(previousCust)) {
            customerFilter.setValue(previousCust);
        } else {
            customerFilter.getSelectionModel().selectFirst();   
        }
    }

    private void loadPayments(Invoice invoice) {
        payments.clear();

        if (invoice == null) return;

        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT CustPayID, Amount, PaymentDate, Method " +
                "FROM CustomerPayment " +
                "WHERE InvoiceID=? ORDER BY PaymentDate")) {

            ps.setInt(1, invoice.id());

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    payments.add(new Payment(
                            rs.getInt(1),
                            rs.getBigDecimal(2),
                            rs.getDate(3).toLocalDate(),
                            rs.getString(4)
                    ));
                }
            }

        } catch (SQLException ex) {
            Util.error("DB error", ex.getMessage());
        }
    }

    private void openPaymentDialog() {
        Invoice selected = invTable.getSelectionModel().getSelectedItem();

        if (selected == null) {
            Util.warn("No selection", "Select an invoice first.");
            return;
        }

        if ("Paid".equals(selected.status()) || selected.balance().compareTo(BigDecimal.ZERO) == 0) {
            Util.info("Already paid", "This invoice is fully paid.");
            return;
        }

        TextField amount = new TextField(selected.balance().toPlainString());
        DatePicker date = new DatePicker(LocalDate.now());

        ComboBox<String> method = new ComboBox<>(FXCollections.observableArrayList(
                "BankTransfer", "Cheque", "Cash", "CreditCard"
        ));
        method.getSelectionModel().selectFirst();

        Label err = new Label();
        err.setStyle("-fx-text-fill: #c0392b;");

        Button save = primaryButton("Save");
        Button cancel = new Button("Cancel");

        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("Record Payment");

        save.setOnAction(e -> {
            String problem = Validate.first(
                Billing.checkAmount(amount.getText(), selected.balance()),
                Billing.checkDate(date.getValue(), selected.issue()),
                method.getValue() == null ? "Payment method is required." : null);
            if (problem != null) { err.setText(problem); return; }
            BigDecimal paymentAmount = Validate.money("Amount", amount.getText(), 10);
            try {
                String status = Billing.recordPayment(Billing.Kind.CUSTOMER, selected.id(),
                        paymentAmount, date.getValue(), method.getValue());
                Util.info("Payment recorded", Util.money(paymentAmount) + " recorded for invoice #"
                        + selected.id() + ".\nNew status: " + status);
                dialog.close();
                refresh();
            } catch (SQLException ex) {
                err.setText("Failed: " + ex.getMessage());
            }
        });

        cancel.setOnAction(e -> dialog.close());

        Label header = new Label("Invoice #" + selected.id() + " — " + selected.customer()
                + "    Balance: " + Util.money(selected.balance()));
        header.setStyle("-fx-text-fill: #6b7280;");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        grid.addRow(0, new Label("Amount *"), amount);
        grid.addRow(1, new Label("Date *"), date);
        grid.addRow(2, new Label("Method *"), method);

        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);

        HBox buttons = new HBox(10, grow, cancel, save);

        VBox box = new VBox(12, heading("Record Payment"), header, grid, err, buttons);
        box.setPadding(new Insets(20));
        box.setPrefWidth(420);

        dialog.setScene(new Scene(box));
        dialog.showAndWait();
    }

    private static <T, S> TableColumn<T, S> col(String text, double width,
            java.util.function.Function<T, javafx.beans.value.ObservableValue<S>> getter) {

        TableColumn<T, S> column = new TableColumn<>(text);
        column.setPrefWidth(width);
        column.setCellValueFactory(cd -> getter.apply(cd.getValue()));
        return column;
    }

    private static TableColumn<Invoice, String> statusCol() {
        TableColumn<Invoice, String> column = new TableColumn<>("Status");
        column.setPrefWidth(120);
        column.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().status()));

        column.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String status, boolean empty) {
                super.updateItem(status, empty);

                if (empty || status == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    setGraphic(Util.pill(status));
                    setText(null);
                }
            }
        });

        return column;
    }

    private static Button primaryButton(String text) {
        Button button = new Button(text);
        button.setStyle("-fx-background-color: #0d8a8a; -fx-text-fill: white; -fx-font-weight: bold;");
        return button;
    }

    private static Label heading(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: #1a3a3a;");
        return label;
    }

    private static Label heading2(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        return label;
    }
}