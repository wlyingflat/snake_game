package snake.application.gateway.server;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.util.List;

/**
 * 协议帧解码器：读取 4 字节长度后（由 LengthFieldBasedFrameDecoder 保证完整帧）， 根据首字节输出： 0x00 → ByteBuf（二进制 FlatBuffers
 * 帧） 0x01 → String（UTF-8 文本）
 */
public class ProtocolFrameDecoder extends ByteToMessageDecoder {

  public static final byte TYPE_BINARY = 0x00;
  public static final byte TYPE_TEXT = 0x01;

  @Override
  protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
    if (in.readableBytes() < 1) {
      return;
    }
    byte type = in.readByte();
    int remaining = in.readableBytes();
    if (remaining < 0) {
      return;
    }
    if (type == TYPE_BINARY) {
      // 保留一份拷贝，因为后续处理器可能需要对 ByteBuf 进行独立读取
      ByteBuf data = in.readRetainedSlice(remaining);
      out.add(data);
    } else if (type == TYPE_TEXT) {
      CharSequence cs = in.readCharSequence(remaining, java.nio.charset.StandardCharsets.UTF_8);
      out.add(cs.toString());
    } else {
      in.skipBytes(remaining);
    }
  }
}
