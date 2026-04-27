package snake.fbs;

import com.google.flatbuffers.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@SuppressWarnings("unused")
public final class Position extends Table {
  // 移除 FLATBUFFERS_25_12_19 调用
  public static void ValidateVersion() {}

  public static Position getRootAsPosition(ByteBuffer _bb) {
    return getRootAsPosition(_bb, new Position());
  }

  public static Position getRootAsPosition(ByteBuffer _bb, Position obj) {
    _bb.order(ByteOrder.LITTLE_ENDIAN);
    return (obj.__assign(_bb.getInt(_bb.position()) + _bb.position(), _bb));
  }

  public void __init(int _i, ByteBuffer _bb) {
    __reset(_i, _bb);
  }

  public Position __assign(int _i, ByteBuffer _bb) {
    __init(_i, _bb);
    return this;
  }

  public int x() {
    int o = __offset(4);
    return o != 0 ? bb.getInt(o + bb_pos) : 0;
  }

  public int y() {
    int o = __offset(6);
    return o != 0 ? bb.getInt(o + bb_pos) : 0;
  }

  public static int createPosition(FlatBufferBuilder builder, int x, int y) {
    builder.startTable(2);
    Position.addY(builder, y);
    Position.addX(builder, x);
    return Position.endPosition(builder);
  }

  public static void startPosition(FlatBufferBuilder builder) {
    builder.startTable(2);
  }

  public static void addX(FlatBufferBuilder builder, int x) {
    builder.addInt(0, x, 0);
  }

  public static void addY(FlatBufferBuilder builder, int y) {
    builder.addInt(1, y, 0);
  }

  public static int endPosition(FlatBufferBuilder builder) {
    int o = builder.endTable();
    return o;
  }

  public static final class Vector extends BaseVector {
    public Vector __assign(int _vector, int _element_size, ByteBuffer _bb) {
      __reset(_vector, _element_size, _bb);
      return this;
    }

    public Position get(int j) {
      return get(new Position(), j);
    }

    public Position get(Position obj, int j) {
      return obj.__assign(__indirect(__element(j), bb), bb);
    }
  }
}
