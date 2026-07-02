package com.company.issueops.service;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.stereotype.Service;

@Service
public class PasswordHashService {

  private static final String PREFIX = "pbkdf2";
  private static final int ITERATIONS = 120_000;
  private static final int SALT_BYTES = 16;
  private static final int KEY_BITS = 256;

  private final SecureRandom secureRandom = new SecureRandom();

  public String hash(String rawPassword) {
    if (rawPassword == null || rawPassword.isBlank()) {
      throw new IllegalArgumentException("密码不能为空");
    }
    byte[] salt = new byte[SALT_BYTES];
    secureRandom.nextBytes(salt);
    byte[] hash = pbkdf2(rawPassword.toCharArray(), salt, ITERATIONS);
    return String.join(
      "$",
      PREFIX,
      String.valueOf(ITERATIONS),
      Base64.getEncoder().encodeToString(salt),
      Base64.getEncoder().encodeToString(hash)
    );
  }

  public boolean matches(String rawPassword, String encoded) {
    if (rawPassword == null || encoded == null) return false;
    String[] parts = encoded.split("\\$", 4);
    if (parts.length != 4 || !PREFIX.equals(parts[0])) return false;
    try {
      int iterations = Integer.parseInt(parts[1]);
      byte[] salt = Base64.getDecoder().decode(parts[2]);
      byte[] expected = Base64.getDecoder().decode(parts[3]);
      byte[] actual = pbkdf2(rawPassword.toCharArray(), salt, iterations);
      return MessageDigest.isEqual(expected, actual);
    } catch (RuntimeException e) {
      return false;
    }
  }

  private byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
    try {
      KeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
      return SecretKeyFactory
        .getInstance("PBKDF2WithHmacSHA256")
        .generateSecret(spec)
        .getEncoded();
    } catch (Exception e) {
      throw new IllegalStateException("密码哈希失败", e);
    } finally {
      for (int i = 0; i < password.length; i++) password[i] = '\0';
    }
  }

  public boolean isHashed(String value) {
    return value != null && value.startsWith(PREFIX + "$");
  }
}
