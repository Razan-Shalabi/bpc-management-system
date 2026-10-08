package bpc;

import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

public class GoodsReceiptsTab extends Tab implements Refreshable {

    public record Receipt(int id, int poId, String supplier, LocalDate received,
                          String employee, String notes) {}
    public record Item(int id, String material, int qty, String notes) {}

    private final ObservableList<Receipt> receipts = FXCollections.observableArrayList();
    private final ObservableList<Item>    items    = FXCollections.observableArrayList();
    private final TableView<Receipt> receiptsTable = new TableView<>();
    private final TableView<Item>    itemsTable    = new TableView<>();

    public GoodsReceiptsTab() {
        setText("Goods Receipts");
        setClosable(false);

        receiptsTable.setItems(receipts);
        receiptsTable.getColumns().addAll(
            col("Receipt #",  100, (Receipt r) -> new SimpleObjectProperty<>(r.id())),
            col("PO #",        80, r -> new SimpleObjectProperty<>(r.poId())),
            col("Supplier",   240, r -> new SimpleStringProperty(r.supplier())),
            col("Received",   120, r -> new SimpleStringProperty(r.received().toString())),
            col("Employee",   200, r -> new SimpleStringProperty(r.employee())),
            col("Notes",      300, r -> new SimpleStringProperty(r.notes() == null ? "" : r.notes()))
        );
        receiptsTable.getSelectionModel().selectedItemProperty()
                     .addListener((obs, oldR, newR) -> loadItems(newR));

        itemsTable.setItems(items);
        itemsTable.getColumns().addAll(
            col("Item #",      90, (Item it) -> new SimpleObjectProperty<>(it.id())),
            col("Material",   280, it -> new SimpleStringProperty(it.material())),
            col("Qty received",120, it -> new SimpleObjectProperty<>(it.qty())),
            col("Notes",      280, it -> new SimpleStringProperty(it.notes() == null ? "" : it.notes()))
        );
        itemsTable.setPrefHeight(200);


        VBox topBlock = new VBox(6, heading("Goods Receipts"));

        VBox root = new VBox(10,
            topBlock, receiptsTable,
            heading2("Items received in selected receipt:"), itemsTable);
        root.setPadding(new Insets(14));
        VBox.setVgrow(receiptsTable, Priority.ALWAYS);
        setContent(root);

        refresh();
    }

    @Override
    public void refresh() {
        receipts.clear();
        items.clear();
        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT gr.ReceiptID, gr.POID, sup.SupName, gr.ReceivedDate, " +
                "       emp.EmpName, gr.Notes " +
                "FROM GoodsReceipt gr " +
                "JOIN PurchaseOrder po ON po.POID         = gr.POID " +
                "JOIN Supplier sup     ON sup.SupplierID  = po.SupplierID " +
                "JOIN Employee emp     ON emp.EmpID       = gr.EmpID " +
                "ORDER BY gr.ReceivedDate DESC, gr.ReceiptID DESC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                receipts.add(new Receipt(
                    rs.getInt(1), rs.getInt(2), rs.getString(3),
                    rs.getDate(4).toLocalDate(),
                    rs.getString(5), rs.getString(6)));
            }
        } catch (SQLException ex) { Util.error("DB error", ex.getMessage()); }
    }

    private void loadItems(Receipt r) {
        items.clear();
        if (r == null) return;
        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT gri.ReceiptItemID, rm.MaterialName, gri.QuantityReceived, gri.Notes " +
                "FROM GoodsReceiptItem gri " +
                "JOIN PurchaseOrderItem poi ON poi.POItemID = gri.POItemID " +
                "JOIN RawMaterial rm ON rm.MaterialID = poi.MaterialID " +
                "WHERE gri.ReceiptID = ? ORDER BY gri.ReceiptItemID")) {
            ps.setInt(1, r.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(new Item(
                        rs.getInt(1), rs.getString(2),
                        rs.getInt(3), rs.getString(4)));
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
