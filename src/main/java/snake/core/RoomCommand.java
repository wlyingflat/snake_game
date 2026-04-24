package snake.core;

import java.util.concurrent.CompletableFuture;
import snake.common.Direction;
import snake.common.GameStateData;

public interface RoomCommand {
  String type();
}

record JoinCommand(String username) implements RoomCommand {
  @Override
  public String type() {
    return "JOIN";
  }
}

record InputCommand(String username, Direction direction) implements RoomCommand {
  @Override
  public String type() {
    return "INPUT";
  }
}

record LeaveCommand(String username) implements RoomCommand {
  @Override
  public String type() {
    return "LEAVE";
  }
}

record SnapshotCommand(String username, CompletableFuture<GameStateData> future)
    implements RoomCommand {
  @Override
  public String type() {
    return "SNAPSHOT";
  }
}
