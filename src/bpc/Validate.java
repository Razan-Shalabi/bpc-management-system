package bpc;

import java.math.BigDecimal;

/**
 * Shared input checks used by the forms. Each check returns an error message
 * ready to show to the user, or null when the value is acceptable.
 */
public final class Validate {

    private Validate() {}

    /** Trimmed text, never null. */
    public static String text(String s) {
        return s == null ? "" : s.trim();
    }

    /** Trimmed text, or null when empty (for optional columns). */
    public static String blankToNull(String s) {
        String t = text(s);
        return t.isEmpty() ? null : t;
    }

    public static String required(String label, String value) {
        return text(value).isEmpty() ? label + " is required." : null;
    }

    /** Matches the VARCHAR size of the column so the database never rejects it. */
    public static String maxLength(String label, String value, int max) {
        return value != null && value.trim().length() > max
            ? label + " must be at most " + max + " characters." : null;
    }

    public static String username(String value) {
        String v = text(value);
        if (v.length() < 3) return "Username must be at least 3 characters.";
        if (v.length() > 50) return "Username must be at most 50 characters.";
        if (!v.matches("[A-Za-z0-9._-]+")) {
            return "Username may contain only letters, digits, '.', '_' and '-'.";
        }
        return null;
    }

    public static String password(String value) {
        if (value == null || value.length() < 4) return "Password must be at least 4 characters.";
        if (value.length() > 60) return "Password must be at most 60 characters.";
        if (!value.equals(value.trim())) return "Password cannot start or end with a space.";
        return null;
    }

    /** Optional email: empty is fine, otherwise it must look like name@host.tld. */
    public static String email(String value) {
        String v = text(value);
        if (v.isEmpty()) return null;
        if (v.length() > 120) return "Email must be at most 120 characters.";
        return v.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
            ? null : "Email must look like name@example.com";
    }

    /** Optional phone: digits, '+', spaces, '-', '()' and at least 7 digits. */
    public static String phone(String value) {
        String v = text(value);
        if (v.isEmpty()) return null;
        if (v.length() > 30) return "Phone must be at most 30 characters.";
        if (!v.matches("^[+0-9 ()\\-]+$")) {
            return "Phone may contain only digits, '+', spaces, '-' and '()'.";
        }
        return v.replaceAll("\\D", "").length() >= 7 ? null : "Phone must contain at least 7 digits.";
    }

    /**
     * Parses a money amount with at most 2 decimals that fits a DECIMAL column
     * with the given number of integer digits (e.g. 8 for DECIMAL(10,2)).
     * Throws IllegalArgumentException with a user-facing message otherwise.
     */
    public static BigDecimal money(String label, String text, int integerDigits) {
        BigDecimal v;
        try { v = new BigDecimal(text(text)); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException(label + " must be a number."); }
        if (v.scale() > 2 && v.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException(label + " can have at most 2 decimal places.");
        }
        if (v.abs().compareTo(BigDecimal.TEN.pow(integerDigits)) >= 0) {
            throw new IllegalArgumentException(label + " is too large.");
        }
        return v.setScale(2, java.math.RoundingMode.UNNECESSARY);
    }

    /** Parses a whole number in [min, max]; throws IllegalArgumentException with a message otherwise. */
    public static int integer(String label, String text, int min, int max) {
        int v;
        try { v = Integer.parseInt(text(text)); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException(label + " must be a whole number."); }
        if (v < min || v > max) {
            throw new IllegalArgumentException(label + " must be between " + min + " and " + max + ".");
        }
        return v;
    }

    /** Returns the first non-null message, or null when every check passed. */
    public static String first(String... messages) {
        for (String m : messages) if (m != null) return m;
        return null;
    }
}
