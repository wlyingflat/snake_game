package snake.application.gateway.server;

import com.fasterxml.jackson.databind.JsonNode;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;
import snake.application.gateway.heartbeat.HeartbeatService;
import snake.application.gateway.session.ClientSession;
import snake.common.JsonUtils;

public class PingPongHandler extends ChannelInboundHandlerAdapter {
  private static final AttributeKey<ClientSession> SESSION_KEY = AttributeKey.valueOf("session");
  private final HeartbeatService heartbeatService;

  public PingPongHandler(HeartbeatService heartbeatService) {
    this.heartbeatService = heartbeatService;
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) {
    String jsonMsg = (String) msg;
    JsonNode root;
    try {
      root = JsonUtils.MAPPER.readTree(jsonMsg);
    } catch (Exception e) {
      // ctx.fireChannelRead(msg);
      return;
    }
    String cmd = root.get("cmd").asText();

    if ("PING".equals(cmd)) {
      ClientSession session = ctx.channel().attr(SESSION_KEY).get();
      ctx.writeAndFlush("{\"cmd\":\"PONG\"}");
      if (session != null) {
        heartbeatService.refresh(session);
      }
      return;
    }
    if ("PONG".equals(cmd)) {
      ClientSession session = ctx.channel().attr(SESSION_KEY).get();
      if (session != null) {
        session.pendingPong = false;
        session.lastHeartbeat = System.currentTimeMillis() / 1000;
        heartbeatService.refresh(session);
      }
      return;
    }
    ctx.fireChannelRead(msg);
  }
}
