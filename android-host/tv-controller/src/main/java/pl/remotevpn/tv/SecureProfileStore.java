package pl.remotevpn.tv;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SecureProfileStore {
    private static final String ALIAS = "pl.remotevpn.tv.profile.v1";
    private static final String FILE = "openvpn-profile.enc";
    private SecureProfileStore() { }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            gen.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build());
            gen.generateKey();
        }
        return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
    }

    static void save(Context ctx, String config) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] ciphertext = cipher.doFinal(config.getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        byte[] result = new byte[1 + iv.length + ciphertext.length];
        result[0] = (byte) iv.length;
        System.arraycopy(iv, 0, result, 1, iv.length);
        System.arraycopy(ciphertext, 0, result, 1 + iv.length, ciphertext.length);
        File file = new File(ctx.getNoBackupFilesDir(), FILE);
        Files.write(file.toPath(), result);
    }

    static String load(Context ctx) throws Exception {
        byte[] raw = Files.readAllBytes(new File(ctx.getNoBackupFilesDir(), FILE).toPath());
        int ivSize = raw[0] & 0xff;
        if (ivSize != 12 || raw.length <= ivSize + 1) throw new IllegalStateException("Invalid encrypted profile");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, raw, 1, ivSize));
        return new String(cipher.doFinal(raw, ivSize + 1, raw.length - 1 - ivSize), StandardCharsets.UTF_8);
    }

    static boolean exists(Context ctx) {
        return new File(ctx.getNoBackupFilesDir(), FILE).exists();
    }
}
