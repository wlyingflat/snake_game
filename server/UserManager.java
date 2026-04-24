package snake.server;

import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import snake.common.*;
import snake.util.*;

public class UserManager {
  private ConcurrentHashMap<String, User> users = new ConcurrentHashMap<>();
  private String filename;
  private final ReentrantReadWriteLock fileLock = new ReentrantReadWriteLock();

  public UserManager(String filename) {
    this.filename = filename;
    load();
    // 注册 JVM shutdown hook，确保退出时保存
    Runtime.getRuntime().addShutdownHook(new Thread(this::save));
  }

  public synchronized boolean register(String username, String password) {
    if (users.containsKey(username)) return false;
    User user = new User();
    user.name = username;
    user.salt = (int) (Math.random() * 0xFFFFFFFFL);
    user.passwordHash = hashPassword(password, user.salt);
    user.online = true;
    user.lastActive = System.currentTimeMillis() / 1000;
    users.put(username, user);
    // 注册成功后立即保存
    save();
    return true;
  }

  public synchronized boolean login(String username, String password) {
    User user = users.get(username);
    if (user == null) return false;
    if (user.online) return false;
    byte[] hash = hashPassword(password, user.salt);
    if (!Arrays.equals(hash, user.passwordHash)) return false;
    user.online = true;
    user.lastActive = System.currentTimeMillis() / 1000;
    // 登录状态变化可选保存，但为性能可暂不保存，退出时统一保存
    // 但为了可靠性，也可以调用 save() 或异步保存
    return true;
  }

  public synchronized boolean logout(String username) {
    User user = users.get(username);
    if (user == null) return false;
    user.online = false;
    user.lastActive = System.currentTimeMillis() / 1000;
    // 同上，退出时保存即可
    return true;
  }

  private byte[] hashPassword(String password, int salt) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      String salted = password + String.format("%08x", salt);
      return md.digest(salted.getBytes());
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }
  }

  private void load() {
    File file = new File(filename);
    if (!file.exists()) {
      // 文件不存在，创建空文件，避免后续操作异常
      try {
        file.createNewFile();
      } catch (IOException e) {
        Logger.error("Cannot create user file: " + e.getMessage());
      }
      return;
    }
    fileLock.writeLock().lock();
    try (BufferedReader br = new BufferedReader(new FileReader(file))) {
      String line;
      while ((line = br.readLine()) != null) {
        if (line.isEmpty() || line.startsWith("#")) continue;
        String[] parts = line.split(" ");
        if (parts.length < 5) continue;
        String username = parts[0];
        int salt = (int) Long.parseLong(parts[1], 16);
        String hashHex = parts[2];
        byte[] hash = hexToBytes(hashHex);
        boolean online = Integer.parseInt(parts[3]) != 0;
        long lastActive = Long.parseLong(parts[4]);
        User user = new User();
        user.name = username;
        user.salt = salt;
        user.passwordHash = hash;
        user.online = online;
        user.lastActive = lastActive;
        users.put(username, user);
      }
    } catch (IOException e) {
      Logger.error("Load users error: " + e.getMessage());
    } finally {
      fileLock.writeLock().unlock();
    }
  }

  /** 原子保存用户数据到文件（先写临时文件，再 rename 覆盖原文件） */
  public void save() {
    // 创建临时文件
    File tempFile = new File(filename + ".tmp");
    fileLock.writeLock().lock();
    try (BufferedWriter bw = new BufferedWriter(new FileWriter(tempFile))) {
      for (User user : users.values()) {
        bw.write(
            String.format(
                "%s %08x %s %d %d\n",
                user.name,
                user.salt,
                bytesToHex(user.passwordHash),
                user.online ? 1 : 0,
                user.lastActive));
      }
      bw.flush();
      // 原子替换
      File target = new File(filename);
      if (target.exists() && !target.delete()) {
        Logger.error("Failed to delete old user file");
        return;
      }
      if (!tempFile.renameTo(target)) {
        Logger.error("Failed to rename temp file to user file");
      }
    } catch (IOException e) {
      Logger.error("Save users error: " + e.getMessage());
    } finally {
      fileLock.writeLock().unlock();
    }
  }

  private static String bytesToHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder();
    for (byte b : bytes) sb.append(String.format("%02x", b));
    return sb.toString();
  }

  private static byte[] hexToBytes(String hex) {
    int len = hex.length();
    byte[] data = new byte[len / 2];
    for (int i = 0; i < len; i += 2) {
      data[i / 2] =
          (byte)
              ((Character.digit(hex.charAt(i), 16) << 4) + Character.digit(hex.charAt(i + 1), 16));
    }
    return data;
  }
}
