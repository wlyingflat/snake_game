package snake.auth;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 密码哈希工具类，被认证服务使用。 */
public final class PasswordUtils {
  private PasswordUtils() {}

  public static byte[] hashPassword(String password, int salt) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      String salted = password + String.format("%08x", salt);
      return md.digest(salted.getBytes());
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException("SHA-256 algorithm not available", e);
    }
  }
}
