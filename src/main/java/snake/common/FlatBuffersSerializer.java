package snake.common;

import com.google.flatbuffers.FlatBufferBuilder;
import java.util.*;
import snake.fbs.*;

public class FlatBuffersSerializer {

  private final ThreadLocal<FlatBufferBuilder> builderThreadLocal =
      ThreadLocal.withInitial(() -> new FlatBufferBuilder(4096));

  public byte[] serializeGameState(GameStateData data) {
    FlatBufferBuilder fbb = builderThreadLocal.get();
    fbb.clear();

    int foodOffset = snake.fbs.Position.createPosition(fbb, data.food.x, data.food.y);

    int[] obstacleOffsets = new int[data.obstacleCount];
    for (int i = 0; i < data.obstacleCount; i++) {
      snake.common.Position p = data.obstacles[i];
      obstacleOffsets[i] = snake.fbs.Position.createPosition(fbb, p.x, p.y);
    }
    int obstaclesVector = snake.fbs.GameState.createObstaclesVector(fbb, obstacleOffsets);

    int[] playerOffsets = new int[data.playerCount];
    for (int i = 0; i < data.playerCount; i++) {
      GameStateData.PlayerInfo pi = data.players[i];
      playerOffsets[i] = buildPlayer(fbb, pi);
    }
    int playersVector = snake.fbs.GameState.createPlayersVector(fbb, playerOffsets);

    snake.fbs.GameState.startGameState(fbb);
    snake.fbs.GameState.addRoomId(fbb, data.roomId);
    snake.fbs.GameState.addFood(fbb, foodOffset);
    snake.fbs.GameState.addObstacles(fbb, obstaclesVector);
    snake.fbs.GameState.addPlayers(fbb, playersVector);
    snake.fbs.GameState.addActivePlayers(fbb, data.activePlayers);
    snake.fbs.GameState.addTotalPlayers(fbb, data.totalPlayers);
    int root = snake.fbs.GameState.endGameState(fbb);
    fbb.finish(root);
    byte[] result = fbb.sizedByteArray();
    System.out.println("[DEBUG] Serialized GameState length: " + result.length);
    return result;
  }

  public byte[] serializeDiff(snake.domain.game.GameStateDiff diff) {
    FlatBufferBuilder fbb = builderThreadLocal.get();
    fbb.clear();

    int foodOffset = 0;
    if (diff.food != null) {
      foodOffset = snake.fbs.Position.createPosition(fbb, diff.food.x, diff.food.y);
    }

    // players diff
    int[] kvOffsets = new int[diff.players.size()];
    int idx = 0;
    for (Map.Entry<String, snake.domain.game.PlayerDiff> entry : diff.players.entrySet()) {
      String key = entry.getKey();
      snake.domain.game.PlayerDiff pd = entry.getValue();
      int keyOffset = fbb.createString(key);
      int valueOffset = buildPlayerDiff(fbb, pd);
      kvOffsets[idx++] = KeyValue.createKeyValue(fbb, keyOffset, valueOffset);
    }
    int playersDiffVector = snake.fbs.GameStateDiff.createPlayersDiffVector(fbb, kvOffsets);

    int[] diedOffsets = new int[diff.died.size()];
    for (int i = 0; i < diff.died.size(); i++) {
      diedOffsets[i] = fbb.createString(diff.died.get(i));
    }
    int diedVector = snake.fbs.GameStateDiff.createDiedVector(fbb, diedOffsets);

    int[] newPlayerOffsets = new int[diff.newPlayers.size()];
    for (int i = 0; i < diff.newPlayers.size(); i++) {
      newPlayerOffsets[i] = buildPlayer(fbb, diff.newPlayers.get(i));
    }
    int newPlayersVector = snake.fbs.GameStateDiff.createNewPlayersVector(fbb, newPlayerOffsets);

    int[] removedOffsets = new int[diff.removedPlayers.size()];
    for (int i = 0; i < diff.removedPlayers.size(); i++) {
      removedOffsets[i] = fbb.createString(diff.removedPlayers.get(i));
    }
    int removedVector = snake.fbs.GameStateDiff.createRemovedPlayersVector(fbb, removedOffsets);

    snake.fbs.GameStateDiff.startGameStateDiff(fbb);
    snake.fbs.GameStateDiff.addRoomId(fbb, diff.roomId);
    snake.fbs.GameStateDiff.addSeq(fbb, diff.seq);
    if (diff.food != null) {
      snake.fbs.GameStateDiff.addFood(fbb, foodOffset);
    }
    snake.fbs.GameStateDiff.addPlayersDiff(fbb, playersDiffVector);
    snake.fbs.GameStateDiff.addDied(fbb, diedVector);
    snake.fbs.GameStateDiff.addNewPlayers(fbb, newPlayersVector);
    snake.fbs.GameStateDiff.addRemovedPlayers(fbb, removedVector);
    int root = snake.fbs.GameStateDiff.endGameStateDiff(fbb);
    fbb.finish(root);

    return fbb.sizedByteArray();
  }

  private int buildPlayer(FlatBufferBuilder fbb, GameStateData.PlayerInfo pi) {
    int nameOffset = fbb.createString(pi.name);
    int headOffset = snake.fbs.Position.createPosition(fbb, pi.head.x, pi.head.y);
    int[] bodyOffsets = new int[pi.length];
    for (int i = 0; i < pi.length; i++) {
      snake.common.Position bp = pi.body[i];
      bodyOffsets[i] = snake.fbs.Position.createPosition(fbb, bp.x, bp.y);
    }
    int bodyVector = Player.createBodyVector(fbb, bodyOffsets);
    Player.startPlayer(fbb);
    Player.addName(fbb, nameOffset);
    Player.addHead(fbb, headOffset);
    Player.addBody(fbb, bodyVector);
    Player.addLength(fbb, pi.length);
    Player.addDirection(fbb, directionToFbs(pi.direction));
    Player.addScore(fbb, pi.score);
    Player.addIsDead(fbb, pi.isDead);
    return Player.endPlayer(fbb);
  }

  private int buildPlayerDiff(FlatBufferBuilder fbb, snake.domain.game.PlayerDiff pd) {
    int headOffset = snake.fbs.Position.createPosition(fbb, pd.newHead.x, pd.newHead.y);
    snake.fbs.PlayerDiff.startPlayerDiff(fbb);
    snake.fbs.PlayerDiff.addNewHead(fbb, headOffset);
    snake.fbs.PlayerDiff.addRemoveTail(fbb, pd.removeTail);
    snake.fbs.PlayerDiff.addLength(fbb, pd.length);
    return snake.fbs.PlayerDiff.endPlayerDiff(fbb);
  }

  private byte directionToFbs(Direction dir) {
    return switch (dir) {
      case UP -> snake.fbs.Direction.UP;
      case DOWN -> snake.fbs.Direction.DOWN;
      case LEFT -> snake.fbs.Direction.LEFT;
      case RIGHT -> snake.fbs.Direction.RIGHT;
    };
  }
}
