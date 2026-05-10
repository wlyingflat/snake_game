package snake.application.actor;

import java.util.List;
import snake.common.*;
import snake.domain.game.AgarGameState;
import snake.domain.game.AgarGameState.AgarPlayerState;

public class GameTickProcessor {
  private final int roomId;
  private final AgarGameState state;
  private final ActorNotifier notifier;
  private final FlatBuffersSerializer serializer = new FlatBuffersSerializer();

  public GameTickProcessor(int roomId, AgarGameState state, ActorNotifier notifier) {
    this.roomId = roomId;
    this.state = state;
    this.notifier = notifier;
  }

  public void processTick() {
    if (state.isEmpty()) return;
    state.update();

    List<AgarPlayerState> playerStates = state.getPlayerStates();
    if (playerStates.isEmpty()) return;

    List<Position> foodPositions = state.getFoodPositions();
    byte[] data = serializer.serializeAgarFrame(playerStates, foodPositions);

    notifier.broadcastBinaryToRoom(
        state.getActiveUsernames(), data, ActorNotifier.SUBTYPE_FULL_STATE);
  }

  public byte[] getLastFrame() {
    return serializer.serializeAgarFrame(state.getPlayerStates(), state.getFoodPositions());
  }
}
