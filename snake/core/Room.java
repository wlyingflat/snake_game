// snake/core/Room.java
package snake.core;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;
import snake.common.Config;
import snake.common.GameStateData;
import snake.common.Protocol;
import snake.common.Serializer;
import snake.util.Logger;

public class Room implements Runnable {
  private final int roomId;
  private final ConcurrentLinkedQueue<Message> mailbox = new ConcurrentLinkedQueue<>();
  private final GameState state;
  private volatile boolean running = true;
  private final BiConsumer<String, String> messageSender;
  private final BiConsumer<Integer, Room> onDestroy;
  private final Runnable onStatusChange;
  private long lastActiveTime;

  public Room(
      int roomId,
      BiConsumer<String, String> messageSender,
      BiConsumer<Integer, Room> onDestroy,
      Runnable onStatusChange) {
    this.roomId = roomId;
    this.state = new GameState(roomId);
    this.messageSender = messageSender;
    this.onDestroy = onDestroy;
    this.onStatusChange = onStatusChange;
  }

  public void post(Message msg) {
    mailbox.offer(msg);
  }

  public GameStateData getSnapshot(String username) {
    return state.snapshot(username);
  }

  @Override
  public void run() {
    lastActiveTime = System.currentTimeMillis();
    Logger.info("Room " + roomId + " started.");
    while (running) {
      long start = System.currentTimeMillis();

      try {
        // 处理消息队列
        Message msg;
        while ((msg = mailbox.poll()) != null) {
          handle(msg);
        }

        // 记录当前所有玩家的存活状态，用于死亡检测
        Map<String, Boolean> wasAlive = new HashMap<>();
        for (GameState.Player p : state.getPlayers()) {
          wasAlive.put(p.username, !p.isDead);
        }

        // 更新游戏逻辑
        state.update();

        // 检测死亡玩家并发送 YOU DIED 消息
        for (GameState.Player p : state.getPlayers()) {
          Boolean aliveBefore = wasAlive.get(p.username);
          if (aliveBefore != null && aliveBefore && p.isDead) {
            messageSender.accept(p.username, Protocol.YOU_DIED);
            Logger.debug("Player " + p.username + " died, sent YOU DIED");
          }
        }

        // 广播快照
        broadcastSnapshot();

        // 更新活跃时间
        if (!state.isEmpty()) {
          lastActiveTime = System.currentTimeMillis();
        }

        // 空闲超时检查
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
    switch (msg.type()) {
      case "JOIN":
        JoinRoomMsg join = (JoinRoomMsg) msg;
        if (state.addPlayer(join.username())) {
          messageSender.accept(join.username(), "JOIN_OK " + roomId);
          Logger.info("Player " + join.username() + " joined room " + roomId);
          if (onStatusChange != null) {
            onStatusChange.run();
          }
        } else {
          messageSender.accept(join.username(), "JOIN_FAIL");
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
        if (onStatusChange != null) {
          onStatusChange.run();
        }
        break;
    }
  }

  private void broadcastSnapshot() {
    for (GameState.Player p : state.getPlayers()) {
      if (p.isDead) continue; // 死亡玩家不再接收 STATE 消息
      GameStateData data = state.snapshot(p.username);
      String json = Serializer.serializeGameState(data);
      if (json != null) {
        messageSender.accept(p.username, json);
      }
    }
  }

  private void cleanup() {
    Logger.info("Room " + roomId + " destroyed.");
    if (onDestroy != null) {
      onDestroy.accept(roomId, this);
    }
  }
}
