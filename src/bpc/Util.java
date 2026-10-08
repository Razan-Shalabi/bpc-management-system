package bpc;

import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextFormatter;
import javafx.scene.image.Image;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.Optional;

public class Util {

    private static final DecimalFormat FMT = new DecimalFormat("#,##0.00");
    public static String money(BigDecimal v) {
        if (v == null) return "—";
        return FMT.format(v) + " ILS";
    }

    public static Label pill(String status) {
        Label l = new Label(status);
        String bg = switch (status == null ? "" : status) {
            case "Paid", "Delivered", "Received", "Completed" -> "#27ae60";
            case "PartiallyPaid", "Shipped", "InProgress" -> "#f39c12";
            case "Open", "Confirmed", "Planned" -> "#2980b9";
            case "Overdue" -> "#c0392b";
            case "Pending" -> "#95a5a6";
            case "Cancelled" -> "#6b4f4f";
            default -> "#7f8c8d";
        };
        l.setStyle("-fx-background-color: " + bg + "; -fx-text-fill: white; " + "-fx-padding: 2 10 2 10; -fx-background-radius: 12; -fx-font-size: 11px;");
        return l;
    }

    public static void info (String t, String m){ show(AlertType.INFORMATION, t, m); }
    public static void warn (String t, String m){ show(AlertType.WARNING, t, m); }
    public static void error(String t, String m){ show(AlertType.ERROR, t, m); }

    public static boolean confirm(String title, String msg) {
        Alert a = new Alert(AlertType.CONFIRMATION, msg, ButtonType.YES, ButtonType.NO);
        a.setTitle(title); a.setHeaderText(null);
        Optional<ButtonType> r = a.showAndWait();
        return r.isPresent() && r.get() == ButtonType.YES;
    }

    private static void show(AlertType t, String title, String msg) {
        Alert a = new Alert(t, msg);
        a.setTitle(title); a.setHeaderText(null);
        a.showAndWait();
    }

    /**
     * Makes an Integer spinner safe to type into: only digits are accepted, the
     * typed value is committed when the field loses focus or Enter is pressed,
     * and an empty or out-of-range entry snaps back to a valid value.
     */
    public static Spinner<Integer> numeric(Spinner<Integer> s) {
        s.setEditable(true);
        s.getEditor().setTextFormatter(new TextFormatter<String>(ch ->
            ch.getControlNewText().matches("\\d{0,9}") ? ch : null));
        Runnable commit = () -> {
            String t = s.getEditor().getText();
            var vf = (javafx.scene.control.SpinnerValueFactory.IntegerSpinnerValueFactory) s.getValueFactory();
            int v = (t == null || t.isEmpty()) ? vf.getMin() : Integer.parseInt(t);
            v = Math.max(vf.getMin(), Math.min(vf.getMax(), v));
            vf.setValue(v);
            s.getEditor().setText(String.valueOf(v));
        };
        s.getEditor().focusedProperty().addListener((o, was, now) -> { if (!now) commit.run(); });
        s.getEditor().setOnAction(e -> commit.run());
        return s;
    }

    /**
     * Loads a product picture: first from the classpath (/bpc/drugs), then from
     * src/bpc/drugs on disk so a picture uploaded in this session shows up
     * without rebuilding. Returns null if the picture cannot be found.
     */
    public static Image productImage(String filename) {
        if (filename == null || filename.isBlank()) return null;
        try {
            InputStream in = Util.class.getResourceAsStream("/bpc/drugs/" + filename);
            if (in == null) {
                File f = new File(System.getProperty("user.dir"), "src/bpc/drugs/" + filename);
                if (!f.isFile()) return null;
                in = new FileInputStream(f);
            }
            try (InputStream is = in) { return new Image(is); }
        } catch (Exception ex) {
            return null;
        }
    }
}
