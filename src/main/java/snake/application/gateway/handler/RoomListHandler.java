package snake.application.gateway.handler;

import com.fasterxml.jackson.databind.JsonNode;
import snake.application.gateway.session.ClientSession;
import snake.common.Config;
import snake.common.JsonUtils;
import snake.distributed.DistributedCoordinator;

public class RoomListHandler implements CommandHandler {
  private final DistributedCoordinator coordinator;

  public RoomListHandler(DistributedCoordinator coordinator) {
    this.coordinator = coordinator;
  }

  @Override
  public void handle(ClientSession session, JsonNode payload) {
    sendRoomList(session);
  }

  private void sendRoomList(ClientSession session) {
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
      session.sendMessage(JsonUtils.MAPPER.writeValueAsString(resp));
    } catch (Exception e) {
      session.sendMessage("{\"cmd\":\"ERROR\"}");
    }
  }
}
