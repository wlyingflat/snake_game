package snake.application.gateway.server;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 协议帧编码器，支持： - byte[] → 标识 0x00 + 原始数据 - String → 标识 0x01 + UTF-8 字节 最终由 LengthFieldPrepender 添加 4
 * 字节长度头。
 */
public class ProtocolFrameEncoder extends MessageToByteEncoder<Object> {

  public static final byte TYPE_BINARY = 0x00;
  public static final byte TYPE_TEXT = 0x01;

  @Override
  protected void encode(ChannelHandlerContext ctx, Object msg, ByteBuf out) {
    if (msg instanceof byte[] data) {
      out.writeByte(TYPE_BINARY);
      out.writeBytes(data);
    } else if (msg instanceof String text) {
      out.writeByte(TYPE_TEXT);
      byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
      out.writeBytes(utf8);
    } else {
      throw new IllegalArgumentException("Unsupported message type: " + msg.getClass());
    }
  }
}
