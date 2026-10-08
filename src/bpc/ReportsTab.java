package bpc;

import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class ReportsTab extends Tab implements Refreshable {

    private final List<Runnable> refreshers = new ArrayList<>();

    public ReportsTab() {
        setText("Reports");
        setClosable(false);
        TabPane sub = new TabPane(
            buildCustomerActivity(),
            buildRevenueByType(),
            buildTopProducts(),
            buildOverdueReceivables(),
            buildLowStockProducts(),
            buildProductionOutput(),
            buildSupplierPurchaseRanking(),
            buildMultiMaterialSuppliers(),
            buildMaterialsWithoutSupplier(),
            buildLowStockRawMaterials(),
            buildProductStockByWarehouse()
        );
        sub.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox root = new VBox(10, heading("Reports"), sub);
        root.setPadding(new Insets(14));
        VBox.setVgrow(sub, Priority.ALWAYS);
        setContent(root);
    }

    @Override
    public void refresh() {
        Billing.markOverdue();
        for (Runnable r : refreshers) {
            try { r.run(); }
            catch (Exception ex) { Util.error("Report refresh error", ex.getMessage()); }
        }
    }

    private Tab buildCustomerActivity() {
        TableView<Object[]> table = new TableView<>();
        table.getColumns().addAll(
            col("Customer", 240, r -> new SimpleStringProperty((String)r[0])),
            col("Type", 110, r -> new SimpleStringProperty((String)r[1])),
            col("City", 120, r -> new SimpleStringProperty((String)r[2])),
            col("Orders", 80, r -> new SimpleObjectProperty<>((Integer)r[3])),
            col("Total discount",160, r -> new SimpleStringProperty(Util.money((BigDecimal)r[4])))
        );
        Runnable load = () -> {
            table.getItems().clear();
            try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT c.CustomerName, c.Type, c.City, COUNT(so.OrderID) AS N, " +
                "COALESCE(SUM(so.Discount),0) AS D " +
                "FROM Customer c LEFT JOIN SalesOrder so ON so.CustomerID = c.CustomerID " +
                "GROUP BY c.CustomerID, c.CustomerName, c.Type, c.City " +
                "ORDER BY N DESC, D DESC");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    table.getItems().add(new Object[]{
                        rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getInt(4), rs.getBigDecimal(5)});
                }
            } catch (SQLException ex) { Util.error("DB error", ex.getMessage()); }
        };
        refreshers.add(load); load.run();

        VBox box = new VBox(8, table);
        box.setPadding(new Insets(10));
        VBox.setVgrow(table, Priority.ALWAYS);
        return new Tab("Customer Activity", box);
    }

    private Tab buildRevenueByType() {
        Spinner<Integer> year = new Spinner<>(2020, 2099, LocalDate.now().getYear());
        year.setPrefWidth(100);
        PieChart pie = new PieChart();
        pie.setPrefHeight(360);

        Runnable load = () -> {
            pie.getData().clear();
            try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT c.Type, COALESCE(SUM(so.TotalAmount),0) " +
                "FROM SalesOrder so JOIN Customer c ON c.CustomerID = so.CustomerID " +
                "WHERE YEAR(so.OrderDate)=? " +
                "GROUP BY c.Type ORDER BY 2 DESC")) {
                ps.setInt(1, year.getValue());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        BigDecimal v = rs.getBigDecimal(2);
                        if (v.signum() > 0) {
                            pie.getData().add(new PieChart.Data(
                                rs.getString(1) + " (" + v + ")", v.doubleValue()));
                        }
                    }
                }
            } catch (SQLException ex) { Util.error("DB error", ex.getMessage()); }
        };
        refreshers.add(load);

        Button btn = primaryButton("Run");
        btn.setOnAction(e -> load.run());
        HBox top = new HBox(10, new Label("Year:"), year, btn);
        top.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(8,
            top, pie);
        box.setPadding(new Insets(10));
        load.run();
        return new Tab("Revenue by Type", box);
    }

    private Tab buildTopProducts() {
        Spinner<Integer> year  = new Spinner<>(2020, 2099, LocalDate.now().getYear());
        Spinner<Integer> month = new Spinner<>(1, 12, LocalDate.now().getMonthValue());
        year.setPrefWidth(100); month.setPrefWidth(80);
        BarChart<String, Number> chart = new BarChart<>(new CategoryAxis(), new NumberAxis());
        chart.setLegendVisible(false);
        chart.setPrefHeight(360);

        Runnable load = () -> {
            chart.getData().clear();
            XYChart.Series<String, Number> s = new XYChart.Series<>();
            // Option C: SalesOrderItem references Product directly.
            try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT p.ProductName, SUM(soi.Quantity) " +
                "FROM SalesOrderItem soi " +
                "JOIN SalesOrder so ON so.OrderID  = soi.OrderID " +
                "JOIN Product p ON p.ProductID = soi.ProductID " +
                "WHERE YEAR(so.OrderDate)=? AND MONTH(so.OrderDate)=? " +
                "GROUP BY p.ProductID, p.ProductName ORDER BY 2 DESC LIMIT 5")) {
                ps.setInt(1, year.getValue());
                ps.setInt(2, month.getValue());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next())
                        s.getData().add(new XYChart.Data<>(rs.getString(1), rs.getInt(2)));
                }
            } catch (SQLException ex) { Util.error("DB error", ex.getMessage()); }
            chart.getData().add(s);
        };
        refreshers.add(load);

        Button btn = primaryButton("Run");
        btn.setOnAction(e -> load.run());
        HBox top = new HBox(10,
            new Label("Year:"), year,
            new Label("Month:"), month, btn);
        top.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(8,
            top, chart);
        box.setPadding(new Insets(10));
        load.run();
        return new Tab("Top Products", box);
    }

    private Tab buildOverdueReceivables() {
        Spinner<Integer> days = new Spinner<>(0, 365, 30);
        days.setPrefWidth(80);
        TableView<Object[]> table = new TableView<>();
        table.getColumns().addAll(
            col("Customer", 240, r -> new SimpleStringProperty((String)r[0])),
            col("Invoice #", 100, r -> new SimpleObjectProperty<>((Integer)r[1])),
            col("Balance", 160, r -> new SimpleStringProperty(Util.money((BigDecimal)r[2]))),
            col("Days overdue", 140, r -> new SimpleObjectProperty<>((Integer)r[3]))
        );

        Runnable load = () -> {
            table.getItems().clear();
            try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT c.CustomerName, i.InvoiceID, " +
                "i.TotalAmount - COALESCE((SELECT SUM(Amount) FROM CustomerPayment cp " +
                "WHERE cp.InvoiceID = i.InvoiceID),0) AS Bal, " +
                "DATEDIFF(CURRENT_DATE, i.DueDate) AS D " +
                "FROM Invoice i " +
                "JOIN SalesOrder so ON so.OrderID = i.OrderID " +
                "JOIN Customer c ON c.CustomerID = so.CustomerID " +
                "WHERE i.Status IN ('Open','PartiallyPaid','Overdue') " +
                "AND DATEDIFF(CURRENT_DATE, i.DueDate) > ? " +
                "ORDER BY D DESC")) {
                ps.setInt(1, days.getValue());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        table.getItems().add(new Object[]{
                            rs.getString(1), rs.getInt(2),
                            rs.getBigDecimal(3), rs.getInt(4)});
                    }
                }
            } catch (SQLException ex) { Util.error("DB error", ex.getMessage()); }
        };
        refreshers.add(load);

        Button btn = primaryButton("Run");
        btn.setOnAction(e -> load.run());
        Region grow = new Region(); HBox.setHgrow(grow, Priority.ALWAYS);
        HBox top = new HBox(10,
            new Label("More than"), days, new Label("days overdue"), grow, btn);
        top.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(8,
            top, table);
        box.setPadding(new Insets(10));
        VBox.setVgrow(table, Priority.ALWAYS);
        load.run();
        return new Tab("Overdue Receivables", box);
    }

    private Tab buildProductionOutput() {
        Spinner<Integer> year = new Spinner<>(2020, 2099, LocalDate.now().getYear());
        year.setPrefWidth(100);
        BarChart<String, Number> chart = new BarChart<>(new CategoryAxis(), new NumberAxis());
        chart.setLegendVisible(false);
        chart.setPrefHeight(360);

        Runnable load = () -> {
            chart.getData().clear();
            XYChart.Series<String, Number> s = new XYChart.Series<>();
            try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT p.ProductName, COALESCE(SUM(po.QuantityProduced),0) " +
                "FROM ProductionOrder po " +
                "JOIN ProductBatch pb ON pb.ProductBatchID = po.ProductBatchID " +
                "JOIN Product p ON p.ProductID = pb.ProductID " +
                "WHERE YEAR(po.ProductionDate) = ? " +
                "GROUP BY p.ProductID, p.ProductName " +
                "ORDER BY 2 DESC")) {
                ps.setInt(1, year.getValue());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next())
                        s.getData().add(new XYChart.Data<>(rs.getString(1), rs.getInt(2)));
                }
            } catch (SQLException ex) { Util.error("DB error", ex.getMessage()); }
            chart.getData().add(s);
        };
        refreshers.add(load);

        Button btn = primaryButton("Run");
        btn.setOnAction(e -> load.run());
        HBox top = new HBox(10, new Label("Year:"), year, btn);
        top.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(8, top, chart);
        box.setPadding(new Insets(10));
        load.run();
        return new Tab("Production Output", box);
    }

    private Tab buildLowStockProducts() {
        TableView<Object[]> table = new TableView<>();
        table.getColumns().addAll(
            col("Product", 260, r -> new SimpleStringProperty((String)r[0])),
            col("Total stock", 120, r -> new SimpleObjectProperty<>((Integer)r[1])),
            col("Reorder level", 130, r -> new SimpleObjectProperty<>((Integer)r[2])),
            col("Threshold", 110, r -> new SimpleObjectProperty<>((Integer)r[3])),
            col("Unit price", 130, r -> new SimpleStringProperty(Util.money((BigDecimal)r[4])))
        );

        Runnable load = () -> {
            table.getItems().clear();
            try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT p.ProductName, " +
                "COALESCE(SUM(pb.Quantity),0) AS Stock, " +
                "p.ReorderLevel, " +
                "p.ReorderLevel - COALESCE(SUM(pb.Quantity),0) AS Threshold, " +
                "p.UnitPrice " +
                "FROM Product p " +
                "LEFT JOIN ProductBatch pb ON pb.ProductID = p.ProductID " +
                "AND pb.ExpiryDate > CURRENT_DATE " +
                "GROUP BY p.ProductID, p.ProductName, p.ReorderLevel, p.UnitPrice " +
                "HAVING COALESCE(SUM(pb.Quantity),0) < p.ReorderLevel " +
                "ORDER BY Threshold DESC");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    table.getItems().add(new Object[]{
                        rs.getString(1), rs.getInt(2), rs.getInt(3),
                        rs.getInt(4), rs.getBigDecimal(5)});
                }
            } catch (SQLException ex) { Util.error("DB error", ex.getMessage()); }
        };
        refreshers.add(load); load.run();

        VBox box = new VBox(8,
            table);
        box.setPadding(new Insets(10));
        VBox.setVgrow(table, Priority.ALWAYS);
        return new Tab("Low Stock Products", box);
    }

    private Tab buildSupplierPurchaseRanking() {

        Label title = new Label("Supplier Purchase Ranking");
        title.setStyle(
                "-fx-font-size: 16px;" +
                "-fx-font-weight: bold;" +
                "-fx-text-fill: #1a3a3a;"
        );

        Label explanation = new Label(
                "Ranks suppliers by total purchase order value for the selected year."
        );
        explanation.setStyle("-fx-text-fill: #555;");

        Spinner<Integer> yearSpinner =
                new Spinner<>(2020, 2099, LocalDate.now().getYear());
        yearSpinner.setPrefWidth(100);

        Button runBtn = primaryButton("Run");

        HBox top = new HBox(10,
                new Label("Year:"),
                yearSpinner,
                runBtn
        );
        top.setAlignment(Pos.CENTER_LEFT);

        TableView<Object[]> table = new TableView<>();

        TableColumn<Object[], Integer> rankCol = new TableColumn<>("Rank");
        rankCol.setPrefWidth(70);
        rankCol.setCellValueFactory(cd ->
                new SimpleObjectProperty<>((Integer) cd.getValue()[0])
        );

        TableColumn<Object[], String> supplierCol = new TableColumn<>("Supplier");
        supplierCol.setPrefWidth(260);
        supplierCol.setCellValueFactory(cd ->
                new SimpleStringProperty((String) cd.getValue()[1])
        );

        TableColumn<Object[], Integer> poCountCol = new TableColumn<>("PO Count");
        poCountCol.setPrefWidth(110);
        poCountCol.setCellValueFactory(cd ->
                new SimpleObjectProperty<>((Integer) cd.getValue()[2])
        );

        TableColumn<Object[], String> totalValueCol = new TableColumn<>("Total Purchase Value");
        totalValueCol.setPrefWidth(180);
        totalValueCol.setCellValueFactory(cd ->
                new SimpleStringProperty(Util.money((BigDecimal) cd.getValue()[3]))
        );

        TableColumn<Object[], String> avgValueCol = new TableColumn<>("Average PO Value");
        avgValueCol.setPrefWidth(170);
        avgValueCol.setCellValueFactory(cd ->
                new SimpleStringProperty(Util.money((BigDecimal) cd.getValue()[4]))
        );

        table.getColumns().add(rankCol);
        table.getColumns().add(supplierCol);
        table.getColumns().add(poCountCol);
        table.getColumns().add(totalValueCol);
        table.getColumns().add(avgValueCol);

        table.setPrefHeight(330);

        PieChart chart = new PieChart();
        chart.setTitle("Supplier Purchase Value");
        chart.setPrefHeight(280);
        chart.setMinHeight(260);
        chart.setLegendVisible(true);
        chart.setLabelsVisible(false);

        Runnable load = () -> {
            table.getItems().clear();
            chart.getData().clear();

            String sql =
                    "SELECT sup.SupName, " +
                    "       COUNT(po.POID) AS POCount, " +
                    "       CASE WHEN SUM(po.TotalCost) IS NULL THEN 0 ELSE SUM(po.TotalCost) END AS TotalPurchaseValue, " +
                    "       CASE " +
                    "           WHEN COUNT(po.POID) = 0 THEN 0 " +
                    "           ELSE SUM(po.TotalCost) / COUNT(po.POID) " +
                    "       END AS AvgPOValue " +
                    "FROM Supplier sup " +
                    "LEFT JOIN PurchaseOrder po ON po.SupplierID = sup.SupplierID " +
                    "     AND YEAR(po.OrderDate) = ? " +
                    "GROUP BY sup.SupplierID, sup.SupName " +
                    "ORDER BY TotalPurchaseValue DESC";

            try (PreparedStatement ps = DB.get().prepareStatement(sql)) {

                ps.setInt(1, yearSpinner.getValue());

                try (ResultSet rs = ps.executeQuery()) {

                    int rank = 1;

                    while (rs.next()) {

                        String supplier = rs.getString("SupName");
                        int poCount = rs.getInt("POCount");
                        BigDecimal total = rs.getBigDecimal("TotalPurchaseValue");
                        BigDecimal avg = rs.getBigDecimal("AvgPOValue");

                        table.getItems().add(new Object[]{
                                rank,
                                supplier,
                                poCount,
                                total,
                                avg
                        });

                        if (total.compareTo(BigDecimal.ZERO) > 0) {
                            chart.getData().add(
                                    new PieChart.Data(
                                            supplier + " - " + Util.money(total),
                                            total.doubleValue()
                                    )
                            );
                        }

                        rank++;
                    }
                }

            } catch (SQLException ex) {
                Util.error("DB error", ex.getMessage());
            }
        };

        refreshers.add(load);
        runBtn.setOnAction(e -> load.run());
        load.run();

        VBox box = new VBox(10,
                title,
                explanation,
                top,
                table,
                chart
        );

        box.setPadding(new Insets(10));
        return new Tab("Purchase Ranking", box);
    }


    private Tab buildMultiMaterialSuppliers() {

        Label title = new Label("Multi-Material Suppliers");
        title.setStyle(
                "-fx-font-size: 16px;" +
                "-fx-font-weight: bold;" +
                "-fx-text-fill: #1a3a3a;"
        );

        Label explanation = new Label(
                "Shows suppliers that provide more than one raw material. This helps identify suppliers with wider material coverage."
        );
        explanation.setStyle("-fx-text-fill: #555;");

        TableView<Object[]> table = new TableView<>();

        TableColumn<Object[], Integer> rankCol = new TableColumn<>("Rank");
        rankCol.setPrefWidth(70);
        rankCol.setCellValueFactory(cd ->
                new SimpleObjectProperty<>((Integer) cd.getValue()[0])
        );

        TableColumn<Object[], String> supplierCol = new TableColumn<>("Supplier");
        supplierCol.setPrefWidth(260);
        supplierCol.setCellValueFactory(cd ->
                new SimpleStringProperty((String) cd.getValue()[1])
        );

        TableColumn<Object[], String> countryCol = new TableColumn<>("Country");
        countryCol.setPrefWidth(150);
        countryCol.setCellValueFactory(cd ->
                new SimpleStringProperty((String) cd.getValue()[2])
        );

        TableColumn<Object[], String> ratingCol = new TableColumn<>("Rating");
        ratingCol.setPrefWidth(100);
        ratingCol.setCellValueFactory(cd ->
                new SimpleStringProperty(String.valueOf(cd.getValue()[3]))
        );

        TableColumn<Object[], Integer> materialCountCol = new TableColumn<>("Material Count");
        materialCountCol.setPrefWidth(140);
        materialCountCol.setCellValueFactory(cd ->
                new SimpleObjectProperty<>((Integer) cd.getValue()[4])
        );

        table.getColumns().add(rankCol);
        table.getColumns().add(supplierCol);
        table.getColumns().add(countryCol);
        table.getColumns().add(ratingCol);
        table.getColumns().add(materialCountCol);

        table.setPrefHeight(520);

        Runnable load = () -> {
            table.getItems().clear();

            String sql =
                    "SELECT sup.SupName, sup.Country, sup.Rating, COUNT(sm.MaterialID) AS MaterialCount " +
                    "FROM Supplier sup " +
                    "JOIN SupplierMaterial sm ON sm.SupplierID = sup.SupplierID " +
                    "GROUP BY sup.SupplierID, sup.SupName, sup.Country, sup.Rating " +
                    "HAVING COUNT(sm.MaterialID) > 1 " +
                    "ORDER BY MaterialCount DESC, sup.SupName ASC";

            try (PreparedStatement ps = DB.get().prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {

                int rank = 1;

                while (rs.next()) {

                    String supplier = rs.getString("SupName");
                    String country = rs.getString("Country");
                    BigDecimal rating = rs.getBigDecimal("Rating");
                    int materialCount = rs.getInt("MaterialCount");

                    table.getItems().add(new Object[]{
                            rank,
                            supplier,
                            country,
                            rating,
                            materialCount
                    });

                    rank++;
                }

            } catch (SQLException ex) {
                Util.error("DB error", ex.getMessage());
            }
        };

        refreshers.add(load);
        load.run();

        VBox box = new VBox(10,
                title,
                explanation,
                table
        );

        box.setPadding(new Insets(10));
        return new Tab("Multi-Material Suppliers", box);
    }


    private Tab buildMaterialsWithoutSupplier() {

        Label title = new Label("Materials Without Supplier");
        title.setStyle(
                "-fx-font-size: 16px;" +
                "-fx-font-weight: bold;" +
                "-fx-text-fill: #1a3a3a;"
        );

        Label explanation = new Label(
                "Lists raw materials that are not linked to any supplier. These materials may cause purchasing problems because no supplier provides them."
        );
        explanation.setStyle("-fx-text-fill: #555;");

        TableView<Object[]> table = new TableView<>();

        TableColumn<Object[], Integer> idCol = new TableColumn<>("Material ID");
        idCol.setPrefWidth(110);
        idCol.setCellValueFactory(cd ->
                new SimpleObjectProperty<>((Integer) cd.getValue()[0])
        );

        TableColumn<Object[], String> materialCol = new TableColumn<>("Material");
        materialCol.setPrefWidth(260);
        materialCol.setCellValueFactory(cd ->
                new SimpleStringProperty((String) cd.getValue()[1])
        );

        TableColumn<Object[], String> categoryCol = new TableColumn<>("Category");
        categoryCol.setPrefWidth(150);
        categoryCol.setCellValueFactory(cd ->
                new SimpleStringProperty((String) cd.getValue()[2])
        );

        TableColumn<Object[], String> unitCol = new TableColumn<>("Unit");
        unitCol.setPrefWidth(100);
        unitCol.setCellValueFactory(cd ->
                new SimpleStringProperty((String) cd.getValue()[3])
        );

        TableColumn<Object[], Integer> reorderCol = new TableColumn<>("Reorder Level");
        reorderCol.setPrefWidth(140);
        reorderCol.setCellValueFactory(cd ->
                new SimpleObjectProperty<>((Integer) cd.getValue()[4])
        );

        table.getColumns().add(idCol);
        table.getColumns().add(materialCol);
        table.getColumns().add(categoryCol);
        table.getColumns().add(unitCol);
        table.getColumns().add(reorderCol);

        table.setPrefHeight(520);

        Runnable load = () -> {
            table.getItems().clear();

            String sql =
                    "SELECT rm.MaterialID, rm.MaterialName, rm.Category, rm.Unit, rm.ReorderLevel " +
                    "FROM RawMaterial rm " +
                    "WHERE NOT EXISTS ( " +
                    "    SELECT sm.MaterialID " +
                    "    FROM SupplierMaterial sm " +
                    "    WHERE sm.MaterialID = rm.MaterialID " +
                    ") " +
                    "ORDER BY rm.Category, rm.MaterialName";

            try (PreparedStatement ps = DB.get().prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    table.getItems().add(new Object[]{
                            rs.getInt("MaterialID"),
                            rs.getString("MaterialName"),
                            rs.getString("Category"),
                            rs.getString("Unit"),
                            rs.getInt("ReorderLevel")
                    });
                }

            } catch (SQLException ex) {
                Util.error("DB error", ex.getMessage());
            }
        };

        refreshers.add(load);
        load.run();

        VBox box = new VBox(10,
                title,
                explanation,
                table
        );

        box.setPadding(new Insets(10));
        return new Tab("Materials Without Supplier", box);
    }


    private Tab buildLowStockRawMaterials() {

        Label title = new Label("Low Stock Raw Materials");
        title.setStyle(
                "-fx-font-size: 16px;" +
                "-fx-font-weight: bold;" +
                "-fx-text-fill: #1a3a3a;"
        );

        Label explanation = new Label(
                "Shows raw materials whose available stock is below their reorder level. Expired batches are not counted as available stock."
        );
        explanation.setStyle("-fx-text-fill: #555;");

        TableView<Object[]> table = new TableView<>();

        TableColumn<Object[], String> materialCol = new TableColumn<>("Material");
        materialCol.setPrefWidth(260);
        materialCol.setCellValueFactory(cd ->
                new SimpleStringProperty((String) cd.getValue()[0])
        );

        TableColumn<Object[], String> categoryCol = new TableColumn<>("Category");
        categoryCol.setPrefWidth(150);
        categoryCol.setCellValueFactory(cd ->
                new SimpleStringProperty((String) cd.getValue()[1])
        );

        TableColumn<Object[], String> unitCol = new TableColumn<>("Unit");
        unitCol.setPrefWidth(90);
        unitCol.setCellValueFactory(cd ->
                new SimpleStringProperty((String) cd.getValue()[2])
        );

        TableColumn<Object[], Integer> currentStockCol = new TableColumn<>("Current Stock");
        currentStockCol.setPrefWidth(130);
        currentStockCol.setCellValueFactory(cd ->
                new SimpleObjectProperty<>((Integer) cd.getValue()[3])
        );

        TableColumn<Object[], Integer> reorderCol = new TableColumn<>("Reorder Level");
        reorderCol.setPrefWidth(130);
        reorderCol.setCellValueFactory(cd ->
                new SimpleObjectProperty<>((Integer) cd.getValue()[4])
        );

        TableColumn<Object[], Integer> shortfallCol = new TableColumn<>("Shortfall");
        shortfallCol.setPrefWidth(110);
        shortfallCol.setCellValueFactory(cd ->
                new SimpleObjectProperty<>((Integer) cd.getValue()[5])
        );

        table.getColumns().add(materialCol);
        table.getColumns().add(categoryCol);
        table.getColumns().add(unitCol);
        table.getColumns().add(currentStockCol);
        table.getColumns().add(reorderCol);
        table.getColumns().add(shortfallCol);

        table.setPrefHeight(520);

        Runnable load = () -> {
            table.getItems().clear();

            String sql =
                    "SELECT rm.MaterialName, rm.Category, rm.Unit, " +
                    "       CASE WHEN SUM(rmb.Quantity) IS NULL THEN 0 ELSE SUM(rmb.Quantity) END AS CurrentStock, " +
                    "       rm.ReorderLevel, " +
                    "       rm.ReorderLevel - CASE WHEN SUM(rmb.Quantity) IS NULL THEN 0 ELSE SUM(rmb.Quantity) END AS Shortfall " +
                    "FROM RawMaterial rm " +
                    "LEFT JOIN RawMaterialBatch rmb ON rmb.MaterialID = rm.MaterialID " +
                    "     AND rmb.ExpiryDate > CURRENT_DATE " +
                    "GROUP BY rm.MaterialID, rm.MaterialName, rm.Category, rm.Unit, rm.ReorderLevel " +
                    "HAVING CurrentStock < rm.ReorderLevel " +
                    "ORDER BY Shortfall DESC, rm.MaterialName ASC";

            try (PreparedStatement ps = DB.get().prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    table.getItems().add(new Object[]{
                            rs.getString("MaterialName"),
                            rs.getString("Category"),
                            rs.getString("Unit"),
                            rs.getInt("CurrentStock"),
                            rs.getInt("ReorderLevel"),
                            rs.getInt("Shortfall")
                    });
                }

            } catch (SQLException ex) {
                Util.error("DB error", ex.getMessage());
            }
        };

        refreshers.add(load);
        load.run();

        VBox box = new VBox(10,
                title,
                explanation,
                table
        );

        box.setPadding(new Insets(10));
        return new Tab("Low Stock Raw Materials", box);
    }

    private Tab buildProductStockByWarehouse() {

        Label title = new Label("Product Stock by Warehouse");
        title.setStyle(
                "-fx-font-size: 16px;" +
                "-fx-font-weight: bold;" +
                "-fx-text-fill: #1a3a3a;"
        );

        Label explanation = new Label(
                "Shows how finished product stock is distributed across warehouses."
        );
        explanation.setStyle("-fx-text-fill: #555;");

        TableView<Object[]> table = new TableView<>();

        TableColumn<Object[], String> warehouseCol = new TableColumn<>("Warehouse");
        warehouseCol.setPrefWidth(260);
        warehouseCol.setCellValueFactory(cd ->
                new SimpleStringProperty((String) cd.getValue()[0])
        );

        TableColumn<Object[], String> typeCol = new TableColumn<>("Warehouse Type");
        typeCol.setPrefWidth(180);
        typeCol.setCellValueFactory(cd ->
                new SimpleStringProperty((String) cd.getValue()[1])
        );

        TableColumn<Object[], Integer> stockCol = new TableColumn<>("Total Product Stock");
        stockCol.setPrefWidth(170);
        stockCol.setCellValueFactory(cd ->
                new SimpleObjectProperty<>((Integer) cd.getValue()[2])
        );

        table.getColumns().add(warehouseCol);
        table.getColumns().add(typeCol);
        table.getColumns().add(stockCol);

        table.setPrefHeight(300);

        PieChart chart = new PieChart();
        chart.setTitle("Product Stock Distribution");
        chart.setPrefHeight(280);
        chart.setMinHeight(260);
        chart.setLegendVisible(true);
        chart.setLabelsVisible(false);

        Runnable load = () -> {
            table.getItems().clear();
            chart.getData().clear();

            String sql =
                    "SELECT w.WarehouseName, w.Type, " +
                    "       CASE WHEN SUM(pb.Quantity) IS NULL THEN 0 ELSE SUM(pb.Quantity) END AS TotalProductStock " +
                    "FROM Warehouse w " +
                    "LEFT JOIN ProductBatch pb ON pb.WarehouseID = w.WarehouseID " +
                    "GROUP BY w.WarehouseID, w.WarehouseName, w.Type " +
                    "ORDER BY TotalProductStock DESC";

            try (PreparedStatement ps = DB.get().prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {

                    String warehouse = rs.getString("WarehouseName");
                    String type = rs.getString("Type");
                    int totalStock = rs.getInt("TotalProductStock");

                    table.getItems().add(new Object[]{
                            warehouse,
                            type,
                            totalStock
                    });

                    if (totalStock > 0) {
                        chart.getData().add(
                                new PieChart.Data(
                                        warehouse + " - " + totalStock,
                                        totalStock
                                )
                        );
                    }
                }

            } catch (SQLException ex) {
                Util.error("DB error", ex.getMessage());
            }
        };

        refreshers.add(load);
        load.run();

        VBox box = new VBox(10,
                title,
                explanation,
                table,
                chart
        );

        box.setPadding(new Insets(10));
        return new Tab("Product Stock by Warehouse", box);
    }

    private static <S> TableColumn<Object[], S> col(String text, double width,
            java.util.function.Function<Object[], javafx.beans.value.ObservableValue<S>> getter) {
        TableColumn<Object[], S> c = new TableColumn<>(text);
        c.setPrefWidth(width);
        c.setCellValueFactory(cd -> getter.apply(cd.getValue()));
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
