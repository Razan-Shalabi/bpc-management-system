package bpc;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class CustomerPortal {

    private final Stage stage;
    private final Runnable backToLogin;

    public CustomerPortal(Stage stage, Runnable backToLogin) {
        this.stage = stage;
        this.backToLogin = backToLogin;
    }

    public Scene buildScene() {
        Label appTitle = new Label("BPC Customer Portal");
        appTitle.setStyle("-fx-text-fill: white; -fx-font-size: 18px; -fx-font-weight: bold;");

        Label welcome = new Label("Welcome, " + Session.customerName());
        welcome.setStyle("-fx-text-fill: white;");

        Button changePwBtn = new Button("Change password");
        changePwBtn.setOnAction(e -> openChangePasswordDialog(stage));

        Button signOut = new Button("Sign out");
        signOut.setOnAction(e -> { Cart.clear(); backToLogin.run(); });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox topBar = new HBox(14, appTitle, spacer, welcome, changePwBtn, signOut);
        topBar.setPadding(new Insets(10, 20, 10, 20));
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setStyle("-fx-background-color: #0a7474;");

        TabPane tabs = new TabPane(
            new CustomerMyOrdersTab(),
            new CustomerMyInvoicesTab(),
            new CustomerCatalogTab(),
            new CustomerCartTab()
        );
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (newTab instanceof Refreshable r) r.refresh();
        });

        BorderPane root = new BorderPane();
        root.setTop(topBar);
        root.setCenter(tabs);
        return new Scene(root, 1180, 720);
    }

    private void openChangePasswordDialog(Stage owner) {
        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.initModality(Modality.APPLICATION_MODAL);
        dlg.setTitle("Change password");

        PasswordField current = new PasswordField();
        PasswordField next    = new PasswordField();
        PasswordField confirm = new PasswordField();
        Label err = new Label(); err.setStyle("-fx-text-fill: #c0392b;");

        Button save = new Button("Update password");
        save.setStyle("-fx-background-color: #0d8a8a; -fx-text-fill: white; -fx-font-weight: bold;");
        Button cancel = new Button("Cancel");

        save.setOnAction(e -> {
            err.setText("");
            if (current.getText().isBlank() || next.getText().isBlank() || confirm.getText().isBlank()) {
                err.setText("All fields are required."); return;
            }
            String pwProblem = Validate.password(next.getText());
            if (pwProblem != null) { err.setText(pwProblem); return; }
            if (!next.getText().equals(confirm.getText())) {
                err.setText("New password and confirmation don't match."); return;
            }
            if (current.getText().equals(next.getText())) {
                err.setText("New password must be different from the current one."); return;
            }
            int customerId = Session.customerId();
            try {
                try (PreparedStatement ps = DB.get().prepareStatement(
                        "SELECT Password FROM Customer WHERE CustomerID=?")) {
                    ps.setInt(1, customerId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) { err.setText("Account not found."); return; }
                        String stored = rs.getString(1);
                        if (stored == null || !stored.equals(current.getText())) {
                            err.setText("Current password is incorrect."); return;
                        }
                    }
                }
                try (PreparedStatement ps = DB.get().prepareStatement(
                        "UPDATE Customer SET Password=? WHERE CustomerID=?")) {
                    ps.setString(1, next.getText());
                    ps.setInt(2, customerId);
                    ps.executeUpdate();
                }
                Util.info("Password updated",
                    "Your password has been changed. Use the new password the next time you sign in.");
                dlg.close();
            } catch (SQLException ex) {
                err.setText("Update failed: " + ex.getMessage());
            }
        });
        cancel.setOnAction(e -> dlg.close());

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(10);
        grid.addRow(0, new Label("Current password *"), current);
        grid.addRow(1, new Label("New password *"), next);
        grid.addRow(2, new Label("Confirm new *"), confirm);

        Region g = new Region(); HBox.setHgrow(g, Priority.ALWAYS);
        HBox buttons = new HBox(10, g, cancel, save);

        Label heading = new Label("Change your password");
        heading.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #0d8a8a;");

        VBox box = new VBox(12, heading, grid, err, buttons);
        box.setPadding(new Insets(20));
        box.setPrefWidth(420);
        dlg.setScene(new Scene(box));
        dlg.showAndWait();
    }
}
