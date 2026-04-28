package snake.application.actor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;

class EnhancedMessageTest {
  @Test
  void protobufRoundtrip() {
    EnhancedMessage msg =
        EnhancedMessage.newInstance().init("JOIN", "testUser", 1, "gw-1", "{\"roomId\":1}");
    byte[] protoBytes = msg.toProtobuf();
    EnhancedMessage decoded = EnhancedMessage.fromProtobuf(protoBytes);
    assertNotNull(decoded);
    assertEquals("JOIN", decoded.getCommand());
    assertEquals("testUser", decoded.getUsername());
    assertEquals(1, decoded.getRoomId());
    assertEquals("gw-1", decoded.getGatewayId());
    // 回收
    decoded.recycle();
  }

  @Test
  void poolReuse() {
    EnhancedMessage msg1 = EnhancedMessage.newInstance();
    EnhancedMessage msg2 = EnhancedMessage.newInstance();
    msg1.recycle();
    msg2.recycle();
    EnhancedMessage msg3 = EnhancedMessage.newInstance();
    assertNotNull(msg3);
  }
}
