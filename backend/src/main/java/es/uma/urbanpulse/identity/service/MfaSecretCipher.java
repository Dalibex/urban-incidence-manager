package es.uma.urbanpulse.identity.service;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Encrypts the TOTP secrets at rest (#57) with AES-256-GCM and {@code MFA_ENCRYPTION_KEY}: a copy
 * of the database alone does not give anyone the codes. The stored text is the Base64 of a random
 * 12-byte IV followed by the ciphertext and its 128-bit tag, so a changed value fails to decrypt
 * instead of giving another secret.
 *
 * <p>The id of the user is the associated data of each text: a text copied to the row of another
 * user does not decrypt there. The pending secret of a challenge and the secret of the account use
 * the same id, so a confirmed enrolment moves the text as it is.
 */
@Component
class MfaSecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;

    MfaSecretCipher(MfaProperties properties) {
        this.key = new SecretKeySpec(properties.encryptionKeyBytes(), "AES");
    }

    String encrypt(byte[] secret, UUID userId) {
        var iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        try {
            var cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(associatedData(userId));
            var ciphertext = cipher.doFinal(secret);
            return Base64.getEncoder()
                    .encodeToString(
                            ByteBuffer.allocate(iv.length + ciphertext.length)
                                    .put(iv)
                                    .put(ciphertext)
                                    .array());
        } catch (GeneralSecurityException e) {
            // Every Java runtime must provide AES/GCM/NoPadding (Cipher javadoc).
            throw new IllegalStateException("Cannot encrypt the second-factor secret", e);
        }
    }

    /**
     * @throws IllegalStateException if the text was not encrypted with this key for this user, or
     *     was changed
     */
    byte[] decrypt(String stored, UUID userId) {
        var bytes = Base64.getDecoder().decode(stored);
        try {
            var cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    new GCMParameterSpec(TAG_BITS, Arrays.copyOf(bytes, IV_BYTES)));
            cipher.updateAAD(associatedData(userId));
            return cipher.doFinal(bytes, IV_BYTES, bytes.length - IV_BYTES);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(
                    "Cannot decrypt a second-factor secret: was MFA_ENCRYPTION_KEY changed, or the"
                            + " text copied from another user?",
                    e);
        }
    }

    private static byte[] associatedData(UUID userId) {
        return ByteBuffer.allocate(Long.BYTES * 2)
                .putLong(userId.getMostSignificantBits())
                .putLong(userId.getLeastSignificantBits())
                .array();
    }
}
