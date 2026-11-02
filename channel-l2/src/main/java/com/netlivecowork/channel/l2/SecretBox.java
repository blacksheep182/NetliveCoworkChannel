package com.netlivecowork.channel.l2;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 凭据保护：AES-GCM，持久主密钥由库外独立供应；失败不退回明文、不生成替代密钥（INV-57）。
 * keyId 与保护版本一起保存，便于识别旧密钥。
 */
public final class SecretBox {

    private static final int TAG_BITS = 128;
    private static final int IV_BYTES = 12;
    private final SecretKey key;
    private final String keyId;
    private final SecureRandom random = new SecureRandom();

    public SecretBox(byte[] masterKey, String keyId) {
        if (masterKey == null || masterKey.length != 32) {
            throw new IllegalArgumentException("master key must be exactly 32 bytes (AES-256)");
        }
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalArgumentException("keyId is required");
        }
        this.key = new SecretKeySpec(masterKey, "AES");
        this.keyId = keyId;
    }

    public String keyId() {
        return keyId;
    }

    /** 密文格式：base64(iv || ciphertext || tag)。 */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(sealed, 0, out, iv.length, sealed.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot protect credential", e);
        }
    }

    public String decrypt(String ciphertext, String storedKeyId) {
        if (!keyId.equals(storedKeyId)) {
            throw new IllegalStateException("credential was protected with another keyId");
        }
        try {
            byte[] raw = Base64.getDecoder().decode(ciphertext);
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(raw, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(raw, IV_BYTES, raw.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot decrypt credential", e);
        }
    }

    /** 从环境变量读取主密钥（base64，32 字节）。未配置时拒绝启动凭据写入。 */
    public static SecretBox fromEnvironment(String envName, String keyId) {
        String raw = System.getenv(envName);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        byte[] decoded = Base64.getDecoder().decode(raw.trim());
        return new SecretBox(decoded, keyId);
    }
}
