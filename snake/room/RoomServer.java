package snake.room;

import java.io.*;
import java.net.*;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.concurrent.*;
import snake.common.*;
import snake.util.*;

public class RoomServer extends NioServer {
  private int roomId;
  private GameManager gameManager;
  private ScheduledExecutorService tickExecutor;
  private Map<SocketChannel, RoomSession> sessions = new ConcurrentHashMap<>();

  public RoomServer(int roomId, int port) {
    super(port);
    this.roomId = roomId;
  }

  @Override
  protected String getServerName() {
    return "RoomServer-" + roomId;
  }

  @Override
  protected NioSession createSession(SocketChannel channel) {
    RoomSession session = new RoomSession(channel, this);
    sessions.put(channel, session);
    return session;
  }

  @Override
  protected void processMessage(NioSession session, String msg) {
    RoomSession roomSession = (RoomSession) session;
    SocketChannel client = roomSession.channel;
    try {
      if (msg.startsWith(Protocol.PLAYER + " ")) {
        String username = msg.substring(7);
        boolean added = gameManager.addPlayer(client, username);
        if (added) {
          roomSession.username = username;
          roomSession.enqueueResponse(Protocol.WELCOME + " TO ROOM " + roomId);
          sendGameState(roomSession);
          Logger.info("[RoomServer-" + roomId + "] Player " + username + " added");
          sendRoomStatus();
        } else {
          Logger.warn("[RoomServer-" + roomId + "] Failed to add player " + username);
          roomSession.enqueueResponse(Protocol.RESP_ERROR + " Cannot add player");
          closeSession(roomSession);
        }
      } else if (msg.length() == 1 && "wasd".contains(msg.toLowerCase())) {
        char c = msg.toUpperCase().charAt(0);
        Direction dir = null;
        switch (c) {
          case 'W':
            dir = Direction.UP;
            break;
          case 'S':
            dir = Direction.DOWN;
            break;
          case 'A':
            dir = Direction.LEFT;
            break;
          case 'D':
            dir = Direction.RIGHT;
            break;
        }
        if (dir != null) {
          gameManager.updateDirection(client, dir);
        }
      } else if (msg.equals(Protocol.QUIT)) {
        Logger.info("[RoomServer-" + roomId + "] Player " + roomSession.username + " quit");
        gameManager.removePlayer(client);
        closeSession(roomSession);
        sendRoomStatus();
      }
    } catch (Exception e) {
      Logger.error("[RoomServer-" + roomId + "] Error processing message: " + e.getMessage());
      closeSession(roomSession);
    }
  }

  @Override
  protected void onSessionClosed(NioSession session) {
    RoomSession roomSession = (RoomSession) session;
    if (roomSession.username != null) {
      Logger.info("[RoomServer-" + roomId + "] Session closed for " + roomSession.username);
    }
    gameManager.removePlayer(roomSession.channel);
    sessions.remove(roomSession.channel);
    sendRoomStatus();
  }

  @Override
  public void start() throws IOException {
    registerWithMain();
    gameManager = new GameManager(roomId);
    gameManager.setMessageSender(
        (channel, msg) -> {
          RoomSession session = sessions.get(channel);
          if (session != null) session.enqueueResponse(msg);
        });
    tickExecutor = Executors.newSingleThreadScheduledExecutor();
    tickExecutor.scheduleAtFixedRate(this::tick, 0, Config.TICK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    super.start();
  }

  private void tick() {
    gameManager.updateWorld();
    broadcastGameState();
    if (gameManager.shouldShutdown()
        && gameManager.getPlayers().stream()
            .noneMatch(p -> p.isUsed && p.channel != null && p.channel.isOpen())) {
      Logger.info("[RoomServer-" + roomId + "] No players left, shutting down");
      sendRoomStatus(); // playerCount = 0
      stop();
    }
  }

  private void broadcastGameState() {
    for (Player player : gameManager.getPlayers()) {
      if (player.isDead) continue;
      RoomSession session = sessions.get(player.channel);
      if (session != null) sendGameState(session);
    }
  }

  private void sendGameState(RoomSession session) {
    GameStateData state = gameManager.getGameState(session.channel);
    if (state != null) {
      String json = Serializer.serializeGameState(state);
      if (json != null) {
        session.enqueueResponse(json);
      }
    }
  }

  private void registerWithMain() {
    try (Socket socket = new Socket("localhost", Config.ROOM_REGISTER_PORT);
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
      out.println("REGISTER " + roomId + " " + port);
      String resp = in.readLine();
      if (!"OK".equals(resp)) {
        Logger.error("[RoomServer-" + roomId + "] Failed to register with main server");
        System.exit(1);
      }
      Logger.info("[RoomServer-" + roomId + "] Registered with main server on port " + port);
    } catch (IOException e) {
      Logger.error("[RoomServer-" + roomId + "] Cannot connect to main server: " + e.getMessage());
      System.exit(1);
    }
  }

  private void unregister() {
    try (Socket socket = new Socket("localhost", Config.ROOM_REGISTER_PORT);
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
      out.println("UNREGISTER " + roomId);
      Logger.info("[RoomServer-" + roomId + "] Sent UNREGISTER to main");
    } catch (IOException e) {
      Logger.error("[RoomServer-" + roomId + "] Unregister error: " + e.getMessage());
    }
  }

  private void sendRoomStatus() {
    int playerCount = gameManager.getPlayers().size();
    int activePlayers = gameManager.getActivePlayers();
    try (Socket socket = new Socket("localhost", Config.ROOM_REGISTER_PORT);
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
      out.println("UPDATE " + roomId + " " + playerCount + " " + activePlayers);
    } catch (IOException e) {
      Logger.warn("[RoomServer-" + roomId + "] Failed to send room status: " + e.getMessage());
    }
  }

  @Override
  protected void cleanup() {
    running = false;
    if (tickExecutor != null) tickExecutor.shutdown();
    unregister();
    gameManager.destroy();
    super.cleanup();
  }

  public static void main(String[] args) {
    if (args.length < 2) {
      System.err.println("Usage: RoomServer <roomId> <port>");
      System.exit(1);
    }
    int roomId = Integer.parseInt(args[0]);
    int port = Integer.parseInt(args[1]);
    try {
      new RoomServer(roomId, port).start();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}
