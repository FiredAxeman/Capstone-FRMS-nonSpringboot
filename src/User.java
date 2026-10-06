import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/**
 * Account identity with salted PBKDF2 credentials and explicit roles.
 */
public class User {
    private static final int ITERATIONS = 210_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    protected final String username;
    protected final String passwordHash;
    protected final String role;

    protected User(String username, String passwordHash, String role) {
        if (username == null || !username.trim().matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("Username must contain 1–64 letters, numbers, dots, underscores, or hyphens.");
        }
        this.username = username.trim().toLowerCase(Locale.ROOT);
        this.passwordHash = passwordHash == null ? "" : passwordHash;
        this.role = Role.from(role == null || role.isBlank() ? "Pilot" : role).label();
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getRole() {
        return role;
    }

    public Role role() {
        return Role.from(role);
    }

    public boolean authenticate(String password) {
        if (password == null) {
            return false;
        }
        char[] characters = password.toCharArray();
        try {
            return authenticate(characters);
        } finally {
            Arrays.fill(characters, '\0');
        }
    }

    public boolean authenticate(char[] password) {
        if (password == null || password.length == 0 || passwordHash.isEmpty()) {
            return false;
        }
        try {
            if (passwordHash.startsWith("pbkdf2$")) {
                String[] fields = passwordHash.split("\\$");
                if (fields.length != 4) {
                    return false;
                }
                int iterations = Integer.parseInt(fields[1]);
                if (iterations < 10_000 || iterations > 1_000_000) {
                    return false;
                }
                byte[] salt = Base64.getDecoder().decode(fields[2]);
                byte[] expected = Base64.getDecoder().decode(fields[3]);
                return MessageDigest.isEqual(expected, derive(password, salt, iterations));
            }
            // Original SHA-256 credentials are upgraded after a successful sign-in.
            if (passwordHash.matches("[0-9a-f]{64}")) {
                byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(new String(password).getBytes(StandardCharsets.UTF_8));
                return MessageDigest.isEqual(HexFormat.of().parseHex(passwordHash), digest);
            }
            return false;
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            return false;
        }
    }

    public static String hashPassword(String password) {
        Objects.requireNonNull(password, "password");
        char[] characters = password.toCharArray();
        try {
            return hashPassword(characters);
        } finally {
            Arrays.fill(characters, '\0');
        }
    }

    public static String hashPassword(char[] password) {
        Objects.requireNonNull(password, "password");
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        try {
            return "pbkdf2$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt)
                + "$" + Base64.getEncoder().encodeToString(derive(password, salt, ITERATIONS));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Password protection is unavailable.", exception);
        }
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations)
        throws GeneralSecurityException {
        PBEKeySpec specification = new PBEKeySpec(password, salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(specification).getEncoded();
        } finally {
            specification.clearPassword();
        }
    }

    public static void validateNewPassword(char[] password) {
        if (password == null || password.length < 10 || password.length > 128) {
            throw new IllegalArgumentException("Use a password with 10–128 characters.");
        }
    }

    public boolean authorize(String requiredRole) {
        return role() == Role.ADMINISTRATOR || role().equals(Role.from(requiredRole));
    }
}
