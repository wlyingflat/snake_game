package snake.gateway;

import java.io.*;
import java.net.*;
import java.nio.channels.SocketChannel;
import java.util.concurrent.*;
import snake.common.*;
import snake.util.*;

public class Gateway extends NioServer {
  private final ConcurrentHashMap<SocketChannel, ClientSession> sessions =
      new ConcurrentHashMap<>();
  private RoomListBroadcaster broadcaster;
  private ServerSocket notifyServer;

  public Gateway(int port) {
    super(port);
    this.broadcaster = new RoomListBroadcaster(sessions);
  }

  @Override
  protected String getServerName() {
    return "Gateway";
  }

  @Override
  protected NioSession createSession(SocketChannel channel) {
    ClientSession session = new ClientSession(channel, this);
    sessions.put(channel, session);
    Logger.debug("[Gateway] New session created, total sessions: " + sessions.size());
    return session;
  }

  @Override
  protected void processMessage(NioSession session, String msg) {
    ClientSession clientSession = (ClientSession) session;
    long now = System.currentTimeMillis() / 1000;

    clientSession.lastHeartbeat = now;
    clientSession.pendingPong = false;

    try {
      if (msg.startsWith(Protocol.USER + " ")) {
        clientSession.username = msg.substring(5);
        Logger.info("[Gateway] Client identified: " + clientSession.username);
        broadcaster.sendRoomListToClient(clientSession);
      } else if (msg.equals(Protocol.PING)) {
        clientSession.enqueueResponse(Protocol.PONG);
      } else if (msg.equals(Protocol.PONG)) {
        Logger.debug("[Gateway] Received PONG from " + clientSession.username);
      } else if (msg.equals(Protocol.QUIT)) {
        Logger.info("[Gateway] QUIT from " + clientSession.username);
        closeSession(clientSession);
      } else if (msg.equals(Protocol.CMD_ROOM_LIST)) {
        broadcaster.sendRoomListToClient(clientSession);
      }
    } catch (Exception e) {
      Logger.error("[Gateway] Error processing message: " + e.getMessage());
      closeSession(clientSession);
    }
  }

  @Override
  protected void onSessionClosed(NioSession session) {
    ClientSession clientSession = (ClientSession) session;
    sessions.remove(clientSession.channel);
    Logger.debug("[Gateway] Session closed, remaining sessions: " + sessions.size());
  }

  private void checkHeartbeats() {
    long now = System.currentTimeMillis() / 1000;
    for (ClientSession session : sessions.values()) {
      if (session.pendingPong && (now - session.lastPingSent) > Config.HEARTBEAT_TIMEOUT) {
        Logger.warn("[Gateway] Client " + session.username + " heartbeat timeout (no PONG)");
        closeSession(session);
        continue;
      }

      if (!session.pendingPong && (now - session.lastHeartbeat) >= Config.HEARTBEAT_INTERVAL) {
        session.enqueueResponse(Protocol.PING);
        session.pendingPong = true;
        session.lastPingSent = now;
        Logger.debug("[Gateway] Sent PING to " + session.username);
      }
    }
  }

  private void startNotifyServer() {
    new Thread(
            () -> {
              try {
                notifyServer = new ServerSocket(Config.GATEWAY_NOTIFY_PORT);
                Logger.info(
                    "[Gateway] Notify server listening on port " + Config.GATEWAY_NOTIFY_PORT);
                while (running) {
                  Socket notifyClient = notifyServer.accept();
                  Logger.debug(
                      "[Gateway] Notify server accepted connection from "
                          + notifyClient.getRemoteSocketAddress());
                  InputStream is = notifyClient.getInputStream();
                  byte[] buf = new byte[64];
                  int n = is.read(buf);
                  if (n > 0) {
                    String msg = new String(buf, 0, n).trim();
                    Logger.info("[Gateway] Received on notify port: " + msg);
                    if (msg.equals("REFRESH")) {
                      Logger.info("[Gateway] REFRESH received, broadcasting room list");
                      broadcaster.broadcastRoomList();
                    } else {
                      Logger.warn("[Gateway] Unknown notify message: " + msg);
                    }
                  }
                  notifyClient.close();
                }
              } catch (IOException e) {
                if (running) Logger.error("[Gateway] Notify server error: " + e.getMessage());
              }
            })
        .start();
  }

  @Override
  public void start() throws IOException {
    startNotifyServer();
    ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
    heartbeatExecutor.scheduleAtFixedRate(
        this::checkHeartbeats,
        Config.HEARTBEAT_INTERVAL,
        Config.HEARTBEAT_INTERVAL,
        TimeUnit.SECONDS);
    super.start();
    heartbeatExecutor.shutdown();
  }

  @Override
  protected void cleanup() {
    running = false;
    try {
      if (notifyServer != null) notifyServer.close();
    } catch (IOException e) {
    }
    super.cleanup();
  }

  public static void main(String[] args) {
    int port = args.length > 0 ? Integer.parseInt(args[0]) : Config.GATEWAY_DEFAULT_PORT;
    try {
      new Gateway(port).start();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}
