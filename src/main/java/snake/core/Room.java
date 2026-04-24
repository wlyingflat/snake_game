package snake.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
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

  // 缓存的最新快照（volatile 保证可见性，无需锁）
  private volatile GameStateData cachedSnapshot;

  public Room(
      int roomId,
      BiConsumer<String, String> messageSender,
      BiConsumer<Integer, Room> onDestroy,
      Runnable onStatusChange) {
    this.roomId = roomId;
    this.mailbox = new ArrayBlockingQueue<>(Config.ROOM_QUEUE_CAPACITY);
    this.state = new GameState(roomId);
    this.messageSender = messageSender;
    this.onDestroy = onDestroy;
    this.onStatusChange = onStatusChange;

    // 初始化缓存快照（避免第一次查询返回 null）
    stateLock.readLock().lock();
    try {
      this.cachedSnapshot = state.snapshot(null);
    } finally {
      stateLock.readLock().unlock();
    }
  }

  public boolean isRunning() {
    return running;
  }

  public void post(Message msg) {
    if (!running) return; // 房间已停止，拒绝新消息

    if (msg instanceof InputMsg) {
      InputMsg input = (InputMsg) msg;
      mailbox.removeIf(
          m -> m instanceof InputMsg && ((InputMsg) m).username().equals(input.username()));
    }

    boolean offered = mailbox.offer(msg);
    if (!offered) {
      String userInfo = "";
      if (msg instanceof InputMsg) {
        userInfo = " from user " + ((InputMsg) msg).username();
      } else if (msg instanceof JoinRoomMsg) {
        userInfo = " from user " + ((JoinRoomMsg) msg).username();
      } else if (msg instanceof LeaveRoomMsg) {
        userInfo = " from user " + ((LeaveRoomMsg) msg).username();
      }
      Logger.warn(
          "Room " + roomId + " mailbox full, dropping message type: " + msg.type() + userInfo);
    }
  }

  /** 直接返回缓存快照，无锁、无阻塞、无 RPC 延迟。 忽略 username 参数（已不需要个性化字段）。 */
  public GameStateData getSnapshot(String username) {
    return cachedSnapshot;
  }

  @Override
  public void run() {
    lastActiveTime = System.currentTimeMillis();
    Logger.info("Room " + roomId + " started.");

    while (running) {
      long now = System.currentTimeMillis();
      long nextTick = now + Config.TICK_INTERVAL_MS;

      // 1. 阻塞等待第一条消息，超时时间为距离下一次 tick 的时间
      long waitMs = Math.max(1, nextTick - now);
      Message first = null;
      try {
        first = mailbox.poll(waitMs, TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }

      // 2. 处理第一批消息（如果有）
      if (first != null) {
        handle(first);
        // 批量取完当前所有积压的消息（非阻塞）
        List<Message> batch = new ArrayList<>();
        mailbox.drainTo(batch);
        for (Message msg : batch) {
          handle(msg);
        }
      }

      // 3. 执行游戏 tick（包含状态更新和缓存刷新）
      doGameTick();

      // 4. 等待到下一次 tick 的剩余时间（保持固定 tick 间隔）
      long afterTick = System.currentTimeMillis();
      long sleep = nextTick - afterTick;
      if (sleep > 0) {
        try {
          Thread.sleep(sleep);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }
    cleanup();
  }

  private void doGameTick() {
    // 记录更新前的存活状态
    Map<String, Boolean> wasAlive = new HashMap<>();
    stateLock.readLock().lock();
    try {
      for (GameState.Player p : state.getPlayers()) {
        wasAlive.put(p.username, !p.isDead);
      }
    } finally {
      stateLock.readLock().unlock();
    }

    // 更新游戏逻辑（写锁）
    stateLock.writeLock().lock();
    try {
      state.update();
    } finally {
      stateLock.writeLock().unlock();
    }

    // 检测死亡玩家
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

    // 移除死亡玩家并发送通知（写锁）
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

    // ★★★ 关键优化：刷新缓存快照（读锁保护）★★★
    stateLock.readLock().lock();
    try {
      cachedSnapshot = state.snapshot(null);
    } finally {
      stateLock.readLock().unlock();
    }

    // 广播快照（复用缓存）
    broadcastSnapshot();

    // 更新活跃时间
    if (!state.isEmpty()) {
      lastActiveTime = System.currentTimeMillis();
    }

    // 空闲超时检测
    if (state.isEmpty()
        && System.currentTimeMillis() - lastActiveTime > Config.ROOM_IDLE_TIMEOUT * 1000L) {
      Logger.info("Room " + roomId + " idle timeout, stopping.");
      running = false;
    }
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

  /** 使用缓存快照进行广播，避免重复生成快照和序列化。 */
  private void broadcastSnapshot() {
    if (cachedSnapshot == null) return;

    String json = Serializer.serializeGameState(cachedSnapshot);
    if (json == null) return;

    // 从缓存快照中获取存活玩家列表（快照中的 isDead 为 false 的玩家）
    for (GameStateData.PlayerInfo player : cachedSnapshot.players) {
      if (player == null) continue;
      if (player.isDead) continue;
      messageSender.accept(player.name, json);
    }
  }

  private void cleanup() {
    running = false;
    // 清空未处理的消息，避免内存泄漏
    mailbox.clear();
    Logger.info("Room " + roomId + " destroyed.");
    if (onDestroy != null) {
      onDestroy.accept(roomId, this);
    }
  }
}
