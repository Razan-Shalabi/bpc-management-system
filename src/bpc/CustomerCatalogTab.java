package bpc;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;


public class CustomerCatalogTab extends Tab implements Refreshable {

    public record Product(int id, String name, String category, String dosageForm,
                          BigDecimal unitPrice, int totalAvailable,
                          String picture, String description) {
        public int availableAfterReservation() {
            return Math.max(0, totalAvailable - Cart.reservedFor(id));
        }
    }

    private final ObservableList<Product> master = FXCollections.observableArrayList();
    private final FilteredList<Product> filtered = new FilteredList<>(master, p -> true);
    private final TextField search = new TextField();
    private final ComboBox<String> categoryFilter = new ComboBox<>();
    private final FlowPane cardPane = new FlowPane();

    public CustomerCatalogTab() {
        setText("Browse Catalog");
        setClosable(false);

        search.setPromptText("Search by name");
        search.setPrefWidth(200);
        categoryFilter.getItems().add("All");
        categoryFilter.getSelectionModel().selectFirst();
        categoryFilter.setPrefWidth(200);

        cardPane.setHgap(16);
        cardPane.setVgap(16);
        cardPane.setPadding(new Insets(16));
        cardPane.setStyle("-fx-background-color: #f8fafc;");

        filtered.addListener((javafx.collections.ListChangeListener<Product>) ch -> renderCards());

        Runnable applyFilter = () -> {
            String q = search.getText() == null ? "" : search.getText().toLowerCase().trim();
            String c = categoryFilter.getValue();
            filtered.setPredicate(p -> {
                boolean okCat = "All".equals(c) || c == null || c.equals(p.category());
                if (!okCat) return false;
                if (q.isEmpty()) return true;
                String name = p.name() == null ? "" : p.name().toLowerCase();
                return name.contains(q);
            });
        };
        search.textProperty().addListener((o, a, b) -> applyFilter.run());
        categoryFilter.valueProperty().addListener((o, a, b) -> applyFilter.run());

        Region grow = new Region(); HBox.setHgrow(grow, Priority.ALWAYS);
        HBox toolbar = new HBox(10, heading("Browse Catalog"), grow,
                                new Label("Search:"), search,
                                new Label("Category:"), categoryFilter);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        ScrollPane scroll = new ScrollPane(cardPane);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent;");

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(14));
        VBox top = new VBox(6, toolbar);
        BorderPane.setMargin(top, new Insets(0, 0, 10, 0));
        root.setTop(top);
        root.setCenter(scroll);
        setContent(root);

        refresh();
    }

    @Override
    public void refresh() {
        String prev = categoryFilter.getValue();
        categoryFilter.getItems().clear();
        categoryFilter.getItems().add("All");
        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT CategoryName FROM ProductCategory ORDER BY CategoryName");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) categoryFilter.getItems().add(rs.getString(1));
        } catch (SQLException ex) { Util.error("DB error", ex.getMessage()); }
        if (prev != null && categoryFilter.getItems().contains(prev))
            categoryFilter.getSelectionModel().select(prev);
        else categoryFilter.getSelectionModel().selectFirst();
        master.clear();
        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT p.ProductID, p.ProductName, c.CategoryName, p.DosageForm, p.UnitPrice, " +
                " p.Picture, p.Description, " +
                " COALESCE((SELECT SUM(pb.Quantity) FROM ProductBatch pb " +
                " WHERE pb.ProductID = p.ProductID " +
                "   AND pb.ExpiryDate > CURRENT_DATE), 0) AS Available " +
                "FROM Product p " +
                "JOIN ProductCategory c ON c.CategoryID = p.CategoryID " +
                "ORDER BY p.ProductName");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                master.add(new Product(
                    rs.getInt("ProductID"),
                    rs.getString("ProductName"),
                    rs.getString("CategoryName"),
                    rs.getString("DosageForm"),
                    rs.getBigDecimal("UnitPrice"),
                    rs.getInt("Available"),
                    rs.getString("Picture"),
                    rs.getString("Description")));
            }
        } catch (SQLException ex) { Util.error("DB error", ex.getMessage()); }
        renderCards();
    }

    private void renderCards() {
        cardPane.getChildren().clear();
        for (Product p : filtered) cardPane.getChildren().add(buildCard(p));
        if (filtered.isEmpty()) {
            Label empty = new Label("No products match your filters.");
            empty.setStyle("-fx-text-fill: #94a3b8; -fx-font-style: italic; -fx-padding: 24;");
            cardPane.getChildren().add(empty);
        }
    }

    private VBox buildCard(Product p) {
        VBox card = new VBox(8);
        card.setPadding(new Insets(12));
        card.setPrefWidth(220);
        card.setMaxWidth(220);
        card.setMinHeight(280);
        card.setStyle("-fx-background-color: white; -fx-background-radius: 8; " +
                      "-fx-border-color: #e2e8f0; -fx-border-radius: 8; " +
                      "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.06), 6, 0.0, 0, 2);");

        ImageView iv = loadProductImage(p.picture(), 180, 130);
        StackPane imageBox = new StackPane(iv);
        imageBox.setPrefHeight(140);
        imageBox.setStyle("-fx-background-color: #f1f5f9; -fx-background-radius: 6;");

        Label name = new Label(p.name());
        name.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));
        name.setWrapText(true);

        Label cat = new Label(p.category() + " - " + p.dosageForm());
        cat.setStyle("-fx-text-fill: #64748b; -fx-font-size: 11px;");

        Label price = new Label(Util.money(p.unitPrice()) + " per unit");
        price.setStyle("-fx-text-fill: #0d8a8a; -fx-font-weight: bold; -fx-font-size: 13px;");

        int available = p.availableAfterReservation();
        Label stock;
        if (available <= 0) {
            stock = new Label("Out of stock");
            stock.setStyle("-fx-text-fill: #c0392b; -fx-font-size: 12px; -fx-font-weight: bold;");
        } else {
            stock = new Label(available + " units available");
            stock.setStyle("-fx-text-fill: #16a34a; -fx-font-size: 12px;");
        }

        Button viewBtn = new Button("View details");
        viewBtn.setMaxWidth(Double.MAX_VALUE);
        viewBtn.setStyle("-fx-background-color: #0d8a8a; -fx-text-fill: white; -fx-font-weight: bold;");
        viewBtn.setOnAction(e -> openDetailDialog(p));

        card.getChildren().addAll(imageBox, name, cat, price, stock, viewBtn);
        card.setCursor(javafx.scene.Cursor.HAND);
        card.setOnMouseClicked(e -> openDetailDialog(p));
        String baseStyle = card.getStyle();
        String hoverStyle = baseStyle.replace(
            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.06), 6, 0.0, 0, 2)",
            "-fx-effect: dropshadow(gaussian, rgba(13,138,138,0.25), 10, 0.1, 0, 3)");
        card.setOnMouseEntered(e -> card.setStyle(hoverStyle));
        card.setOnMouseExited (e -> card.setStyle(baseStyle));
        return card;
    }

    private void openDetailDialog(Product p) {
        Stage dlg = new Stage();
        dlg.initModality(Modality.APPLICATION_MODAL);
        dlg.setTitle(p.name());

        ImageView iv = loadProductImage(p.picture(), 260, 200);
        StackPane imageBox = new StackPane(iv);
        imageBox.setPrefSize(280, 220);
        imageBox.setStyle("-fx-background-color: #f1f5f9; -fx-background-radius: 8;");

        Label name = new Label(p.name());
        name.setFont(Font.font("Segoe UI", FontWeight.BOLD, 20));

        Label cat = new Label(p.category() + "  -  " + p.dosageForm());
        cat.setStyle("-fx-text-fill: #64748b; -fx-font-size: 12px;");

        Label price = new Label("Price: " + Util.money(p.unitPrice()) + " per unit");
        price.setStyle("-fx-text-fill: #0d8a8a; -fx-font-weight: bold; -fx-font-size: 14px;");

        Label desc = new Label(p.description() == null || p.description().isBlank()
                                ? "(no description available)" : p.description());
        desc.setWrapText(true);
        desc.setMaxWidth(560);
        desc.setStyle("-fx-text-fill: #334155;");

        int available = p.availableAfterReservation();
        Label totalLbl = new Label("Available now: " + available + " units");
        totalLbl.setStyle("-fx-font-weight: bold; -fx-font-size: 14px; "
            + (available > 0 ? "-fx-text-fill: #16a34a;" : "-fx-text-fill: #c0392b;"));
        Spinner<Integer> qtySpinner = new Spinner<>(1, Math.max(1, available), 1);
        qtySpinner.setEditable(true);
        qtySpinner.setPrefWidth(110);
        qtySpinner.setDisable(available <= 0);
        qtySpinner.getEditor().textProperty().addListener((obs, oldV, newV) -> {
            if (newV != null && !newV.matches("\\d*")) {
                qtySpinner.getEditor().setText(newV.replaceAll("[^\\d]", ""));
            }
        });

        Label livePreview = new Label("");
        livePreview.setStyle("-fx-text-fill: #334155; -fx-font-size: 12px;");
        Runnable updatePreview = () -> {
            int q;
            try { q = qtySpinner.getValue() == null ? 0 : qtySpinner.getValue(); }
            catch (Exception ex) { q = 0; }
            BigDecimal total = p.unitPrice().multiply(BigDecimal.valueOf(q));
            livePreview.setText(q + " unit(s) * " + Util.money(p.unitPrice())
                + " = " + Util.money(total));
        };
        qtySpinner.valueProperty().addListener((obs, oldV, newV) -> updatePreview.run());
        qtySpinner.getEditor().textProperty().addListener((obs, oldT, newT) -> updatePreview.run());
        updatePreview.run();

        Button addBtn = new Button("Add to cart");
        addBtn.setStyle("-fx-background-color: #0d8a8a; -fx-text-fill: white; " +
                        "-fx-font-weight: bold; -fx-padding: 8 22;");
        addBtn.setDisable(available <= 0);
        addBtn.setOnAction(e -> {
            Integer q;
            try { q = qtySpinner.getValue(); }
            catch (Exception ex) {
                Util.warn("Bad quantity", "Quantity must be a number greater than 0."); return;
            }
            if (q == null || q <= 0) {
                Util.warn("Bad quantity", "Quantity must be greater than 0."); return;
            }
            if (q > available) {
                Util.warn("Not enough stock",
                    "Only " + available + " units of " + p.name() + " are available right now."); return;
            }
            Cart.add(new Cart.Item(p.id(), p.name(), q, p.unitPrice()));
            Util.info("Added to cart",
                q + " unit(s) of " + p.name() + " added to your cart.");
            dlg.close();
            refresh();
        });

        Button close = new Button("Close");
        close.setOnAction(e -> dlg.close());
        Region gr = new Region(); HBox.setHgrow(gr, Priority.ALWAYS);
        HBox addRow = new HBox(10, new Label("Units to buy:"), qtySpinner,
                                livePreview, gr, addBtn, close);
        addRow.setAlignment(Pos.CENTER_LEFT);
        VBox left  = new VBox(10, imageBox);
        left.setAlignment(Pos.TOP_CENTER);
        VBox right = new VBox(8, name, cat, price, new Separator(), desc,
                              new Separator(), totalLbl);
        right.setPrefWidth(580);
        HBox row = new HBox(20, left, right);
        row.setPadding(new Insets(20));
        row.setAlignment(Pos.TOP_LEFT);

        VBox box = new VBox(12, row, addRow);
        box.setPadding(new Insets(0, 20, 20, 20));
        box.setMaxWidth(960);

        ScrollPane sp = new ScrollPane(box);
        sp.setFitToWidth(true);
        sp.setPrefSize(960, 620);

        dlg.setScene(new Scene(sp));
        dlg.showAndWait();
    }

    private ImageView loadProductImage(String filename, double width, double height) {
        ImageView iv = new ImageView();
        iv.setFitWidth(width);
        iv.setFitHeight(height);
        iv.setPreserveRatio(true);
        iv.setImage(Util.productImage(filename));
        return iv;
    }

    private static Label heading(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: #1a3a3a;");
        return l;
    }
}
