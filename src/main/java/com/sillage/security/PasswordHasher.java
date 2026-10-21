package com.sillage.security;

import com.sillage.core.Util;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Password hashing (PBKDF2-HMAC-SHA256) and policy checking.
 *
 * <p>Salts are 16 random bytes per account, the derived key is 256 bits and the iteration count
 * is stored alongside the hash so the cost can be raised later without invalidating existing
 * credentials ({@link #needsRehash}).
 */
public final class PasswordHasher {

    public static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    public static final int DEFAULT_ITERATIONS = 210_000;
    public static final int KEY_LENGTH_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Passwords that must never be accepted, whatever their length. */
    private static final Set<String> DENYLIST = Set.of(
            "password", "password1", "password123", "passw0rd", "123456", "1234567", "12345678", "123456789",
            "1234567890", "qwerty", "qwerty123", "abc123", "letmein", "welcome", "welcome1", "admin", "admin123",
            "salon", "salon123", "sillage", "sillage123", "iloveyou", "sunshine", "princess", "football",
            "changeme", "test", "test123", "guest", "guest123", "qwertyuiop", "111111", "000000", "123123",
            "654321", "666666", "pass123", "master", "dragon", "monkey", "zaq12wsx", "p@ssw0rd", "password@123");

    private final int iterations;

    public PasswordHasher() {
        this(Util.parseInt(System.getenv("SILLAGE_PBKDF2_ITERATIONS"), DEFAULT_ITERATIONS));
    }

    public PasswordHasher(int iterations) {
        this.iterations = Math.max(10_000, iterations);
    }

    public int iterations() { return iterations; }

    public String newSalt() {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    public String hash(String password, String salt, int cost) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), Base64.getDecoder().decode(salt), cost, KEY_LENGTH_BITS);
            SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
            byte[] derived = factory.generateSecret(spec).getEncoded();
            spec.clearPassword();
            return Base64.getEncoder().encodeToString(derived);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | IllegalArgumentException e) {
            throw new IllegalStateException("password hashing failed", e);
        }
    }

    public String hash(String password, String salt) {
        return hash(password, salt, iterations);
    }

    /** Constant-time verification against a stored credential. */
    public boolean verify(String password, String salt, String expectedHash, int cost) {
        if (password == null || salt == null || expectedHash == null) return false;
        String actual;
        try {
            actual = hash(password, salt, cost > 0 ? cost : iterations);
        } catch (RuntimeException e) {
            return false;
        }
        return MessageDigest.isEqual(
                actual.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                expectedHash.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public boolean needsRehash(int storedIterations) {
        return storedIterations < iterations;
    }

    /** Result of a policy check. */
    public record Policy(int score, String label, List<String> problems, List<String> hints) {
        public boolean isAcceptable() { return problems.isEmpty(); }
    }

    /**
     * Password policy: length, character classes, denylist, repeated and sequential runs, and a
     * rough entropy estimate. Returns human-readable feedback for the sign-up meter.
     */
    public Policy evaluate(String password) {
        List<String> problems = new ArrayList<>();
        List<String> hints = new ArrayList<>();
        String value = password == null ? "" : password;

        if (value.length() < 10) problems.add("Use at least 10 characters.");
        if (value.length() > 256) problems.add("That is longer than we can accept (256 characters).");

        boolean lower = false, upper = false, digit = false, symbol = false;
        for (char c : value.toCharArray()) {
            if (Character.isLowerCase(c)) lower = true;
            else if (Character.isUpperCase(c)) upper = true;
            else if (Character.isDigit(c)) digit = true;
            else symbol = true;
        }
        int classes = (lower ? 1 : 0) + (upper ? 1 : 0) + (digit ? 1 : 0) + (symbol ? 1 : 0);
        if (classes < 3 && !value.isEmpty()) problems.add("Mix at least three of: lowercase, uppercase, number, symbol.");

        String normalised = value.toLowerCase(Locale.ROOT);
        if (DENYLIST.contains(normalised)) problems.add("That password is on the common-password list.");
        for (String banned : DENYLIST) {
            if (banned.length() >= 6 && normalised.contains(banned)) {
                problems.add("Remove the common fragment \u201c" + banned + "\u201d.");
                break;
            }
        }
        if (hasSequentialRun(value, 4)) hints.add("Avoid runs like abcd or 1234.");
        if (hasRepeatedRun(value, 4)) hints.add("Avoid repeated characters like aaaa.");

        int pool = (lower ? 26 : 0) + (upper ? 26 : 0) + (digit ? 10 : 0) + (symbol ? 33 : 0);
        double entropy = pool == 0 ? 0 : value.length() * (Math.log(pool) / Math.log(2));
        int score;
        if (!problems.isEmpty()) score = entropy < 28 ? 0 : 1;
        else if (entropy >= 70) score = 4;
        else if (entropy >= 52) score = 3;
        else score = 2;

        String label = switch (score) {
            case 0 -> "Too weak";
            case 1 -> "Weak";
            case 2 -> "Fair";
            case 3 -> "Strong";
            default -> "Excellent";
        };
        if (hints.isEmpty() && score >= 3) hints.add("A passphrase of unrelated words works even better.");
        return new Policy(score, label, problems, hints);
    }

    public Policy require(String password) {
        Policy policy = evaluate(password);
        if (!policy.isAcceptable()) {
            throw new SecurityPolicyException("Password rejected", policy.problems());
        }
        return policy;
    }

    private static boolean hasSequentialRun(String value, int length) {
        int run = 1;
        for (int i = 1; i < value.length(); i++) {
            if (value.charAt(i) - value.charAt(i - 1) == 1 || value.charAt(i - 1) - value.charAt(i) == 1) {
                if (++run >= length) return true;
            } else run = 1;
        }
        return false;
    }

    private static boolean hasRepeatedRun(String value, int length) {
        int run = 1;
        for (int i = 1; i < value.length(); i++) {
            if (value.charAt(i) == value.charAt(i - 1)) {
                if (++run >= length) return true;
            } else run = 1;
        }
        return false;
    }

    /** Raised when a password (or another security invariant) fails policy. */
    public static final class SecurityPolicyException extends RuntimeException {
        private final List<String> details;

        public SecurityPolicyException(String message, List<String> details) {
            super(message);
            this.details = details;
        }

        public List<String> details() { return details; }
    }
}
