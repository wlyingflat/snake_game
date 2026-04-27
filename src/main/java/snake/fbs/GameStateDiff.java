package snake.fbs;

import com.google.flatbuffers.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@SuppressWarnings("unused")
public final class GameStateDiff extends Table {
  public static void ValidateVersion() {}

  public static GameStateDiff getRootAsGameStateDiff(ByteBuffer _bb) {
    return getRootAsGameStateDiff(_bb, new GameStateDiff());
  }

  public static GameStateDiff getRootAsGameStateDiff(ByteBuffer _bb, GameStateDiff obj) {
    _bb.order(ByteOrder.LITTLE_ENDIAN);
    return (obj.__assign(_bb.getInt(_bb.position()) + _bb.position(), _bb));
  }

  public void __init(int _i, ByteBuffer _bb) {
    __reset(_i, _bb);
  }

  public GameStateDiff __assign(int _i, ByteBuffer _bb) {
    __init(_i, _bb);
    return this;
  }

  public int roomId() {
    int o = __offset(4);
    return o != 0 ? bb.getInt(o + bb_pos) : 0;
  }

  public long seq() {
    int o = __offset(6);
    return o != 0 ? bb.getLong(o + bb_pos) : 0L;
  }

  public Position food() {
    return food(new Position());
  }

  public Position food(Position obj) {
    int o = __offset(8);
    return o != 0 ? obj.__assign(__indirect(o + bb_pos), bb) : null;
  }

  public KeyValue playersDiff(int j) {
    return playersDiff(new KeyValue(), j);
  }

  public KeyValue playersDiff(KeyValue obj, int j) {
    int o = __offset(10);
    return o != 0 ? obj.__assign(__indirect(__vector(o) + j * 4), bb) : null;
  }

  public int playersDiffLength() {
    int o = __offset(10);
    return o != 0 ? __vector_len(o) : 0;
  }

  public KeyValue.Vector playersDiffVector() {
    return playersDiffVector(new KeyValue.Vector());
  }

  public KeyValue.Vector playersDiffVector(KeyValue.Vector obj) {
    int o = __offset(10);
    return o != 0 ? obj.__assign(__vector(o), 4, bb) : null;
  }

  public String died(int j) {
    int o = __offset(12);
    return o != 0 ? __string(__vector(o) + j * 4) : null;
  }

  public int diedLength() {
    int o = __offset(12);
    return o != 0 ? __vector_len(o) : 0;
  }

  public StringVector diedVector() {
    return diedVector(new StringVector());
  }

  public StringVector diedVector(StringVector obj) {
    int o = __offset(12);
    return o != 0 ? obj.__assign(__vector(o), 4, bb) : null;
  }

  public Player newPlayers(int j) {
    return newPlayers(new Player(), j);
  }

  public Player newPlayers(Player obj, int j) {
    int o = __offset(14);
    return o != 0 ? obj.__assign(__indirect(__vector(o) + j * 4), bb) : null;
  }

  public int newPlayersLength() {
    int o = __offset(14);
    return o != 0 ? __vector_len(o) : 0;
  }

  public Player.Vector newPlayersVector() {
    return newPlayersVector(new Player.Vector());
  }

  public Player.Vector newPlayersVector(Player.Vector obj) {
    int o = __offset(14);
    return o != 0 ? obj.__assign(__vector(o), 4, bb) : null;
  }

  public String removedPlayers(int j) {
    int o = __offset(16);
    return o != 0 ? __string(__vector(o) + j * 4) : null;
  }

  public int removedPlayersLength() {
    int o = __offset(16);
    return o != 0 ? __vector_len(o) : 0;
  }

  public StringVector removedPlayersVector() {
    return removedPlayersVector(new StringVector());
  }

  public StringVector removedPlayersVector(StringVector obj) {
    int o = __offset(16);
    return o != 0 ? obj.__assign(__vector(o), 4, bb) : null;
  }

  public static int createGameStateDiff(
      FlatBufferBuilder builder,
      int roomId,
      long seq,
      int foodOffset,
      int playersDiffOffset,
      int diedOffset,
      int newPlayersOffset,
      int removedPlayersOffset) {
    builder.startTable(7);
    GameStateDiff.addSeq(builder, seq);
    GameStateDiff.addRemovedPlayers(builder, removedPlayersOffset);
    GameStateDiff.addNewPlayers(builder, newPlayersOffset);
    GameStateDiff.addDied(builder, diedOffset);
    GameStateDiff.addPlayersDiff(builder, playersDiffOffset);
    GameStateDiff.addFood(builder, foodOffset);
    GameStateDiff.addRoomId(builder, roomId);
    return GameStateDiff.endGameStateDiff(builder);
  }

  public static void startGameStateDiff(FlatBufferBuilder builder) {
    builder.startTable(7);
  }

  public static void addRoomId(FlatBufferBuilder builder, int roomId) {
    builder.addInt(0, roomId, 0);
  }

  public static void addSeq(FlatBufferBuilder builder, long seq) {
    builder.addLong(1, seq, 0L);
  }

  public static void addFood(FlatBufferBuilder builder, int foodOffset) {
    builder.addOffset(2, foodOffset, 0);
  }

  public static void addPlayersDiff(FlatBufferBuilder builder, int playersDiffOffset) {
    builder.addOffset(3, playersDiffOffset, 0);
  }

  public static int createPlayersDiffVector(FlatBufferBuilder builder, int[] data) {
    builder.startVector(4, data.length, 4);
    for (int i = data.length - 1; i >= 0; i--) builder.addOffset(data[i]);
    return builder.endVector();
  }

  public static void startPlayersDiffVector(FlatBufferBuilder builder, int numElems) {
    builder.startVector(4, numElems, 4);
  }

  public static void addDied(FlatBufferBuilder builder, int diedOffset) {
    builder.addOffset(4, diedOffset, 0);
  }

  public static int createDiedVector(FlatBufferBuilder builder, int[] data) {
    builder.startVector(4, data.length, 4);
    for (int i = data.length - 1; i >= 0; i--) builder.addOffset(data[i]);
    return builder.endVector();
  }

  public static void startDiedVector(FlatBufferBuilder builder, int numElems) {
    builder.startVector(4, numElems, 4);
  }

  public static void addNewPlayers(FlatBufferBuilder builder, int newPlayersOffset) {
    builder.addOffset(5, newPlayersOffset, 0);
  }

  public static int createNewPlayersVector(FlatBufferBuilder builder, int[] data) {
    builder.startVector(4, data.length, 4);
    for (int i = data.length - 1; i >= 0; i--) builder.addOffset(data[i]);
    return builder.endVector();
  }

  public static void startNewPlayersVector(FlatBufferBuilder builder, int numElems) {
    builder.startVector(4, numElems, 4);
  }

  public static void addRemovedPlayers(FlatBufferBuilder builder, int removedPlayersOffset) {
    builder.addOffset(6, removedPlayersOffset, 0);
  }

  public static int createRemovedPlayersVector(FlatBufferBuilder builder, int[] data) {
    builder.startVector(4, data.length, 4);
    for (int i = data.length - 1; i >= 0; i--) builder.addOffset(data[i]);
    return builder.endVector();
  }

  public static void startRemovedPlayersVector(FlatBufferBuilder builder, int numElems) {
    builder.startVector(4, numElems, 4);
  }

  public static int endGameStateDiff(FlatBufferBuilder builder) {
    int o = builder.endTable();
    return o;
  }

  public static void finishGameStateDiffBuffer(FlatBufferBuilder builder, int offset) {
    builder.finish(offset);
  }

  public static void finishSizePrefixedGameStateDiffBuffer(FlatBufferBuilder builder, int offset) {
    builder.finishSizePrefixed(offset);
  }

  public static final class Vector extends BaseVector {
    public Vector __assign(int _vector, int _element_size, ByteBuffer _bb) {
      __reset(_vector, _element_size, _bb);
      return this;
    }

    public GameStateDiff get(int j) {
      return get(new GameStateDiff(), j);
    }

    public GameStateDiff get(GameStateDiff obj, int j) {
      return obj.__assign(__indirect(__element(j), bb), bb);
    }
  }
}
