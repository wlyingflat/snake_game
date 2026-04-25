package snake.gateway.lobby;

import snake.base.Config;
import snake.base.JsonUtils;
import snake.distributed.DistributedCoordinator;
import snake.gateway.session.ClientSession;
import snake.gateway.session.SessionManager;

public class LobbyService {
  private final SessionManager sessionManager;
  private final DistributedCoordinator coordinator;

  public LobbyService(SessionManager sessionManager, DistributedCoordinator coordinator) {
    this.sessionManager = sessionManager;
    this.coordinator = coordinator;
  }

  public void sendRoomListToLobby() {
    String json = buildRoomListJson();
    for (ClientSession s : sessionManager.getAllSessions()) {
      if (s != null && s.isActive() && s.username != null && s.roomId == -1) {
        s.sendMessage(json);
      }
    }
  }

  private String buildRoomListJson() {
    var rooms = JsonUtils.MAPPER.createArrayNode();
    if (coordinator != null) {
      for (var entry : coordinator.getAllRooms()) {
        var room = JsonUtils.MAPPER.createObjectNode();
        room.put("id", entry.roomId());
        room.put("status", "FULL".equals(entry.status()) ? "FULL" : "OPEN");
        room.put("players", entry.playerCount());
        room.put("maxPlayers", Config.MAX_PLAYERS_PER_ROOM);
        rooms.add(room);
      }
    }
    var resp = JsonUtils.MAPPER.createObjectNode();
    resp.put("cmd", "ROOM_LIST");
    resp.set("rooms", rooms);
    try {
      return JsonUtils.MAPPER.writeValueAsString(resp);
    } catch (Exception e) {
      return "{}";
    }
  }
}
