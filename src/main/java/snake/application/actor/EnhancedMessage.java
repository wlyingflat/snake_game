package snake.application.actor;

import io.netty.util.Recycler;
import snake.domain.game.Message;
import snake.messaging.CommandMsg; // 由 protobuf 生成

public class EnhancedMessage implements Message {

  private static final Recycler<EnhancedMessage> RECYCLER =
      new Recycler<>() {
        @Override
        protected EnhancedMessage newObject(Handle<EnhancedMessage> handle) {
          return new EnhancedMessage(handle);
        }
      };

  private final Recycler.Handle<EnhancedMessage> handle;

  public static EnhancedMessage newInstance() {
    return RECYCLER.get();
  }

  private EnhancedMessage(Recycler.Handle<EnhancedMessage> handle) {
    this.handle = handle;
  }

  private String command;
  private String username;
  private int roomId;
  private String gatewayId;
  private String rawMessage;

  public EnhancedMessage init(
      String command, String username, int roomId, String gatewayId, String rawMessage) {
    this.command = command;
    this.username = username;
    this.roomId = roomId;
    this.gatewayId = gatewayId;
    this.rawMessage = rawMessage;
    return this;
  }

  @Override
  public String type() {
    return command;
  }

  public String getCommand() {
    return command;
  }

  public String getUsername() {
    return username;
  }

  public int getRoomId() {
    return roomId;
  }

  public String getGatewayId() {
    return gatewayId;
  }

  public String getRawMessage() {
    return rawMessage;
  }

  public void recycle() {
    this.command = null;
    this.username = null;
    this.gatewayId = null;
    this.rawMessage = null;
    handle.recycle(this);
  }

  // ---------- Protobuf 序列化 ----------
  public byte[] toProtobuf() {
    CommandMsg proto =
        CommandMsg.newBuilder()
            .setCommand(command)
            .setUsername(username)
            .setRoomId(roomId)
            .setGatewayId(gatewayId)
            .setRawMessage(rawMessage)
            .build();
    return proto.toByteArray();
  }

  // ---------- Protobuf 反序列化 ----------
  public static EnhancedMessage fromProtobuf(byte[] data) {
    try {
      CommandMsg proto = CommandMsg.parseFrom(data);
      EnhancedMessage msg = newInstance();
      msg.command = proto.getCommand();
      msg.username = proto.getUsername();
      msg.roomId = proto.getRoomId();
      msg.gatewayId = proto.getGatewayId();
      msg.rawMessage = proto.getRawMessage();
      return msg;
    } catch (Exception e) {
      return null;
    }
  }
}
