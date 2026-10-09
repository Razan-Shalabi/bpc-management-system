package bpc;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Password hashing with PBKDF2-HMAC-SHA256 and a random salt per password.
 * Stored format:  pbkdf2$<iterations>$<salt>$<hash>   (salt and hash are Base64)
 *
 * Plain-text passwords from older copies of the database are still accepted
 * by verify(); the login screen then replaces them with a hash.
 */
public final class Passwords {

    private static final String PREFIX = "pbkdf2$";
    private static final int ITERATIONS = 120_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Passwords() {}

    public static String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(password, salt, ITERATIONS);
        Base64.Encoder b64 = Base64.getEncoder().withoutPadding();
        return PREFIX + ITERATIONS + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(hash);
    }

    public static boolean verify(String password, String stored) {
        if (password == null || stored == null) return false;
        if (!isHashed(stored)) {
            return MessageDigest.isEqual(password.getBytes(StandardCharsets.UTF_8),
                                         stored.getBytes(StandardCharsets.UTF_8));
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4) return false;
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            return MessageDigest.isEqual(pbkdf2(password, salt, iterations), expected);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /** True when the stored value is a hash (false for a legacy plain-text password). */
    public static boolean isHashed(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("PBKDF2 is not available", ex);
        } finally {
            spec.clearPassword();
        }
    }
}
