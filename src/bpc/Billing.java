package bpc;

import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;

/**
 * Invoice payment rules shared by the staff Invoices tab, the customer
 * My Invoices tab and the Supplier Invoices tab.
 *
 * Status rules:  Paid          - fully paid
 *                Overdue       - money still owed and the due date has passed
 *                PartiallyPaid - something paid, not yet due
 *                Open          - nothing paid, not yet due
 */
public final class Billing {

    /** Which invoice table a payment belongs to. */
    public enum Kind {
        CUSTOMER("Invoice", "InvoiceID", "CustomerPayment", "InvoiceID"),
        SUPPLIER("SupplierInvoice", "SuppInvoiceID", "SupplierPayment", "SuppInvoiceID");

        final String invoiceTable, invoiceKey, paymentTable, paymentKey;
        Kind(String invoiceTable, String invoiceKey, String paymentTable, String paymentKey) {
            this.invoiceTable = invoiceTable; this.invoiceKey = invoiceKey;
            this.paymentTable = paymentTable; this.paymentKey = paymentKey;
        }
    }

    private Billing() {}

    public static String status(BigDecimal total, BigDecimal paid, LocalDate due) {
        if (paid.compareTo(total) >= 0) return "Paid";
        if (due.isBefore(LocalDate.now())) return "Overdue";
        return paid.signum() > 0 ? "PartiallyPaid" : "Open";
    }

    /** Flags unpaid customer and supplier invoices whose due date has passed. */
    public static void markOverdue() {
        for (Kind k : Kind.values()) {
            try (PreparedStatement ps = DB.get().prepareStatement(
                    "UPDATE " + k.invoiceTable + " i SET i.Status = 'Overdue' " +
                    "WHERE i.Status IN ('Open','PartiallyPaid') AND i.DueDate < CURRENT_DATE " +
                    "  AND i.TotalAmount > COALESCE((SELECT SUM(p.Amount) FROM " + k.paymentTable +
                    "      p WHERE p." + k.paymentKey + " = i." + k.invoiceKey + "), 0)")) {
                ps.executeUpdate();
            } catch (SQLException ex) {
                System.out.println("Could not refresh overdue invoices: " + ex.getMessage());
            }
        }
    }

    /**
     * Validates a payment amount typed by the user against the balance shown.
     * Returns an error message, or null if the amount is acceptable.
     */
    public static String checkAmount(String text, BigDecimal balance) {
        BigDecimal amt;
        try { amt = Validate.money("Amount", text, 10); }
        catch (IllegalArgumentException ex) { return ex.getMessage(); }
        if (amt.signum() <= 0) return "Amount must be greater than 0.";
        if (amt.compareTo(balance) > 0) {
            return "Payment exceeds the remaining balance of " + Util.money(balance) + ".";
        }
        return null;
    }

    /** Returns an error message for an unacceptable payment date, or null. */
    public static String checkDate(LocalDate date, LocalDate issueDate) {
        if (date == null) return "Payment date is required.";
        if (date.isAfter(LocalDate.now())) return "Payment date cannot be in the future.";
        if (issueDate != null && date.isBefore(issueDate)) {
            return "Payment date cannot be before the invoice issue date (" + issueDate + ").";
        }
        return null;
    }

    /**
     * Records one payment and recalculates the invoice status in a single
     * transaction. The balance is re-checked inside the transaction (with the
     * invoice row locked) so two people cannot overpay the same invoice.
     * Returns the new status.
     */
    public static String recordPayment(Kind k, int invoiceId, BigDecimal amount,
                                       LocalDate date, String method) throws SQLException {
        Connection conn = DB.get();
        try {
            conn.setAutoCommit(false);
            BigDecimal total; LocalDate due;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT TotalAmount, DueDate FROM " + k.invoiceTable +
                    " WHERE " + k.invoiceKey + "=? FOR UPDATE")) {
                ps.setInt(1, invoiceId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new SQLException("Invoice #" + invoiceId + " no longer exists.");
                    total = rs.getBigDecimal(1);
                    due = rs.getDate(2).toLocalDate();
                }
            }
            BigDecimal paid = paidSoFar(conn, k, invoiceId);
            if (amount.compareTo(total.subtract(paid)) > 0) {
                throw new SQLException("Payment exceeds the remaining balance of "
                    + Util.money(total.subtract(paid)) + ". Refresh and try again.");
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO " + k.paymentTable + " (" + k.paymentKey +
                    ", Amount, PaymentDate, Method) VALUES (?,?,?,?)")) {
                ps.setInt(1, invoiceId);
                ps.setBigDecimal(2, amount);
                ps.setDate(3, Date.valueOf(date));
                ps.setString(4, method);
                ps.executeUpdate();
            }
            String newStatus = status(total, paid.add(amount), due);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE " + k.invoiceTable + " SET Status=? WHERE " + k.invoiceKey + "=?")) {
                ps.setString(1, newStatus);
                ps.setInt(2, invoiceId);
                ps.executeUpdate();
            }
            conn.commit();
            return newStatus;
        } catch (SQLException ex) {
            try { conn.rollback(); } catch (SQLException ignored) {}
            throw ex;
        } finally {
            try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }

    private static BigDecimal paidSoFar(Connection conn, Kind k, int invoiceId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COALESCE(SUM(Amount),0) FROM " + k.paymentTable + " WHERE " + k.paymentKey + "=?")) {
            ps.setInt(1, invoiceId);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getBigDecimal(1); }
        }
    }
}
