package snake.common;

public class User {
  public String name;
  public int salt; // 32-bit salt
  public byte[] passwordHash = new byte[32]; // SHA-256
  public boolean online;
  public long lastActive; // timestamp seconds
}
