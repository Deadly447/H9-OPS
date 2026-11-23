package io.github.deadly447.h9ops;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Seals the radio PIN with a key held in
 * the Android Keystore, which never leaves the phone's secure hardware where
 * the phone has it. A copy of the app's files alone is then not enough to
 * read them.
 */
final class KeyVault {
    private static final String ALIAS = "h9ops-vault";
    private static final String STORE = "AndroidKeyStore";

    private KeyVault() {}

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance(STORE);
        ks.load(null);
        KeyStore.Entry e = ks.getEntry(ALIAS, null);
        if (e instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) e).getSecretKey();
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE);
        g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return g.generateKey();
    }

    /** Returns base64(iv | ciphertext), or null if the keystore failed. */
    static String seal(byte[] secret) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(secret);
            byte[] out = new byte[1 + iv.length + ct.length];
            out[0] = (byte) iv.length;
            System.arraycopy(iv, 0, out, 1, iv.length);
            System.arraycopy(ct, 0, out, 1 + iv.length, ct.length);
            return Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    /** Returns the secret, or null if missing or it can't be opened (for example after a restore to another phone). */
    static byte[] open(String sealed) {
        if (sealed == null) return null;
        try {
            byte[] in = Base64.decode(sealed, Base64.NO_WRAP);
            int ivLen = in[0];
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, in, 1, ivLen));
            return c.doFinal(in, 1 + ivLen, in.length - 1 - ivLen);
        } catch (Exception e) {
            return null;
        }
    }
}
