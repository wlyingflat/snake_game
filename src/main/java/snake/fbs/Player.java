package snake.fbs;

import com.google.flatbuffers.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@SuppressWarnings("unused")
public final class Player extends Table {
  public static void ValidateVersion() {}

  public static Player getRootAsPlayer(ByteBuffer _bb) {
    return getRootAsPlayer(_bb, new Player());
  }

  public static Player getRootAsPlayer(ByteBuffer _bb, Player obj) {
    _bb.order(ByteOrder.LITTLE_ENDIAN);
    return (obj.__assign(_bb.getInt(_bb.position()) + _bb.position(), _bb));
  }

  public void __init(int _i, ByteBuffer _bb) {
    __reset(_i, _bb);
  }

  public Player __assign(int _i, ByteBuffer _bb) {
    __init(_i, _bb);
    return this;
  }

  public String name() {
    int o = __offset(4);
    return o != 0 ? __string(o + bb_pos) : null;
  }

  public ByteBuffer nameAsByteBuffer() {
    return __vector_as_bytebuffer(4, 1);
  }

  public ByteBuffer nameInByteBuffer(ByteBuffer _bb) {
    return __vector_in_bytebuffer(_bb, 4, 1);
  }

  public Position head() {
    return head(new Position());
  }

  public Position head(Position obj) {
    int o = __offset(6);
    return o != 0 ? obj.__assign(__indirect(o + bb_pos), bb) : null;
  }

  public Position body(int j) {
    return body(new Position(), j);
  }

  public Position body(Position obj, int j) {
    int o = __offset(8);
    return o != 0 ? obj.__assign(__indirect(__vector(o) + j * 4), bb) : null;
  }

  public int bodyLength() {
    int o = __offset(8);
    return o != 0 ? __vector_len(o) : 0;
  }

  public Position.Vector bodyVector() {
    return bodyVector(new Position.Vector());
  }

  public Position.Vector bodyVector(Position.Vector obj) {
    int o = __offset(8);
    return o != 0 ? obj.__assign(__vector(o), 4, bb) : null;
  }

  public int length() {
    int o = __offset(10);
    return o != 0 ? bb.getInt(o + bb_pos) : 0;
  }

  public byte direction() {
    int o = __offset(12);
    return o != 0 ? bb.get(o + bb_pos) : 0;
  }

  public int score() {
    int o = __offset(14);
    return o != 0 ? bb.getInt(o + bb_pos) : 0;
  }

  public boolean isDead() {
    int o = __offset(16);
    return o != 0 ? 0 != bb.get(o + bb_pos) : false;
  }

  public static int createPlayer(
      FlatBufferBuilder builder,
      int nameOffset,
      int headOffset,
      int bodyOffset,
      int length,
      byte direction,
      int score,
      boolean isDead) {
    builder.startTable(7);
    Player.addScore(builder, score);
    Player.addLength(builder, length);
    Player.addBody(builder, bodyOffset);
    Player.addHead(builder, headOffset);
    Player.addName(builder, nameOffset);
    Player.addIsDead(builder, isDead);
    Player.addDirection(builder, direction);
    return Player.endPlayer(builder);
  }

  public static void startPlayer(FlatBufferBuilder builder) {
    builder.startTable(7);
  }

  public static void addName(FlatBufferBuilder builder, int nameOffset) {
    builder.addOffset(0, nameOffset, 0);
  }

  public static void addHead(FlatBufferBuilder builder, int headOffset) {
    builder.addOffset(1, headOffset, 0);
  }

  public static void addBody(FlatBufferBuilder builder, int bodyOffset) {
    builder.addOffset(2, bodyOffset, 0);
  }

  public static int createBodyVector(FlatBufferBuilder builder, int[] data) {
    builder.startVector(4, data.length, 4);
    for (int i = data.length - 1; i >= 0; i--) builder.addOffset(data[i]);
    return builder.endVector();
  }

  public static void startBodyVector(FlatBufferBuilder builder, int numElems) {
    builder.startVector(4, numElems, 4);
  }

  public static void addLength(FlatBufferBuilder builder, int length) {
    builder.addInt(3, length, 0);
  }

  public static void addDirection(FlatBufferBuilder builder, byte direction) {
    builder.addByte(4, direction, 0);
  }

  public static void addScore(FlatBufferBuilder builder, int score) {
    builder.addInt(5, score, 0);
  }

  public static void addIsDead(FlatBufferBuilder builder, boolean isDead) {
    builder.addBoolean(6, isDead, false);
  }

  public static int endPlayer(FlatBufferBuilder builder) {
    int o = builder.endTable();
    return o;
  }

  public static final class Vector extends BaseVector {
    public Vector __assign(int _vector, int _element_size, ByteBuffer _bb) {
      __reset(_vector, _element_size, _bb);
      return this;
    }

    public Player get(int j) {
      return get(new Player(), j);
    }

    public Player get(Player obj, int j) {
      return obj.__assign(__indirect(__element(j), bb), bb);
    }
  }
}
