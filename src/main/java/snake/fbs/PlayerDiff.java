package snake.fbs;

import com.google.flatbuffers.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@SuppressWarnings("unused")
public final class PlayerDiff extends Table {
  public static void ValidateVersion() {}

  public static PlayerDiff getRootAsPlayerDiff(ByteBuffer _bb) {
    return getRootAsPlayerDiff(_bb, new PlayerDiff());
  }

  public static PlayerDiff getRootAsPlayerDiff(ByteBuffer _bb, PlayerDiff obj) {
    _bb.order(ByteOrder.LITTLE_ENDIAN);
    return (obj.__assign(_bb.getInt(_bb.position()) + _bb.position(), _bb));
  }

  public void __init(int _i, ByteBuffer _bb) {
    __reset(_i, _bb);
  }

  public PlayerDiff __assign(int _i, ByteBuffer _bb) {
    __init(_i, _bb);
    return this;
  }

  public Position newHead() {
    return newHead(new Position());
  }

  public Position newHead(Position obj) {
    int o = __offset(4);
    return o != 0 ? obj.__assign(__indirect(o + bb_pos), bb) : null;
  }

  public boolean removeTail() {
    int o = __offset(6);
    return o != 0 ? 0 != bb.get(o + bb_pos) : false;
  }

  public int length() {
    int o = __offset(8);
    return o != 0 ? bb.getInt(o + bb_pos) : 0;
  }

  public static int createPlayerDiff(
      FlatBufferBuilder builder, int newHeadOffset, boolean removeTail, int length) {
    builder.startTable(3);
    PlayerDiff.addLength(builder, length);
    PlayerDiff.addNewHead(builder, newHeadOffset);
    PlayerDiff.addRemoveTail(builder, removeTail);
    return PlayerDiff.endPlayerDiff(builder);
  }

  public static void startPlayerDiff(FlatBufferBuilder builder) {
    builder.startTable(3);
  }

  public static void addNewHead(FlatBufferBuilder builder, int newHeadOffset) {
    builder.addOffset(0, newHeadOffset, 0);
  }

  public static void addRemoveTail(FlatBufferBuilder builder, boolean removeTail) {
    builder.addBoolean(1, removeTail, false);
  }

  public static void addLength(FlatBufferBuilder builder, int length) {
    builder.addInt(2, length, 0);
  }

  public static int endPlayerDiff(FlatBufferBuilder builder) {
    int o = builder.endTable();
    return o;
  }

  public static final class Vector extends BaseVector {
    public Vector __assign(int _vector, int _element_size, ByteBuffer _bb) {
      __reset(_vector, _element_size, _bb);
      return this;
    }

    public PlayerDiff get(int j) {
      return get(new PlayerDiff(), j);
    }

    public PlayerDiff get(PlayerDiff obj, int j) {
      return obj.__assign(__indirect(__element(j), bb), bb);
    }
  }
}
