package bpc;

import javafx.application.Application;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;

public class Main extends Application {

    private Stage stage;
    private static final int WIDTH  = 1180;
    private static final int HEIGHT = 720;

    @Override
    public void start(Stage primary) {
        this.stage = primary;
        primary.setTitle("BPC - Birzeit Pharmaceutical Company");
        primary.setScene(buildLoginScene());
        primary.setWidth(WIDTH);
        primary.setHeight(HEIGHT);
        primary.centerOnScreen();
        primary.setMaximized(true);
        primary.show();
    }

  
    private Scene buildLoginScene() {
  
        ImageView logoView = loadLogo();
        Node logoContent = (logoView != null) ? logoView : badge("BPC");
        StackPane logoCard = new StackPane(logoContent);
        logoCard.setPadding(new Insets(20));
        logoCard.setMaxSize(180, 180);
        logoCard.setMinSize(180, 180);
        logoCard.setStyle(
            "-fx-background-color: white; " +
            "-fx-background-radius: 16; " +
            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.25), 24, 0.15, 0, 8);");

        Label appName = new Label("BPC System");
        appName.setFont(Font.font("Segoe UI", FontWeight.BOLD, 32));
        appName.setStyle("-fx-text-fill: white;");

        Label tagline = new Label("Birzeit Pharmaceutical Company");
        tagline.setStyle("-fx-text-fill: #c4e8e8; -fx-font-size: 15px; -fx-font-weight: 500;");

        Region accentBar = new Region();
        accentBar.setPrefSize(60, 3);
        accentBar.setMaxSize(60, 3);
        accentBar.setStyle("-fx-background-color: #f5c674; -fx-background-radius: 2;");

        Label motto = new Label("\"From the heart of Palestine, for a better life.\"");
        motto.setStyle("-fx-text-fill: #a4d8d6; -fx-font-style: italic; -fx-font-size: 13px;");
        motto.setWrapText(true);
        motto.setMaxWidth(320);
        motto.setAlignment(Pos.CENTER);
        motto.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);

        VBox brandBlock = new VBox(22, logoCard, appName, tagline, accentBar, motto);
        brandBlock.setAlignment(Pos.CENTER);
        brandBlock.setMaxWidth(420);

        VBox leftPane = new VBox(brandBlock);
        leftPane.setAlignment(Pos.CENTER);
        leftPane.setSpacing(40);
        leftPane.setPadding(new Insets(40));
        leftPane.setMinWidth(460);
        leftPane.setMaxWidth(460);
        leftPane.setStyle(
            "-fx-background-color: linear-gradient(to bottom right, #1a9a9a 0%, #0d8a8a 40%, #0a6a78 75%, #114e60 100%);");

        Label signInTitle = new Label("Welcome Back");
        signInTitle.setFont(Font.font("Segoe UI", FontWeight.BOLD, 28));
        signInTitle.setStyle("-fx-text-fill: #114e60;");

        Label signInSub = new Label("Choose your role to continue");
        signInSub.setStyle("-fx-text-fill: #64748b; -fx-font-size: 14px;");

        ToggleGroup roleGroup = new ToggleGroup();
        ToggleButton staffBtn    = new ToggleButton("Staff");
        ToggleButton customerBtn = new ToggleButton("Customer");
        staffBtn.setToggleGroup(roleGroup);
        customerBtn.setToggleGroup(roleGroup);
        staffBtn.setSelected(true);
        styleToggle(staffBtn);
        styleToggle(customerBtn);
        staffBtn.setPrefWidth(160);
        customerBtn.setPrefWidth(160);
        staffBtn.setPrefHeight(44);
        customerBtn.setPrefHeight(44);

        HBox toggleRow = new HBox(0, staffBtn, customerBtn);
        toggleRow.setAlignment(Pos.CENTER);
        toggleRow.setStyle("-fx-background-color: #f1f5f9; -fx-background-radius: 6; -fx-padding: 0;");

        TextField userField = new TextField();
        userField.setPromptText("Enter your username");
        styleField(userField);
        PasswordField passField = new PasswordField();
        passField.setPromptText("Enter your password");
        styleField(passField);
        VBox staffPane = new VBox(8, formLabel("Username"), userField,
                                      formLabel("Password"), passField);

        ComboBox<CustomerOption> customerBox = new ComboBox<>();
        customerBox.setPromptText("Select your account");
        customerBox.setMaxWidth(Double.MAX_VALUE);
        customerBox.setStyle(
            "-fx-background-color: #f8fafc; -fx-border-color: #cbd5e1; " +
            "-fx-border-width: 1; -fx-border-radius: 6; -fx-background-radius: 6; " +
            "-fx-font-size: 13px;");
        loadCustomerList(customerBox);
        PasswordField custLoginPass = new PasswordField();
        custLoginPass.setPromptText("Enter your password");
        styleField(custLoginPass);
        VBox customerPane = new VBox(8,
            formLabel("Account"),  customerBox,
            formLabel("Password"), custLoginPass);

        StackPane formPane = new StackPane(staffPane);

        roleGroup.selectedToggleProperty().addListener((obs, oldT, newT) -> {
            if (newT == null) { oldT.setSelected(true); return; }
            formPane.getChildren().setAll(newT == staffBtn ? staffPane : customerPane);
        });

        Label errorLbl = new Label();
        errorLbl.setStyle("-fx-text-fill: #dc2626; -fx-font-size: 12px;");
        errorLbl.setWrapText(true);
        errorLbl.setMinHeight(20);

        Button signIn = new Button("Sign In");
        signIn.setDefaultButton(true);
        signIn.setMaxWidth(Double.MAX_VALUE);
        String signInBaseStyle =
            "-fx-background-color: linear-gradient(to right, #0d8a8a, #0a7474); " +
            "-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 15px; " +
            "-fx-padding: 13 0; -fx-background-radius: 6; " +
            "-fx-effect: dropshadow(gaussian, rgba(13,138,138,0.35), 8, 0.0, 0, 3);";
        String signInHoverStyle =
            "-fx-background-color: linear-gradient(to right, #0a7474, #085f5f); " +
            "-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 15px; " +
            "-fx-padding: 13 0; -fx-background-radius: 6; " +
            "-fx-effect: dropshadow(gaussian, rgba(13,138,138,0.5), 10, 0.0, 0, 4);";
        signIn.setStyle(signInBaseStyle);
        signIn.setOnMouseEntered(e -> signIn.setStyle(signInHoverStyle));
        signIn.setOnMouseExited (e -> signIn.setStyle(signInBaseStyle));

        signIn.setOnAction(e -> {
            errorLbl.setText("");
            try { DB.get().createStatement().executeQuery("SELECT 1").close(); }
            catch (Exception ex) {
                errorLbl.setText("Database connection failed - check MySQL is running.");
                return;
            }
            if (staffBtn.isSelected()) {
                String u = userField.getText() == null ? "" : userField.getText().trim();
                String p = passField.getText() == null ? "" : passField.getText();
                if (u.isEmpty()) { errorLbl.setText("Please enter your username."); return; }
                if (p.isEmpty()) { errorLbl.setText("Please enter your password."); return; }
                EmployeeRow emp = findEmployee(u, p);
                if (emp == null) {
                    errorLbl.setText("Invalid staff credentials.");
                    return;
                }
                Session.loginAsEmployee(emp.id(), u, emp.name(), emp.role());
                passField.clear();
                openStaffShell();
            } else {
                CustomerOption sel = customerBox.getValue();
                String p = custLoginPass.getText() == null ? "" : custLoginPass.getText();
                if (sel == null) { errorLbl.setText("Please choose your account."); return; }
                if (p.isEmpty()) { errorLbl.setText("Please enter your password."); return; }
                if (!verifyCustomerPassword(sel.id(), p)) {
                    errorLbl.setText("Incorrect password for this account.");
                    return;
                }
                Session.loginAsCustomer(sel.id(), sel.name());
                custLoginPass.clear();
                openCustomerPortal();
            }
        });

        Region divider = new Region();
        divider.setPrefHeight(1);
        divider.setMaxWidth(Double.MAX_VALUE);
        divider.setStyle("-fx-background-color: #e5e7eb;");

        VBox form = new VBox(14,
            signInTitle, signInSub,
            toggleRow,
            formPane,
            errorLbl,
            signIn,
            divider);
        form.setPadding(new Insets(36, 60, 36, 60));
        form.setMaxWidth(440);
        form.setMaxHeight(Region.USE_PREF_SIZE);
        form.setAlignment(Pos.TOP_LEFT);

        StackPane rightPane = new StackPane(form);
        StackPane.setAlignment(form, Pos.CENTER);
        rightPane.setAlignment(Pos.CENTER);
        rightPane.setStyle("-fx-background-color: #fafbfc;");

        HBox root = new HBox(leftPane, rightPane);
        HBox.setHgrow(rightPane, Priority.ALWAYS);

        return new Scene(root, WIDTH, HEIGHT);
    }

    public record EmployeeRow(int id, String name, String role) {}

    private EmployeeRow findEmployee(String username, String password) {
        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT EmpID, EmpName, Role FROM Employee " +
                "WHERE Username=? AND Password COLLATE utf8mb4_bin = ?")) {
            ps.setString(1, username);
            ps.setString(2, password);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new EmployeeRow(rs.getInt(1), rs.getString(2), rs.getString(3));
                }
                return null;
            }
        } catch (Exception ex) {
            return null;
        }
    }

    private boolean verifyCustomerPassword(int customerId, String password) {
        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT 1 FROM Customer WHERE CustomerID=? AND Password COLLATE utf8mb4_bin = ?")) {
            ps.setInt(1, customerId);
            ps.setString(2, password);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception ex) {
            return false;
        }
    }

    public record CustomerOption(int id, String name, String city) {
        @Override public String toString() { return name + " (" + city + ")"; }
    }

    private void loadCustomerList(ComboBox<CustomerOption> box) {
        try (PreparedStatement ps = DB.get().prepareStatement(
                "SELECT CustomerID, CustomerName, City FROM Customer ORDER BY CustomerName");
             ResultSet rs = ps.executeQuery()) {
            Map<Integer, CustomerOption> map = new LinkedHashMap<>();
            while (rs.next()) {
                map.put(rs.getInt(1),
                    new CustomerOption(rs.getInt(1), rs.getString(2), rs.getString(3)));
            }
            box.setItems(FXCollections.observableArrayList(map.values()));
        } catch (Exception ex) {
        }
    }

    private ImageView loadLogo() {
        try {
            var stream = getClass().getResourceAsStream("/bpc/bpc-logo.png");
            if (stream == null) return null;
            ImageView v = new ImageView(new Image(stream));
            v.setFitHeight(140);
            v.setPreserveRatio(true);
            return v;
        } catch (Exception ex) { return null; }
    }
    private Label badge(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #0d8a8a; " +
                   "-fx-font-size: 48px; -fx-font-weight: bold;");
        return l;
    }
    private void styleToggle(ToggleButton b) {
        Runnable setUnselected = () -> b.setStyle(
            "-fx-background-color: transparent; -fx-text-fill: #64748b; " +
            "-fx-font-weight: bold; -fx-font-size: 14px; " +
            "-fx-background-radius: 6;");
        Runnable setSelected = () -> b.setStyle(
            "-fx-background-color: #0d8a8a; -fx-text-fill: white; " +
            "-fx-font-weight: bold; -fx-font-size: 14px; " +
            "-fx-background-radius: 6; " +
            "-fx-effect: dropshadow(gaussian, rgba(13,138,138,0.3), 6, 0.0, 0, 2);");
        if (b.isSelected()) setSelected.run(); else setUnselected.run();
        b.selectedProperty().addListener((o, was, now) -> {
            if (now) setSelected.run(); else setUnselected.run();
        });
    }
    private void styleField(TextField f) {
        f.setStyle(
            "-fx-background-color: #f8fafc; " +
            "-fx-border-color: #cbd5e1; -fx-border-width: 1; " +
            "-fx-border-radius: 6; -fx-background-radius: 6; " +
            "-fx-padding: 10 12; -fx-font-size: 13px;");
        f.focusedProperty().addListener((obs, was, now) -> {
            if (now) {
                f.setStyle(
                    "-fx-background-color: white; " +
                    "-fx-border-color: #0d8a8a; -fx-border-width: 2; " +
                    "-fx-border-radius: 6; -fx-background-radius: 6; " +
                    "-fx-padding: 9 11; -fx-font-size: 13px;");
            } else {
                f.setStyle(
                    "-fx-background-color: #f8fafc; " +
                    "-fx-border-color: #cbd5e1; -fx-border-width: 1; " +
                    "-fx-border-radius: 6; -fx-background-radius: 6; " +
                    "-fx-padding: 10 12; -fx-font-size: 13px;");
            }
        });
    }
    private Label formLabel(String t) {
        Label l = new Label(t);
        l.setStyle("-fx-text-fill: #334155; -fx-font-weight: bold; -fx-font-size: 12px;");
        return l;
    }

  
    private void openStaffShell() {
        Label appTitle = new Label("BPC System");
        appTitle.setStyle("-fx-text-fill: white; -fx-font-size: 18px; -fx-font-weight: bold;");

        String greeting = (Session.empName() != null ? Session.empName() : Session.username())
                        + (Session.role() != null ? "  (" + Session.role() + ")" : "");
        Label userLbl = new Label(greeting);
        userLbl.setStyle("-fx-text-fill: white;");

        Button signOut = new Button("Sign out");
        signOut.setOnAction(e -> backToLogin());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox topBar = new HBox(14, appTitle, spacer, userLbl, signOut);
        topBar.setPadding(new Insets(10, 20, 10, 20));
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setStyle("-fx-background-color: #114e60;");

        // Each role only sees the modules it works with; the General Manager sees everything.
        java.util.List<Tab> visibleTabs = new java.util.ArrayList<>();
        String role = Session.role() == null ? "" : Session.role();
        boolean gm          = Session.isGeneralManager();
        boolean whManager   = role.equals("Warehouse Manager");
        boolean salesMgr    = role.equals("Sales Manager");
        boolean procurement = role.equals("Procurement Officer");
        boolean salesRep    = role.equals("Sales Representative");
        boolean production  = role.equals("Production Officer");

        if (Session.isManager()) {
            visibleTabs.add(standaloneTab("Dashboard", new DashboardTab()));
        }
        if (gm || salesMgr || salesRep) {
            visibleTabs.add(sectionTab("Sales", new OrdersTab(), new InvoicesTab()));
        }
        if (gm || procurement || whManager) {
            visibleTabs.add(sectionTab("Procurement",
                new SuppliersTab(), new PurchaseOrdersTab(), new GoodsReceiptsTab(),
                new SupplierInvoicesTab(), new RawMaterialsTab()));
        }
        if (gm || whManager || procurement || production) {
            visibleTabs.add(sectionTab("Warehouse",
                new WarehousesTab(), new InventoryTab(), new StockTransfersTab()));
        }
        if (gm || whManager || production) {
            visibleTabs.add(sectionTab("Catalog & Production",
                new CategoriesTab(), new ProductsTab(), new ProductionTab()));
        } else if (salesMgr) {
            visibleTabs.add(sectionTab("Catalog", new CategoriesTab(), new ProductsTab()));
        }
        if (Session.isManager()) {
            visibleTabs.add(standaloneTab("Reports", new ReportsTab()));
        }
        if (gm || salesMgr) {
            visibleTabs.add(standaloneTab("Customer Management", new CustomersTab()));
        }
        if (gm) {
            visibleTabs.add(standaloneTab("User Management", new UserManagementTab()));
        }
        if (visibleTabs.isEmpty()) {
            Label none = new Label("Your role (" + role + ") has no modules assigned. " +
                                   "Ask the General Manager to update your account.");
            none.setPadding(new Insets(30));
            Tab t = new Tab("Home", none);
            t.setClosable(false);
            visibleTabs.add(t);
        }

        TabPane tabs = new TabPane(visibleTabs.toArray(new Tab[0]));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        BorderPane root = new BorderPane();
        root.setTop(topBar);
        root.setCenter(tabs);

        stage.setScene(new Scene(root, WIDTH, HEIGHT));
        stage.setMaximized(true);
    }

    private Tab sectionTab(String name, Tab... innerTabs) {
        Tab section = new Tab(name);
        section.setClosable(false);

        TabPane inner = new TabPane(innerTabs);
        inner.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        inner.setStyle("-fx-tab-min-width: 90;");

        inner.getSelectionModel().selectedItemProperty().addListener((obs, oldT, newT) -> {
            if (newT instanceof Refreshable r) r.refresh();
        });

        section.setOnSelectionChanged(e -> {
            if (section.isSelected()) {
                Tab current = inner.getSelectionModel().getSelectedItem();
                if (current instanceof Refreshable r) r.refresh();
            }
        });

        section.setContent(inner);
        return section;
    }

    private Tab standaloneTab(String name, Tab inner) {
        Tab section = new Tab(name);
        section.setClosable(false);
        section.setContent(inner.getContent());
        section.setOnSelectionChanged(e -> {
            if (section.isSelected() && inner instanceof Refreshable r) r.refresh();
        });
        return section;
    }

  
    private void openCustomerPortal() {
        Scene scene = new CustomerPortal(stage, this::backToLogin).buildScene();
        stage.setScene(scene);
        stage.setMaximized(true);
    }

    private void backToLogin() {
        Session.logout();
        stage.setScene(buildLoginScene());
        stage.setMaximized(true);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
