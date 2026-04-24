package snake.room;

import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;
import snake.common.*;
import snake.util.*;

public class GameManager {
  private int roomId;
  private GameWorld world;
  private ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
  private Map<SocketChannel, Player> playersByChannel = new ConcurrentHashMap<>();
  private volatile boolean shouldShutdown = false;
  private BiConsumer<SocketChannel, String> messageSender;

  public GameManager(int roomId) {
    this.roomId = roomId;
    world = new GameWorld();
    initWorld();
  }

  public void setMessageSender(BiConsumer<SocketChannel, String> sender) {
    this.messageSender = sender;
  }

  private void initWorld() {
    world.init();
  }

  public synchronized boolean addPlayer(SocketChannel channel, String username) {
    if (playersByChannel.containsKey(channel)) return false;
    if (world.totalPlayers >= Config.MAX_PLAYERS_PER_ROOM) return false;

    Player player = new Player();
    player.isUsed = true;
    player.channel = channel;
    player.name = username;
    player.body = new ArrayList<>();
    Position start = findSafeSpawnPosition();
    player.body.add(start);
    player.length = 1;
    player.direction = Direction.values()[new Random().nextInt(4)];
    player.score = 0;
    player.isDead = false;
    playersByChannel.put(channel, player);
    world.totalPlayers++;
    world.activePlayers++;
    world.initialDelayDone = true;
    return true;
  }

  public void removePlayer(SocketChannel channel) {
    Player p = playersByChannel.remove(channel);
    if (p != null) {
      world.totalPlayers--;
      if (!p.isDead) world.activePlayers--;
      if (world.totalPlayers == 0) {
        shouldShutdown = true;
      }
    }
  }

  public void updateDirection(SocketChannel channel, Direction dir) {
    Player p = playersByChannel.get(channel);
    if (p != null && !p.isDead) {
      p.direction = dir;
    }
  }

  public void updateWorld() {
    lock.writeLock().lock();
    try {
      if (!world.initialDelayDone) return;

      List<Player> players = new ArrayList<>(playersByChannel.values());
      Map<Player, Position> nextHeads = new HashMap<>();
      Map<Player, Boolean> willDie = new HashMap<>();
      Map<Player, Boolean> willGrow = new HashMap<>();

      for (Player p : players) {
        Position next = calculateNextPosition(p);
        nextHeads.put(p, next);
        willGrow.put(p, next.x == world.food.x && next.y == world.food.y);
        willDie.put(p, checkCollision(p, next));
      }

      for (Player p : players) {
        if (willDie.get(p)) {
          p.isDead = true;
          if (messageSender != null && p.channel != null && p.channel.isOpen()) {
            messageSender.accept(p.channel, Protocol.YOU_DIED);
          }
          willGrow.put(p, false);
        }
      }

      boolean foodEaten = false;
      for (Player p : players) {
        if (p.isDead) continue;
        Position next = nextHeads.get(p);
        if (willGrow.get(p)) {
          p.body.add(0, next);
          p.length++;
          p.score++;
          foodEaten = true;
        } else {
          p.body.add(0, next);
          p.body.remove(p.body.size() - 1);
        }
      }

      if (foodEaten) {
        world.food = findSafeFoodPosition();
      }

      updateStatistics();

      if (world.totalPlayers == 0 && world.initialDelayDone) {
        shouldShutdown = true;
      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  private void updateStatistics() {
    int active = 0;
    for (Player p : playersByChannel.values()) {
      if (!p.isDead) active++;
    }
    world.activePlayers = active;
    world.totalPlayers = playersByChannel.size();
  }

  public int getActivePlayers() {
    return world.activePlayers;
  }

  private Position findSafeSpawnPosition() {
    Random rand = new Random();
    Position pos;
    int attempts = 0;
    do {
      pos =
          new Position(
              rand.nextInt(Config.MAP_WIDTH - 2) + 1, rand.nextInt(Config.MAP_HEIGHT - 2) + 1);
      attempts++;
      if (attempts > Config.MAX_SPAWN_ATTEMPTS) {
        pos = new Position(Config.MAP_WIDTH / 2, Config.MAP_HEIGHT / 2);
        break;
      }
    } while (isPositionOccupied(pos));
    return pos;
  }

  private Position findSafeFoodPosition() {
    Random rand = new Random();
    Position pos;
    int attempts = 0;
    do {
      pos =
          new Position(
              rand.nextInt(Config.MAP_WIDTH - 2) + 1, rand.nextInt(Config.MAP_HEIGHT - 2) + 1);
      attempts++;
      if (attempts > Config.MAX_SPAWN_ATTEMPTS) {
        pos = new Position(Config.MAP_WIDTH / 2, Config.MAP_HEIGHT / 2);
        break;
      }
    } while (isPositionOccupied(pos) || (pos.x == world.food.x && pos.y == world.food.y));
    return pos;
  }

  private boolean isPositionOccupied(Position pos) {
    if (pos.x <= 0
        || pos.x >= Config.MAP_WIDTH - 1
        || pos.y <= 0
        || pos.y >= Config.MAP_HEIGHT - 1) {
      return true;
    }
    for (Position obs : world.obstacles) {
      if (obs != null && obs.x == pos.x && obs.y == pos.y) return true;
    }
    for (Player p : playersByChannel.values()) {
      if (p.isDead) continue;
      for (Position seg : p.body) {
        if (seg.x == pos.x && seg.y == pos.y) return true;
      }
    }
    return false;
  }

  private Position calculateNextPosition(Player p) {
    Position head = p.body.get(0);
    Position next = new Position(head.x, head.y);
    switch (p.direction) {
      case UP:
        next.y--;
        break;
      case DOWN:
        next.y++;
        break;
      case LEFT:
        next.x--;
        break;
      case RIGHT:
        next.x++;
        break;
    }
    return next;
  }

  private boolean checkCollision(Player p, Position next) {
    if (next.x <= 0
        || next.x >= Config.MAP_WIDTH - 1
        || next.y <= 0
        || next.y >= Config.MAP_HEIGHT - 1) return true;
    for (Position obs : world.obstacles) {
      if (obs != null && obs.x == next.x && obs.y == next.y) return true;
    }
    for (int i = 0; i < p.body.size(); i++) {
      if (next.x == p.body.get(i).x && next.y == p.body.get(i).y) return true;
    }
    for (Player other : playersByChannel.values()) {
      if (other == p || other.isDead) continue;
      for (Position seg : other.body) {
        if (next.x == seg.x && next.y == seg.y) return true;
      }
    }
    return false;
  }

  public GameStateData getGameState(SocketChannel client) {
    lock.readLock().lock();
    try {
      GameStateData state = new GameStateData();
      state.roomId = roomId;
      state.food = world.food;
      state.obstacleCount = Config.OBSTACLE_COUNT;
      for (int i = 0; i < Config.OBSTACLE_COUNT; i++) {
        state.obstacles[i] = world.obstacles[i];
      }
      List<Player> players = new ArrayList<>(playersByChannel.values());
      state.playerCount = players.size();
      for (int i = 0; i < players.size(); i++) {
        Player p = players.get(i);
        GameStateData.PlayerInfo info = new GameStateData.PlayerInfo();
        info.name = p.name;
        info.head = p.body.get(0);
        info.body = p.body.toArray(new Position[0]);
        info.length = p.length;
        info.direction = p.direction;
        info.score = p.score;
        info.isDead = p.isDead;
        info.isYou = (p.channel == client);
        state.players[i] = info;
      }
      state.activePlayers = world.activePlayers;
      state.totalPlayers = world.totalPlayers;
      return state;
    } finally {
      lock.readLock().unlock();
    }
  }

  public boolean shouldShutdown() {
    return shouldShutdown;
  }

  public void resetShutdown() {
    shouldShutdown = false;
  }

  public void destroy() {}

  public List<Player> getPlayers() {
    return new ArrayList<>(playersByChannel.values());
  }
}
