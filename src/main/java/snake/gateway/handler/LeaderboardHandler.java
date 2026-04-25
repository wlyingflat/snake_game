package snake.gateway.handler;

import com.fasterxml.jackson.databind.JsonNode;
import snake.base.JsonUtils;
import snake.distributed.DistributedCoordinator;
import snake.gateway.session.ClientSession;

public class LeaderboardHandler implements CommandHandler {
  private final DistributedCoordinator coordinator;

  public LeaderboardHandler(DistributedCoordinator coordinator) {
    this.coordinator = coordinator;
  }

  @Override
  public void handle(ClientSession session, JsonNode payload) {
    if (session.username == null) {
      session.sendMessage("{\"cmd\":\"ERROR\",\"message\":\"Not authenticated\"}");
      return;
    }
    int limit = payload.has("limit") ? payload.get("limit").asInt() : 10;
    var ranks = coordinator.getLeaderboard(limit);
    var resp = JsonUtils.MAPPER.createObjectNode();
    resp.put("cmd", "LEADERBOARD");
    var entries = JsonUtils.MAPPER.createArrayNode();
    for (var rank : ranks) {
      var entry = JsonUtils.MAPPER.createObjectNode();
      entry.put("rank", rank.rank);
      entry.put("username", rank.username);
      entry.put("score", rank.score);
      entries.add(entry);
    }
    resp.set("leaderboard", entries);
    try {
      session.sendMessage(JsonUtils.MAPPER.writeValueAsString(resp));
    } catch (Exception e) {
      session.sendMessage("{\"cmd\":\"ERROR\"}");
    }
  }
}
