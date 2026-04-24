package snake.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;
import snake.common.*;
import snake.util.Logger;

public class Room implements Runnable {
  private final int roomId;
  private final BlockingQueue<Message> mailbox;
  private final GameState state;
  private volatile boolean running = true;
  private final BiConsumer<String, String> messageSender;
  private final BiConsumer<Integer, Room> onDestroy;
  private final Runnable onStatusChange;
  private long lastActiveTime;
  private final ReentrantReadWriteLock stateLock = new ReentrantReadWriteLock();
  private final ObjectMapper mapper = new ObjectMapper();

  public Room(
      int roomId,
      BiConsumer<String, String> messageSender,
      BiConsumer<Integer, Room> onDestroy,
      Runnable onStatusChange) {
    this.roomId = roomId;
    this.mailbox = new ArrayBlockingQueue<>(1024);
    this.state = new GameState(roomId);
    this.messageSender = messageSender;
    this.onDestroy = onDestroy;
    this.onStatusChange = onStatusChange;
  }

  public void post(Message msg) {
    if (msg instanceof InputMsg) {
      InputMsg input = (InputMsg) msg;
      mailbox.removeIf(
          m -> m instanceof InputMsg && ((InputMsg) m).username().equals(input.username()));
      boolean offered = mailbox.offer(msg);
      if (!offered) {
        Logger.warn("Room " + roomId + " mailbox full, dropping INPUT from " + input.username());
      }
    } else {
      boolean offered = mailbox.offer(msg);
      if (!offered) {
        Logger.warn("Room " + roomId + " mailbox full, dropping message: " + msg.type());
      }
    }
  }

  public GameStateData getSnapshot(String username) {
    if (!running) return null;
    stateLock.readLock().lock();
    try {
      return state.snapshot(username);
    } finally {
      stateLock.readLock().unlock();
    }
  }

  @Override
  public void run() {
    Logger.info("[Room " + roomId + "] Thread started");
    lastActiveTime = System.currentTimeMillis();
    Logger.info("Room " + roomId + " started.");
    while (running) {
      long start = System.currentTimeMillis();

      try {
        Message msg;
        while ((msg = mailbox.poll()) != null) {
          handle(msg);
        }

        // 记录玩家存活状态（更新前）
        Map<String, Boolean> wasAlive = new HashMap<>();
        stateLock.readLock().lock();
        try {
          for (GameState.Player p : state.getPlayers()) {
            wasAlive.put(p.username, !p.isDead);
          }
        } finally {
          stateLock.readLock().unlock();
        }

        // 更新游戏逻辑
        stateLock.writeLock().lock();
        try {
          state.update();
        } finally {
          stateLock.writeLock().unlock();
        }

        // 收集死亡玩家
        List<String> diedPlayers = new ArrayList<>();
        stateLock.readLock().lock();
        try {
          for (GameState.Player p : state.getPlayers()) {
            Boolean aliveBefore = wasAlive.get(p.username);
            if (aliveBefore != null && aliveBefore && p.isDead) {
              diedPlayers.add(p.username);
            }
          }
        } finally {
          stateLock.readLock().unlock();
        }

        // 移除死亡玩家并发送通知
        if (!diedPlayers.isEmpty()) {
          stateLock.writeLock().lock();
          try {
            for (String username : diedPlayers) {
              state.removePlayer(username);
              messageSender.accept(username, "{\"cmd\":\"YOU_DIED\"}");
              Logger.debug("Player " + username + " died, removed from room");
            }
          } finally {
            stateLock.writeLock().unlock();
          }
          if (onStatusChange != null) onStatusChange.run();
        }

        // 广播快照
        broadcastSnapshot();

        if (!state.isEmpty()) {
          lastActiveTime = System.currentTimeMillis();
        }

        // 空闲超时检测
        if (state.isEmpty()
            && System.currentTimeMillis() - lastActiveTime > Config.ROOM_IDLE_TIMEOUT * 1000L) {
          Logger.info("Room " + roomId + " idle timeout, stopping.");
          running = false;
        }
      } catch (Exception e) {
        Logger.error("Room " + roomId + " crashed: " + e.getMessage());
        e.printStackTrace();
        running = false;
      }

      long elapsed = System.currentTimeMillis() - start;
      long sleep = Config.TICK_INTERVAL_MS - elapsed;
      if (sleep > 0) {
        try {
          Thread.sleep(sleep);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }
    cleanup();
  }

  private void handle(Message msg) {
    stateLock.writeLock().lock();
    try {
      switch (msg.type()) {
        case "JOIN":
          JoinRoomMsg join = (JoinRoomMsg) msg;
          if (state.addPlayer(join.username())) {
            ObjectNode resp = mapper.createObjectNode();
            resp.put("cmd", "JOIN_OK");
            resp.put("roomId", roomId);
            messageSender.accept(join.username(), resp.toString());
            Logger.info("Player " + join.username() + " joined room " + roomId);
            if (onStatusChange != null) onStatusChange.run();
          } else {
            ObjectNode resp = mapper.createObjectNode();
            resp.put("cmd", "JOIN_FAIL");
            resp.put("message", "Room is full or join failed");
            messageSender.accept(join.username(), resp.toString());
          }
          break;
        case "INPUT":
          InputMsg input = (InputMsg) msg;
          state.updateDirection(input.username(), input.direction());
          break;
        case "LEAVE":
          LeaveRoomMsg leave = (LeaveRoomMsg) msg;
          state.removePlayer(leave.username());
          Logger.info("Player " + leave.username() + " left room " + roomId);
          if (onStatusChange != null) onStatusChange.run();
          break;
      }
    } finally {
      stateLock.writeLock().unlock();
    }
  }

  private void broadcastSnapshot() {
    GameStateData snapshot;
    stateLock.readLock().lock();
    try {
      snapshot = state.snapshot(null);
    } finally {
      stateLock.readLock().unlock();
    }
    if (snapshot == null) return;

    String json = Serializer.serializeGameState(snapshot);
    if (json == null) return;

    List<GameState.Player> players;
    stateLock.readLock().lock();
    try {
      players = state.getPlayers();
    } finally {
      stateLock.readLock().unlock();
    }

    for (GameState.Player p : players) {
      if (p.isDead) continue;
      messageSender.accept(p.username, json);
    }
  }

  private void cleanup() {
    running = false;
    Logger.info("Room " + roomId + " destroyed.");
    if (onDestroy != null) {
      onDestroy.accept(roomId, this);
    }
  }
}
