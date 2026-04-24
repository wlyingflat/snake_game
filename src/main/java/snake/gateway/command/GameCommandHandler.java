package snake.gateway.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import snake.base.Config;
import snake.base.Direction;
import snake.base.ILeaderboardRepository;
import snake.base.ILeaderboardRepository.UserRank;
import snake.base.ILogger;
import snake.base.JsonUtils;
import snake.base.Logger;
import snake.base.RoomListEntry;
import snake.game.event.InputMsg;
import snake.game.event.JoinRoomMsg;
import snake.game.event.LeaveRoomMsg;
import snake.game.room.Room;
import snake.game.room.RoomManager;
import snake.game.state.RoomStatus;
import snake.gateway.heartbeat.HeartbeatService;
import snake.gateway.session.ClientSession;
import snake.gateway.session.SessionManager;

public class GameCommandHandler {
  private final RoomManager roomManager;
  private final SessionManager sessionManager;
  private final HeartbeatService heartbeatService;
  private final ILogger logger = Logger.getInstance();
  private final ILeaderboardRepository leaderboardRepo;

  public GameCommandHandler(
      RoomManager roomManager,
      SessionManager sessionManager,
      HeartbeatService heartbeatService,
      ILeaderboardRepository leaderboardRepo) {
    this.roomManager = roomManager;
    this.sessionManager = sessionManager;
    this.heartbeatService = heartbeatService;
    this.leaderboardRepo = leaderboardRepo;
  }

  public void handleUser(ClientSession session, JsonNode params) {
    String username = params.get("username").asText();
    session.username = username;
    sessionManager.bindUsername(session.getSessionId(), username);
    logger.info("Client identified: " + username);
    session.sendMessage(buildRoomListJson());
    heartbeatService.refresh(session);
  }

  public void handleQuit(ClientSession session, JsonNode params) {
    if (session.roomId != -1) {
      Room room = roomManager.getRoom(session.roomId);
      if (room != null) room.post(new LeaveRoomMsg(session.username));
    }
    session.close();
  }

  public void handleJoin(ClientSession session, JsonNode params) {
    if (session.username == null) {
      session.sendMessage("{\"cmd\":\"ERROR\",\"message\":\"Not authenticated\"}");
      return;
    }
    int roomId = params.get("roomId").asInt();
    Room room = roomManager.getRoom(roomId);
    if (room != null) {
      room.post(new JoinRoomMsg(session.username));
    } else {
      session.sendMessage("{\"cmd\":\"ERROR\",\"message\":\"Room not found\"}");
    }
    heartbeatService.refresh(session);
  }

  public void handleCreate(ClientSession session, JsonNode params) {
    if (session.username == null) {
      session.sendMessage("{\"cmd\":\"ERROR\",\"message\":\"Not authenticated\"}");
      return;
    }
    int newRoomId = params.get("roomId").asInt();
    Room room = roomManager.createRoom(newRoomId, null, null);
    if (room != null) {
      room.post(new JoinRoomMsg(session.username));
    } else {
      session.sendMessage("{\"cmd\":\"ERROR\",\"message\":\"Cannot create room\"}");
    }
    heartbeatService.refresh(session);
  }

  public void handleInput(ClientSession session, JsonNode params) {
    if (session.username == null || session.roomId == -1) return;
    Direction dir = Direction.valueOf(params.get("direction").asText());
    Room room = roomManager.getRoom(session.roomId);
    if (room != null) {
      // 使用对象池复用 InputMsg（可选，显著减少 GC）
      // ReusableInputMsg msg = MessagePool.borrowInputMsg(session.username, dir);
      // room.post(msg);
      // 如果不使用对象池，仍可 new：
      room.post(new InputMsg(session.username, dir));
    }
    heartbeatService.refresh(session);
  }

  public void handleRoomList(ClientSession session, JsonNode params) {
    session.sendMessage(buildRoomListJson());
    heartbeatService.refresh(session);
  }

  public void handlePing(ClientSession session, JsonNode params) {
    session.sendMessage("{\"cmd\":\"PONG\"}");
    heartbeatService.refresh(session);
  }

  public void handlePong(ClientSession session, JsonNode params) {
    session.pendingPong = false;
    session.lastHeartbeat = System.currentTimeMillis() / 1000;
    heartbeatService.refresh(session);
  }

  private String buildRoomListJson() {
    ArrayNode rooms = JsonUtils.MAPPER.createArrayNode();
    List<RoomListEntry> entries = roomManager.getRoomList();
    for (RoomListEntry entry : entries) {
      ObjectNode room = JsonUtils.MAPPER.createObjectNode();
      room.put("id", entry.roomId);
      room.put("status", entry.status == RoomStatus.OPEN ? "OPEN" : "FULL");
      room.put("players", entry.playerCount);
      room.put("maxPlayers", Config.MAX_PLAYERS_PER_ROOM);
      rooms.add(room);
    }
    ObjectNode response = JsonUtils.MAPPER.createObjectNode();
    response.put("cmd", "ROOM_LIST");
    response.set("rooms", rooms);
    try {
      return JsonUtils.MAPPER.writeValueAsString(response);
    } catch (Exception e) {
      return "{\"cmd\":\"ERROR\",\"message\":\"Cannot build room list\"}";
    }
  }

  public void handleLeaderboard(ClientSession session, JsonNode params) {
    if (session.username == null) {
      session.sendMessage("{\"cmd\":\"ERROR\",\"message\":\"Not authenticated\"}");
      return;
    }
    int limit = params.has("limit") ? params.get("limit").asInt() : 10; // 默认取前10名
    List<UserRank> ranks = leaderboardRepo.getLeaderboard(limit);

    ObjectNode resp = JsonUtils.MAPPER.createObjectNode();
    resp.put("cmd", "LEADERBOARD");
    ArrayNode entries = JsonUtils.MAPPER.createArrayNode();
    for (UserRank r : ranks) {
      ObjectNode entry = JsonUtils.MAPPER.createObjectNode();
      entry.put("rank", r.rank);
      entry.put("username", r.username);
      entry.put("score", r.score);
      entries.add(entry);
    }
    resp.set("leaderboard", entries);
    session.sendMessage(resp.toString());
    heartbeatService.refresh(session);
  }
}
