package bpc;

import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;

import java.sql.*;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class InventoryTab extends Tab implements Refreshable {

    public record Batch(int batchId, String kind, String itemName, String warehouseName,
                        int quantity, LocalDate manufactureDate, LocalDate expiryDate) {
        public long daysUntilExpiry() {
            return ChronoUnit.DAYS.between(LocalDate.now(), expiryDate);
        }
    }

    private final ObservableList<Batch> master = FXCollections.observableArrayList();
    private final TableView<Batch> table = new TableView<>();
    private final ComboBox<String> kindFilter      = new ComboBox<>();
    private final ComboBox<String> warehouseFilter = new ComboBox<>();
    private final ComboBox<String> expiryFilter    = new ComboBox<>();

    private final Map<String, Integer> warehouseMap = new LinkedHashMap<>();

    public InventoryTab() {
        setText("Inventory");
        setClosable(false);

        FilteredList<Batch> filtered = new FilteredList<>(master, b -> true);
        table.setItems(filtered);
        table.getColumns().addAll(
            col("Batch #",        80,  (Batch b) -> new SimpleObjectProperty<>(b.batchId())),
            col("Kind",          100,  b -> new SimpleStringProperty(b.kind())),
            col("Item",          280,  b -> new SimpleStringProperty(b.itemName())),
            col("Warehouse",     220,  b -> new SimpleStringProperty(b.warehouseName())),
            col("Quantity",       90,  b -> new SimpleObjectProperty<>(b.quantity())),
            col("Manufactured",  130,  b -> new SimpleStringProperty(
                                              b.manufactureDate() == null ? "—" : b.manufactureDate().toString())),
            col("Expires",       130,  b -> new SimpleStringProperty(b.expiryDate().toString())),
            col("Days left",      90,  b -> new SimpleObjectProperty<>(b.daysUntilExpiry()))
        );
        table.setRowFactory(tv -> new TableRow<>() {
            @Override protected void updateItem(Batch row, boolean empty) {
                super.updateItem(row, empty);
                if (empty || row == null) { setStyle(""); return; }
                long d = row.daysUntilExpiry();
                if (d < 0)        setStyle("-fx-background-color: #fadbd8;");  // already expired
                else if (d <= 30) setStyle("-fx-background-color: #fdebd0;");  // expiring soon
                else              setStyle("");
            }
        });

        kindFilter.getItems().setAll("All", "RawMaterial", "Product");
        kindFilter.getSelectionModel().selectFirst();

        warehouseFilter.getItems().add("All");
        warehouseFilter.getSelectionModel().selectFirst();

        expiryFilter.getItems().setAll("Any expiry",
                                       "Expired",
                                       "Within 30 days",
                                       "Within 90 days",
                                       "More than 90 days");
        expiryFilter.getSelectionModel().selectFirst();

        Runnable applyFilter = () -> {
            String k = kindFilter.getValue();
            String w = warehouseFilter.getValue();
            String x = expiryFilter.getValue();
            filtered.setPredicate(b -> {
                if (!"All".equals(k) && k != null && !k.equals(b.kind())) return false;
                if (!"All".equals(w) && w != null && !w.equals(b.warehouseName())) return false;
                long d = b.daysUntilExpiry();
                switch (x == null ? "Any expiry" : x) {
                    case "Expired":          return d < 0;
                    case "Within 30 days":   return d >= 0 && d <= 30;
                    case "Within 90 days":   return d >= 0 && d <= 90;
                    case "More than 90 days":return d > 90;
                    default:                 return true;
                }
            });
        };
        kindFilter     .valueProperty().addListener((o,a,b) -> applyFilter.run());
        warehouseFilter.valueProperty().addListener((o,a,b) -> applyFilter.run());
        expiryFilter   .valueProperty().addListener((o,a,b) -> applyFilter.run());

        Region grow = new Region(); HBox.setHgrow(grow, Priority.ALWAYS);
        HBox toolbar = new HBox(10,
            heading("Inventory"), grow,
            new Label("Kind:"), kindFilter,
            new Label("Warehouse:"), warehouseFilter,
            new Label("Expiry:"), expiryFilter);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        Label legend = new Label("Red = expired   Yellow = expiring within 30 days");
        legend.setStyle("-fx-text-fill: #6b7280; -fx-font-size: 11px;");

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(14));
        VBox top = new VBox(6, toolbar, legend);
        root.setTop(top);
        BorderPane.setMargin(top, new Insets(0,0,10,0));
        root.setCenter(table);
        setContent(root);

        refresh();
    }

    @Override
    public void refresh() {
        master.clear();
        loadWarehousesIntoFilter();

        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT rmb.RMBatchID, rm.MaterialName, w.WarehouseName, " +
                "       rmb.Quantity, rmb.ReceivedDate, rmb.ExpiryDate " +
                "FROM RawMaterialBatch rmb " +
                "JOIN RawMaterial rm ON rm.MaterialID = rmb.MaterialID " +
                "JOIN Warehouse w    ON w.WarehouseID = rmb.WarehouseID " +
                "ORDER BY rmb.ExpiryDate");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                master.add(new Batch(
                    rs.getInt(1), "RawMaterial", rs.getString(2),
                    rs.getString(3), rs.getInt(4),
                    rs.getDate(5).toLocalDate(),
                    rs.getDate(6).toLocalDate()));
            }
        } catch (SQLException ex) { Util.error("DB error", ex.getMessage()); }

        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT pb.ProductBatchID, p.ProductName, w.WarehouseName, " +
                "       pb.Quantity, pb.ManufactureDate, pb.ExpiryDate " +
                "FROM ProductBatch pb " +
                "JOIN Product p     ON p.ProductID   = pb.ProductID " +
                "JOIN Warehouse w   ON w.WarehouseID = pb.WarehouseID " +
                "ORDER BY pb.ExpiryDate");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                master.add(new Batch(
                    rs.getInt(1), "Product", rs.getString(2),
                    rs.getString(3), rs.getInt(4),
                    rs.getDate(5).toLocalDate(),
                    rs.getDate(6).toLocalDate()));
            }
        } catch (SQLException ex) { Util.error("DB error", ex.getMessage()); }
    }

    private void loadWarehousesIntoFilter() {
        String currentSel = warehouseFilter.getValue();
        warehouseMap.clear();
        warehouseFilter.getItems().clear();
        warehouseFilter.getItems().add("All");
        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT WarehouseID, WarehouseName FROM Warehouse ORDER BY WarehouseName");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int wid = rs.getInt(1);
                String wname = rs.getString(2);
                warehouseMap.put(wname, wid);
                warehouseFilter.getItems().add(wname);
            }
        } catch (SQLException ex) { Util.error("DB error", ex.getMessage()); }
        if (currentSel != null && warehouseFilter.getItems().contains(currentSel)) {
            warehouseFilter.getSelectionModel().select(currentSel);
        } else {
            warehouseFilter.getSelectionModel().selectFirst();
        }
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
}
