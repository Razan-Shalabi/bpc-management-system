package bpc;

import javafx.geometry.Insets;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;

public class DashboardTab extends Tab implements Refreshable {

    private final Label kpiOrders   = new Label("0");
    private final Label kpiRevenue  = new Label("0 ILS");
    private final Label kpiOverdue  = new Label("0");
    private final Label kpiOpenPOs   = new Label("0");
    private final Label kpiLowStock  = new Label("0");
    private final Label kpiExpiring  = new Label("0");
    private final BarChart<String, Number>  quarterChart =
        new BarChart<>(new CategoryAxis(), new NumberAxis());
    private final LineChart<String, Number> monthChart   =
        new LineChart<>(new CategoryAxis(), new NumberAxis());

    public DashboardTab() {
        setText("Dashboard");
        setClosable(false);

        HBox salesRow = new HBox(16,
            kpiTile(kpiOrders,  "Orders this month",     "#0d8a8a"),
            kpiTile(kpiRevenue, "Revenue year-to-date",  "#0d8a8a"),
            kpiTile(kpiOverdue, "Overdue invoices",      "#c0392b")
        );
        for (var n : salesRow.getChildren()) HBox.setHgrow(n, Priority.ALWAYS);

        HBox opsRow = new HBox(16,
            kpiTile(kpiOpenPOs,  "Open purchase orders",     "#2980b9"),
            kpiTile(kpiLowStock, "Products below reorder",   "#c0392b"),
            kpiTile(kpiExpiring, "Batches expiring (30d)",   "#f39c12")
        );
        for (var n : opsRow.getChildren()) HBox.setHgrow(n, Priority.ALWAYS);

        quarterChart.setTitle("Quarterly Sales Revenue (this year)");
        quarterChart.setLegendVisible(false);
        monthChart  .setTitle("Monthly Sales Revenue (last 12 months)");
        monthChart  .setLegendVisible(false);

        HBox charts = new HBox(16, quarterChart, monthChart);
        HBox.setHgrow(quarterChart, Priority.ALWAYS);
        HBox.setHgrow(monthChart,   Priority.ALWAYS);

        VBox root = new VBox(14,
            heading("Dashboard"),
            salesRow,
            opsRow,
            charts);
        root.setPadding(new Insets(14));
        VBox.setVgrow(charts, Priority.ALWAYS);
        setContent(root);

        refresh();
    }

    @Override
    public void refresh() {
        Billing.markOverdue();
        try {
            int orders     = scalarInt(
                "SELECT COUNT(*) FROM SalesOrder " +
                "WHERE OrderDate >= DATE_FORMAT(CURRENT_DATE,'%Y-%m-01')");
            kpiOrders.setText(String.valueOf(orders));

            var rev = scalarBigDecimal(
                "SELECT COALESCE(SUM(TotalAmount),0) FROM Invoice " +
                "WHERE IssueDate >= DATE_FORMAT(CURRENT_DATE,'%Y-01-01')");
            kpiRevenue.setText(Util.money(rev));

            int overdue = scalarInt("SELECT COUNT(*) FROM Invoice WHERE Status='Overdue'");
            kpiOverdue.setText(String.valueOf(overdue));

        
            int openPOs = scalarInt(
                "SELECT COUNT(*) FROM PurchaseOrder WHERE Status='Pending'");
            kpiOpenPOs.setText(String.valueOf(openPOs));
            int lowStock = scalarInt(
                "SELECT COUNT(*) FROM ( " +
                "  SELECT p.ProductID FROM Product p " +
                "  LEFT JOIN ProductBatch pb ON pb.ProductID = p.ProductID " +
                "       AND pb.ExpiryDate > CURRENT_DATE " +
                "  GROUP BY p.ProductID, p.ReorderLevel " +
                "  HAVING COALESCE(SUM(pb.Quantity),0) < p.ReorderLevel " +
                ") low");
            kpiLowStock.setText(String.valueOf(lowStock));

            int expiring = scalarInt(
                "SELECT COUNT(*) FROM ( " +
                "  SELECT ProductBatchID AS id FROM ProductBatch " +
                "  WHERE ExpiryDate BETWEEN CURRENT_DATE AND DATE_ADD(CURRENT_DATE, INTERVAL 30 DAY) " +
                "  UNION ALL " +
                "  SELECT RMBatchID AS id FROM RawMaterialBatch " +
                "  WHERE ExpiryDate BETWEEN CURRENT_DATE AND DATE_ADD(CURRENT_DATE, INTERVAL 30 DAY) " +
                ") combined");
            kpiExpiring.setText(String.valueOf(expiring));

            quarterChart.getData().clear();
            monthChart  .getData().clear();

            int year = LocalDate.now().getYear();
            XYChart.Series<String, Number> qs = new XYChart.Series<>();
            try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT QUARTER(OrderDate) AS Q, COALESCE(SUM(TotalAmount),0) AS R " +
                "FROM SalesOrder WHERE YEAR(OrderDate)=? " +
                "GROUP BY QUARTER(OrderDate) ORDER BY QUARTER(OrderDate)")) {
                ps.setInt(1, year);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        qs.getData().add(new XYChart.Data<>(
                            "Q" + rs.getInt("Q"), rs.getBigDecimal("R")));
                    }
                }
            }
            quarterChart.getData().add(qs);

            XYChart.Series<String, Number> ms = new XYChart.Series<>();
            try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT DATE_FORMAT(OrderDate,'%Y-%m') AS YM, " +
                "       COALESCE(SUM(TotalAmount),0) AS R " +
                "FROM SalesOrder " +
                "WHERE OrderDate >= DATE_SUB(CURRENT_DATE, INTERVAL 12 MONTH) " +
                "GROUP BY YM ORDER BY YM");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ms.getData().add(new XYChart.Data<>(
                        rs.getString("YM"), rs.getBigDecimal("R")));
                }
            }
            monthChart.getData().add(ms);

        } catch (Exception ex) {
            Util.error("Dashboard error", ex.getMessage());
        }
    }

    private int scalarInt(String sql) throws java.sql.SQLException {
        try (PreparedStatement ps = DB.get().prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
    private java.math.BigDecimal scalarBigDecimal(String sql) throws java.sql.SQLException {
        try (PreparedStatement ps = DB.get().prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getBigDecimal(1) : java.math.BigDecimal.ZERO;
        }
    }

    private static VBox kpiTile(Label value, String labelText, String color) {
        value.setStyle("-fx-font-size: 26px; -fx-font-weight: bold; -fx-text-fill: " + color + ";");
        Label desc = new Label(labelText);
        desc.setStyle("-fx-text-fill: #555; -fx-font-size: 12px;");
        VBox box = new VBox(4, value, desc);
        box.setPadding(new Insets(18));
        box.setStyle("-fx-background-color: white; -fx-background-radius: 6;");
        return box;
    }
    private static Label heading(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: #1a3a3a;");
        return l;
    }
}
