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

public class SupplierInvoicesTab extends Tab implements Refreshable {

    public record SInvoice(int id, int poId, String supplier, LocalDate issue,
                           LocalDate due, BigDecimal total, BigDecimal paid, String status) {
        public BigDecimal balance() {
            BigDecimal b = total.subtract(paid == null ? BigDecimal.ZERO : paid);
            return b.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : b;
        }
    }
    public record SPayment(int id, BigDecimal amount, LocalDate date, String method) {}

    private final ObservableList<SInvoice> invoices = FXCollections.observableArrayList();
    private final ObservableList<SPayment> payments = FXCollections.observableArrayList();
    private final TableView<SInvoice> invTable = new TableView<>();
    private final TableView<SPayment> payTable = new TableView<>();

    public SupplierInvoicesTab() {
        setText("Supplier Invoices");
        setClosable(false);

        invTable.setItems(invoices);
        invTable.getColumns().addAll(
            col("Inv #",     80,  (SInvoice i) -> new SimpleObjectProperty<>(i.id())),
            col("PO #",      80,  i -> new SimpleObjectProperty<>(i.poId())),
            col("Supplier", 230,  i -> new SimpleStringProperty(i.supplier())),
            col("Issued",   100,  i -> new SimpleStringProperty(i.issue().toString())),
            col("Due",      100,  i -> new SimpleStringProperty(i.due().toString())),
            col("Total",    130,  i -> new SimpleStringProperty(Util.money(i.total()))),
            col("Paid",     130,  i -> new SimpleStringProperty(Util.money(i.paid()))),
            col("Balance",  130,  i -> new SimpleStringProperty(Util.money(i.balance()))),
            statusCol()
        );
        invTable.getSelectionModel().selectedItemProperty()
                .addListener((o,a,sel) -> loadPayments(sel));

        payTable.setItems(payments);
        payTable.getColumns().addAll(
            col("#",      60,  (SPayment p) -> new SimpleObjectProperty<>(p.id())),
            col("Date",   120, p -> new SimpleStringProperty(p.date().toString())),
            col("Amount", 140, p -> new SimpleStringProperty(Util.money(p.amount()))),
            col("Method", 160, p -> new SimpleStringProperty(p.method()))
        );
        payTable.setPrefHeight(180);

        Button payBtn = primaryButton("Record Payment");
        payBtn.setOnAction(e -> openPaymentDialog());

        Region grow = new Region(); HBox.setHgrow(grow, Priority.ALWAYS);
        HBox toolbar = new HBox(10, heading("Supplier Invoices"), grow, payBtn);
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
                "SELECT si.SuppInvoiceID, si.POID, s.SupName, si.IssueDate, si.DueDate, " +
                "       si.TotalAmount, si.Status, " +
                "       COALESCE((SELECT SUM(Amount) FROM SupplierPayment " +
                "                 WHERE SuppInvoiceID = si.SuppInvoiceID), 0) AS Paid " +
                "FROM SupplierInvoice si " +
                "JOIN PurchaseOrder po ON po.POID = si.POID " +
                "JOIN Supplier s       ON s.SupplierID = po.SupplierID " +
                "ORDER BY si.IssueDate DESC, si.SuppInvoiceID DESC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                invoices.add(new SInvoice(
                    rs.getInt("SuppInvoiceID"), rs.getInt("POID"),
                    rs.getString("SupName"),
                    rs.getDate("IssueDate").toLocalDate(),
                    rs.getDate("DueDate").toLocalDate(),
                    rs.getBigDecimal("TotalAmount"),
                    rs.getBigDecimal("Paid"),
                    rs.getString("Status")));
            }
        } catch (SQLException ex) { Util.error("DB error", ex.getMessage()); }
    }

    private void loadPayments(SInvoice inv) {
        payments.clear();
        if (inv == null) return;
        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT SupPayID, Amount, PaymentDate, Method FROM SupplierPayment " +
                "WHERE SuppInvoiceID=? ORDER BY PaymentDate")) {
            ps.setInt(1, inv.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    payments.add(new SPayment(
                        rs.getInt(1), rs.getBigDecimal(2),
                        rs.getDate(3).toLocalDate(), rs.getString(4)));
                }
            }
        } catch (SQLException ex) { Util.error("DB error", ex.getMessage()); }
    }

    private void openPaymentDialog() {
        SInvoice sel = invTable.getSelectionModel().getSelectedItem();
        if (sel == null) { Util.warn("No selection", "Select an invoice first."); return; }
        if ("Paid".equals(sel.status()) || sel.balance().compareTo(BigDecimal.ZERO) == 0) {
            Util.info("Already paid", "This supplier invoice is fully paid."); return;
        }

        TextField amount = new TextField(sel.balance().toPlainString());
        DatePicker date = new DatePicker(LocalDate.now());
        ComboBox<String> method = new ComboBox<>(FXCollections.observableArrayList(
            "BankTransfer", "Cheque", "Cash", "LetterOfCredit"));
        method.getSelectionModel().selectFirst();
        Label err = new Label(); err.setStyle("-fx-text-fill: #c0392b;");

        Button save = primaryButton("Save");
        Button cancel = new Button("Cancel");

        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("Record Supplier Payment");

        save.setOnAction(e -> {
            String problem = Validate.first(
                Billing.checkAmount(amount.getText(), sel.balance()),
                Billing.checkDate(date.getValue(), sel.issue()),
                method.getValue() == null ? "Payment method is required." : null);
            if (problem != null) { err.setText(problem); return; }
            BigDecimal amt = Validate.money("Amount", amount.getText(), 10);
            try {
                Billing.recordPayment(Billing.Kind.SUPPLIER, sel.id(), amt, date.getValue(), method.getValue());
                dialog.close();
                refresh();
            } catch (SQLException ex) {
                err.setText("Failed: " + ex.getMessage());
            }
        });
        cancel.setOnAction(e -> dialog.close());

        Label header = new Label("Invoice #" + sel.id() + " — " + sel.supplier()
                                 + "    Balance: " + Util.money(sel.balance()));
        header.setStyle("-fx-text-fill: #6b7280;");

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(10);
        grid.addRow(0, new Label("Amount *"), amount);
        grid.addRow(1, new Label("Date *"),   date);
        grid.addRow(2, new Label("Method *"), method);

        Region grow = new Region(); HBox.setHgrow(grow, Priority.ALWAYS);
        HBox buttons = new HBox(10, grow, cancel, save);

        VBox box = new VBox(12,
            heading("Record Supplier Payment"),
            header, grid, err, buttons);
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
    private static TableColumn<SInvoice, String> statusCol() {
        TableColumn<SInvoice, String> c = new TableColumn<>("Status");
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
