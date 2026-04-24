package snake.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import snake.common.*;
import snake.util.ILogger;
import snake.util.Logger;

public class Room implements Runnable {
  private final int roomId;
  private final BlockingQueue<Message> mailbox;
  private final GameState state;
  private volatile boolean running = true;
  private final IGameClientNotifier notifier;
  private final IRoomDestroyCallback destroyCallback;
  private final Runnable onStatusChange;
  private long lastActiveTime;
  private final ReentrantReadWriteLock stateLock = new ReentrantReadWriteLock();
  private final ObjectMapper mapper = new ObjectMapper();
  private volatile GameStateData cachedSnapshot;
  private final ILogger logger = Logger.getInstance();

  public Room(
      int roomId,
      IGameClientNotifier notifier,
      IRoomDestroyCallback destroyCallback,
      Runnable onStatusChange) {
    this.roomId = roomId;
    this.mailbox = new ArrayBlockingQueue<>(Config.ROOM_QUEUE_CAPACITY);
    this.state = new GameState(roomId);
    this.notifier = notifier;
    this.destroyCallback = destroyCallback;
    this.onStatusChange = onStatusChange;
    stateLock.readLock().lock();
    try {
      this.cachedSnapshot = state.snapshot(null);
    } finally {
      stateLock.readLock().unlock();
    }
  }

  public int getRoomId() {
    return roomId;
  }

  public boolean isRunning() {
    return running;
  }

  public void post(Message msg) {
    if (!running) return;
    if (msg instanceof InputMsg) {
      InputMsg input = (InputMsg) msg;
      mailbox.removeIf(
          m -> m instanceof InputMsg && ((InputMsg) m).username().equals(input.username()));
    }
    boolean offered = mailbox.offer(msg);
    if (!offered) {
      logger.warn("Room " + roomId + " mailbox full, dropping message type: " + msg.type());
    }
  }

  public GameStateData getSnapshot(String username) {
    return cachedSnapshot;
  }

  @Override
  public void run() {
    lastActiveTime = System.currentTimeMillis();
    logger.info("Room " + roomId + " started.");

    while (running) {
      long now = System.currentTimeMillis();
      long nextTick = now + Config.TICK_INTERVAL_MS;
      long waitMs = Math.max(1, nextTick - now);
      Message first = null;
      try {
        first = mailbox.poll(waitMs, TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }

      if (first != null) {
        handle(first);
        List<Message> batch = new ArrayList<>();
        mailbox.drainTo(batch);
        for (Message msg : batch) handle(msg);
      }

      doGameTick();

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
    Map<String, Boolean> wasAlive = new HashMap<>();
    stateLock.readLock().lock();
    try {
      for (GameState.Player p : state.getPlayers()) {
        wasAlive.put(p.username, !p.isDead);
      }
    } finally {
      stateLock.readLock().unlock();
    }

    stateLock.writeLock().lock();
    try {
      state.update();
    } finally {
      stateLock.writeLock().unlock();
    }

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

    if (!diedPlayers.isEmpty()) {
      stateLock.writeLock().lock();
      try {
        for (String username : diedPlayers) {
          state.removePlayer(username);
          notifier.notifyPlayer(username, "{\"cmd\":\"YOU_DIED\"}");
          logger.debug("Player " + username + " died, removed from room");
        }
      } finally {
        stateLock.writeLock().unlock();
      }
      if (onStatusChange != null) onStatusChange.run();
    }

    stateLock.readLock().lock();
    try {
      cachedSnapshot = state.snapshot(null);
    } finally {
      stateLock.readLock().unlock();
    }

    broadcastSnapshot();

    if (!state.isEmpty()) {
      lastActiveTime = System.currentTimeMillis();
    }

    if (state.isEmpty()
        && System.currentTimeMillis() - lastActiveTime > Config.ROOM_IDLE_TIMEOUT * 1000L) {
      logger.info("Room " + roomId + " idle timeout, stopping.");
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
            notifier.notifyPlayer(join.username(), resp.toString());
            logger.info("Player " + join.username() + " joined room " + roomId);
            if (onStatusChange != null) onStatusChange.run();
          } else {
            ObjectNode resp = mapper.createObjectNode();
            resp.put("cmd", "JOIN_FAIL");
            resp.put("message", "Room is full or join failed");
            notifier.notifyPlayer(join.username(), resp.toString());
          }
          break;
        case "INPUT":
          InputMsg input = (InputMsg) msg;
          state.updateDirection(input.username(), input.direction());
          break;
        case "LEAVE":
          LeaveRoomMsg leave = (LeaveRoomMsg) msg;
          state.removePlayer(leave.username());
          logger.info("Player " + leave.username() + " left room " + roomId);
          if (onStatusChange != null) onStatusChange.run();
          break;
      }
    } finally {
      stateLock.writeLock().unlock();
    }
  }

  private void broadcastSnapshot() {
    if (cachedSnapshot == null) return;
    String json = new Serializer().serialize(cachedSnapshot);
    if (json == null) return;
    for (GameStateData.PlayerInfo player : cachedSnapshot.players) {
      if (player == null || player.isDead) continue;
      notifier.notifyPlayer(player.name, json);
    }
  }

  private void cleanup() {
    running = false;
    mailbox.clear();
    logger.info("Room " + roomId + " destroyed.");
    if (destroyCallback != null) {
      destroyCallback.onRoomDestroyed(roomId, this);
    }
  }
}
