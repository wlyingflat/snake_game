package snake.fbs;

import com.google.flatbuffers.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@SuppressWarnings("unused")
public final class GameState extends Table {
  public static void ValidateVersion() {}

  public static GameState getRootAsGameState(ByteBuffer _bb) {
    return getRootAsGameState(_bb, new GameState());
  }

  public static GameState getRootAsGameState(ByteBuffer _bb, GameState obj) {
    _bb.order(ByteOrder.LITTLE_ENDIAN);
    return (obj.__assign(_bb.getInt(_bb.position()) + _bb.position(), _bb));
  }

  public void __init(int _i, ByteBuffer _bb) {
    __reset(_i, _bb);
  }

  public GameState __assign(int _i, ByteBuffer _bb) {
    __init(_i, _bb);
    return this;
  }

  public int roomId() {
    int o = __offset(4);
    return o != 0 ? bb.getInt(o + bb_pos) : 0;
  }

  public Position food() {
    return food(new Position());
  }

  public Position food(Position obj) {
    int o = __offset(6);
    return o != 0 ? obj.__assign(__indirect(o + bb_pos), bb) : null;
  }

  public Position obstacles(int j) {
    return obstacles(new Position(), j);
  }

  public Position obstacles(Position obj, int j) {
    int o = __offset(8);
    return o != 0 ? obj.__assign(__indirect(__vector(o) + j * 4), bb) : null;
  }

  public int obstaclesLength() {
    int o = __offset(8);
    return o != 0 ? __vector_len(o) : 0;
  }

  public Position.Vector obstaclesVector() {
    return obstaclesVector(new Position.Vector());
  }

  public Position.Vector obstaclesVector(Position.Vector obj) {
    int o = __offset(8);
    return o != 0 ? obj.__assign(__vector(o), 4, bb) : null;
  }

  public Player players(int j) {
    return players(new Player(), j);
  }

  public Player players(Player obj, int j) {
    int o = __offset(10);
    return o != 0 ? obj.__assign(__indirect(__vector(o) + j * 4), bb) : null;
  }

  public int playersLength() {
    int o = __offset(10);
    return o != 0 ? __vector_len(o) : 0;
  }

  public Player.Vector playersVector() {
    return playersVector(new Player.Vector());
  }

  public Player.Vector playersVector(Player.Vector obj) {
    int o = __offset(10);
    return o != 0 ? obj.__assign(__vector(o), 4, bb) : null;
  }

  public int activePlayers() {
    int o = __offset(12);
    return o != 0 ? bb.getInt(o + bb_pos) : 0;
  }

  public int totalPlayers() {
    int o = __offset(14);
    return o != 0 ? bb.getInt(o + bb_pos) : 0;
  }

  public static int createGameState(
      FlatBufferBuilder builder,
      int roomId,
      int foodOffset,
      int obstaclesOffset,
      int playersOffset,
      int activePlayers,
      int totalPlayers) {
    builder.startTable(6);
    GameState.addTotalPlayers(builder, totalPlayers);
    GameState.addActivePlayers(builder, activePlayers);
    GameState.addPlayers(builder, playersOffset);
    GameState.addObstacles(builder, obstaclesOffset);
    GameState.addFood(builder, foodOffset);
    GameState.addRoomId(builder, roomId);
    return GameState.endGameState(builder);
  }

  public static void startGameState(FlatBufferBuilder builder) {
    builder.startTable(6);
  }

  public static void addRoomId(FlatBufferBuilder builder, int roomId) {
    builder.addInt(0, roomId, 0);
  }

  public static void addFood(FlatBufferBuilder builder, int foodOffset) {
    builder.addOffset(1, foodOffset, 0);
  }

  public static void addObstacles(FlatBufferBuilder builder, int obstaclesOffset) {
    builder.addOffset(2, obstaclesOffset, 0);
  }

  public static int createObstaclesVector(FlatBufferBuilder builder, int[] data) {
    builder.startVector(4, data.length, 4);
    for (int i = data.length - 1; i >= 0; i--) builder.addOffset(data[i]);
    return builder.endVector();
  }

  public static void startObstaclesVector(FlatBufferBuilder builder, int numElems) {
    builder.startVector(4, numElems, 4);
  }

  public static void addPlayers(FlatBufferBuilder builder, int playersOffset) {
    builder.addOffset(3, playersOffset, 0);
  }

  public static int createPlayersVector(FlatBufferBuilder builder, int[] data) {
    builder.startVector(4, data.length, 4);
    for (int i = data.length - 1; i >= 0; i--) builder.addOffset(data[i]);
    return builder.endVector();
  }

  public static void startPlayersVector(FlatBufferBuilder builder, int numElems) {
    builder.startVector(4, numElems, 4);
  }

  public static void addActivePlayers(FlatBufferBuilder builder, int activePlayers) {
    builder.addInt(4, activePlayers, 0);
  }

  public static void addTotalPlayers(FlatBufferBuilder builder, int totalPlayers) {
    builder.addInt(5, totalPlayers, 0);
  }

  public static int endGameState(FlatBufferBuilder builder) {
    int o = builder.endTable();
    return o;
  }

  public static void finishGameStateBuffer(FlatBufferBuilder builder, int offset) {
    builder.finish(offset);
  }

  public static void finishSizePrefixedGameStateBuffer(FlatBufferBuilder builder, int offset) {
    builder.finishSizePrefixed(offset);
  }

  public static final class Vector extends BaseVector {
    public Vector __assign(int _vector, int _element_size, ByteBuffer _bb) {
      __reset(_vector, _element_size, _bb);
      return this;
    }

    public GameState get(int j) {
      return get(new GameState(), j);
    }

    public GameState get(GameState obj, int j) {
      return obj.__assign(__indirect(__element(j), bb), bb);
    }
  }
}
