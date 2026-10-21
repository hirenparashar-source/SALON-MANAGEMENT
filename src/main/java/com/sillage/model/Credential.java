package com.sillage.model;

import java.time.LocalDateTime;

/**
 * Password material, stored separately from the account so it can never be returned by an API
 * endpoint by accident. Hashing is PBKDF2-HMAC-SHA256 with a per-user salt.
 */
public record Credential(
        String id,
        String userId,
        String algorithm,
        String salt,
        String hash,
        int iterations,
        int keyLengthBits,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String history) {
}
