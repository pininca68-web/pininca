package tw.family.locator;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Tokens never enter plain-text preferences, logs, bundles, or backups. */
final class SessionStore {
    private static final String ALIAS = "family_locator_session_v1";
    private final SharedPreferences prefs;

    SessionStore(Context context) {
        prefs = context.getSharedPreferences("family_locator_secrets", Context.MODE_PRIVATE);
    }

    synchronized String read(String name) throws GeneralSecurityException {
        String encoded = prefs.getString(name, null);
        if (encoded == null) return null;
        try {
            String[] parts = encoded.split(":", 2);
            if (parts.length != 2) throw new GeneralSecurityException("Invalid encrypted session");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128,
                    Base64.decode(parts[0], Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw new GeneralSecurityException("Invalid encrypted session", exception);
        }
    }

    synchronized void write(String name, String value) throws GeneralSecurityException {
        if (value == null) {
            if (!prefs.edit().remove(name).commit()) throw new GeneralSecurityException("Storage unavailable");
            return;
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        String encoded = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                + Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        if (!prefs.edit().putString(name, encoded).commit())
            throw new GeneralSecurityException("Storage unavailable");
    }

    private static SecretKey key() throws GeneralSecurityException {
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build());
            return generator.generateKey();
        } catch (java.io.IOException exception) {
            throw new GeneralSecurityException("Android Keystore unavailable", exception);
        }
    }
}
